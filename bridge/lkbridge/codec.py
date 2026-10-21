"""LKP v1 – Wire-Format (siehe protocol/PROTOCOL.md).

Alles läuft als MeshCore-Textnachricht (ASCII). Binärdaten werden basE91-kodiert.
Die Kotlin-Implementierung in core/ muss byte-identisch arbeiten; gemeinsame
Testvektoren liegen in protocol/test-vectors.json.
"""
from __future__ import annotations

import zlib
from dataclasses import dataclass, field
from pathlib import Path

from . import base91

NUM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
MAX_NUM = 64 * 64 - 1  # 4095

MARKER = "~"
T_DATA = "D"
T_POLL = "P"  # Daten-Fragment mit Ack-Anforderung (letztes Fragment eines Fensters)
T_ACK = "A"
T_ABORT = "X"

HEADER_CHARS = 8  # ~ + Typ + id(2) + idx(2) + total(2)
# 5 Byte (Zeitstempel+Flags) + 154 Zeichen + 1 (Terminator) = 160 = exakt 10 AES-Blöcke:
# jedes Zeichen mehr würde einen weiteren 16-Byte-Block (mehr Airtime) kosten.
TEXT_MAX = 154
BODY_CHARS_MAX = TEXT_MAX - HEADER_CHARS  # 146
FRAGMENT_BYTES = 118  # basE91-Worst-Case: 118 Byte -> höchstens 146 Zeichen
WINDOW = 6
MAX_MISSING = 12

KIND_TEXT = 1
KIND_IMAGE = 2
KIND_FILE = 3
KIND_REPLY = 4
KIND_STATUS = 5
KIND_RESET = 6
KIND_MODEL_LIST_REQ = 7  # App -> Server: verfügbare Modelle anfordern (leere Nutzlast)
KIND_MODEL_LIST = 8  # Server -> App: Zeilen 'name\tflags' (flags: v = Bildverständnis)
KIND_SET_MODEL = 9  # App -> Server: Modellname für diese conv (leer = Server-Standard)

FLAG_DEFLATE = 0x02  # rohes Deflate (ohne zlib-Header)
FLAG_DICT = 0x04  # zusätzlich mit eingebautem Wörterbuch (dictionary.txt)
MAX_INFLATED = 8 * 1024 * 1024


class ProtocolError(ValueError):
    pass


# Gemeinsames Vorab-Wörterbuch; muss byte-identisch zu core/src/main/resources/de/loraki/core/dictionary.txt sein.
DICTIONARY = (Path(__file__).with_name("dictionary.txt")).read_bytes()


def _deflate(data: bytes, use_dict: bool) -> bytes:
    c = (zlib.compressobj(9, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY, DICTIONARY) if use_dict
         else zlib.compressobj(9, zlib.DEFLATED, -15))
    return c.compress(data) + c.flush()


def _inflate(data: bytes, use_dict: bool) -> bytes:
    d = zlib.decompressobj(-15, DICTIONARY) if use_dict else zlib.decompressobj(-15)
    try:
        out = d.decompress(data, MAX_INFLATED + 1)
    except zlib.error as e:
        raise ProtocolError(f"deflate: {e}") from e
    if len(out) > MAX_INFLATED:
        raise ProtocolError("Entpackte Daten zu groß")
    return out


def enc_num(n: int) -> str:
    if not 0 <= n <= MAX_NUM:
        raise ProtocolError(f"Zahl außerhalb 0..{MAX_NUM}: {n}")
    return NUM_ALPHABET[n >> 6] + NUM_ALPHABET[n & 63]


def dec_num(s: str) -> int:
    if len(s) != 2:
        raise ProtocolError("Zahl braucht 2 Zeichen")
    a, b = NUM_ALPHABET.find(s[0]), NUM_ALPHABET.find(s[1])
    if a < 0 or b < 0:
        raise ProtocolError("Ungültiges Zeichen in Zahl")
    return (a << 6) | b


# ---------------------------------------------------------------- Envelope

@dataclass
class Envelope:
    kind: int
    conv: int
    payload: bytes


def build_envelope(kind: int, conv: int, payload: bytes, compress: bool = True) -> bytes:
    flags, body = 0, payload
    if compress and payload:
        for f, use_dict in ((FLAG_DEFLATE, False), (FLAG_DEFLATE | FLAG_DICT, True)):
            z = _deflate(payload, use_dict)
            if len(z) < len(body):
                flags, body = f, z
    rest = bytes([flags, kind, conv]) + body
    return zlib.crc32(rest).to_bytes(4, "big") + rest


def parse_envelope(data: bytes) -> Envelope:
    if len(data) < 7:
        raise ProtocolError("Envelope zu kurz")
    crc, rest = int.from_bytes(data[:4], "big"), data[4:]
    if zlib.crc32(rest) != crc:
        raise ProtocolError("CRC32 stimmt nicht")
    flags, kind, conv = rest[0], rest[1], rest[2]
    body = rest[3:]
    if flags & FLAG_DEFLATE:
        body = _inflate(body, bool(flags & FLAG_DICT))
    elif flags:
        raise ProtocolError(f"Unbekannte Flags: {flags:#x}")
    return Envelope(kind, conv, body)


def pack_file(name: str, mime: str, data: bytes) -> bytes:
    n, m = name.encode()[:255], mime.encode()[:255]
    return bytes([len(n)]) + n + bytes([len(m)]) + m + data


def unpack_file(payload: bytes) -> tuple[str, str, bytes]:
    try:
        nl = payload[0]
        name = payload[1:1 + nl].decode()
        ml = payload[1 + nl]
        mime = payload[2 + nl:2 + nl + ml].decode()
        return name, mime, payload[2 + nl + ml:]
    except (IndexError, UnicodeDecodeError) as e:
        raise ProtocolError("Ungültige Datei-Nutzlast") from e


# ---------------------------------------------------------------- Frames

@dataclass
class Frame:
    type: str
    msg_id: int
    idx: int = 0
    total: int = 0
    body: bytes = b""
    hi: int = 0
    missing: list[int] = field(default_factory=list)


def fragment(envelope: bytes) -> list[bytes]:
    chunks = [envelope[i:i + FRAGMENT_BYTES] for i in range(0, len(envelope), FRAGMENT_BYTES)]
    if len(chunks) > MAX_NUM:
        raise ProtocolError("Objekt zu groß")
    return chunks or [b""]


def format_data(msg_id: int, idx: int, total: int, chunk: bytes, poll: bool) -> str:
    t = T_POLL if poll else T_DATA
    return (MARKER + t + enc_num(msg_id) + enc_num(idx) + enc_num(total)
            + base91.encode(chunk))


def format_ack(msg_id: int, hi: int, missing: list[int]) -> str:
    return (MARKER + T_ACK + enc_num(msg_id) + enc_num(hi)
            + "".join(enc_num(m) for m in missing[:MAX_MISSING]))


def format_abort(msg_id: int) -> str:
    return MARKER + T_ABORT + enc_num(msg_id)


def parse_frame(text: str) -> Frame | None:
    """Gibt None zurück, wenn der Text kein LKP-Frame ist."""
    if len(text) < 4 or text[0] != MARKER:
        return None
    t = text[1]
    try:
        mid = dec_num(text[2:4])
        if t in (T_DATA, T_POLL):
            if len(text) < HEADER_CHARS:
                return None
            idx, total = dec_num(text[4:6]), dec_num(text[6:8])
            if total == 0 or idx >= total:
                return None
            body = base91.decode(text[8:])
            return Frame(t, mid, idx, total, body)
        if t == T_ACK:
            hi = dec_num(text[4:6])
            rest = text[6:]
            if len(rest) % 2:
                return None
            return Frame(t, mid, hi=hi, missing=[dec_num(rest[i:i + 2]) for i in range(0, len(rest), 2)])
        if t == T_ABORT:
            return Frame(t, mid)
    except (ProtocolError, ValueError):
        return None
    return None
