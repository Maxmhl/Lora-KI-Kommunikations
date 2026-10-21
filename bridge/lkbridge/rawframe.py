"""LKR v1 – kompakter Binärrahmen für MeshCore-Rohdatenpakete (CMD_SEND_RAW_DATA), nur Server -> Handy.

Gedacht für öffentliche Inhalte (Wikipedia-Text, Kartenbilder): kein Base91, kein AES-Block-Padding, kein Empfänger-/MAC-Kopf.
Die Pakete sind **unverschlüsselt**, aber **authentisch**: die letzten 4 Byte sind ein HMAC-SHA256 über den Rahmen, mit einem
gemeinsamen Geheimnis (dem Gateway-Token). Wer mithört, kann den Inhalt lesen, aber keine gefälschten Fragmente einschleusen.

Aufbau (Big Endian):  0xA7 | (Version<<4 | Poll) | msg_id(2) | idx(2) | total(2) | Daten (≤ 148) | HMAC[:4]
"""
from __future__ import annotations

import hashlib
import hmac

MAGIC = 0xA7
VERSION = 1
HEADER = 8
TAG = 4
MAX_CHUNK = 148            # Rahmen höchstens 160 Byte -> Funkpaket ≈ 162 Byte (Kopf + Pfad + Rahmen)
MAX_NUM = 4095


def derive_key(secret: str) -> bytes:
    return hashlib.sha256(b"lkr1|" + secret.encode()).digest()


def _tag(key: bytes, body: bytes) -> bytes:
    return hmac.new(key, body, hashlib.sha256).digest()[:TAG]


def build(key: bytes, msg_id: int, idx: int, total: int, chunk: bytes, poll: bool) -> bytes:
    if not (0 <= msg_id <= MAX_NUM and 0 <= idx < total <= MAX_NUM) or len(chunk) > MAX_CHUNK:
        raise ValueError("Ungültige Rahmenwerte")
    body = bytes([MAGIC, (VERSION << 4) | (1 if poll else 0)]) + msg_id.to_bytes(2, "big") + idx.to_bytes(2, "big") + total.to_bytes(2, "big") + chunk
    return body + _tag(key, body)


def parse(key: bytes, frame: bytes):
    """-> (msg_id, idx, total, chunk, poll) oder None (falsche Magic/Version, zu kurz, Echtheits-Tag stimmt nicht)."""
    if len(frame) < HEADER + TAG or frame[0] != MAGIC or frame[1] >> 4 != VERSION:
        return None
    body, tag = frame[:-TAG], frame[-TAG:]
    if not hmac.compare_digest(tag, _tag(key, body)):
        return None
    msg_id, idx, total = (int.from_bytes(body[i:i + 2], "big") for i in (2, 4, 6))
    if total == 0 or idx >= total or msg_id > MAX_NUM or total > MAX_NUM:
        return None
    return msg_id, idx, total, body[HEADER:], bool(body[1] & 1)
