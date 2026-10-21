"""Transport über ein per USB angeschlossenes MeshCore-Companion-Gerät (Heltec V3)."""
from __future__ import annotations

import logging
from typing import Awaitable, Callable

from .airtime import RadioParams

log = logging.getLogger("lkbridge.mc")


class MeshCoreTransport:
    def __init__(self, port: str, baudrate: int = 115200):
        self.port, self.baudrate = port, baudrate
        self.mc = None
        self.on_text: Callable[[str, str], Awaitable[None]] | None = None

    async def start(self) -> None:
        from meshcore import EventType, MeshCore  # lazy: nur auf dem Server nötig

        self.mc = await MeshCore.create_serial(self.port, self.baudrate)
        if self.mc is None:
            raise ConnectionError(f"Keine Antwort von MeshCore-Node an {self.port}")
        await self.mc.ensure_contacts()

        async def _recv(event) -> None:
            p = event.payload
            if p.get("txt_type", 0) != 0 or self.on_text is None:
                return
            await self.on_text(p["pubkey_prefix"], p["text"])

        self.mc.subscribe(EventType.CONTACT_MSG_RECV, _recv)
        await self.mc.start_auto_message_fetching()

    def radio_params(self) -> RadioParams | None:
        si = self.mc.self_info if self.mc else None
        if not si or "radio_freq" not in si:
            return None
        return RadioParams(freq_mhz=si["radio_freq"], bw_khz=si["radio_bw"], sf=si["radio_sf"],
                           cr=si["radio_cr"], tx_power_dbm=si.get("tx_power"))

    async def send_text(self, peer: str, text: str) -> None:
        from meshcore import EventType

        contact = self.mc.get_contact_by_key_prefix(peer)
        if contact is None:
            await self.mc.commands.get_contacts()
            contact = self.mc.get_contact_by_key_prefix(peer)
        if contact is None:
            raise ConnectionError(f"Kontakt {peer} unbekannt (Advert vom Handy-Node fehlt?)")
        ev = await self.mc.commands.send_msg(contact, text)
        if ev.type == EventType.ERROR:
            raise ConnectionError(f"Node meldet Fehler: {ev.payload}")

    # ------------------------------------------------------------ Rohdaten (unverschlüsselt, direkter Pfad)

    def _raw_path(self, peer: str) -> bytes:
        c = self.mc.get_contact_by_key_prefix(peer) if self.mc else None
        if c is None:
            raise LookupError("Kontakt unbekannt")
        n = c.get("out_path_len", -1)
        if n is None or n < 0:
            raise LookupError("kein bekannter Pfad (Flood) – Rohdaten brauchen einen direkten Pfad")
        if c.get("out_path_hash_mode", 0) not in (0, None):
            raise LookupError("Mehrbyte-Pfade werden für Rohdaten nicht unterstützt")
        return bytes.fromhex(c.get("out_path") or "")[:n]

    def raw_path_len(self, peer: str) -> int:
        try:
            return len(self._raw_path(peer))
        except LookupError:
            return 0

    async def send_raw(self, peer: str, frame: bytes) -> None:
        from meshcore import EventType

        ev = await self.mc.commands.send_raw_data(frame, self._raw_path(peer))
        if ev.type == EventType.ERROR:
            raise ConnectionError(f"Node lehnt Rohdaten ab: {ev.payload}")
