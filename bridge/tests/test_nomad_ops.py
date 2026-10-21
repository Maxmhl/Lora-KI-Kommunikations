import gzip
import io
import math
from pathlib import Path

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from PIL import Image

from lkbridge import codec
from lkbridge.gateway import build_app
from lkbridge.nomad_ops import NomadOps, html_to_text
from lkbridge.nomad_sync import Catalog

FIX = Path(__file__).parent / "fixtures"
BOOK = "wikipedia_en_100_mini_2026-01"
TOKEN = "t" * 24


@pytest.fixture
async def kiwix():
    async def entries(r): return web.Response(body=(FIX / "kiwix_entries.xml").read_bytes(), content_type="application/atom+xml")
    async def suggest(r): return web.Response(body=(FIX / "kiwix_suggest.json").read_bytes(), content_type="application/json")
    async def search(r): return web.Response(body=(FIX / "kiwix_search.xml").read_bytes(), content_type="application/xml")
    async def content(r):
        if r.match_info["p"] == "Bird":
            return web.Response(body=(FIX / "kiwix_bird.html").read_bytes(), content_type="text/html")
        raise web.HTTPNotFound()
    app = web.Application()
    app.router.add_get("/catalog/v2/entries", entries)
    app.router.add_get("/suggest", suggest)
    app.router.add_get("/search", search)
    app.router.add_get(f"/content/{BOOK}/{{p:.+}}", content)
    s = TestServer(app)
    await s.start_server()
    yield str(s.make_url("/")).rstrip("/")
    await s.close()


def parse(resp: bytes):
    head, _, body = resp.partition(b"\n")
    return head.decode().split("\t"), body


def test_html_to_text_keeps_article_drops_noise():
    t = html_to_text((FIX / "kiwix_bird.html").read_text())
    assert "Birds are a group of warm-blooded theropod dinosaurs" in t
    assert "<" not in t and "[1]" not in t and "[edit]" not in t
    assert "Scientific classification" not in t           # Infobox (Tabelle) entfällt
    assert "\n\n" in t and len(t) < len((FIX / "kiwix_bird.html").read_text()) / 2


async def test_books_search_article(kiwix):
    ops = NomadOps(kiwix, None)
    head, body = parse(await ops.handle(b"books"))
    assert head == ["ok", "books"]
    assert body.decode().split("\t")[:3] == [BOOK, "Wikipedia 100", "eng"]

    head, body = parse(await ops.handle(f"search\t{BOOK}\tpar\t8".encode()))
    rows = [l.split("\t") for l in body.decode().splitlines()]
    assert ["Pardine", "Pardine"] in rows and len(rows) >= 5      # Titeltreffer aus /suggest; „containing“-Zeile (kind=pattern) fehlt

    head, body = parse(await ops.handle(f"article\t{BOOK}\tBird\t0\t1500\ttext".encode()))
    assert head[:2] == ["ok", "article"] and head[5] == "text"
    total, offset, n = int(head[2]), int(head[3]), int(head[4])
    assert offset == 0 and n == len(body.decode()) == 1500 and total > 3000
    # Weiterblättern
    head2, body2 = parse(await ops.handle(f"article\t{BOOK}\tBird\t1500\t1500\ttext".encode()))
    assert int(head2[3]) == 1500 and body2 != body


async def test_article_summary_uses_llm(kiwix):
    class FakeLLM:
        async def chat(self, messages, model=None, images=None):
            assert "Zusammenfassung" in messages[0]["content"] and "Birds" in messages[1]["content"]
            return "Vögel sind Dinosaurier mit Federn."
    ops = NomadOps(kiwix, None, FakeLLM())
    head, body = parse(await ops.handle(f"article\t{BOOK}\tBird\t0\t0\tsummary".encode()))
    assert head[:2] == ["ok", "article"] and head[5] == "summary" and body.decode() == "Vögel sind Dinosaurier mit Federn."


async def test_errors_are_short_text(kiwix):
    ops = NomadOps(kiwix, None)
    for req in (b"quatsch", b"article\tx", b"map\tabc\t1\t2\t3", b"search"):
        head, _ = parse(await ops.handle(req))
        assert head[0] == "error", req
    head, _ = parse(await NomadOps(None, None).handle(b"books"))
    assert head[0] == "error" and "konfiguriert" in head[1]
    head, _ = parse(await ops.handle(f"article\t{BOOK}\tGibtsNicht\t0\t100\ttext".encode()))
    assert head[0] == "error"
    head, _ = parse(await ops.handle(b"map\t49.4\t8.7\t14\t160"))
    assert head[0] == "error"                                       # kein Speicher konfiguriert


# ------------------------------------------------------------ Karten (synthetische PMTiles, Protomaps-Schema v4)

def make_pmtiles(path: Path, lat: float, lon: float, z: int = 14):
    import mapbox_vector_tile
    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import Writer

    n = 2**z
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    mvt = mapbox_vector_tile.encode([
        {"name": "earth", "features": [{"geometry": "POLYGON((0 0,4096 0,4096 4096,0 4096,0 0))", "properties": {"kind": "earth"}}]},
        {"name": "water", "features": [{"geometry": "POLYGON((0 0,4096 0,4096 900,0 900,0 0))", "properties": {"kind": "water"}}]},
        {"name": "landuse", "features": [{"geometry": "POLYGON((2500 2500,4096 2500,4096 4096,2500 4096,2500 2500))", "properties": {"kind": "park"}}]},
        {"name": "roads", "features": [{"geometry": "LINESTRING(0 2048,4096 2048)", "properties": {"kind": "major_road"}},
                                        {"geometry": "LINESTRING(2048 0,2048 4096)", "properties": {"kind": "minor_road"}}]},
        {"name": "places", "features": [{"geometry": "POINT(2048 1500)", "properties": {"kind": "locality", "name": "Teststadt"}}]},
    ], default_options={"y_coord_down": True})
    with path.open("wb") as f:
        w = Writer(f)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                w.write_tile(zxy_to_tileid(z, x + dx, y + dy), gzip.compress(mvt))
        w.finalize({"tile_type": TileType.MVT, "tile_compression": Compression.GZIP, "min_zoom": z, "max_zoom": z,
                    "min_lon_e7": int((lon - 0.05) * 1e7), "min_lat_e7": int((lat - 0.05) * 1e7),
                    "max_lon_e7": int((lon + 0.05) * 1e7), "max_lat_e7": int((lat + 0.05) * 1e7),
                    "center_zoom": z, "center_lon_e7": int(lon * 1e7), "center_lat_e7": int(lat * 1e7)}, {})


@pytest.fixture
def storage(tmp_path):
    (tmp_path / "maps" / "pmtiles").mkdir(parents=True)
    make_pmtiles(tmp_path / "maps" / "pmtiles" / "teststadt_2026-10.pmtiles", 49.40, 8.69)
    return tmp_path


async def test_map_section_is_small_valid_image_with_bbox(storage):
    ops = NomadOps(None, storage)
    head, body = parse(await ops.handle(b"mapinfo"))
    assert head == ["ok", "mapinfo"] and body.decode().startswith("teststadt_2026-10\t8.6400\t49.3500")

    sizes = {}
    for size, colors in ((96, 8), (160, 12)):
        head, img = parse(await ops.handle(f"map\t49.40\t8.69\t14\t{size}\t{colors}".encode()))
        assert head[:3] == ["ok", "map", head[2]] and head[2] in ("png", "webp")
        im = Image.open(io.BytesIO(img))
        assert im.size == (size, size)
        sizes[size] = len(img)
        min_lon, min_lat, max_lon, max_lat = map(float, head[6:10])
        assert min_lon < 8.69 < max_lon and min_lat < 49.40 < max_lat      # Mittelpunkt liegt im gemeldeten Ausschnitt
    assert sizes[96] < 2500 and sizes[160] < 5000                           # LoRa-tauglich klein
    px = Image.open(io.BytesIO(img)).convert("RGB")
    assert px.getpixel((4, 4)) != px.getpixel((150, 150))                  # Wasser oben links ≠ Park unten rechts


async def test_map_outside_coverage_and_bad_input(storage):
    ops = NomadOps(None, storage)
    head, _ = parse(await ops.handle(b"map\t10.0\t10.0\t14\t160\t12"))
    assert head[0] == "error" and "keine karte" in head[1].lower()
    head, _ = parse(await ops.handle(b"map\t95\t10\t14\t160\t12"))
    assert head[0] == "error"


# ------------------------------------------------------------ Gateway + Bridge

async def test_gateway_nomad_endpoint(kiwix, storage):
    ops = NomadOps(kiwix, storage)
    c = TestClient(TestServer(build_app(Catalog(storage), TOKEN, None, ops)))
    await c.start_server()
    try:
        H = {"Authorization": f"Bearer {TOKEN}"}
        assert (await c.post("/lk/nomad", data=b"books")).status == 401
        r = await c.post("/lk/nomad", headers=H, data=b"books")
        assert r.status == 200 and (await r.read()).startswith(b"ok\tbooks")
        r = await c.post("/lk/nomad", headers=H, data=b"map\t49.40\t8.69\t14\t96\t8")
        assert (await r.read()).split(b"\n", 1)[0].startswith(b"ok\tmap")
    finally:
        await c.close()
