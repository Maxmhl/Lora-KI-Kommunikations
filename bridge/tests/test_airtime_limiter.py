import pytest

from lkbridge.airtime import RadioParams, airtime_s, mesh_packet_bytes, subband_limits
from lkbridge.limiter import DutyCycleLimiter


def test_airtime_reference():
    r = RadioParams(869.618, 62.5, 8, 8, 16)
    assert airtime_s(160, r) == pytest.approx(1.4592, abs=1e-3)


def test_packet_size_estimate():
    assert mesh_packet_bytes(140) == 1 + 1 + 4 + 160


def test_subbands():
    assert subband_limits(869.618)[:2] == (0.10, 500)
    assert subband_limits(868.1)[0] == 0.01
    assert subband_limits(868.9)[0] == 0.001
    assert subband_limits(869.3)[2] is False


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    async def sleep(self, s):
        self.t += s


async def test_pause_rule():
    c = Clock()
    lim = DutyCycleLimiter(0.10, margin=1.0, clock=c, sleep=c.sleep)
    start = c.t
    await lim.acquire(1.0)
    await lim.acquire(1.0)
    assert c.t - start == pytest.approx(10.0)  # 1 s Sendung -> 9 s Pause (1 s on / 10 s Zyklus)


async def test_hourly_budget_never_exceeded():
    c = Clock()
    lim = DutyCycleLimiter(0.01, margin=1.0, clock=c, sleep=c.sleep)
    t0 = c.t
    sent = []
    for _ in range(60):
        await lim.acquire(1.5)
        sent.append((c.t, 1.5))
        # in jedem 1-h-Fenster höchstens 36 s Airtime
        used = sum(a for t, a in sent if t > c.t - 3600)
        assert used <= 36.0 + 1e-6
    assert c.t - t0 > 3600  # 60*1.5 s = 90 s bei 1 % braucht > 1 h


async def test_state_persistence(tmp_path):
    c = Clock()
    f = str(tmp_path / "s.json")
    a = DutyCycleLimiter(0.1, clock=c, sleep=c.sleep, state_file=f)
    await a.acquire(2.0)
    b = DutyCycleLimiter(0.1, clock=c, sleep=c.sleep, state_file=f)
    assert b.used_s() == 2.0 and b.earliest(1.0) > c.t
