"""LKP v1 – Wire-Format (siehe protocol/PROTOCOL.md).

Alles läuft als MeshCore-Textnachricht (UTF-8). Binärdaten werden Base64-kodiert.
Die Kotlin-Implementierung in core/ muss byte-identisch arbeiten; gemeinsame
Testvektoren liegen in protocol/test-vectors.json.
"""
from __future__ import annotations

import base64
import binascii
import zlib
from dataclasses import dataclass, field

NUM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
MAX_NUM = 64 * 64 - 1  # 4095

MARKER = "~"
T_DATA = "D"
T_POLL = "P"  # Daten-Fragment mit Ack-Anforderung (letztes Fragment eines Fensters)
T_ACK = "A"
T_ABORT = "X"

HEADER_CHARS = 8  # ~ + Typ + id(2) + idx(2) + total(2)
BODY_CHARS = 132  # Vielfaches von 4 -> keine Padding-Zeichen in Zwischenfragmenten
FRAGMENT_BYTES = BODY_CHARS // 4 * 3  # 99
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

FLAG_ZLIB = 0x01
MAX_INFLATED = 8 * 1024 * 1024


class ProtocolError(ValueError):
    pass


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
    flags = 0
    body = payload
    if compress and len(payload) > 24:
        z = zlib.compress(payload, 9)
        if len(z) < len(payload):
            body, flags = z, FLAG_ZLIB
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
    if flags & FLAG_ZLIB:
        d = zlib.decompressobj()
        try:
            body = d.decompress(body, MAX_INFLATED + 1)
        except zlib.error as e:
            raise ProtocolError(f"zlib: {e}") from e
        if len(body) > MAX_INFLATED:
            raise ProtocolError("Entpackte Daten zu groß")
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
            + base64.b64encode(chunk).decode())


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
            body = base64.b64decode(text[8:], validate=True)
            return Frame(t, mid, idx, total, body)
        if t == T_ACK:
            hi = dec_num(text[4:6])
            rest = text[6:]
            if len(rest) % 2:
                return None
            return Frame(t, mid, hi=hi, missing=[dec_num(rest[i:i + 2]) for i in range(0, len(rest), 2)])
        if t == T_ABORT:
            return Frame(t, mid)
    except (ProtocolError, binascii.Error):
        return None
    return None
