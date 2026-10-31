"""Run from the analysis/ folder with:  python -m pytest"""

from pathlib import Path

import numpy as np
import pandas as pd
import pytest

import detector_proto as dp
import make_synthetic_session as synth
import wristshot_io as wio


@pytest.fixture(scope="module")
def session(tmp_path_factory):
    """A 3-minute fake session written and read back exactly like a real one."""
    raw, events = synth.generate(minutes=3, seed=1)
    folder = tmp_path_factory.mktemp("sessions")
    synth.write_session(raw, events, folder, "session_20260101_120000")
    paths = wio.find_session(str(folder))
    raw = wio.load_raw(paths.raw)
    return raw, wio.load_events(paths.events, raw.attrs["start_ns"])


def test_find_session_accepts_any_of_the_files(tmp_path):
    raw, events = synth.generate(minutes=0.1, seed=2)
    synth.write_session(raw, events, tmp_path, "session_x")
    for name in ("session_x_raw.csv", "session_x_events.csv", "session_x_meta.json", "session_x"):
        assert wio.find_session(str(tmp_path / name)).prefix == "session_x"


def test_timestamps_line_up(session):
    raw, events = session
    rep = wio.alignment_report(raw, events)
    assert rep["ok"]
    assert rep["dropouts"] == 0
    assert rep["mean_rate_hz"] == pytest.approx(100, rel=1e-3)


def test_alignment_report_flags_clock_mismatch(session):
    raw, events = session
    shifted = events.copy()
    shifted["t_ns"] += 50_000_000  # events 50 ms off the raw clock
    assert not wio.alignment_report(raw, shifted)["ok"]


def test_detector_finds_synthetic_shots(session):
    raw, events = session
    p = dp.DetectorParams()
    s = dp.score(dp.detect(dp.Signals.from_raw(raw, p), p), events, dp.MatchWindow())
    assert s["recall"] == 1.0
    assert s["precision"] == 1.0


def test_match_prefers_latest_detection_before_label():
    pairs, fp, missed = dp.match(np.array([1.0, 4.0, 20.0]), np.array([5.0, 30.0]), dp.MatchWindow(6.0, 1.0))
    assert pairs == [(1, 0)]
    assert fp == [0, 2]
    assert missed == [1]


def make_signals(pitch: np.ndarray, gyro: np.ndarray, acc: np.ndarray) -> dp.Signals:
    t = np.arange(len(pitch)) / 100.0
    return dp.Signals(t=t, gyro=gyro, acc=acc, pitch=pitch, rate_hz=100.0)


def test_arm_raise_with_flick_is_a_shot_and_without_is_not():
    n = 400
    pitch = np.full(n, 30.0)
    pitch[100:230] = -60.0            # arm raised for 1.3 s
    pitch[300:320] = -60.0            # brief raise, 0.2 s
    gyro = np.full(n, 0.5)
    gyro[98] = 12.0                   # flick as the arm goes up
    gyro[299] = 12.0
    acc = np.full(n, 9.8)
    acc[101] = 60.0                   # release spike
    acc[301] = 60.0
    det = dp.detect(make_signals(pitch, gyro, acc), dp.DetectorParams())
    assert det["t_release"].round(2).tolist() == [0.98, 2.99]
    assert det["accepted"].tolist() == [True, False]
    assert det["reject_reason"].tolist() == ["", "short_hold"]
    assert det["hold_s"].iloc[0] == pytest.approx(1.3)


def test_min_quiet_rejects_second_raise_too_soon():
    n = 400
    pitch = np.full(n, 30.0)
    pitch[50:150] = -60.0
    pitch[170:270] = -60.0            # starts 1.2 s after the first
    gyro = np.full(n, 0.5)
    gyro[[49, 169]] = 12.0
    acc = np.full(n, 9.8)
    acc[[51, 171]] = 60.0
    det = dp.detect(make_signals(pitch, gyro, acc), dp.DetectorParams(min_quiet_s=2.0))
    assert det["reject_reason"].tolist() == ["", "too_soon"]


def test_best_plateau_picks_middle():
    sweep = pd.DataFrame({"release_gyro_min": [1.0, 2.0, 3.0, 4.0], "f1": [0.5, 0.9, 0.9, 0.4]})
    assert dp.best_plateau(sweep) == (2.0, 3.0, 2.5)


RECORDINGS = Path(__file__).resolve().parents[2] / "recordings"
# Real-data regression floor: the session the defaults were tuned on scored F1 0.90.
MIN_F1_ON_RECORDING = 0.85


@pytest.mark.parametrize("prefix", ["session_20261030_163316"])
def test_detector_on_real_recording(prefix):
    try:
        paths = wio.find_session(str(RECORDINGS / prefix))
    except FileNotFoundError:
        pytest.skip(f"{prefix} not in recordings/")
    raw = wio.load_raw(paths.raw)
    events = wio.load_events(paths.events, raw.attrs["start_ns"])
    p = dp.DetectorParams()
    s = dp.score(dp.detect(dp.Signals.from_raw(raw, p), p), events, dp.MatchWindow())
    assert s["f1"] >= MIN_F1_ON_RECORDING
