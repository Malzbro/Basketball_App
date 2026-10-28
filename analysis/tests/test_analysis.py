"""Run from the analysis/ folder with:  python -m pytest"""

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
    s = dp.score(dp.detect(dp.Signals.from_raw(raw, p.smooth_s), p), events, dp.MatchWindow())
    assert s["recall"] == 1.0
    assert s["precision"] == 1.0


def test_match_prefers_latest_detection_before_label():
    pairs, fp, missed = dp.match(np.array([1.0, 4.0, 20.0]), np.array([5.0, 30.0]), dp.MatchWindow(6.0, 1.0))
    assert pairs == [(1, 0)]
    assert fp == [0, 2]
    assert missed == [1]


def test_hold_duration():
    t = np.arange(0, 2, 0.01)
    gyro = np.zeros_like(t)
    gyro[:50] = 10.0          # moving until 0.5 s
    gyro[100:120] = 10.0      # moving again 1.0-1.2 s
    sig = dp.Signals(t=t, gyro=gyro, acc=np.full_like(t, 9.8), pitch=np.zeros_like(t), rate_hz=100.0)
    hold = dp.hold_duration(sig, 49, dp.DetectorParams(hold_gyro_max=3.0, hold_search_s=0.6))
    assert hold == pytest.approx(0.49, abs=1e-9)  # quiet from 0.50 s to 0.99 s


def test_best_plateau_picks_middle():
    sweep = pd.DataFrame({"release_gyro_min": [1.0, 2.0, 3.0, 4.0], "f1": [0.5, 0.9, 0.9, 0.4]})
    assert dp.best_plateau(sweep) == (2.0, 3.0, 2.5)
