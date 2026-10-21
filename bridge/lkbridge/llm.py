"""Minimaler Ollama-Client (nur Standardbibliothek)."""
from __future__ import annotations

import asyncio
import base64
import json
import urllib.request


class OllamaClient:
    def __init__(self, base_url: str, model: str, num_predict: int = 400, timeout_s: float = 300):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.num_predict = num_predict
        self.timeout_s = timeout_s

    def _chat_sync(self, model: str, messages: list[dict]) -> str:
        body = json.dumps({"model": model, "messages": messages, "stream": False,
                           "options": {"num_predict": self.num_predict}}).encode()
        req = urllib.request.Request(self.base_url + "/api/chat", body,
                                     {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=self.timeout_s) as r:
            return json.load(r)["message"]["content"].strip()

    def _get_json(self, path: str, body: dict | None = None) -> dict:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base_url + path, data, {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)

    def _list_sync(self) -> list[tuple[str, bool]]:
        out = []
        for m in self._get_json("/api/tags").get("models", []):
            name = m.get("name") or m.get("model")
            if not name:
                continue
            vision = False
            try:  # neuere Ollama-Versionen melden Fähigkeiten
                caps = self._get_json("/api/show", {"model": name}).get("capabilities", [])
                vision = "vision" in caps
            except Exception:
                pass
            out.append((name, vision))
        return out

    async def list_models(self) -> list[tuple[str, bool]]:
        """[(Name, kann Bilder)] der auf dem Ollama-Server installierten Modelle."""
        return await asyncio.to_thread(self._list_sync)

    async def chat(self, messages: list[dict], model: str | None = None,
                   images: list[bytes] | None = None) -> str:
        msgs = [dict(m) for m in messages]
        if images:
            msgs[-1]["images"] = [base64.b64encode(i).decode() for i in images]
        return await asyncio.to_thread(self._chat_sync, model or self.model, msgs)
