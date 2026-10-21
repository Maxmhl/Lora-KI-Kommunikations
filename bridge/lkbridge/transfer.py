"""Zuverlässige Übertragung großer Objekte in Fragmenten (Selective-Repeat mit Fenster)."""
from __future__ import annotations

import asyncio
import logging
import time
from collections import OrderedDict, deque
from typing import Awaitable, Callable

from . import codec
from .airtime import RadioParams, text_airtime_s
from .limiter import DutyCycleLimiter

log = logging.getLogger("lkbridge.transfer")

SendText = Callable[[str, str], Awaitable[None]]


class _Partial:
    def __init__(self, total: int):
        self.total = total
        self.parts: dict[int, bytes] = {}
        self.updated = time.time()


class Receiver:
    """Setzt Fragmente zusammen und erzeugt die zugehörigen ACK-Texte."""

    def __init__(self, max_object_bytes: int = 200_000, ttl_s: float = 6 * 3600):
        self.max_fragments = max(1, max_object_bytes // codec.FRAGMENT_BYTES)
        self.ttl_s = ttl_s
        self.active: dict[tuple[str, int], _Partial] = {}
        self.done: OrderedDict[tuple[str, int], int] = OrderedDict()

    def _gc(self) -> None:
        now = time.time()
        for k in [k for k, p in self.active.items() if now - p.updated > self.ttl_s]:
            del self.active[k]

    def on_frame(self, peer: str, f: codec.Frame) -> tuple[str | None, bytes | None]:
        """-> (Antworttext oder None, fertiges Envelope oder None)"""
        self._gc()
        key = (peer, f.msg_id)
        poll = f.type == codec.T_POLL
        if key in self.done:  # Duplikat eines abgeschlossenen Transfers
            return (codec.format_ack(f.msg_id, self.done[key], []) if poll else None), None
        if f.total > self.max_fragments:
            return codec.format_abort(f.msg_id), None
        p = self.active.get(key)
        if p is None or p.total != f.total:
            p = self.active[key] = _Partial(f.total)
        p.parts[f.idx] = f.body
        p.updated = time.time()
        if len(p.parts) == p.total:
            data = b"".join(p.parts[i] for i in range(p.total))
            del self.active[key]
            self.done[key] = p.total
            while len(self.done) > 256:
                self.done.popitem(last=False)
            return codec.format_ack(f.msg_id, p.total, []), data
        if poll:
            hi = max(p.parts) + 1
            missing = [i for i in range(hi) if i not in p.parts]
            if len(missing) > codec.MAX_MISSING:
                # Liste passt nicht in ein Paket: hi bis zur ersten ausgelassenen Lücke kürzen,
                # damit der Sender nie Fragmente als empfangen annimmt, die fehlen.
                hi = missing[codec.MAX_MISSING]
                missing = missing[:codec.MAX_MISSING]
            return codec.format_ack(f.msg_id, hi, missing), None
        return None, None


class Sender:
    """Sendet ein Envelope fragmentiert, hält Duty-Cycle ein und wartet auf ACKs."""

    def __init__(self, send_text: SendText, limiter: DutyCycleLimiter, radio: RadioParams,
                 ack_timeout_s: float = 90.0, max_retries: int = 6,
                 on_progress: Callable[[str, int, int, int], None] | None = None):
        self._send_text = send_text
        self.limiter = limiter
        self.radio = radio
        self.ack_timeout_s = ack_timeout_s
        self.max_retries = max_retries
        self.on_progress = on_progress
        self._waiting: dict[tuple[str, int], asyncio.Queue] = {}

    async def tx(self, peer: str, text: str) -> None:
        """Einzelnes Textpaket unter Duty-Cycle-Kontrolle senden."""
        await self.limiter.acquire(text_airtime_s(text, self.radio))
        for attempt in range(3):
            try:
                await self._send_text(peer, text)
                return
            except Exception as e:  # Node kurzzeitig nicht erreichbar
                log.warning("Senden fehlgeschlagen (%s), Versuch %d", e, attempt + 1)
                await asyncio.sleep(5)
        raise ConnectionError("Node nicht erreichbar")

    def on_ack(self, peer: str, f: codec.Frame) -> None:
        q = self._waiting.get((peer, f.msg_id))
        if q is not None:
            q.put_nowait(f)

    async def send(self, peer: str, msg_id: int, envelope: bytes) -> bool:
        chunks = codec.fragment(envelope)
        total = len(chunks)
        key = (peer, msg_id)
        q: asyncio.Queue = asyncio.Queue()
        self._waiting[key] = q
        received: set[int] = set()
        try:
            queue = deque(range(total))
            while len(received) < total:
                batch = [queue.popleft() for _ in range(min(codec.WINDOW, len(queue)))]
                texts = [codec.format_data(msg_id, i, total, chunks[i], poll=(n == len(batch) - 1))
                         for n, i in enumerate(batch)]
                for t in texts:
                    await self.tx(peer, t)
                    if self.on_progress:
                        self.on_progress(peer, msg_id, len(received), total)
                retries = self.max_retries
                while True:
                    try:
                        ack = await asyncio.wait_for(q.get(), self.ack_timeout_s)
                    except asyncio.TimeoutError:
                        retries -= 1
                        if retries < 0:
                            log.error("Keine ACK-Antwort von %s für %d", peer, msg_id)
                            return False
                        await self.tx(peer, texts[-1])  # Poll wiederholen
                        continue
                    if ack.type == codec.T_ABORT:
                        return False
                    break
                received = set(range(min(ack.hi, total))) - set(ack.missing)
                queue = deque(i for i in range(total) if i not in received)
                if self.on_progress:
                    self.on_progress(peer, msg_id, len(received), total)
            return True
        finally:
            self._waiting.pop(key, None)
