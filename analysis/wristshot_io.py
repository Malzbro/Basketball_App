"""Loading WristShot session files and checking that they line up.

A session recorded on the watch produces three files with the same prefix:

    session_YYYYMMDD_HHMMSS_raw.csv     sensor samples (~100 Hz)
    session_YYYYMMDD_HHMMSS_events.csv  your Make / Miss / Shot? labels
    session_YYYYMMDD_HHMMSS_meta.json   device + recording info

Both CSVs use the same clock (t_ns = Android's elapsedRealtimeNanos), so a
label's t_ns can be compared directly with the samples' t_ns.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

# A gap between samples longer than this counts as dropped data.
DROPOUT_GAP_S = 0.1
# Raw and events files must agree on the session start to within this much.
CLOCK_TOLERANCE_S = 0.001
STANDARD_GRAVITY = 9.80665


@dataclass
class SessionPaths:
    prefix: str
    raw: Path
    events: Path
    meta: Path | None


def find_session(path_like: str) -> SessionPaths:
    """Accepts a raw CSV, an events CSV, a session prefix, or a folder.

    For a folder, the most recent session in it is used.
    """
    path = Path(path_like)
    if path.is_dir():
        raws = sorted(path.glob("session_*_raw.csv"))
        if not raws:
            raise FileNotFoundError(f"No session_*_raw.csv files in {path}")
        path = raws[-1]
    name = path.name
    for suffix in ("_raw.csv", "_events.csv", "_meta.json"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
    folder = path.parent
    raw = folder / f"{name}_raw.csv"
    events = folder / f"{name}_events.csv"
    meta = folder / f"{name}_meta.json"
    if not raw.exists():
        raise FileNotFoundError(raw)
    if not events.exists():
        raise FileNotFoundError(events)
    return SessionPaths(name, raw, events, meta if meta.exists() else None)


def load_raw(path: Path) -> pd.DataFrame:
    """Loads the raw CSV and adds handy derived columns.

    Added columns:
      t            seconds since session start (same as t_rel_s, from t_ns)
      acc_mag      |accel| in m/s^2 (about 9.8 when still)
      gyro_mag     |gyro| in rad/s (0 when still)
      lin_acc_mag  |accel - gravity| in m/s^2 (motion without gravity)
      pitch_deg    angle of the watch x axis above the horizon, from gravity.
                   The x axis runs along the forearm, so this is roughly
                   forearm elevation. Its sign depends on which wrist and
                   which way round the watch is worn.
    """
    df = pd.read_csv(path)
    start_ns = int(df["t_ns"].iloc[0] - round(df["t_rel_s"].iloc[0] * 1e9))
    df["t"] = (df["t_ns"] - start_ns) / 1e9
    df["acc_mag"] = np.sqrt(df.ax**2 + df.ay**2 + df.az**2)
    df["gyro_mag"] = np.sqrt(df.gx**2 + df.gy**2 + df.gz**2)
    lin = df[["ax", "ay", "az"]].to_numpy() - df[["grav_x", "grav_y", "grav_z"]].to_numpy()
    df["lin_acc_mag"] = np.sqrt((lin**2).sum(axis=1))
    g = np.sqrt(df.grav_x**2 + df.grav_y**2 + df.grav_z**2).replace(0, np.nan)
    df["pitch_deg"] = np.degrees(np.arcsin(np.clip(df.grav_x / g, -1, 1)))
    df.attrs["start_ns"] = start_ns
    return df


def load_events(path: Path, start_ns: int | None = None) -> pd.DataFrame:
    """Loads the events CSV. With start_ns (from load_raw) times use the raw file's origin."""
    ev = pd.read_csv(path)
    if start_ns is None:
        start_ns = int(ev["t_ns"].iloc[0] - round(ev["t_rel_s"].iloc[0] * 1e9)) if len(ev) else 0
    ev["t"] = (ev["t_ns"] - start_ns) / 1e9
    return ev


def load_meta(path: Path | None) -> dict:
    if path is None:
        return {}
    return json.loads(path.read_text())


def alignment_report(raw: pd.DataFrame, events: pd.DataFrame) -> dict:
    """Checks the acceptance criterion: raw and event timestamps line up."""
    dt = np.diff(raw["t_ns"].to_numpy()) / 1e9
    raw_origin = raw["t_ns"] - raw["t_rel_s"] * 1e9
    report = {
        "samples": len(raw),
        "duration_s": float(raw["t"].iloc[-1] - raw["t"].iloc[0]) if len(raw) else 0.0,
        "mean_rate_hz": float(1 / dt.mean()) if len(dt) else 0.0,
        "median_dt_ms": float(np.median(dt) * 1000) if len(dt) else 0.0,
        "max_gap_ms": float(dt.max() * 1000) if len(dt) else 0.0,
        "dropouts": int((dt > DROPOUT_GAP_S).sum()),
        "non_monotonic": int((dt <= 0).sum()),
        "events": len(events),
        "events_outside_raw": 0,
        "clock_mismatch_ms": 0.0,
    }
    if len(events):
        inside = events["t"].between(raw["t"].iloc[0], raw["t"].iloc[-1])
        report["events_outside_raw"] = int((~inside).sum())
        ev_origin = events["t_ns"] - events["t_rel_s"] * 1e9
        report["clock_mismatch_ms"] = float(abs(ev_origin.median() - raw_origin.median()) / 1e6)
    report["ok"] = (
        report["non_monotonic"] == 0
        and report["events_outside_raw"] == 0
        and report["clock_mismatch_ms"] <= CLOCK_TOLERANCE_S * 1000
    )
    return report


def sample_rate(raw: pd.DataFrame) -> float:
    dt = np.median(np.diff(raw["t"].to_numpy()))
    return float(1.0 / dt) if dt > 0 else 100.0
