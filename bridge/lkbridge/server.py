"""Bridge-Logik: LKP-Frames <-> LLM."""
from __future__ import annotations

import asyncio
import json
import logging
from pathlib import Path
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
        self.allowed_models = lcfg.get("models") or []  # leer = alle von Ollama gemeldeten
        self.default_model = lcfg.get("model")
        self._models_file = Path(cfg.get("model_state_file", "models-state.json"))
        self.conv_models: dict[str, str] = self._load_models()
        self._model_cache: tuple[float, list[tuple[str, bool]]] | None = None
        self.max_ctx_chars = int(cfg.get("limits", {}).get("max_context_chars", 12000))
        self.history: dict[tuple[str, int], list[dict]] = {}
        self.staged: dict[tuple[str, int], list[tuple[int, str, str, bytes, float]]] = {}
        self.jobs: asyncio.Queue = asyncio.Queue()
        self._ids: dict[str, int] = {}
        self._worker: asyncio.Task | None = None

    # ------------------------------------------------------------ Modelle
    def _load_models(self) -> dict[str, str]:
        try:
            return json.loads(self._models_file.read_text())
        except (OSError, ValueError):
            return {}

    def _save_models(self) -> None:
        try:
            self._models_file.write_text(json.dumps(self.conv_models))
        except OSError as e:
            log.warning("Modellauswahl nicht speicherbar: %s", e)

    async def available_models(self) -> list[tuple[str, bool]]:
        now = time.time()
        if self._model_cache and now - self._model_cache[0] < 60:
            return self._model_cache[1]
        found = await self.llm.list_models()
        if self.allowed_models:
            known = dict(found)
            found = [(m, known.get(m, False)) for m in self.allowed_models]
        self._model_cache = (now, found)
        return found

    @staticmethod
    def _mkey(peer: str, conv: int) -> str:
        return f"{peer}|{conv}"

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
            await self.jobs.put(("chat", peer, 0, text.strip(), []))
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
            await self.jobs.put(("chat", peer, env.conv, env.payload.decode("utf-8", "replace"), atts))
        elif env.kind in (codec.KIND_IMAGE, codec.KIND_FILE):
            try:
                name, mime, data = codec.unpack_file(env.payload)
            except codec.ProtocolError as e:
                log.warning("Anhang ungültig: %s", e)
                return
            lst = self.staged.setdefault(key, [])
            lst[:] = [a for a in lst if time.time() - a[4] < 3600][-4:]
            lst.append((env.kind, name, mime, data, time.time()))
        elif env.kind == codec.KIND_MODEL_LIST_REQ:
            await self.jobs.put(("models", peer, env.conv))
        elif env.kind == codec.KIND_SET_MODEL:
            await self.jobs.put(("setmodel", peer, env.conv, env.payload.decode("utf-8", "replace").strip()))
        elif env.kind == codec.KIND_RESET:
            self.history.pop(key, None)
            self.staged.pop(key, None)

    # ------------------------------------------------------------ Verarbeitung
    async def _work(self) -> None:
        while True:
            job = await self.jobs.get()
            kind, peer, conv = job[0], job[1], job[2]
            try:
                if kind == "chat":
                    answer = await self._answer(peer, conv, job[3], job[4])
                    await self._reply(peer, conv, codec.KIND_REPLY, answer)
                elif kind == "models":
                    lines = "\n".join(f"{n}\t{'v' if v else ''}" for n, v in await self.available_models())
                    await self._reply(peer, conv, codec.KIND_MODEL_LIST, lines)
                elif kind == "setmodel":
                    await self._set_model(peer, conv, job[3])
            except Exception as e:
                log.exception("Fehler bei %s", kind)
                await self._reply(peer, conv, codec.KIND_STATUS, f"Fehler auf dem Server: {e}")

    async def _set_model(self, peer: str, conv: int, name: str) -> None:
        key = self._mkey(peer, conv)
        if not name:
            self.conv_models.pop(key, None)
            self._save_models()
            return
        if name not in dict(await self.available_models()):
            await self._reply(peer, conv, codec.KIND_STATUS, f"Unbekanntes Modell: {name}")
            return
        self.conv_models[key] = name
        self._save_models()
        log.info("Modell für %s: %s", key, name)

    async def _answer(self, peer: str, conv: int, text: str, atts) -> str:
        notes, images, blocks = [], [], []
        for kind, name, mime, data, _ in atts:
            if kind == codec.KIND_IMAGE:
                images.append(files.to_jpeg(data))  # ob ein Modell Bilder versteht, wird unten entschieden
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
        chosen = self.conv_models.get(self._mkey(peer, conv)) or self.default_model
        model = chosen
        if images:
            vision_ok = dict(await self.available_models()).get(chosen, False)
            model = chosen if vision_ok else self.vision_model
            if model is None:
                images = []
                notes.append(f"[Bild ignoriert: Modell '{chosen}' versteht keine Bilder und es ist kein vision_model konfiguriert]")
        answer = await self.llm.chat(msgs, model=model, images=images or None)
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
