"""LoRa-Airtime (Semtech AN1200.13) und Duty-Cycle-Zuordnung für EU868."""
from __future__ import annotations

import math
from dataclasses import dataclass


@dataclass
class RadioParams:
    freq_mhz: float = 869.618
    bw_khz: float = 62.5
    sf: int = 8
    cr: int = 8  # Nenner von 4/x (5..8)
    preamble: int = 16
    tx_power_dbm: int | None = None


def airtime_s(payload_bytes: int, r: RadioParams) -> float:
    tsym = (2 ** r.sf) / (r.bw_khz * 1000.0)
    de = 1 if tsym > 0.016 else 0
    num = 8 * payload_bytes - 4 * r.sf + 28 + 16  # CRC an, expliziter Header
    n_pl = 8 + max(math.ceil(num / (4 * (r.sf - 2 * de))) * r.cr, 0)
    return (r.preamble + 4.25 + n_pl) * tsym


def mesh_packet_bytes(text_utf8_len: int, path_len: int = 0) -> int:
    """Geschätzte Funkpaketgröße einer MeshCore-Direktnachricht.

    Header(1) + path_len(1) + Pfad + dest/src-Hash(2) + MAC(2) + AES-Blöcke(ts4+flags1+Text+NUL).
    """
    cipher = -(-(5 + text_utf8_len + 1) // 16) * 16  # +1: Terminator (konservativ)
    return 1 + 1 + path_len + 2 + 2 + cipher


def raw_packet_bytes(frame_len: int, path_len: int = 0) -> int:
    """Funkpaket eines MeshCore-Rohdatenpakets: Kopf(1) + Pfadlänge(1) + Pfad + Nutzdaten (kein Empfänger-Hash, kein MAC, kein Padding)."""
    return 2 + path_len + frame_len


def text_airtime_s(text: str, r: RadioParams) -> float:
    return airtime_s(mesh_packet_bytes(len(text.encode())), r)


# (von MHz, bis MHz, Duty-Cycle, max ERP mW) – BNetzA Allgemeinzuteilung SRD, bitte prüfen!
SUBBANDS = [
    (865.0, 868.0, 0.01, 25),
    (868.0, 868.6, 0.01, 25),
    (868.7, 869.2, 0.001, 25),
    (869.4, 869.65, 0.10, 500),
    (869.7, 870.0, 0.01, 25),
]


def subband_limits(freq_mhz: float) -> tuple[float, int, bool]:
    """(duty, max_erp_mw, bekannt). Unbekannte Frequenz -> strengster Wert 0,1 %."""
    for lo, hi, duty, erp in SUBBANDS:
        if lo <= freq_mhz <= hi:
            return duty, erp, True
    return 0.001, 25, False


def dbm_to_mw(dbm: float) -> float:
    return 10 ** (dbm / 10)
