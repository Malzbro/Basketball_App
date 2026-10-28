"""Plots for analyze_session.py. Import after choosing the matplotlib backend."""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

import detector_proto as dp
import wristshot_io as wio

LABEL_COLORS = {"make": "tab:green", "miss": "tab:red", "shot": "tab:gray"}
SNIPPET_HALF_WIDTH_S = 1.5
MAX_SNIPPETS = 40


def plot_overview(raw: pd.DataFrame, events: pd.DataFrame, detections: pd.DataFrame, s: dict,
                  p: dp.DetectorParams, title: str, path: Path) -> plt.Figure:
    """Whole session: |accel|, |gyro| and pitch with labels and detections on top."""
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
    accepted = detections[detections.accepted].reset_index(drop=True)
    matched = {di for di, _ in s["pairs"]}
    for di, d in accepted.iterrows():
        ok = di in matched
        axes[1].plot(d.t_release, d.peak_gyro, "^" if ok else "x", ms=8,
                     color="tab:green" if ok else "tab:red")
    rejected = detections[~detections.accepted]
    axes[1].plot(rejected.t_release, rejected.peak_gyro, "o", mfc="none", color="gray", ms=6)

    axes[0].legend(loc="upper right")
    axes[1].legend(loc="upper right")
    axes[0].set_title(title + "\nvertical lines = your labels (green make, red miss, gray shot?) | "
                      "▲ detected & matched, ✕ false positive, ○ rejected candidate")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig


def plot_snippets(raw: pd.DataFrame, shots: pd.DataFrame, path: Path) -> plt.Figure:
    """Every labeled shot, aligned at its release peak: shows how consistent the motion is."""
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
