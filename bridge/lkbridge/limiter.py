"""Duty-Cycle-Begrenzer: Pausenregel (ta/duty) + gleitendes 1-h-Fenster."""
from __future__ import annotations

import asyncio
import json
import time
from collections import deque
from pathlib import Path
from typing import Awaitable, Callable


class DutyCycleLimiter:
    def __init__(self, duty: float, margin: float = 0.8, window_s: float = 3600.0,
                 clock: Callable[[], float] = time.time,
                 sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
                 state_file: str | None = None):
        if not 0 < duty <= 1:
            raise ValueError("duty muss in (0,1] liegen")
        self.duty = duty
        self.eff = duty * margin
        self.window_s = window_s
        self._clock, self._sleep = clock, sleep
        self._events: deque[tuple[float, float]] = deque()
        self._next_start = 0.0
        self._lock = asyncio.Lock()
        self._state_file = Path(state_file) if state_file else None
        self._load()

    # ------------------------------------------------------------ Planung
    def _prune(self, now: float) -> None:
        while self._events and self._events[0][0] <= now - self.window_s:
            self._events.popleft()

    def used_s(self) -> float:
        self._prune(self._clock())
        return sum(a for _, a in self._events)

    def budget_s(self) -> float:
        return self.eff * self.window_s

    def earliest(self, airtime: float) -> float:
        """Zeitpunkt (clock), ab dem ein Paket mit dieser Airtime erlaubt ist."""
        now = self._clock()
        self._prune(now)
        t = max(now, self._next_start)
        total = sum(a for _, a in self._events) + airtime
        if total > self.budget_s():
            # Fenster: so lange warten, bis genug alte Sendungen herausfallen
            acc = total
            for ts, a in self._events:
                acc -= a
                if acc <= self.budget_s():
                    t = max(t, ts + self.window_s)
                    break
        return t

    async def acquire(self, airtime: float) -> None:
        """Wartet bis die Sendung erlaubt ist und verbucht sie dann."""
        async with self._lock:
            while True:
                t = self.earliest(airtime)
                wait = t - self._clock()
                if wait <= 0:
                    break
                await self._sleep(wait)
            now = self._clock()
            self._events.append((now, airtime))
            self._next_start = now + airtime / self.eff
            self._save()

    # ------------------------------------------------------------ Persistenz
    def _save(self) -> None:
        if not self._state_file:
            return
        try:
            self._state_file.write_text(json.dumps(
                {"events": list(self._events), "next_start": self._next_start}))
        except OSError:
            pass

    def _load(self) -> None:
        if not self._state_file or not self._state_file.exists():
            return
        try:
            d = json.loads(self._state_file.read_text())
            self._events = deque((float(t), float(a)) for t, a in d["events"])
            self._next_start = float(d["next_start"])
        except (OSError, ValueError, KeyError):
            pass
