import asyncio
import os
import random

import pytest

from lkbridge import codec, rawframe
from lkbridge.airtime import RadioParams, airtime_s, mesh_packet_bytes, raw_packet_bytes
from lkbridge.limiter import DutyCycleLimiter
from lkbridge.transfer import Receiver, Sender

from .test_transfer import VClock

KEY = rawframe.derive_key("ein-gemeinsames-geheimnis")


def test_frame_roundtrip_and_authenticity():
    chunk = os.urandom(rawframe.MAX_CHUNK)
    f = rawframe.build(KEY, 77, 5, 40, chunk, poll=True)
    assert len(f) == 160                                         # Kopf 8 + Daten 148 + Tag 4
    assert rawframe.parse(KEY, f) == (77, 5, 40, chunk, True)
    # Fälschung / Übertragungsfehler / falsches Geheimnis werden verworfen
    bad = bytearray(f); bad[20] ^= 1
    assert rawframe.parse(KEY, bytes(bad)) is None
    assert rawframe.parse(rawframe.derive_key("anderes-geheimnis!"), f) is None
    assert rawframe.parse(KEY, f[:-1]) is None and rawframe.parse(KEY, b"") is None
    assert rawframe.parse(KEY, b"\x00" + f[1:]) is None           # falsche Magic
    with pytest.raises(ValueError):
        rawframe.build(KEY, 1, 0, 1, b"x" * 149, False)
    with pytest.raises(ValueError):
        rawframe.build(KEY, 1, 5, 5, b"", False)


def test_raw_is_denser_than_text_per_airtime():
    r = RadioParams()
    text_pkt = airtime_s(mesh_packet_bytes(codec.TEXT_MAX), r)                 # 118 Nutzbyte
    raw_pkt = airtime_s(raw_packet_bytes(160, 0), r)                           # 148 Nutzbyte
    per_byte_text, per_byte_raw = text_pkt / codec.FRAGMENT_BYTES, raw_pkt / rawframe.MAX_CHUNK
    assert per_byte_raw < per_byte_text * 0.85                                 # mindestens ~15 % weniger Funkzeit je Nutzbyte


class Link:
    """Zwei Seiten: Server (Sender) und Handy (Receiver, Rohdaten + Text, ACKs per Text zurück)."""

    def __init__(self, raw_ok=True, raw_drop=lambda f: False, text_drop=lambda t: False, path_len=0):
        self.c = VClock()
        self.raw_frames, self.texts = [], []
        self.raw_ok, self.raw_drop, self.text_drop = raw_ok, raw_drop, text_drop
        self.receiver = Receiver()
        self.completed = []
        lim = DutyCycleLimiter(0.10, clock=self.c, sleep=self.c.sleep)
        self.server = Sender(self._text_to_phone, lim, RadioParams(), ack_timeout_s=0.05, max_retries=20,
                             raw_send=self._raw_to_phone, raw_path_len=lambda p: path_len, raw_key=KEY, clock=self.c)

    async def _raw_to_phone(self, peer, frame):
        if not self.raw_ok:
            raise LookupError("kein Pfad")
        self.raw_frames.append(frame)
        if self.raw_drop(frame):
            return
        parsed = rawframe.parse(KEY, frame)
        if parsed is None:
            return
        mid, idx, total, chunk, poll = parsed
        await self._phone_receive(codec.Frame(codec.T_POLL if poll else codec.T_DATA, mid, idx, total, chunk))

    async def _text_to_phone(self, peer, text):
        self.texts.append(text)
        if self.text_drop(text):
            return
        f = codec.parse_frame(text)
        if f is not None and f.type in (codec.T_DATA, codec.T_POLL):
            await self._phone_receive(f)

    async def _phone_receive(self, f):
        reply, env = self.receiver.on_frame("srv", f)
        if env is not None:
            self.completed.append(env)
        if reply:  # ACK geht (wie in der App) als Textnachricht zurück zum Server
            ack = codec.parse_frame(reply)
            asyncio.get_running_loop().call_soon(lambda: self.server.on_ack("phone", ack))


def envelope(n=3000):
    return codec.build_envelope(codec.KIND_NOMAD_RESP, 1, os.urandom(n))


async def test_raw_transfer_succeeds_and_is_faster_than_text():
    env = envelope()
    raw = Link()
    assert await raw.server.send("phone", 7, env, prefer_raw=True)
    assert raw.completed == [env] and raw.texts == [] and raw.raw_frames
    txt = Link()
    assert await txt.server.send("phone", 7, env, prefer_raw=False)
    assert txt.completed == [env] and txt.raw_frames == []
    assert raw.c.t < txt.c.t * 0.85                                           # Rohdatenweg braucht spürbar weniger Funkzeit


async def test_raw_with_packet_loss_still_delivers():
    rnd = random.Random(3)
    link = Link(raw_drop=lambda f: rnd.random() < 0.2)
    link.server.max_retries = 30
    env = envelope(1500)
    # bei Verlust darf der Rohweg mehrfach nachfordern; gelingt es nicht rechtzeitig, rettet der Textweg die Übertragung
    assert await link.server.send("phone", 9, env, prefer_raw=True)
    assert link.completed[-1] == env


async def test_fallback_to_text_when_raw_unavailable_and_pause_after_failures():
    link = Link(raw_ok=False)                                                  # z. B. kein Pfad / Firmware ohne Rohdaten
    env = envelope(800)
    assert await link.server.send("phone", 1, env, prefer_raw=True)
    assert link.completed == [env] and link.texts and not link.raw_frames
    assert await link.server.send("phone", 2, env, prefer_raw=True)
    assert not link.server.raw_usable("phone")                                # nach 2 Fehlschlägen pausiert der Rohweg
    n_raw_calls = len(link.raw_frames)
    assert await link.server.send("phone", 3, env, prefer_raw=True)
    assert len(link.raw_frames) == n_raw_calls                                # während der Pause wird gar nicht erst probiert
    link.c.t += Sender.RAW_PAUSE_S + 1
    assert link.server.raw_usable("phone")                                    # danach wieder


async def test_fallback_when_raw_frames_never_arrive():
    link = Link(raw_drop=lambda f: True)                                      # Pakete gehen verloren / Empfänger versteht sie nicht
    env = envelope(600)
    assert await link.server.send("phone", 4, env, prefer_raw=True)
    assert link.completed == [env] and link.texts                             # Textweg hat übernommen


async def test_forged_raw_frames_are_ignored_by_phone():
    link = Link()
    forged = rawframe.build(rawframe.derive_key("fremdes-geheimnis!!"), 5, 0, 1, b"gefaelscht", True)
    await link._raw_to_phone("phone", forged)
    assert link.completed == []                                                # Tag passt nicht -> verworfen
