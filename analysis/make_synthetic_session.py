#!/usr/bin/env python3
"""Makes a FAKE session in the watch's file format, for testing the analysis code.

The motion is invented (simple shapes for walking, dribbling and shots). Use it
only to check that the scripts run; never to choose thresholds.

    python make_synthetic_session.py --out ../data/synthetic --minutes 10
    python analyze_session.py ../data/synthetic
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

RATE_HZ = 100
G = 9.81


def bump(t: np.ndarray, center: float, width: float) -> np.ndarray:
    return np.exp(-0.5 * ((t - center) / width) ** 2)


def plateau(t: np.ndarray, start: float, end: float, edge: float = 0.08) -> np.ndarray:
    """Smooth step up at start and down at end (0..1)."""
    def sigmoid(x: np.ndarray) -> np.ndarray:
        return 0.5 * (1 + np.tanh(x / 2))  # same as 1 / (1 + e^-x), without overflow

    return sigmoid((t - start) / edge) - sigmoid((t - end) / edge)


def generate(minutes: float = 10, seed: int = 7) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Returns (raw, events) DataFrames in the watch's CSV column format."""
    rng = np.random.default_rng(seed)

    n = int(minutes * 60 * RATE_HZ)
    t = np.arange(n) / RATE_HZ
    acc = np.zeros((n, 3)) + rng.normal(0, 0.15, (n, 3))
    gyro = rng.normal(0, 0.05, (n, 3))
    # Forearm pitch: about +30 deg (hand lower than elbow) when not shooting. On a
    # right wrist a raised shooting arm reads about -60 deg (see detector_proto).
    pitch = np.full(n, 30.0)

    # Walking between shots: steps at ~1.8 Hz.
    walking = (np.sin(2 * np.pi * t / 37) > 0.3).astype(float)
    acc[:, 2] += walking * 2.0 * np.sin(2 * np.pi * 1.8 * t)
    gyro[:, 1] += walking * 1.0 * np.sin(2 * np.pi * 0.9 * t)

    # Dribbling bursts: sharp hand pumps at ~2 Hz.
    for start in np.arange(15, t[-1] - 10, 47):
        for k in range(int(rng.integers(6, 14))):
            c = start + k * 0.5
            acc[:, 1] += 9.0 * bump(t, c, 0.05)
            gyro[:, 0] += 4.0 * bump(t, c, 0.06)

    # Shots: dip -> drive -> arm up with wrist flick + accel spike -> arm held up
    # ~1.3 s (follow-through) -> arm down. Labels 1.5-4 s after the release.
    events = []
    centers = np.arange(8, t[-1] - 6, 22)
    for c in centers + rng.uniform(-3, 3, len(centers)):
        acc[:, 2] -= 3.5 * bump(t, c - 0.55, 0.12)          # dip
        acc[:, 2] += rng.uniform(8, 12) * bump(t, c - 0.15, 0.1)  # drive
        gyro[:, 2] += rng.uniform(9, 14) * bump(t, c, 0.04)  # release flick
        acc[:, 0] += rng.uniform(40, 80) * bump(t, c + 0.05, 0.02)  # release spike
        pitch -= 92 * plateau(t, c - 0.05, c + rng.uniform(1.0, 1.6))  # arm raised and held
        label = "make" if rng.random() < 0.45 else "miss"
        events.append((c + rng.uniform(1.5, 4.0), label))

    p = np.radians(pitch)
    grav = np.stack([G * np.sin(p), np.zeros(n), G * np.cos(p)], axis=1)
    acc += grav

    start_ns = 123_456_789_000
    t_ns = start_ns + (t * 1e9).astype(np.int64) + 1_000_000
    raw = pd.DataFrame({
        "t_ns": t_ns, "t_rel_s": ((t_ns - start_ns) / 1e9).round(4),
        "ax": acc[:, 0], "ay": acc[:, 1], "az": acc[:, 2],
        "gx": gyro[:, 0], "gy": gyro[:, 1], "gz": gyro[:, 2],
        "grav_x": grav[:, 0], "grav_y": grav[:, 1], "grav_z": grav[:, 2],
        "qx": 0.0, "qy": 0.0, "qz": 0.0, "qw": 1.0,
    }).round(4)

    ev_ns = [start_ns + int(e * 1e9) for e, _ in events]
    events_df = pd.DataFrame({
        "t_ns": ev_ns,
        "t_rel_s": [round((x - start_ns) / 1e9, 4) for x in ev_ns],
        "wall_time": ["synthetic"] * len(events),
        "label": [lab for _, lab in events],
        "source": ["button"] * len(events),
    })
    return raw, events_df


def write_session(raw: pd.DataFrame, events: pd.DataFrame, out: Path, prefix: str) -> None:
    out.mkdir(parents=True, exist_ok=True)
    raw.to_csv(out / f"{prefix}_raw.csv", index=False)
    events.to_csv(out / f"{prefix}_events.csv", index=False)
    (out / f"{prefix}_meta.json").write_text(json.dumps({
        "session_id": prefix, "device_model": "SYNTHETIC", "android_sdk": 0, "start_wall_time": prefix,
    }, indent=2))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="../data/synthetic")
    ap.add_argument("--minutes", type=float, default=10)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    raw, events = generate(args.minutes, args.seed)
    prefix = "session_" + datetime.now().strftime("%Y%m%d_%H%M%S")
    write_session(raw, events, Path(args.out), prefix)
    print(f"Wrote {len(raw)} samples and {len(events)} labels to {args.out}/{prefix}_*")


if __name__ == "__main__":
    main()
