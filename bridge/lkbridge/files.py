"""Textextraktion aus gängigen Dateitypen."""
from __future__ import annotations

import io
import re
import zipfile
import xml.etree.ElementTree as ET

TEXT_EXT = {".txt", ".md", ".csv", ".tsv", ".json", ".xml", ".html", ".htm", ".log",
            ".yaml", ".yml", ".ini", ".py", ".kt", ".java", ".js", ".c", ".cpp", ".h", ".sh"}


def _ext(name: str) -> str:
    i = name.rfind(".")
    return name[i:].lower() if i >= 0 else ""


def _decode(data: bytes) -> str:
    for enc in ("utf-8", "cp1252", "latin-1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", "replace")


def _docx(data: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        root = ET.fromstring(z.read("word/document.xml"))
    ns = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    paras = ["".join(t.text or "" for t in p.iter(ns + "t")) for p in root.iter(ns + "p")]
    return "\n".join(paras)


def _odt(data: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        root = ET.fromstring(z.read("content.xml"))
    return "\n".join("".join(e.itertext()) for e in root.iter()
                     if e.tag.endswith("}p") or e.tag.endswith("}h"))


def _pdf(data: bytes) -> str:
    try:
        from pypdf import PdfReader
    except ImportError as e:
        raise ValueError("PDF-Unterstützung braucht 'pypdf'") from e
    r = PdfReader(io.BytesIO(data))
    return "\n".join((p.extract_text() or "") for p in r.pages)


def extract_text(name: str, mime: str, data: bytes) -> str:
    ext = _ext(name)
    if ext == ".pdf" or mime == "application/pdf":
        txt = _pdf(data)
    elif ext == ".docx":
        txt = _docx(data)
    elif ext == ".odt":
        txt = _odt(data)
    elif ext in TEXT_EXT or mime.startswith("text/") or mime in ("application/json", "application/xml"):
        txt = _decode(data)
    else:
        raise ValueError(f"Dateityp nicht unterstützt: {name or mime}")
    if ext in (".html", ".htm") or mime == "text/html":
        txt = re.sub(r"<[^>]+>", " ", txt)
    return re.sub(r"\n{3,}", "\n\n", txt).strip()


def to_jpeg(data: bytes) -> bytes:
    """WebP/PNG -> JPEG für Vision-Modelle (benötigt Pillow, sonst unverändert)."""
    try:
        from PIL import Image
    except ImportError:
        return data
    try:
        im = Image.open(io.BytesIO(data)).convert("RGB")
    except Exception:
        return data
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=90)
    return buf.getvalue()
