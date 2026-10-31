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


def report_timing(raw: pd.DataFrame, events: pd.DataFrame) -> None:
    print_section("1. Timing check (raw vs events)")
    rep = wio.alignment_report(raw, events)
    for k, v in rep.items():
        print(f"  {k:20s} {v:.3f}" if isinstance(v, float) else f"  {k:20s} {v}")
    print("  -> timestamps line up" if rep["ok"] else "  -> PROBLEM: see values above")
    if rep["dropouts"]:
        print("  note: dropouts mean the watch stopped delivering samples for >100 ms "
              "(check battery saver / that the session notification was showing)")
    print("  labels:", events["label"].value_counts().to_dict())


def report_detector(sig: dp.Signals, events: pd.DataFrame, params: dp.DetectorParams,
                    window: dp.MatchWindow) -> tuple[pd.DataFrame, dict]:
    print_section("2. Detector with current params")
    print(dp.params_as_kotlin(params))
    det = dp.detect(sig, params)
    s = dp.score(det, events, window)
    print_score(s)
    if len(det):
        print("  rejected candidates by reason:", det.loc[~det.accepted, "reject_reason"].value_counts().to_dict())
    return det, s


def report_features(sig: dp.Signals, events: pd.DataFrame, params: dp.DetectorParams,
                    window: dp.MatchWindow) -> tuple[pd.DataFrame, pd.DataFrame]:
    print_section("3. Labeled shots vs other arm raises")
    shots, background = dp.split_by_labels(sig, events, params, window)
    print(f"Labeled shots ({len(shots)} of {len(events)} labels): the longest arm raise before each label")
    print(describe(shots, dp.FEATURE_COLUMNS + ["label_delay_s"]).to_string() if len(shots) else "  (none found)")
    print(f"\nOther arm raises ({len(background)}): not near any label (rebounds, reaching up, unlabeled shots)")
    print(describe(background, dp.FEATURE_COLUMNS).to_string() if len(background) else "  (none)")
    if len(shots):
        print(f"\nlabel_delay_s = how long after the release you pressed the label button. "
              f"If it's often near {window.before_s:.0f}s, raise --label-before.")
    return shots, background


def report_suggestions(sig: dp.Signals, events: pd.DataFrame, shots: pd.DataFrame,
                       params: dp.DetectorParams, window: dp.MatchWindow) -> dp.DetectorParams:
    print_section("4. Suggested thresholds (from YOUR data; still verify on another session)")
    tuned, notes = dp.suggest_params(shots, params)
    for n in notes:
        print("  note:", n)
    print(dp.params_as_kotlin(tuned))
    if len(events):
        top = max(float(shots["peak_gyro"].max()) if len(shots) else params.release_gyro_min * 2, 1.0)
        sweep = dp.sweep_release_threshold(sig, events, tuned, window, np.linspace(top * 0.2, top, SWEEP_STEPS))
        print("\nSweep of release_gyro_min (other params = suggested):")
        print(sweep.round(3).to_string(index=False))
        lo, hi, mid = dp.best_plateau(sweep)
        print(f"best F1 {sweep.f1.max():.2f} for release_gyro_min in [{lo:.2f}, {hi:.2f}]; "
              f"the middle ({mid:.2f}) leaves the most margin on both sides")
        tuned = replace(tuned, release_gyro_min=round(mid, 2))
    print("\nWith suggested params (release_gyro_min from the sweep):")
    print(dp.params_as_kotlin(tuned))
    print_score(dp.score(dp.detect(sig, tuned), events, window))
    return tuned


def main() -> None:
    args = parse_args()
    if not args.show:
        matplotlib.use("Agg")
    import plots  # after choosing the backend

    paths = wio.find_session(args.session)
    raw = wio.load_raw(paths.raw)
    events = wio.load_events(paths.events, raw.attrs["start_ns"])
    meta = wio.load_meta(paths.meta)
    window = dp.MatchWindow(before_s=args.label_before, after_s=args.label_after)
    overrides = {f.name: getattr(args, f.name) for f in fields(dp.DetectorParams) if getattr(args, f.name) is not None}
    params = replace(dp.DetectorParams(), **overrides)
    sig = dp.Signals.from_raw(raw, params)
    out_dir = Path(args.out) / paths.prefix
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Session: {paths.prefix}")
    if meta:
        print(f"Device: {meta.get('device_model')} (SDK {meta.get('android_sdk')}), started {meta.get('start_wall_time')}")

    report_timing(raw, events)
    det, s = report_detector(sig, events, params, window)
    shots, _ = report_features(sig, events, params, window)
    tuned = report_suggestions(sig, events, shots, params, window)

    det.to_csv(out_dir / "detections.csv", index=False)
    (out_dir / "suggested_params.txt").write_text("\n".join(f"{k} = {v}" for k, v in asdict(tuned).items()) + "\n")
    plots.plot_overview(raw, events, det, s, params, f"{paths.prefix} (current params)", out_dir / "overview.png")
    if len(shots):
        shots.to_csv(out_dir / "labeled_shot_features.csv", index=False)
        plots.plot_snippets(raw, shots, out_dir / "shot_snippets.png")
    print(f"\nSaved plots and CSVs to {out_dir}")

    if args.show:
        plots.plt.show()


if __name__ == "__main__":
    main()
