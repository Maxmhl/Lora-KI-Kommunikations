"""Rendert kleine Kartenausschnitte aus den PMTiles von NOMAD (Protomaps-Schema v4) als winzige Bilder für LoRa.

Reines Python (pmtiles, mapbox-vector-tile, Pillow) – kein Browser nötig. Ziel: wenige KB pro Ausschnitt.
"""
from __future__ import annotations

import gzip
import io
import math
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

TILE = 256  # Kachelgröße in „Karten-Pixeln"; ein Ausgabepixel = ein Karten-Pixel

# Farben (an das helle Protomaps-Thema angelehnt, bewusst wenige für kleine PNGs)
C_BG = (232, 228, 223)
C_WATER = (120, 200, 230)
C_GREEN = (190, 222, 178)
C_FOREST = (160, 205, 150)
C_BUILD = (205, 198, 190)
C_ROAD_MINOR = (255, 255, 255)
C_ROAD_MAJOR = (252, 214, 120)
C_ROAD_HIGHWAY = (240, 160, 90)
C_PATH = (170, 160, 150)
C_RAIL = (140, 140, 150)
C_TEXT = (40, 40, 60)
C_MARK = (220, 30, 30)

GREEN_KINDS = {"park", "grass", "garden", "meadow", "playground", "recreation_ground", "pitch", "golf_course", "cemetery", "farmland"}
FOREST_KINDS = {"forest", "wood", "scrub", "nature_reserve", "national_park", "protected_area"}
ROAD_STYLE = {  # kind -> (Breite, Farbe)
    "highway": (4, C_ROAD_HIGHWAY), "major_road": (3, C_ROAD_MAJOR), "medium_road": (2, C_ROAD_MAJOR),
    "minor_road": (2, C_ROAD_MINOR), "other": (1, C_ROAD_MINOR), "path": (1, C_PATH), "rail": (1, C_RAIL),
}


@dataclass
class Region:
    path: Path
    name: str
    min_lon: float
    min_lat: float
    max_lon: float
    max_lat: float
    max_zoom: int

    def covers(self, lat: float, lon: float) -> bool:
        return self.min_lat <= lat <= self.max_lat and self.min_lon <= lon <= self.max_lon

    @property
    def area(self) -> float:
        return (self.max_lon - self.min_lon) * (self.max_lat - self.min_lat)


def list_regions(pmtiles_dir: str | Path) -> list[Region]:
    from pmtiles.reader import MmapSource, Reader

    out: list[Region] = []
    d = Path(pmtiles_dir)
    if not d.is_dir():
        return out
    for p in sorted(d.glob("*.pmtiles")):
        try:
            with p.open("rb") as f:
                h = Reader(MmapSource(f)).header()
            out.append(Region(p, p.stem, h["min_lon_e7"] / 1e7, h["min_lat_e7"] / 1e7, h["max_lon_e7"] / 1e7,
                              h["max_lat_e7"] / 1e7, h["max_zoom"]))
        except Exception:  # defekte Datei überspringen
            continue
    return out


def _world_px(lat: float, lon: float, z: int) -> tuple[float, float]:
    n = TILE * 2**z
    x = (lon + 180.0) / 360.0 * n
    y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    return x, y


def _pixel_to_latlon(x: float, y: float, z: int) -> tuple[float, float]:
    n = TILE * 2**z
    lon = x / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    return lat, lon


def _load_tile(reader, z: int, x: int, y: int):
    import mapbox_vector_tile

    raw = reader.get(z, x, y)
    if raw is None:
        return None
    try:
        raw = gzip.decompress(raw)
    except OSError:
        pass
    return mapbox_vector_tile.decode(raw, default_options={"y_coord_down": True})


def _lines(geom) -> list[list]:
    t, c = geom["type"], geom["coordinates"]
    if t == "LineString":
        return [c]
    if t == "MultiLineString":
        return list(c)
    return []


def _polys(geom) -> list[list]:
    t, c = geom["type"], geom["coordinates"]
    if t == "Polygon":
        return [c]
    if t == "MultiPolygon":
        return list(c)
    return []


PLACE_RANK = {"city": 0, "town": 1, "village": 2, "suburb": 3, "hamlet": 4, "neighbourhood": 5, "quarter": 5, "locality": 6}


def zoom_for_radius(lat: float, radius_m: float, size: int) -> float:
    """Fraktionaler Zoom, bei dem [size] Pixel einen Durchmesser von 2·radius_m abdecken."""
    mpp = 2.0 * radius_m / size
    return math.log2(156543.03392 * max(math.cos(math.radians(lat)), 0.05) / mpp)


def render(regions: list[Region], lat: float, lon: float, radius_m: float, size: int = 160, colors: int = 12,
           label: bool = True, mark: tuple[float, float] | None = None) -> tuple[bytes, dict]:
    """-> (Bilddaten, Metadaten inkl. Ortsliste). Der Ausschnitt reicht [radius_m] Meter vom Mittelpunkt bis zum Bildrand.

    Wählt die kleinste Region, die den Punkt abdeckt, sonst die Weltkarte. Vektordaten lassen sich stufenlos skalieren,
    deshalb ist jeder Radius möglich (über die höchste Kachelstufe hinaus wird „überzoomt“). [mark] = Position, die als Punkt
    eingezeichnet wird (z. B. der eigene Standort); ohne Angabe markiert ein Fadenkreuz den Mittelpunkt."""
    from pmtiles.reader import MmapSource, Reader

    size = max(64, min(int(size), 320))
    radius_m = max(50.0, min(float(radius_m), 200_000.0))
    cands = [r for r in regions if r.covers(lat, lon) and r.name != "world"] or [r for r in regions if r.name == "world"]
    if not cands:
        raise LookupError("Für diese Position ist keine Karte auf dem Server")
    region = min(cands, key=lambda r: r.area)
    zf = max(0.0, min(zoom_for_radius(lat, radius_m, size), region.max_zoom + 2.5))
    z = max(0, min(int(zf), region.max_zoom))
    k = 2.0 ** (zf - z)  # Skalierung der Kachelpixel auf Ausgabepixel (1 ≤ k < 2 bzw. mehr beim Überzoomen)

    cx, cy = _world_px(lat, lon, z)
    half = size / (2 * k)
    left, top = cx - half, cy - half
    img = Image.new("RGB", (size, size), C_BG)
    d = ImageDraw.Draw(img)
    try:
        font = ImageFont.load_default(size=10)
    except TypeError:
        font = ImageFont.load_default()

    x0, x1 = int(left // TILE), int((cx + half) // TILE)
    y0, y1 = int(top // TILE), int((cy + half) // TILE)
    tiles = []
    with region.path.open("rb") as f:
        reader = Reader(MmapSource(f))
        for ty in range(y0, y1 + 1):
            for tx in range(x0, x1 + 1):
                if 0 <= tx < 2**z and 0 <= ty < 2**z:
                    t = _load_tile(reader, z, tx, ty)
                    if t:
                        tiles.append((tx, ty, t))

    def pt(tx, ty, ext, p):
        return ((tx * TILE + p[0] * TILE / ext - cx) * k + size / 2, (ty * TILE + p[1] * TILE / ext - cy) * k + size / 2)

    def fill_layer(name: str, color_of):
        for tx, ty, t in tiles:
            layer = t.get(name)
            if not layer:
                continue
            ext = layer.get("extent", 4096)
            for ft in layer["features"]:
                col = color_of(ft["properties"])
                if col is None:
                    continue
                for poly in _polys(ft["geometry"]):
                    d.polygon([pt(tx, ty, ext, p) for p in poly[0]], fill=col)

    def line_layer(name: str, style_of):
        items = []
        for tx, ty, t in tiles:
            layer = t.get(name)
            if not layer:
                continue
            ext = layer.get("extent", 4096)
            for ft in layer["features"]:
                st = style_of(ft["properties"])
                if st is None:
                    continue
                for ln in _lines(ft["geometry"]):
                    items.append((st, [pt(tx, ty, ext, p) for p in ln]))
        return items

    fill_layer("earth", lambda p: C_BG)
    fill_layer("landuse", lambda p: C_FOREST if p.get("kind") in FOREST_KINDS else C_GREEN if p.get("kind") in GREEN_KINDS else None)
    fill_layer("natural", lambda p: C_FOREST if p.get("kind") in FOREST_KINDS else None)
    fill_layer("water", lambda p: C_WATER)
    for (w, col), pts in line_layer("water", lambda p: (2, C_WATER) if p.get("kind") in ("river", "canal") else (1, C_WATER) if p.get("kind") == "stream" else None):
        d.line(pts, fill=col, width=w)
    if zf >= 13.5:
        fill_layer("buildings", lambda p: C_BUILD if p.get("kind") == "building" else None)
    scale = 1 if zf < 15 else 2
    for (w, col), pts in sorted(line_layer("roads", lambda p: ROAD_STYLE.get(p.get("kind"))), key=lambda i: (i[0][0], i[0][1] != C_ROAD_MINOR)):
        d.line(pts, fill=col, width=max(1, w * scale // 2 if zf < 13 else w * scale // 2 + 1))

    # Orte sammeln (alle im Ausschnitt) und die wichtigsten beschriften
    found: list[dict] = []
    for tx, ty, t in tiles:
        layer = t.get("places")
        if not layer:
            continue
        ext = layer.get("extent", 4096)
        for ft in layer["features"]:
            nm = ft["properties"].get("name")
            if not nm or ft["geometry"]["type"] != "Point":
                continue
            x, y = pt(tx, ty, ext, ft["geometry"]["coordinates"])
            if not (2 <= x <= size - 2 and 2 <= y <= size - 2):
                continue
            wx = tx * TILE + ft["geometry"]["coordinates"][0] * TILE / ext
            wy = ty * TILE + ft["geometry"]["coordinates"][1] * TILE / ext
            plat, plon = _pixel_to_latlon(wx, wy, z)
            detail = ft["properties"].get("kind_detail") or ft["properties"].get("kind") or ""
            found.append({"name": nm, "lat": plat, "lon": plon, "kind": detail, "x": x, "y": y,
                          "rank": (PLACE_RANK.get(detail, 7), -(ft["properties"].get("population") or 0))})
    found.sort(key=lambda p: p["rank"])
    uniq, seen_places = [], set()
    for p in found:  # derselbe Ort kann in Nachbarkacheln mehrfach vorkommen
        key = (p["name"], round(p["lat"], 3), round(p["lon"], 3))
        if key not in seen_places:
            seen_places.add(key)
            uniq.append(p)
    found = uniq
    if label:
        placed: list[tuple[float, float, float, float]] = []
        for p in found:
            box = d.textbbox((p["x"], p["y"]), p["name"], font=font, anchor="mm")
            if box[0] < 0 or box[2] > size or box[1] < 0 or box[3] > size:
                continue
            if any(not (box[2] < b[0] or box[0] > b[2] or box[3] < b[1] or box[1] > b[3]) for b in placed):
                continue
            placed.append(box)
            d.text((p["x"], p["y"]), p["name"], fill=C_TEXT, font=font, anchor="mm", stroke_width=1, stroke_fill=(255, 255, 255))

    m = size / 2
    if mark is None:
        d.line([(m - 5, m), (m + 5, m)], fill=C_MARK, width=2)
        d.line([(m, m - 5), (m, m + 5)], fill=C_MARK, width=2)
    else:  # eigene Position als Punkt mit weißem Rand
        wx, wy = _world_px(mark[0], mark[1], z)
        mx, my = (wx - cx) * k + size / 2, (wy - cy) * k + size / 2
        if -6 <= mx <= size + 6 and -6 <= my <= size + 6:
            d.ellipse([mx - 5, my - 5, mx + 5, my + 5], fill=(255, 255, 255))
            d.ellipse([mx - 3, my - 3, mx + 3, my + 3], fill=C_MARK)

    nlat, wlon = _pixel_to_latlon(left, top, z) if k == 1 else _pixel_to_latlon(cx - half, cy - half, z)
    slat, elon = _pixel_to_latlon(cx + half, cy + half, z)
    mpp = 156543.03392 * math.cos(math.radians(lat)) / 2**zf  # Meter pro Ausgabepixel
    places = [{"name": p["name"][:24], "lat": p["lat"], "lon": p["lon"], "kind": p["kind"]} for p in found[:8]]
    meta = {"region": region.name, "zoom": round(zf, 2), "size": size, "min_lon": wlon, "min_lat": slat, "max_lon": elon, "max_lat": nlat,
            "m_per_px": round(mpp, 2), "places": places}
    return encode(img, colors), meta


FIXED = [C_BG, C_WATER, C_GREEN, C_BUILD, C_ROAD_MINOR, C_ROAD_MAJOR, C_TEXT, C_MARK, C_FOREST, C_ROAD_HIGHWAY, C_PATH, C_RAIL]


def _fixed_palette(n: int) -> Image.Image:
    pal = Image.new("P", (1, 1))
    flat: list[int] = []
    for c in FIXED[:n]:
        flat += list(c)
    pal.putpalette(flat + [0] * (768 - len(flat)))
    return pal


def encode(img: Image.Image, colors: int) -> bytes:
    """Kleinstes Format aus Palette-PNG und verlustfreiem WebP.

    colors <= 12: feste Kartenpalette (stabile Farben, sehr klein); darüber: adaptive Palette."""
    if colors <= 12:
        q = img.quantize(palette=_fixed_palette(max(4, colors)), dither=Image.Dither.NONE)
    else:
        q = img.quantize(colors=min(colors, 64), method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    buf = io.BytesIO()
    q.save(buf, "PNG", optimize=True)
    png = buf.getvalue()
    wb = io.BytesIO()
    q.convert("RGB").save(wb, "WEBP", lossless=True, quality=100, method=6)
    web = wb.getvalue()
    return web if len(web) < len(png) else png
