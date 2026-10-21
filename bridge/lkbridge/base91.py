"""basE91 (Joachim Henke): ~23 % Overhead statt 33 % bei Base64. Nur druckbare ASCII-Zeichen."""
from __future__ import annotations

ALPHABET = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            "!#$%&()*+,./:;<=>?@[]^_`{|}~\"")
_DEC = {c: i for i, c in enumerate(ALPHABET)}


def encode(data: bytes) -> str:
    b = n = 0
    out: list[str] = []
    for byte in data:
        b |= byte << n
        n += 8
        if n > 13:
            v = b & 8191
            if v > 88:
                b >>= 13
                n -= 13
            else:
                v = b & 16383
                b >>= 14
                n -= 14
            out.append(ALPHABET[v % 91])
            out.append(ALPHABET[v // 91])
    if n:
        out.append(ALPHABET[b % 91])
        if n > 7 or b > 90:
            out.append(ALPHABET[b // 91])
    return "".join(out)


def decode(text: str) -> bytes:
    v = -1
    b = n = 0
    out = bytearray()
    for ch in text:
        c = _DEC.get(ch)
        if c is None:
            raise ValueError(f"Ungültiges basE91-Zeichen: {ch!r}")
        if v < 0:
            v = c
        else:
            v += c * 91
            b |= v << n
            n += 13 + (0 if (v & 8191) > 88 else 1)
            while True:
                out.append(b & 255)
                b >>= 8
                n -= 8
                if n <= 7:
                    break
            v = -1
    if v + 1:
        out.append((b | v << n) & 255)
    return bytes(out)
