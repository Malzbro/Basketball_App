"""Plots for analyze_session.py. Import after choosing the matplotlib backend.

Style: one measure per panel (never two y-axes), a fixed colorblind-checked
palette (each measure always has the same color), thin lines, quiet grid, and
every panel names its measure in text so nothing relies on color alone.
See docs/reading_the_plots.md for how to read each figure.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

import detector_proto as dp
import wristshot_io as wio

# Palette (validated for color-vision deficiency on the light surface).
PITCH = "#2a78d6"   # blue: forearm angle
GYRO = "#eb6834"    # orange: wrist rotation speed
ACCEL = "#1baf7a"   # aqua: acceleration
MAKE = "#2a78d6"    # make/miss reuse slots 1-2 in the snippet plot (legend + labels)
MISS = "#eb6834"
INK = "#0b0b0b"
INK_MUTED = "#52514e"
GRID = "#e4e3df"
SURFACE = "#fcfcfb"
RAISED_FILL = "#2a78d6"

SNIPPET_HALF_WIDTH_S = 1.5
MAX_SNIPPETS = 40
ANATOMY_BEFORE_S = 2.0
ANATOMY_AFTER_S = 3.0
HIST_BINS = 30

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": INK_MUTED, "axes.titlecolor": INK,
    "xtick.color": INK_MUTED, "ytick.color": INK_MUTED, "axes.grid": True, "grid.color": GRID,
    "grid.linewidth": 0.6, "axes.spines.top": False, "axes.spines.right": False,
    "lines.linewidth": 1.0, "font.size": 9, "legend.frameon": False,
})


def _threshold(ax: plt.Axes, y: float, text: str) -> None:
    ax.axhline(y, color=INK_MUTED, lw=0.8, ls="--")
    ax.annotate(text, xy=(0, y), xycoords=("axes fraction", "data"), xytext=(4, 3),
                textcoords="offset points", ha="left", va="bottom", color=INK_MUTED, fontsize=8,
                bbox=dict(boxstyle="square,pad=0.15", fc=SURFACE, ec="none", alpha=0.9))


def _label_lines(axes, events: pd.DataFrame, t0: float = 0.0) -> None:
    """Your Make/Miss taps: thin dark lines, named in text on the top panel."""
    for _, e in events.iterrows():
        for ax in axes:
            ax.axvline(e.t - t0, color=INK, lw=0.7, alpha=0.5)
        axes[0].annotate(e.label, xy=(e.t - t0, 1), xycoords=("data", "axes fraction"), xytext=(2, -10),
                         textcoords="offset points", fontsize=7, color=INK_MUTED)


def plot_overview(raw: pd.DataFrame, events: pd.DataFrame, detections: pd.DataFrame, s: dict,
                  p: dp.DetectorParams, title: str, path: Path,
                  xlim: tuple[float, float] | None = None) -> plt.Figure:
    """Session timeline: arm angle, wrist rotation, acceleration, labels and detections."""
    view = raw if xlim is None else raw[raw.t.between(*xlim)]
    ev = events if xlim is None else events[events.t.between(*xlim)]
    fig, axes = plt.subplots(3, 1, figsize=(14, 8), sharex=True)

    axes[0].plot(view.t, view.pitch_deg, color=PITCH)
    axes[0].set_ylabel("Forearm angle (°)\nnegative = arm up")
    _threshold(axes[0], p.raise_pitch_max_deg, f"arm counts as raised below {p.raise_pitch_max_deg:.0f}°")

    axes[1].plot(view.t, view.gyro_mag, color=GYRO)
    axes[1].set_ylabel("Wrist rotation speed\n|gyro| (rad/s)")
    _threshold(axes[1], p.release_gyro_min, f"flick ≥ {p.release_gyro_min:g} rad/s")

    axes[2].plot(view.t, view.acc_mag, color=ACCEL)
    axes[2].axhline(wio.STANDARD_GRAVITY, color=GRID, lw=1)
    axes[2].set_ylabel("Acceleration\n|accel| (m/s²)")
    axes[2].set_xlabel("time since Start (s)")
    _threshold(axes[2], p.release_accel_min, f"spike ≥ {p.release_accel_min:g} m/s²")

    _label_lines(axes, ev)
    accepted = detections[detections.accepted].reset_index(drop=True)
    matched = {di for di, _ in s["pairs"]}
    for di, d in accepted.iterrows():
        if xlim is not None and not xlim[0] <= d.t_release <= xlim[1]:
            continue
        ok = di in matched
        axes[1].plot(d.t_release, d.peak_gyro, "v" if ok else "X", ms=8, color=INK if ok else GYRO,
                     mec=SURFACE, mew=1.5)
    axes[1].plot([], [], "v", color=INK, label="detected shot, matches a label")
    axes[1].plot([], [], "X", color=GYRO, label="detected, no label nearby")
    axes[1].legend(loc="upper left", fontsize=8)
    axes[0].set_title(title + "   (vertical lines = your Make/Miss taps)", loc="left")
    if xlim is not None:
        axes[0].set_xlim(*xlim)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig


def plot_shot_anatomy(raw: pd.DataFrame, shot: pd.Series, p: dp.DetectorParams, path: Path) -> plt.Figure:
    """One labeled shot, annotated step by step: what each number the detector uses means."""
    t0 = shot.t_release
    m = raw.t.between(t0 - ANATOMY_BEFORE_S, t0 + ANATOMY_AFTER_S)
    r = raw[m]
    tt = r.t - t0
    fig, axes = plt.subplots(3, 1, figsize=(10, 8.5), sharex=True)

    # 1. arm angle
    ax = axes[0]
    ax.plot(tt, r.pitch_deg, color=PITCH, lw=1.6)
    _threshold(ax, p.raise_pitch_max_deg, f"raised below {p.raise_pitch_max_deg:.0f}°")
    raised = r.pitch_deg.to_numpy() < p.raise_pitch_max_deg
    ax.fill_between(tt, r.pitch_deg.min() - 5, p.raise_pitch_max_deg, where=raised, color=RAISED_FILL,
                    alpha=0.10, lw=0)
    up = tt[raised]
    if len(up):
        ax.annotate(f"follow-through: arm held up\nhold = {shot.hold_s:.2f} s", xy=(up.iloc[0] + shot.hold_s / 2,
                    shot.min_pitch_deg), xytext=(0, -28), textcoords="offset points", ha="center", fontsize=8,
                    color=INK)
    ax.annotate(f"top angle {shot.min_pitch_deg:.0f}°", xy=(tt.iloc[int(np.argmin(r.pitch_deg))], shot.min_pitch_deg),
                xytext=(-70, -6), textcoords="offset points", fontsize=8, color=INK,
                arrowprops=dict(arrowstyle="-", color=INK_MUTED, lw=0.6))
    ax.set_ylabel("Forearm angle (°)\nnegative = arm up")
    ax.set_title(f"Anatomy of one {shot.label} at {t0:.1f} s: the three things the detector checks", loc="left")

    # 2. wrist rotation
    ax = axes[1]
    ax.plot(tt, r.gyro_mag, color=GYRO, lw=1.2)
    _threshold(ax, p.release_gyro_min, f"flick ≥ {p.release_gyro_min:g} rad/s")
    ax.plot(0, shot.peak_gyro, "o", ms=8, color=GYRO, mec=SURFACE, mew=1.5)
    ax.annotate(f"release flick\npeak {shot.peak_gyro:.1f} rad/s", xy=(0, shot.peak_gyro), xytext=(12, -6),
                textcoords="offset points", fontsize=8, color=INK)
    ax.set_ylabel("Wrist rotation speed\n|gyro| (rad/s)")

    # 3. acceleration
    ax = axes[2]
    ax.plot(tt, r.acc_mag, color=ACCEL, lw=1.0)
    ax.axhline(wio.STANDARD_GRAVITY, color=GRID, lw=1)
    ax.text(0.01, 0.95, "light line at 9.8 = gravity only (watch still)", transform=ax.transAxes,
            fontsize=8, color=INK_MUTED, va="top")
    _threshold(ax, p.release_accel_min, f"spike ≥ {p.release_accel_min:g} m/s²")
    ax.axvspan(0, p.release_accel_window_s, color=ACCEL, alpha=0.10, lw=0)
    ax.annotate(f"release spike {shot.release_accel:.0f} m/s²\n(within {p.release_accel_window_s:g} s after flick)",
                xy=(p.release_accel_window_s, shot.release_accel * 0.8), xytext=(8, 0), textcoords="offset points",
                fontsize=8, color=INK)
    ax.set_ylabel("Acceleration\n|accel| (m/s²)")
    ax.set_xlabel("seconds relative to the release (0 = flick)")

    delay = shot.label_t - t0
    for ax in axes:
        ax.axvline(0, color=INK, lw=0.8, ls=":")
        ax.axvline(delay, color=INK, lw=0.7, alpha=0.5)
    axes[0].annotate(f"you tapped {shot.label.upper()}\n{delay:.1f} s later", xy=(delay, 1),
                     xycoords=("data", "axes fraction"), xytext=(4, -24), textcoords="offset points",
                     fontsize=8, color=INK_MUTED)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig


def plot_feature_histograms(shots: pd.DataFrame, background: pd.DataFrame, p: dp.DetectorParams,
                            path: Path) -> plt.Figure:
    """For each gate: how your labeled shots vs other arm raises are spread, and where the threshold sits."""
    gates = [
        ("peak_gyro", "Release flick (rad/s)", p.release_gyro_min, "≥"),
        ("release_accel", "Release spike (m/s²)", p.release_accel_min, "≥"),
        ("hold_s", "Follow-through hold (s)", p.hold_min_s, "≥"),
    ]
    fig, axes = plt.subplots(1, 3, figsize=(13, 3.8))
    for ax, (col, name, thr, op) in zip(axes, gates):
        data = [shots[col], background[col]] if len(background) else [shots[col]]
        bins = np.histogram_bin_edges(np.concatenate([d.to_numpy() for d in data]), HIST_BINS)
        ax.hist(shots[col], bins=bins, color=PITCH, alpha=0.85, label=f"your labeled shots ({len(shots)})",
                edgecolor=SURFACE, linewidth=1)
        if len(background):
            ax.hist(background[col], bins=bins, histtype="step", color=GYRO, lw=1.6,
                    label=f"other arm raises ({len(background)})")
        ax.axvline(thr, color=INK, lw=1, ls="--")
        ax.annotate(f"threshold {op} {thr:g}", xy=(thr, 1), xycoords=("data", "axes fraction"), xytext=(4, -12),
                    textcoords="offset points", fontsize=8, color=INK)
        ax.set_xlabel(name)
        ax.set_ylabel("number of raises")
    axes[-1].legend(loc="upper right", fontsize=8)
    fig.suptitle("Where the thresholds come from: shots to the right of each dashed line pass that check",
                 x=0.01, ha="left", color=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig


def plot_snippets(raw: pd.DataFrame, shots: pd.DataFrame, path: Path) -> plt.Figure:
    """Every labeled shot, aligned at its release: shows how consistent the motion is."""
    fig, axes = plt.subplots(3, 1, figsize=(10, 8), sharex=True)
    t = raw.t.to_numpy()
    cols = [("pitch_deg", "Forearm angle (°)"), ("gyro_mag", "|gyro| (rad/s)"), ("acc_mag", "|accel| (m/s²)")]
    for _, sh in shots.head(MAX_SNIPPETS).iterrows():
        m = (t >= sh.t_release - SNIPPET_HALF_WIDTH_S) & (t <= sh.t_release + SNIPPET_HALF_WIDTH_S)
        color = MAKE if sh.label == "make" else MISS
        for ax, (col, _) in zip(axes, cols):
            ax.plot(t[m] - sh.t_release, raw[col].to_numpy()[m], color=color, alpha=0.35, lw=0.8)
    for ax, (_, name) in zip(axes, cols):
        ax.set_ylabel(name)
        ax.axvline(0, color=INK, lw=0.6, ls=":")
    axes[0].plot([], [], color=MAKE, label="make")
    axes[0].plot([], [], color=MISS, label="miss")
    axes[0].legend(loc="upper center", ncol=2)
    axes[2].set_xlabel("seconds relative to release")
    axes[0].set_title(f"{min(len(shots), MAX_SNIPPETS)} labeled shots stacked on top of each other, "
                      "lined up at the release", loc="left")
    fig.tight_layout()
    fig.savefig(path, dpi=110)
    return fig
