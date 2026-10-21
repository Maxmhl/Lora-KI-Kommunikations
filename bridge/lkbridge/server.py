"""Bridge-Logik: LKP-Frames <-> LLM."""
from __future__ import annotations

import asyncio
import logging
import random
import time

from . import codec, files
from .airtime import RadioParams
from .llm import OllamaClient
from .transfer import Receiver, Sender

log = logging.getLogger("lkbridge.server")

DEFAULT_SYSTEM = (
    "Du bist ein hilfreicher Assistent. Die Verbindung zum Nutzer läuft über LoRa-Funk mit "
    "sehr geringer Bandbreite: Antworte kurz, präzise, ohne Floskeln und ohne unnötige Formatierung."
)


class Bridge:
    def __init__(self, cfg: dict, transport, llm: OllamaClient, sender: Sender, receiver: Receiver):
        self.cfg, self.transport, self.llm = cfg, transport, llm
        self.sender, self.receiver = sender, receiver
        acc = cfg.get("access", {})
        self.allowed = [p.lower() for p in acc.get("allowed_peers", [])]
        self.allow_all = bool(acc.get("allow_all", False))
        lcfg = cfg.get("llm", {})
        self.system = lcfg.get("system_prompt", DEFAULT_SYSTEM)
        self.history_turns = int(lcfg.get("history_turns", 6))
        self.vision_model = lcfg.get("vision_model") or None
        self.max_ctx_chars = int(cfg.get("limits", {}).get("max_context_chars", 12000))
        self.history: dict[tuple[str, int], list[dict]] = {}
        self.staged: dict[tuple[str, int], list[tuple[int, str, str, bytes, float]]] = {}
        self.jobs: asyncio.Queue = asyncio.Queue()
        self._ids: dict[str, int] = {}
        self._worker: asyncio.Task | None = None

    # ------------------------------------------------------------ Eingang
    def start(self) -> None:
        self.transport.on_text = self.on_text
        self._worker = asyncio.create_task(self._work())

    def _is_allowed(self, peer: str) -> bool:
        if self.allow_all:
            return True
        return any(peer.lower().startswith(a) or a.startswith(peer.lower()) for a in self.allowed)

    async def on_text(self, peer: str, text: str) -> None:
        if not self._is_allowed(peer):
            log.warning("Absender %s nicht freigegeben – in [access].allowed_peers eintragen", peer)
            return
        f = codec.parse_frame(text)
        if f is None:  # normale Klartextnachricht eines beliebigen MeshCore-Clients
            await self.jobs.put((peer, 0, text.strip(), []))
            return
        if f.type in (codec.T_ACK, codec.T_ABORT):
            self.sender.on_ack(peer, f)
            return
        reply, env = self.receiver.on_frame(peer, f)
        if reply:
            asyncio.create_task(self._safe_tx(peer, reply))
        if env is not None:
            await self._on_envelope(peer, env)

    async def _safe_tx(self, peer: str, text: str) -> None:
        try:
            await self.sender.tx(peer, text)
        except Exception as e:
            log.error("ACK-Senden fehlgeschlagen: %s", e)

    async def _on_envelope(self, peer: str, raw: bytes) -> None:
        try:
            env = codec.parse_envelope(raw)
        except codec.ProtocolError as e:
            log.warning("Ungültiges Objekt von %s: %s", peer, e)
            return
        key = (peer, env.conv)
        if env.kind == codec.KIND_TEXT:
            atts = self.staged.pop(key, [])
            await self.jobs.put((peer, env.conv, env.payload.decode("utf-8", "replace"), atts))
        elif env.kind in (codec.KIND_IMAGE, codec.KIND_FILE):
            try:
                name, mime, data = codec.unpack_file(env.payload)
            except codec.ProtocolError as e:
                log.warning("Anhang ungültig: %s", e)
                return
            lst = self.staged.setdefault(key, [])
            lst[:] = [a for a in lst if time.time() - a[4] < 3600][-4:]
            lst.append((env.kind, name, mime, data, time.time()))
        elif env.kind == codec.KIND_RESET:
            self.history.pop(key, None)
            self.staged.pop(key, None)

    # ------------------------------------------------------------ Verarbeitung
    async def _work(self) -> None:
        while True:
            peer, conv, text, atts = await self.jobs.get()
            try:
                answer = await self._answer(peer, conv, text, atts)
            except Exception as e:
                log.exception("LLM-Fehler")
                answer = f"Fehler auf dem Server: {e}"
                await self._reply(peer, conv, codec.KIND_STATUS, answer)
                continue
            await self._reply(peer, conv, codec.KIND_REPLY, answer)

    async def _answer(self, peer: str, conv: int, text: str, atts) -> str:
        notes, images, blocks = [], [], []
        for kind, name, mime, data, _ in atts:
            if kind == codec.KIND_IMAGE:
                if self.vision_model:
                    images.append(files.to_jpeg(data))
                else:
                    notes.append(f"[Bild '{name}' ignoriert: kein Vision-Modell konfiguriert]")
            else:
                try:
                    blocks.append(f"[Datei: {name}]\n{files.extract_text(name, mime, data)}")
                except Exception as e:
                    notes.append(f"[Datei '{name}' nicht lesbar: {e}]")
        prompt = text
        if blocks:
            ctx = "\n\n".join(blocks)[: self.max_ctx_chars]
            prompt = f"{ctx}\n\n---\n{text}"
        key = (peer, conv)
        hist = self.history.setdefault(key, [])
        msgs = [{"role": "system", "content": self.system}, *hist[-2 * self.history_turns:],
                {"role": "user", "content": prompt}]
        answer = await self.llm.chat(msgs, model=self.vision_model if images else None,
                                     images=images or None)
        hist += [{"role": "user", "content": prompt}, {"role": "assistant", "content": answer}]
        del hist[: -2 * self.history_turns]
        return ("\n".join(notes) + "\n" + answer).strip() if notes else answer

    def _next_id(self, peer: str) -> int:
        n = self._ids.get(peer)
        n = random.randrange(codec.MAX_NUM + 1) if n is None else (n + 1) % (codec.MAX_NUM + 1)
        self._ids[peer] = n
        return n

    async def _reply(self, peer: str, conv: int, kind: int, text: str) -> None:
        env = codec.build_envelope(kind, conv, text.encode())
        ok = await self.sender.send(peer, self._next_id(peer), env)
        if not ok:
            log.error("Antwort an %s nicht zugestellt", peer)
