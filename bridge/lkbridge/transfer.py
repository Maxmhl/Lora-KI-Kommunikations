"""Zuverlässige Übertragung großer Objekte in Fragmenten (Selective-Repeat mit Fenster)."""
from __future__ import annotations

import asyncio
import logging
import time
from collections import OrderedDict, deque
from typing import Awaitable, Callable

from . import codec, rawframe
from .airtime import RadioParams, airtime_s, raw_packet_bytes, text_airtime_s
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
    """Sendet ein Envelope fragmentiert, hält Duty-Cycle ein und wartet auf ACKs.

    Standardweg: verschlüsselte MeshCore-Textnachrichten (LKP). Optional (``prefer_raw``): unverschlüsselte, per HMAC beglaubigte
    Rohdatenpakete (LKR, kompakter). Funktioniert der Rohdatenweg nicht (Firmware ohne Unterstützung, kein Pfad, keine ACKs), fällt
    der Sender automatisch auf den Textweg zurück und merkt sich das für eine Weile."""

    RAW_PAUSE_AFTER_FAILS = 2
    RAW_PAUSE_S = 3600.0

    def __init__(self, send_text: SendText, limiter: DutyCycleLimiter, radio: RadioParams,
                 ack_timeout_s: float = 90.0, max_retries: int = 6,
                 on_progress: Callable[[str, int, int, int], None] | None = None,
                 raw_send: Callable[[str, bytes], Awaitable[None]] | None = None,
                 raw_path_len: Callable[[str], int] | None = None, raw_key: bytes | None = None,
                 clock: Callable[[], float] = time.time):
        self._send_text = send_text
        self.limiter = limiter
        self.radio = radio
        self.ack_timeout_s = ack_timeout_s
        self.max_retries = max_retries
        self.on_progress = on_progress
        self._raw_send, self._raw_path_len, self._raw_key = raw_send, raw_path_len or (lambda peer: 0), raw_key
        self._clock = clock
        self._raw_fails: dict[str, int] = {}
        self._raw_paused_until: dict[str, float] = {}
        self._waiting: dict[tuple[str, int], asyncio.Queue] = {}

    @property
    def raw_available(self) -> bool:
        return self._raw_send is not None and self._raw_key is not None

    def raw_usable(self, peer: str) -> bool:
        return self.raw_available and self._clock() >= self._raw_paused_until.get(peer, 0.0)

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

    async def tx_raw(self, peer: str, frame: bytes) -> None:
        assert self._raw_send is not None
        await self.limiter.acquire(airtime_s(raw_packet_bytes(len(frame), self._raw_path_len(peer)), self.radio))
        await self._raw_send(peer, frame)

    def on_ack(self, peer: str, f: codec.Frame) -> None:
        q = self._waiting.get((peer, f.msg_id))
        if q is not None:
            q.put_nowait(f)

    async def send(self, peer: str, msg_id: int, envelope: bytes, prefer_raw: bool = False) -> bool:
        if prefer_raw and self.raw_usable(peer):
            chunks = [envelope[i:i + rawframe.MAX_CHUNK] for i in range(0, len(envelope), rawframe.MAX_CHUNK)] or [b""]
            key = self._raw_key

            async def tx_frame(i, total, chunk, poll):
                frame = rawframe.build(key, msg_id, i, total, chunk, poll)
                await self.tx_raw(peer, frame)
                return frame

            try:
                ok = await self._run(peer, msg_id, chunks, tx_frame, retries=1)
            except Exception as e:  # kein Pfad, Firmware lehnt ab, …
                log.info("Rohdatenweg nicht nutzbar (%s) – wechsle auf Textweg", e)
                ok = False
            if ok:
                self._raw_fails[peer] = 0
                return True
            self._raw_fails[peer] = self._raw_fails.get(peer, 0) + 1
            if self._raw_fails[peer] >= self.RAW_PAUSE_AFTER_FAILS:
                self._raw_paused_until[peer] = self._clock() + self.RAW_PAUSE_S
                log.warning("Rohdatenweg zu %s %d-mal ohne Bestätigung – für %d min pausiert", peer, self._raw_fails[peer], self.RAW_PAUSE_S // 60)
            msg_id = (msg_id + 1) % (codec.MAX_NUM + 1)  # neue ID: Teilstand des Rohdatenversuchs nicht vermischen
        chunks = codec.fragment(envelope)

        async def tx_text(i, total, chunk, poll):
            text = codec.format_data(msg_id, i, total, chunk, poll)
            await self.tx(peer, text)
            return text

        return await self._run(peer, msg_id, chunks, tx_text, retries=self.max_retries)

    async def _run(self, peer: str, msg_id: int, chunks: list[bytes], tx_frame, retries: int) -> bool:
        """Selective Repeat mit Fenster; [tx_frame] sendet ein Fragment und liefert das gesendete Paket (für Poll-Wiederholung)."""
        total = len(chunks)
        key = (peer, msg_id)
        q: asyncio.Queue = asyncio.Queue()
        self._waiting[key] = q
        received: set[int] = set()
        try:
            queue = deque(range(total))
            while len(received) < total:
                batch = [queue.popleft() for _ in range(min(codec.WINDOW, len(queue)))]
                sent = []
                for n, i in enumerate(batch):
                    sent.append(await tx_frame(i, total, chunks[i], n == len(batch) - 1))
                    if self.on_progress:
                        self.on_progress(peer, msg_id, len(received), total)
                left = retries
                while True:
                    try:
                        ack = await asyncio.wait_for(q.get(), self.ack_timeout_s)
                    except asyncio.TimeoutError:
                        left -= 1
                        if left < 0:
                            log.error("Keine ACK-Antwort von %s für %d", peer, msg_id)
                            return False
                        i = batch[-1]
                        await tx_frame(i, total, chunks[i], True)  # Poll wiederholen
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
