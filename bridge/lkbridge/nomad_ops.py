"""NOMAD-Funktionen per Anfrage/Antwort – für LoRa (Anfrage klein, Antwort kompakt) und für das WLAN-Gateway.

Statt Inhalte aufs Handy zu laden, fragt die App gezielt: „Artikel X“, „Kartenausschnitt um (lat, lon)“ – und bekommt nur diese
Antwort. Der Server liest dafür NOMADs Bibliothek (Kiwix-HTTP) und Karten (PMTiles) und hält die Antwort klein.

Anfrage (UTF-8, Tab-getrennt):
  books
  search   <buch> <suchtext> <max>
  article  <buch> <pfad> <offset> <länge> <modus>        modus: text | summary
  mapinfo
  map      <lat> <lon> <zoom> <größe> <farben>
Antwort: erste Zeile = Kopf (``ok\\t<art>\\t…`` oder ``error\\t<meldung>``), danach der Inhalt (Text oder Bild).
"""
from __future__ import annotations

import asyncio
import json
import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

from . import map_render

ATOM = "{http://www.w3.org/2005/Atom}"
SKIP_TAGS = {"script", "style", "head", "nav", "table", "sup", "figure", "noscript", "svg", "button", "form", "footer"}
SKIP_CLASS = ("navbox", "infobox", "reflist", "mw-references", "toc", "hatnote", "mw-editsection", "thumb", "metadata", "sidebar")
BLOCK = {"p", "div", "li", "ul", "ol", "br", "section", "blockquote", "dd", "dt", "tr"}


class _Text(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out: list[str] = []
        self._skip = 0
        self._stack: list[bool] = []
        self.title = ""

    def handle_starttag(self, tag, attrs):
        cls = dict(attrs).get("class", "") or ""
        tokens = cls.split()  # Klassen einzeln prüfen: „vector-toc-not-available" am <html> darf nicht als „toc" zählen
        skip = tag in SKIP_TAGS or any(t == c or t.startswith(c + "-") or t.startswith(c) and c in ("navbox", "infobox", "thumb")
                                       for t in tokens for c in SKIP_CLASS)
        if tag not in ("br", "img", "meta", "link", "hr", "input"):
            self._stack.append(skip)
            if skip:
                self._skip += 1
        if self._skip:
            return
        if tag in ("h1", "h2", "h3", "h4"):
            self.out.append("\n\n" + "#" * min(int(tag[1]), 3) + " ")
        elif tag == "li":
            self.out.append("\n- ")
        elif tag in BLOCK:
            self.out.append("\n")

    def handle_endtag(self, tag):
        if tag in ("br", "img", "meta", "link", "hr", "input"):
            return
        if self._stack:
            if self._stack.pop():
                self._skip -= 1
        if not self._skip and tag in ("h1", "h2", "h3", "h4", "p"):
            self.out.append("\n")

    def handle_data(self, data):
        if not self._skip:
            self.out.append(data)


def html_to_text(html: str) -> str:
    p = _Text()
    p.feed(html)
    t = "".join(p.out)
    t = re.sub(r"\[(?:\d+|edit|[a-z])\]", "", t)          # [1], [edit]
    t = re.sub(r"[ \t ]+", " ", t)
    t = re.sub(r" *\n *", "\n", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    return t.strip()


def _get(url: str, timeout: float = 20) -> bytes:
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


class NomadOps:
    def __init__(self, kiwix_url: str | None, storage: str | Path | None, llm=None, summary_prompt: str | None = None):
        self.kiwix = kiwix_url.rstrip("/") if kiwix_url else None
        self.storage = Path(storage) if storage else None
        self.llm = llm
        self.summary_prompt = summary_prompt or ("Fasse den folgenden Text auf Deutsch in höchstens 6 kurzen Sätzen zusammen. "
                                                "Nur die Zusammenfassung, ohne Einleitung.")

    # ------------------------------------------------------------ Bibliothek (Kiwix)
    def _need_kiwix(self):
        if not self.kiwix:
            raise LookupError("Keine Bibliothek konfiguriert ([nomad] kiwix_url)")

    def books_sync(self) -> list[dict]:
        self._need_kiwix()
        root = ET.fromstring(_get(f"{self.kiwix}/catalog/v2/entries?count=200"))
        out = []
        for e in root.findall(f"{ATOM}entry"):
            link = next((l.get("href") for l in e.findall(f"{ATOM}link") if l.get("type") == "text/html"), None)
            if not link:
                continue
            out.append({"id": link.rstrip("/").rsplit("/", 1)[-1],
                        "title": (e.findtext(f"{ATOM}title") or "").strip(),
                        "lang": (e.findtext(f"{ATOM}language") or "").strip(),
                        "articles": int(e.findtext(f"{ATOM}articleCount") or 0)})
        return out

    def search_sync(self, book: str, query: str, limit: int) -> list[tuple[str, str]]:
        """Titel-Treffer zuerst, dann Volltext. -> [(pfad, titel)]"""
        self._need_kiwix()
        q = urllib.parse.quote(query)
        b = urllib.parse.quote(book)
        res: list[tuple[str, str]] = []
        seen = set()
        try:
            for it in json.loads(_get(f"{self.kiwix}/suggest?content={b}&term={q}&count={limit}")):
                if it.get("kind") == "path" and it.get("path") and it["path"] not in seen:
                    seen.add(it["path"])
                    res.append((it["path"], it.get("value") or it["path"]))
        except Exception:
            pass
        if len(res) < limit:
            try:
                root = ET.fromstring(_get(f"{self.kiwix}/search?content={b}&pattern={q}&pageLength={limit}&format=xml"))
                for item in root.iter("item"):
                    link = item.findtext("link") or ""
                    path = urllib.parse.unquote(link.split(f"/content/{book}/", 1)[-1]) if f"/content/{book}/" in link else ""
                    if path and path not in seen:
                        seen.add(path)
                        res.append((path, (item.findtext("title") or path).strip()))
            except Exception:
                pass
        return res[:limit]

    def article_text_sync(self, book: str, path: str) -> str:
        self._need_kiwix()
        html = _get(f"{self.kiwix}/content/{urllib.parse.quote(book)}/{urllib.parse.quote(path)}", timeout=30).decode("utf-8", "replace")
        return html_to_text(html)

    # ------------------------------------------------------------ Karten
    def mapinfo_sync(self) -> list[map_render.Region]:
        if not self.storage:
            raise LookupError("Kein NOMAD-Speicher konfiguriert ([nomad] storage)")
        return map_render.list_regions(self.storage / "maps" / "pmtiles")

    def map_sync(self, lat: float, lon: float, zoom: int, size: int, colors: int):
        regions = self.mapinfo_sync()
        if not regions:
            raise LookupError("Keine Karten auf dem Server")
        return map_render.render(regions, lat, lon, zoom, size, colors)

    # ------------------------------------------------------------ Anfrage-Behandlung
    async def handle(self, payload: bytes) -> bytes:
        parts = payload.decode("utf-8", "replace").split("\t")
        op, a = parts[0].strip().lower(), parts[1:]
        try:
            if op == "books":
                books = await asyncio.to_thread(self.books_sync)
                return ("ok\tbooks\n" + "\n".join(f"{b['id']}\t{b['title']}\t{b['lang']}\t{b['articles']}" for b in books)).encode()
            if op == "search":
                book, q, n = a[0], a[1], max(1, min(int(a[2]) if len(a) > 2 else 8, 20))
                hits = await asyncio.to_thread(self.search_sync, book, q, n)
                return ("ok\tsearch\n" + "\n".join(f"{p}\t{t}" for p, t in hits)).encode()
            if op == "article":
                book, path = a[0], a[1]
                offset = max(0, int(a[2])) if len(a) > 2 else 0
                length = max(200, min(int(a[3]), 20000)) if len(a) > 3 else 1500
                mode = a[4] if len(a) > 4 else "text"
                text = await asyncio.to_thread(self.article_text_sync, book, path)
                total = len(text)
                if mode == "summary":
                    if self.llm is None:
                        raise LookupError("Kein Sprachmodell für Kurzfassungen verfügbar")
                    body = await self.llm.chat([{"role": "system", "content": self.summary_prompt},
                                                {"role": "user", "content": text[:6000]}])
                    return f"ok\tarticle\t{total}\t0\t{len(body)}\tsummary\n{body}".encode()
                chunk = text[offset:offset + length]
                return f"ok\tarticle\t{total}\t{offset}\t{len(chunk)}\ttext\n{chunk}".encode()
            if op == "mapinfo":
                regs = await asyncio.to_thread(self.mapinfo_sync)
                rows = "\n".join(f"{r.name}\t{r.min_lon:.4f}\t{r.min_lat:.4f}\t{r.max_lon:.4f}\t{r.max_lat:.4f}\t{r.max_zoom}" for r in regs)
                return ("ok\tmapinfo\n" + rows).encode()
            if op == "map":
                lat, lon, zoom, size = float(a[0]), float(a[1]), int(a[2]), int(a[3])
                colors = int(a[4]) if len(a) > 4 else 12
                if not (-85 <= lat <= 85 and -180 <= lon <= 180):
                    raise ValueError("Koordinaten außerhalb des gültigen Bereichs")
                img, meta = await asyncio.to_thread(self.map_sync, lat, lon, zoom, size, colors)
                fmt = "webp" if img[:4] == b"RIFF" else "png"
                head = "ok\tmap\t{fmt}\t{region}\t{zoom}\t{size}\t{min_lon:.5f}\t{min_lat:.5f}\t{max_lon:.5f}\t{max_lat:.5f}\t{m_per_px}".format(fmt=fmt, **meta)
                return head.encode() + b"\n" + img
            raise ValueError(f"Unbekannte Anfrage: {op}")
        except (LookupError, ValueError, IndexError) as e:
            return f"error\t{e}".encode()
        except Exception as e:  # Kiwix/Netzwerk
            return f"error\tServerfehler: {e}".encode()
