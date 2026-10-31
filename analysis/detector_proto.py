"""Prototype shot detector, run offline on recorded sessions.

What a jump shot looks like at the wrist, measured on real data
(session_20261030_163316: 144 labeled shots, watch on the right/shooting wrist):

    arm goes up      forearm pitch drops from about 0 deg to about -60 deg
    release          sharp wrist-flick gyro peak (~12 rad/s) and accel spike
                     right as the arm goes up
    follow-through   the arm STAYS raised for ~1.3 s
    arm comes down   then (in Phase 1) you tap the label ~2.2 s after release

Dribbling, catching and walking also produce big gyro/accel peaks, but with
the forearm pointing down (pitch around +40 deg). So the detector is built
around the held, raised arm, and the flick only confirms it:

    idle -> arm raised (pitch < raise_pitch_max_deg) -> release flick found
         -> arm held raised >= hold_min_s -> shot -> quiet for min_quiet_s -> idle

The pitch SIGN depends on which wrist the watch is on and which way round it
is worn. These values are for a right wrist in the default orientation; the
Phase 4 calibration step will handle other setups.

The DetectorParams defaults were tuned on ONE session (F1 0.90, and 0.91 on
the half of the session they were not tuned on). Re-check them on every new
session with analyze_session.py.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, replace

import numpy as np
import pandas as pd

from wristshot_io import sample_rate


@dataclass
class DetectorParams:
    # --- Tuned on session_20261030_163316. Verify on new sessions. ---
    # Arm raised: forearm pitch (deg) below this. Shots reach about -60 deg.
    raise_pitch_max_deg: float = -45.0
    # Follow-through: the arm stays raised at least this long (shots: ~1.3 s).
    hold_min_s: float = 0.3
    # Release flick: peak |gyro| (rad/s) near the moment the arm goes up.
    release_gyro_min: float = 8.0
    # Release spike: peak |accel| (m/s^2) just after the flick.
    release_accel_min: float = 20.0
    # Where to look for the flick, relative to the moment the arm crosses the threshold.
    release_lookback_s: float = 0.5
    release_lookahead_s: float = 0.2
    # Window after the flick for the accel spike.
    release_accel_window_s: float = 0.3
    # Minimum time between two shots.
    min_quiet_s: float = 2.0
    # Moving-average smoothing.
    gyro_smooth_s: float = 0.03
    pitch_smooth_s: float = 0.05
    # Feature-only windows (used for form scoring later, not for detection).
    drive_window_s: float = 0.6
    dip_window_s: float = 1.2


@dataclass
class MatchWindow:
    """When you label a shot, the label comes AFTER the release (you watch the ball).

    A label at time e matches a detected release at time d if
    e - before_s <= d <= e + after_s.
    """

    before_s: float = 6.0
    after_s: float = 1.0


# Suggestions: gates for "at least X" features are set this fraction of the
# 10th percentile of your labeled shots, so ~all real shots pass.
SUGGEST_MIN_FACTOR = 0.8
# Suggested raise threshold = the least-raised shots (90th percentile of
# min_pitch) plus this margin in degrees.
SUGGEST_PITCH_MARGIN_DEG = 15.0
# Suggested hold_min_s = this fraction of the 10th-percentile hold, capped.
HOLD_SUGGEST_FACTOR = 0.5
HOLD_SUGGEST_MAX_S = 0.4

FEATURE_COLUMNS = ["peak_gyro", "release_accel", "hold_s", "min_pitch_deg", "peak_accel_drive",
                   "min_accel_dip", "dip_to_release_s"]


@dataclass(frozen=True)
class Signals:
    """Smoothed arrays the detector works on, computed once per session."""

    t: np.ndarray
    gyro: np.ndarray
    acc: np.ndarray
    pitch: np.ndarray
    rate_hz: float

    @classmethod
    def from_raw(cls, df: pd.DataFrame, p: DetectorParams) -> "Signals":
        rate = sample_rate(df)

        def smooth(col: str, seconds: float) -> np.ndarray:
            n = max(1, int(round(seconds * rate)))
            # Trailing average (past samples only), exactly what the watch can compute live.
            return df[col].rolling(n, min_periods=1).mean().to_numpy()

        return cls(
            t=df["t"].to_numpy(),
            gyro=smooth("gyro_mag", p.gyro_smooth_s),
            acc=df["acc_mag"].to_numpy(),
            pitch=smooth("pitch_deg", p.pitch_smooth_s),
            rate_hz=rate,
        )

    def window(self, start_s: float, end_s: float) -> slice:
        """Sample indices with start_s <= t <= end_s (t is sorted, so binary search)."""
        return slice(int(np.searchsorted(self.t, start_s, "left")), int(np.searchsorted(self.t, end_s, "right")))


def arm_raises(sig: Signals, p: DetectorParams) -> list[tuple[int, int]]:
    """(start, end) sample indices of every stretch with the arm raised; end is exclusive."""
    raised = (sig.pitch < p.raise_pitch_max_deg).astype(int)
    edges = np.diff(np.r_[0, raised, 0])
    return list(zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1)))


def raise_features(sig: Signals, start: int, end: int, p: DetectorParams) -> dict:
    """Features of one arm raise. The release is the biggest flick around the moment the arm goes up."""
    t_up = sig.t[start]
    search = sig.window(t_up - p.release_lookback_s, t_up + p.release_lookahead_s)
    i = search.start + int(np.argmax(sig.gyro[search]))
    t0 = sig.t[i]
    after = sig.window(t0, t0 + p.release_accel_window_s)
    drive = sig.window(t0 - p.drive_window_s, t0)
    dip = sig.window(t0 - p.dip_window_s, t0)
    dip_i = dip.start + int(np.argmin(sig.acc[dip]))
    return {
        "t_release": float(t0),
        "peak_gyro": float(sig.gyro[i]),
        "release_accel": float(sig.acc[after].max()),
        "hold_s": float(sig.t[min(end, len(sig.t) - 1)] - t_up),
        "min_pitch_deg": float(sig.pitch[start:end].min()),
        "peak_accel_drive": float(sig.acc[drive].max()),
        "min_accel_dip": float(sig.acc[dip_i]),
        "dip_to_release_s": float(t0 - sig.t[dip_i]),
    }


def candidates(sig: Signals, p: DetectorParams) -> pd.DataFrame:
    """Every arm raise with its features, before any thresholds are applied."""
    return pd.DataFrame([raise_features(sig, a, b, p) for a, b in arm_raises(sig, p)],
                        columns=["t_release"] + FEATURE_COLUMNS)


def detect(sig: Signals, p: DetectorParams) -> pd.DataFrame:
    """Runs the detector. Returns every candidate, whether it was accepted, and why not."""
    det = candidates(sig, p)
    reasons = []
    last_shot = -np.inf
    for _, c in det.iterrows():
        r = []
        if c.hold_s < p.hold_min_s:
            r.append("short_hold")
        if c.peak_gyro < p.release_gyro_min:
            r.append("weak_flick")
        if c.release_accel < p.release_accel_min:
            r.append("no_accel_spike")
        if not r and c.t_release - last_shot < p.min_quiet_s:
            r.append("too_soon")
        if not r:
            last_shot = c.t_release
        reasons.append("+".join(r))
    det["reject_reason"] = reasons
    det["accepted"] = det["reject_reason"] == ""
    return det


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


def split_by_labels(sig: Signals, events: pd.DataFrame, p: DetectorParams,
                    w: MatchWindow) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Splits all arm raises into (labeled shots, background).

    For each label, the raise with the longest hold in its window is taken as the
    shot. Raises not near any label are background (rebounds, reaching up, ...).
    """
    cand = candidates(sig, p)
    t = cand["t_release"].to_numpy()
    shot_rows = []
    near_label = np.zeros(len(cand), dtype=bool)
    for _, e in events.iterrows():
        in_win = (t >= e["t"] - w.before_s) & (t <= e["t"] + w.after_s)
        near_label |= in_win
        if in_win.any():
            in_window = cand[in_win]
            row = in_window.loc[in_window["hold_s"].idxmax()].copy()
            row["label"] = e["label"]
            row["label_t"] = float(e["t"])
            row["label_delay_s"] = float(e["t"] - row["t_release"])
            shot_rows.append(row)
    return pd.DataFrame(shot_rows).reset_index(drop=True), cand[~near_label].reset_index(drop=True)


def suggest_params(shots: pd.DataFrame, base: DetectorParams) -> tuple[DetectorParams, list[str]]:
    """Thresholds that let ~all of your labeled shots through. Returns params and notes."""
    notes = []
    if len(shots) < 30:
        notes.append(f"Only {len(shots)} labeled shots found: suggestions are rough. Record 30+.")
    if len(shots) == 0:
        return base, notes

    def low(col: str) -> float:
        return float(shots[col].quantile(0.10)) * SUGGEST_MIN_FACTOR

    tuned = replace(
        base,
        raise_pitch_max_deg=round(min(base.raise_pitch_max_deg,
                                      float(shots["min_pitch_deg"].quantile(0.90)) + SUGGEST_PITCH_MARGIN_DEG), 1),
        hold_min_s=round(min(float(shots["hold_s"].quantile(0.10)) * HOLD_SUGGEST_FACTOR, HOLD_SUGGEST_MAX_S), 2),
        release_gyro_min=round(low("peak_gyro"), 2),
        release_accel_min=round(low("release_accel"), 1),
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
    return "\n".join(f"    const val {k.upper()} = {v!r}" for k, v in asdict(p).items())
