"""Katalog der Offline-Inhalte (Karten als PMTiles, Bibliotheken als ZIM) im Project-NOMAD-Speicher.

Der HTTP-Teil (Manifest, Dateien mit Range/ETag, Durchleitung der NOMAD-Oberfläche) steht in gateway.py.
"""
from __future__ import annotations

import hashlib
import logging
import re
import tempfile
import threading
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("lkbridge.sync")

DEFAULT_EXCLUDE = ("ted_", "khan", "kolibri", "video", "youtube", "tedx")
_NAME = re.compile(r"^(?P<name>.+?)_(?P<ver>\d{4}-\d{2}(?:-\d{2})?)(?P<rest>.*)$")
ASSET_BUNDLE_ID = "mapassets"


@dataclass
class Item:
    id: str
    kind: str  # zim | map | mapassets
    name: str  # Serienname ohne Datum (bleibt bei Updates gleich)
    file: str
    version: str  # Datum aus dem Dateinamen oder mtime-Datum
    size: int
    mtime: int
    path: Path | None = None

    @property
    def etag(self) -> str:
        return hashlib.sha1(f"{self.id}:{self.size}:{self.mtime}".encode()).hexdigest()[:16]

    def to_json(self) -> dict:
        return {"id": self.id, "kind": self.kind, "name": self.name, "file": self.file,
                "version": self.version, "size": self.size, "mtime": self.mtime, "etag": self.etag}


def split_name(filename: str) -> tuple[str, str | None]:
    """'washington_2025-12_z12.pmtiles' -> ('washington_z12', '2025-12'); ohne Datum -> (Name, None)."""
    stem = filename.rsplit(".", 1)[0]
    m = _NAME.match(stem)
    if not m:
        return stem, None
    return m["name"] + m["rest"], m["ver"]


class Catalog:
    def __init__(self, storage: str | Path, exclude=DEFAULT_EXCLUDE, max_file_bytes: int | None = None):
        self.storage = Path(storage)
        self.exclude = tuple(x.lower() for x in exclude)
        self.max_file_bytes = max_file_bytes
        self._bundle_lock = threading.Lock()
        self._bundle_cache: tuple[str, Path] | None = None

    def _excluded(self, filename: str) -> bool:
        low = filename.lower()
        return any(x in low for x in self.exclude)

    def _scan(self, folder: Path, suffix: str, kind: str, prefix: str) -> list[Item]:
        out: list[Item] = []
        if not folder.is_dir():
            return out
        for p in sorted(folder.iterdir()):
            if not p.is_file() or not p.name.endswith(suffix) or self._excluded(p.name):
                continue
            st = p.stat()
            if self.max_file_bytes and st.st_size > self.max_file_bytes:
                continue
            name, ver = split_name(p.name)
            ver = ver or time.strftime("%Y-%m-%d", time.gmtime(st.st_mtime))
            out.append(Item(f"{prefix}/{p.name}", kind, name, p.name, ver, st.st_size, int(st.st_mtime), p))
        return out

    def _assets_files(self) -> list[tuple[Path, str]]:
        maps = self.storage / "maps"
        files: list[tuple[Path, str]] = []
        style = maps / "nomad-base-styles.json"
        if style.is_file():
            files.append((style, "nomad-base-styles.json"))
        assets = maps / "basemaps-assets"
        if assets.is_dir():
            for p in sorted(assets.rglob("*")):
                if p.is_file():
                    files.append((p, "basemaps-assets/" + p.relative_to(assets).as_posix()))
        return files

    def items(self) -> list[Item]:
        items = self._scan(self.storage / "zim", ".zim", "zim", "zim")
        items += self._scan(self.storage / "maps" / "pmtiles", ".pmtiles", "map", "map")
        files = self._assets_files()
        if files:
            newest = max(int(p.stat().st_mtime) for p, _ in files)
            total = sum(p.stat().st_size for p, _ in files)
            items.append(Item(ASSET_BUNDLE_ID, "mapassets", "mapassets", "mapassets.zip",
                              time.strftime("%Y-%m-%d", time.gmtime(newest)), total, newest))
        return items

    def get(self, item_id: str) -> Item | None:
        return next((i for i in self.items() if i.id == item_id), None)

    def bundle_path(self) -> Path | None:
        """Erzeugt (und cached) das ZIP mit Kartenstil, Symbolen und Schriften."""
        files = self._assets_files()
        if not files:
            return None
        key = hashlib.sha1("|".join(f"{a}:{a.stat().st_mtime_ns}:{a.stat().st_size}" for a, _ in files).encode()).hexdigest()
        with self._bundle_lock:
            if self._bundle_cache and self._bundle_cache[0] == key and self._bundle_cache[1].exists():
                return self._bundle_cache[1]
            tmp = Path(tempfile.gettempdir()) / f"lkbridge-mapassets-{key[:12]}.zip"
            if not tmp.exists():
                part = tmp.with_suffix(".part")
                with zipfile.ZipFile(part, "w", zipfile.ZIP_DEFLATED) as z:
                    for p, arc in files:
                        z.write(p, arc)
                part.replace(tmp)
            self._bundle_cache = (key, tmp)
            return tmp
