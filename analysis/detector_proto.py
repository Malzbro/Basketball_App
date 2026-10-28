"""Prototype shot detector, run offline on recorded sessions.

This mirrors the state machine planned for the watch (Phase 3):

    idle -> dip (knees bend, |accel| drops) -> drive (|accel| rises)
         -> release (sharp wrist-flick gyro peak) -> follow-through hold (wrist quiet)

EVERY THRESHOLD IN DetectorParams IS AN INITIAL GUESS. None of them has been
validated on real shots. The point of this script is to replace them with
values measured from your own recordings (see suggest_params).
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, replace

import numpy as np
import pandas as pd
from scipy.signal import find_peaks

from wristshot_io import sample_rate


@dataclass
class DetectorParams:
    # --- INITIAL GUESSES, NOT VALIDATED. Tune from your data. ---
    # Release: peak wrist angular speed (rad/s). Wrist flicks are fast; walking
    # and most dribbles should stay below this.
    release_gyro_min: float = 6.0
    # Drive: peak |accel| (m/s^2) in the drive window just before release.
    drive_accel_min: float = 13.0
    drive_window_s: float = 0.6
    # Dip: lowest |accel| (m/s^2) before the drive. None = don't require a dip
    # (it may be too subtle to see at the wrist).
    dip_accel_max: float | None = None
    dip_window_s: float = 1.2
    # Follow-through hold: after release the wrist stays fairly still.
    hold_gyro_max: float = 3.0
    hold_min_s: float = 0.15
    hold_search_s: float = 0.6
    # Minimum time between two shots (rejects bursts of dribbles).
    min_quiet_s: float = 2.0
    # Moving-average smoothing applied to the magnitudes.
    smooth_s: float = 0.03


@dataclass
class MatchWindow:
    """When you label a shot, the label comes AFTER the release (you watch the ball).

    A label at time e matches a detected release at time d if
    e - before_s <= d <= e + after_s.
    """

    before_s: float = 6.0
    after_s: float = 1.0


# Peaks below this (rad/s) are ignored when collecting "background" motion stats.
BACKGROUND_PEAK_FLOOR = 1.5
# Minimum spacing (s) between background peaks, much looser than min_quiet_s.
BACKGROUND_PEAK_MIN_GAP_S = 0.3
# Max length (s) we measure a follow-through hold for.
HOLD_MEASURE_CAP_S = 1.5
# Suggested hold_min_s = this fraction of the 10th-percentile hold of your shots,
# capped, because a long hold requirement makes the detector fragile.
HOLD_SUGGEST_FACTOR = 0.5
HOLD_SUGGEST_MAX_S = 0.4


@dataclass(frozen=True)
class Signals:
    """Smoothed arrays the detector works on, computed once per session."""

    t: np.ndarray
    gyro: np.ndarray
    acc: np.ndarray
    pitch: np.ndarray
    rate_hz: float

    @classmethod
    def from_raw(cls, df: pd.DataFrame, smooth_s: float) -> "Signals":
        rate = sample_rate(df)
        n = max(1, int(round(smooth_s * rate)))

        def smooth(col: str) -> np.ndarray:
            return df[col].rolling(n, center=True, min_periods=1).mean().to_numpy()

        return cls(df["t"].to_numpy(), smooth("gyro_mag"), smooth("acc_mag"), df["pitch_deg"].to_numpy(), rate)

    def window(self, start_s: float, end_s: float) -> slice:
        """Sample indices with start_s <= t <= end_s (t is sorted, so binary search)."""
        return slice(np.searchsorted(self.t, start_s, "left"), np.searchsorted(self.t, end_s, "right"))

    def peaks(self, height: float, min_gap_s: float) -> np.ndarray:
        peaks, _ = find_peaks(self.gyro, height=height, distance=max(1, int(min_gap_s * self.rate_hz)))
        return peaks


def features_at(sig: Signals, i: int, p: DetectorParams) -> dict:
    """Shot features around a candidate release at sample index i."""
    t0 = sig.t[i]
    drive = sig.window(t0 - p.drive_window_s, t0)
    dip = sig.window(t0 - p.dip_window_s, t0)
    peak_acc_i = drive.start + int(np.argmax(sig.acc[drive]))
    dip_i = dip.start + int(np.argmin(sig.acc[dip]))
    return {
        "t_release": float(t0),
        "peak_gyro": float(sig.gyro[i]),
        "peak_accel_drive": float(sig.acc[peak_acc_i]),
        "min_accel_dip": float(sig.acc[dip_i]),
        "dip_to_release_s": float(t0 - sig.t[dip_i]),
        "hold_s": hold_duration(sig, i, p),
        "pitch_at_release_deg": float(sig.pitch[i]),
    }


def hold_duration(sig: Signals, i: int, p: DetectorParams) -> float:
    """Follow-through: first quiet moment after release, then how long the wrist stays quiet."""
    t0 = sig.t[i]
    search = sig.window(t0, t0 + p.hold_search_s)
    quiet = np.flatnonzero(sig.gyro[search] < p.hold_gyro_max)
    quiet = quiet[sig.t[search][quiet] > t0]
    if len(quiet) == 0:
        return 0.0
    start = search.start + int(quiet[0])
    stop = int(np.searchsorted(sig.t, sig.t[start] + HOLD_MEASURE_CAP_S, "left"))
    loud = np.flatnonzero(sig.gyro[start:stop] >= p.hold_gyro_max)
    end = start + int(loud[0]) - 1 if len(loud) else stop - 1
    return float(sig.t[end] - sig.t[start])


DETECTION_COLUMNS = ["t_release", "peak_gyro", "peak_accel_drive", "min_accel_dip", "dip_to_release_s",
                     "hold_s", "pitch_at_release_deg", "accepted", "reject_reason"]


def detect(sig: Signals, p: DetectorParams) -> pd.DataFrame:
    """Runs the detector. Returns every candidate with features and why it was rejected."""
    rows = []
    for i in sig.peaks(p.release_gyro_min, p.min_quiet_s):
        f = features_at(sig, i, p)
        reasons = []
        if f["peak_accel_drive"] < p.drive_accel_min:
            reasons.append("no_drive")
        if p.dip_accel_max is not None and f["min_accel_dip"] > p.dip_accel_max:
            reasons.append("no_dip")
        if f["hold_s"] < p.hold_min_s:
            reasons.append("no_hold")
        f["accepted"] = not reasons
        f["reject_reason"] = "+".join(reasons)
        rows.append(f)
    return pd.DataFrame(rows, columns=DETECTION_COLUMNS)


def match(detected_t: np.ndarray, label_t: np.ndarray, w: MatchWindow) -> tuple[list[tuple[int, int]], list[int], list[int]]:
    """One-to-one matching of detections to labels.

    For each label (in time order) pick the latest unmatched detection before it
    within the window; if none, the earliest one after it within the window.
    Returns (pairs, unmatched_detection_indices, unmatched_label_indices).
    """
    used: set[int] = set()
    pairs = []
    missed = []
    for li, e in enumerate(label_t):
        options = [di for di, d in enumerate(detected_t) if di not in used and e - w.before_s <= d <= e + w.after_s]
        if not options:
            missed.append(li)
            continue
        before = [di for di in options if detected_t[di] <= e]
        di = max(before, key=lambda k: detected_t[k]) if before else min(options, key=lambda k: detected_t[k])
        used.add(di)
        pairs.append((di, li))
    false_pos = [di for di in range(len(detected_t)) if di not in used]
    return pairs, false_pos, missed


def score(detections: pd.DataFrame, events: pd.DataFrame, w: MatchWindow) -> dict:
    d_t = detections.loc[detections["accepted"], "t_release"].to_numpy()
    pairs, fp, fn = match(d_t, events["t"].to_numpy(), w)
    tp = len(pairs)
    precision = tp / len(d_t) if len(d_t) else 0.0
    recall = tp / len(events) if len(events) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"detected": len(d_t), "labels": len(events), "true_pos": tp, "false_pos": len(fp),
            "missed": len(fn), "precision": precision, "recall": recall, "f1": f1,
            "pairs": pairs, "false_pos_idx": fp, "missed_idx": fn}


def near_any_label(t: float, events: pd.DataFrame, w: MatchWindow) -> bool:
    return bool(((events["t"] - w.before_s <= t) & (t <= events["t"] + w.after_s)).any())


def labeled_shot_features(sig: Signals, events: pd.DataFrame, p: DetectorParams, w: MatchWindow) -> pd.DataFrame:
    """For each label, assume the biggest gyro peak in its window is the release."""
    rows = []
    for _, e in events.iterrows():
        win = sig.window(e["t"] - w.before_s, e["t"] + w.after_s)
        if win.stop <= win.start:
            continue
        i = win.start + int(np.argmax(sig.gyro[win]))
        f = features_at(sig, i, p)
        f["label"] = e["label"]
        f["label_t"] = float(e["t"])
        f["label_delay_s"] = float(e["t"] - f["t_release"])
        rows.append(f)
    return pd.DataFrame(rows)


def background_features(sig: Signals, events: pd.DataFrame, p: DetectorParams, w: MatchWindow) -> pd.DataFrame:
    """Features of gyro peaks that are NOT near any label (dribbles, walking, fidgeting)."""
    peaks = sig.peaks(BACKGROUND_PEAK_FLOOR, BACKGROUND_PEAK_MIN_GAP_S)
    return pd.DataFrame([features_at(sig, i, p) for i in peaks if not near_any_label(sig.t[i], events, w)])


def _between(shot_low: float, background_high: float, fallback_factor: float) -> tuple[float, bool]:
    """Midpoint if the two groups separate, otherwise a bit under the shot value."""
    if np.isfinite(background_high) and shot_low > background_high:
        return (shot_low + background_high) / 2, True
    return shot_low * fallback_factor, False


def suggest_params(shots: pd.DataFrame, background: pd.DataFrame, events: pd.DataFrame,
                   base: DetectorParams) -> tuple[DetectorParams, list[str]]:
    """Derives thresholds from the labeled shots vs. background motion. Returns params and notes."""
    notes = []
    if len(shots) < 5:
        notes.append(f"Only {len(shots)} labeled shots: suggestions are very rough. Record 30+.")
    if len(shots) == 0:
        return base, notes

    def bg_q(col: str, q: float) -> float:
        return float(background[col].quantile(q)) if len(background) else float("nan")

    gyro_thr, sep = _between(float(shots["peak_gyro"].quantile(0.10)), bg_q("peak_gyro", 0.95), 0.85)
    notes.append("release_gyro_min: shots and background " + ("separate well" if sep else "OVERLAP, other gates matter"))

    drive_thr, sep = _between(float(shots["peak_accel_drive"].quantile(0.10)), bg_q("peak_accel_drive", 0.50), 0.9)
    if not sep:
        notes.append("drive_accel_min: drive peak not clearly above background, keep it loose")

    hold_min = min(float(shots["hold_s"].quantile(0.10)) * HOLD_SUGGEST_FACTOR, HOLD_SUGGEST_MAX_S)
    if hold_min <= 0:
        notes.append("hold: many labeled shots have no quiet follow-through at hold_gyro_max; try raising it")

    gaps = np.diff(np.sort(events["t"].to_numpy()))
    quiet = float(np.clip(np.min(gaps) * 0.8, 0.8, base.min_quiet_s)) if len(gaps) else base.min_quiet_s

    dip_thr = float(shots["min_accel_dip"].quantile(0.90))
    if np.isfinite(bg_q("min_accel_dip", 0.5)) and dip_thr < bg_q("min_accel_dip", 0.5):
        notes.append(f"dip looks informative (shots dip to <= {dip_thr:.1f} m/s^2); consider dip_accel_max")

    window = float(np.clip(shots["dip_to_release_s"].quantile(0.9) * 1.2, 0.3, 2.5))

    tuned = replace(
        base,
        release_gyro_min=round(gyro_thr, 2),
        drive_accel_min=round(drive_thr, 2),
        hold_min_s=round(max(0.0, hold_min), 3),
        min_quiet_s=round(quiet, 2),
        dip_window_s=round(window, 2),
    )
    return tuned, notes


def sweep_release_threshold(sig: Signals, events: pd.DataFrame, p: DetectorParams, w: MatchWindow,
                            values: np.ndarray) -> pd.DataFrame:
    rows = []
    for v in values:
        s = score(detect(sig, replace(p, release_gyro_min=float(v))), events, w)
        rows.append({"release_gyro_min": float(v), **{k: s[k] for k in ("detected", "true_pos", "false_pos",
                                                                         "missed", "precision", "recall", "f1")}})
    return pd.DataFrame(rows)


def best_plateau(sweep: pd.DataFrame) -> tuple[float, float, float]:
    """Range of thresholds that reach the best F1, and its midpoint (the safest choice)."""
    best = sweep["f1"].max()
    good = sweep.loc[np.isclose(sweep["f1"], best), "release_gyro_min"]
    return float(good.min()), float(good.max()), float((good.min() + good.max()) / 2)


def params_as_kotlin(p: DetectorParams) -> str:
    """Prints params in a form that is easy to paste into the Phase 3 Kotlin config."""
    lines = []
    for k, v in asdict(p).items():
        name = k.upper()
        lines.append(f"    const val {name} = {v!r}" if v is not None else f"    // {name} = disabled")
    return "\n".join(lines)
