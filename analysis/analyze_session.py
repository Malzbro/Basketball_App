#!/usr/bin/env python3
"""Analyze one WristShot session: check timing, plot it, and prototype shot detection.

Usage (from the analysis/ folder):

    python analyze_session.py ../data/session_20261028_181502_raw.csv
    python analyze_session.py ../data                 # newest session in the folder
    python analyze_session.py ../data --show          # also open interactive plots
    python analyze_session.py ../data --release-gyro-min 7.5 --drive-accel-min 14

What it prints:
  1. Timing check: sample rate, dropped data, and whether raw and event
     timestamps line up (the Phase 1 acceptance test).
  2. Detector results with the CURRENT params: precision / recall vs your labels.
  3. Features of your labeled shots vs. "background" motion (dribbling, walking).
  4. Suggested thresholds, a threshold sweep, and results with the suggestions.

Plots are saved to analysis/output/<session>/.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, fields, replace
from pathlib import Path

import matplotlib

import numpy as np
import pandas as pd

import detector_proto as dp
import wristshot_io as wio

LABEL_COLORS = {"make": "tab:green", "miss": "tab:red", "shot": "tab:gray"}
SNIPPET_HALF_WIDTH_S = 1.5
MAX_SNIPPETS = 40
SWEEP_STEPS = 25


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", help="raw CSV, events CSV, session prefix, or a folder (newest session)")
    ap.add_argument("--out", default=str(Path(__file__).parent / "output"), help="output folder for plots")
    ap.add_argument("--show", action="store_true", help="open interactive plot windows (zoom with the mouse)")
    ap.add_argument("--label-before", type=float, default=dp.MatchWindow.before_s,
                    help="a label may come up to this many seconds after the release")
    ap.add_argument("--label-after", type=float, default=dp.MatchWindow.after_s,
                    help="a label may come up to this many seconds before the release")
    for f in fields(dp.DetectorParams):
        flag = "--" + f.name.replace("_", "-")
        ap.add_argument(flag, type=float, default=None, help=f"override DetectorParams.{f.name}")
    return ap.parse_args()


def print_section(title: str) -> None:
    print(f"\n=== {title} " + "=" * max(0, 60 - len(title)))


def describe(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    if len(df) == 0:
        return pd.DataFrame()
    q = df[cols].quantile([0.0, 0.1, 0.5, 0.9, 1.0]).T
    q.columns = ["min", "p10", "median", "p90", "max"]
    return q.round(3)


def print_score(s: dict) -> None:
    print(f"detected {s['detected']:3d} | labels {s['labels']:3d} | true pos {s['true_pos']:3d} | "
          f"false pos {s['false_pos']:3d} | missed {s['missed']:3d}")
    print(f"precision {s['precision']:.2f} | recall {s['recall']:.2f} | F1 {s['f1']:.2f}")


def plot_overview(raw, events, detections, s, p, title, path):
    import matplotlib.pyplot as plt

    fig, axes = plt.subplots(3, 1, figsize=(16, 9), sharex=True)
    axes[0].plot(raw.t, raw.acc_mag, lw=0.6, color="tab:blue")
    axes[0].axhline(wio.STANDARD_GRAVITY, color="k", lw=0.5, ls=":")
    axes[0].axhline(p.drive_accel_min, color="tab:orange", lw=0.8, ls="--", label="drive_accel_min")
    axes[0].set_ylabel("|accel| m/s²")
    axes[1].plot(raw.t, raw.gyro_mag, lw=0.6, color="tab:purple")
    axes[1].axhline(p.release_gyro_min, color="tab:orange", lw=0.8, ls="--", label="release_gyro_min")
    axes[1].set_ylabel("|gyro| rad/s")
    axes[2].plot(raw.t, raw.pitch_deg, lw=0.6, color="tab:brown")
    axes[2].set_ylabel("forearm pitch °")
    axes[2].set_xlabel("time since start (s)")

    for _, e in events.iterrows():
        for ax in axes:
            ax.axvline(e.t, color=LABEL_COLORS.get(e.label, "k"), lw=1.0, alpha=0.7)
    accepted = detections[detections.accepted].reset_index(drop=True) if len(detections) else detections
    matched = {di for di, _ in s["pairs"]}
    for di, d in accepted.iterrows():
        ok = di in matched
        axes[1].plot(d.t_release, d.peak_gyro, "^" if ok else "x", ms=8,
                     color="tab:green" if ok else "tab:red")
    rejected = detections[~detections.accepted] if len(detections) else detections
    axes[1].plot(rejected.t_release, rejected.peak_gyro, "o", mfc="none", color="gray", ms=6)

    axes[0].legend(loc="upper right")
    axes[1].legend(loc="upper right")
    axes[0].set_title(title + "\nvertical lines = your labels (green make, red miss, gray shot?) | "
                      "▲ detected & matched, ✕ false positive, ○ rejected candidate")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig


def plot_snippets(raw, shots, path):
    """Every labeled shot, aligned at its release peak: shows how consistent the motion is."""
    import matplotlib.pyplot as plt

    fig, axes = plt.subplots(2, 1, figsize=(10, 7), sharex=True)
    t = raw.t.to_numpy()
    for _, sh in shots.head(MAX_SNIPPETS).iterrows():
        m = (t >= sh.t_release - SNIPPET_HALF_WIDTH_S) & (t <= sh.t_release + SNIPPET_HALF_WIDTH_S)
        color = LABEL_COLORS.get(sh.label, "k")
        axes[0].plot(t[m] - sh.t_release, raw.acc_mag.to_numpy()[m], color=color, alpha=0.4, lw=0.8)
        axes[1].plot(t[m] - sh.t_release, raw.gyro_mag.to_numpy()[m], color=color, alpha=0.4, lw=0.8)
    axes[0].set_ylabel("|accel| m/s²")
    axes[1].set_ylabel("|gyro| rad/s")
    axes[1].set_xlabel("seconds relative to release (biggest gyro peak before each label)")
    axes[0].set_title(f"{min(len(shots), MAX_SNIPPETS)} labeled shots aligned at release (green make, red miss)")
    for ax in axes:
        ax.axvline(0, color="k", lw=0.6, ls=":")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig


def main() -> None:
    args = parse_args()
    if not args.show:
        matplotlib.use("Agg")

    paths = wio.find_session(args.session)
    raw = wio.load_raw(paths.raw)
    events = wio.load_events(paths.events, raw.attrs["start_ns"])
    meta = wio.load_meta(paths.meta)
    window = dp.MatchWindow(before_s=args.label_before, after_s=args.label_after)
    overrides = {f.name: getattr(args, f.name) for f in fields(dp.DetectorParams) if getattr(args, f.name) is not None}
    params = replace(dp.DetectorParams(), **overrides)
    out_dir = Path(args.out) / paths.prefix
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Session: {paths.prefix}")
    if meta:
        print(f"Device: {meta.get('device_model')} (SDK {meta.get('android_sdk')}), started {meta.get('start_wall_time')}")

    print_section("1. Timing check (raw vs events)")
    rep = wio.alignment_report(raw, events)
    for k, v in rep.items():
        print(f"  {k:20s} {v:.3f}" if isinstance(v, float) else f"  {k:20s} {v}")
    print("  -> timestamps line up" if rep["ok"] else "  -> PROBLEM: see values above")
    if rep["dropouts"]:
        print("  note: dropouts mean the watch stopped delivering samples for >100 ms "
              "(check battery saver / that the session notification was showing)")
    print("  labels:", events["label"].value_counts().to_dict() if len(events) else {})

    print_section("2. Detector with current params (INITIAL GUESSES)")
    print(dp.params_as_kotlin(params))
    det = dp.detect(raw, params)
    s = dp.score(det, events, window)
    print_score(s)
    if len(det):
        print("  rejected candidates by reason:", det.loc[~det.accepted, "reject_reason"].value_counts().to_dict())

    print_section("3. Labeled shots vs background motion")
    shots = dp.labeled_shot_features(raw, events, params, window)
    background = dp.background_features(raw, events, params, window)
    feat_cols = ["peak_gyro", "peak_accel_drive", "min_accel_dip", "dip_to_release_s", "hold_s", "pitch_at_release_deg"]
    print(f"Labeled shots ({len(shots)}): biggest gyro peak in the window before each label")
    print(describe(shots, feat_cols + ["label_delay_s"]).to_string() if len(shots) else "  (no labels)")
    print(f"\nBackground peaks ({len(background)}): gyro peaks > {dp.BACKGROUND_PEAK_FLOOR} rad/s away from labels")
    print(describe(background, feat_cols).to_string() if len(background) else "  (none)")
    if len(shots):
        shots.to_csv(out_dir / "labeled_shot_features.csv", index=False)
        print(f"\nlabel_delay_s = how long after the release you pressed the label button. "
              f"If it's often near {window.before_s:.0f}s, raise --label-before.")

    print_section("4. Suggested thresholds (from YOUR data; still verify on another session)")
    tuned, notes = dp.suggest_params(shots, background, events, params)
    for n in notes:
        print("  note:", n)
    print(dp.params_as_kotlin(tuned))
    if len(events):
        top = max(float(shots["peak_gyro"].max()) if len(shots) else params.release_gyro_min * 2, 1.0)
        sweep = dp.sweep_release_threshold(raw, events, tuned, window, np.linspace(top * 0.2, top, SWEEP_STEPS))
        print("\nSweep of release_gyro_min (other params = suggested):")
        print(sweep.round(3).to_string(index=False))
        lo, hi, mid = dp.best_plateau(sweep)
        print(f"best F1 {sweep.f1.max():.2f} for release_gyro_min in [{lo:.2f}, {hi:.2f}]; "
              f"the middle ({mid:.2f}) leaves the most margin on both sides")
        tuned = replace(tuned, release_gyro_min=round(mid, 2))
    s2 = dp.score(dp.detect(raw, tuned), events, window)
    print("\nWith suggested params (release_gyro_min from the sweep):")
    print(dp.params_as_kotlin(tuned))
    print_score(s2)
    (out_dir / "suggested_params.txt").write_text(
        "\n".join(f"{k} = {v}" for k, v in asdict(tuned).items()) + "\n")

    fig1 = plot_overview(raw, events, det, s, params, f"{paths.prefix} (current params)", out_dir / "overview.png")
    figs = [fig1]
    if len(shots):
        figs.append(plot_snippets(raw, shots, out_dir / "shot_snippets.png"))
    det.to_csv(out_dir / "detections.csv", index=False)
    print(f"\nSaved plots and CSVs to {out_dir}")

    if args.show:
        import matplotlib.pyplot as plt
        plt.show()


if __name__ == "__main__":
    main()
