import asyncio
import os
import zipfile

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from lkbridge.gateway import build_app
from lkbridge.nomad_sync import Catalog, split_name

TOKEN = "t" * 24
H = {"Authorization": f"Bearer {TOKEN}"}


def make_storage(tmp_path):
    zim = tmp_path / "zim"
    pm = tmp_path / "maps" / "pmtiles"
    assets = tmp_path / "maps" / "basemaps-assets" / "fonts" / "Noto Sans Regular"
    zim.mkdir(parents=True)
    pm.mkdir(parents=True)
    assets.mkdir(parents=True)
    (zim / "wikipedia_de_mini_2026-01.zim").write_bytes(os.urandom(5000))
    (zim / "ted_talks_2025-05.zim").write_bytes(b"x" * 100)       # Video -> ausgeschlossen
    (zim / "khan_academy_2025-05.zim").write_bytes(b"x" * 100)    # Bildung -> ausgeschlossen
    (pm / "bayern_2025-12.pmtiles").write_bytes(os.urandom(3000))
    (pm / "world.pmtiles").write_bytes(os.urandom(500))
    (tmp_path / "maps" / "nomad-base-styles.json").write_text('{"version":8,"layers":[]}')
    (assets / "0-255.pbf").write_bytes(b"glyphs")
    return tmp_path


@pytest.fixture
async def client(tmp_path):
    cat = Catalog(make_storage(tmp_path))

    async def upstream_index(request):
        return web.Response(text=f"NOMAD host={request.headers.get('X-Forwarded-Host')} cookie={request.headers.get('Cookie')} "
                                 f"q={request.rel_url.query_string} auth={request.headers.get('Authorization')}", headers={"X-Up": "1"})

    async def sse(request):
        resp = web.StreamResponse(headers={"Content-Type": "text/event-stream"})
        await resp.prepare(request)
        for i in range(3):
            await resp.write(f"data: {i}\n\n".encode())
            await asyncio.sleep(0.01)
        return resp

    async def ranged(request):
        return web.FileResponse(tmp_path / "maps" / "pmtiles" / "bayern_2025-12.pmtiles")

    async def redirect(request):
        raise web.HTTPFound(f"http://{request.host}/home")

    async def echo(request):
        return web.json_response({"body": (await request.read()).decode(), "method": request.method})

    up = web.Application()
    up.router.add_get("/", upstream_index)
    up.router.add_get("/sse", sse)
    up.router.add_get("/pm", ranged)
    up.router.add_get("/go", redirect)
    up.router.add_route("*", "/echo", echo)
    up_server = TestServer(up)
    await up_server.start_server()
    gw = TestServer(build_app(cat, TOKEN, str(up_server.make_url("/")).rstrip("/")))
    c = TestClient(gw)
    await c.start_server()
    yield c
    await c.close()
    await up_server.close()


def test_split_name():
    assert split_name("washington_2025-12_z12.pmtiles") == ("washington_z12", "2025-12")
    assert split_name("wikipedia_en_100_mini_2026-01.zim") == ("wikipedia_en_100_mini", "2026-01")
    assert split_name("world.pmtiles") == ("world", None)


async def test_auth_required(client):
    assert (await client.get("/lk/manifest")).status == 401
    assert (await client.get("/")).status == 401
    assert (await client.get("/lk/ping")).status == 200
    assert (await client.get("/lk/manifest", headers={"Authorization": "Bearer falsch"})).status == 401


async def test_manifest_filters_education_and_video(client):
    r = await client.get("/lk/manifest", headers=H)
    items = {i["id"]: i for i in (await r.json())["items"]}
    assert set(items) == {"zim/wikipedia_de_mini_2026-01.zim", "map/bayern_2025-12.pmtiles", "map/world.pmtiles", "mapassets"}
    z = items["zim/wikipedia_de_mini_2026-01.zim"]
    assert (z["kind"], z["name"], z["version"], z["size"]) == ("zim", "wikipedia_de_mini", "2026-01", 5000)
    assert items["map/world.pmtiles"]["version"]  # Datum aus mtime


async def test_file_range_and_ifrange(client):
    full = await (await client.get("/lk/files/zim/wikipedia_de_mini_2026-01.zim", headers=H)).read()
    assert len(full) == 5000
    r = await client.get("/lk/files/zim/wikipedia_de_mini_2026-01.zim", headers={**H, "Range": "bytes=100-199"})
    assert r.status == 206 and await r.read() == full[100:200]
    assert r.headers["Content-Range"] == "bytes 100-199/5000"
    r = await client.get("/lk/files/zim/wikipedia_de_mini_2026-01.zim", headers={**H, "Range": "bytes=-50"})
    assert await r.read() == full[-50:]
    etag = (await client.head("/lk/files/zim/wikipedia_de_mini_2026-01.zim", headers=H)).headers["ETag"]
    r = await client.get("/lk/files/zim/wikipedia_de_mini_2026-01.zim", headers={**H, "Range": "bytes=0-9", "If-Range": etag})
    assert r.status == 206
    r = await client.get("/lk/files/zim/wikipedia_de_mini_2026-01.zim", headers={**H, "Range": "bytes=0-9", "If-Range": '"alt"'})
    assert r.status == 200 and len(await r.read()) == 5000


async def test_excluded_file_not_downloadable_and_no_traversal(client):
    assert (await client.get("/lk/files/zim/ted_talks_2025-05.zim", headers=H)).status == 404
    assert (await client.get("/lk/files/zim/..%2F..%2Fetc%2Fpasswd", headers=H)).status == 404


async def test_mapassets_zip(client, tmp_path):
    r = await client.get("/lk/files/mapassets", headers=H)
    assert r.status == 200
    p = tmp_path / "a.zip"
    p.write_bytes(await r.read())
    names = set(zipfile.ZipFile(p).namelist())
    assert names == {"nomad-base-styles.json", "basemaps-assets/fonts/Noto Sans Regular/0-255.pbf"}


async def test_proxy_passes_ui_and_strips_token_cookie(client):
    r = await client.get("/?lk_token=" + TOKEN + "&x=1")
    body = await r.text()
    assert r.status == 200 and r.headers["X-Up"] == "1"
    assert "lk_token" not in body and "x=1" in body            # Token nicht an NOMAD weitergegeben
    assert "lk_token" in r.headers.get("Set-Cookie", "")        # aber als Cookie für das WebView gesetzt
    r = await client.get("/", headers={"Cookie": f"lk_token={TOKEN}; other=1"})
    assert r.status == 200 and "cookie=other=1" in await r.text()
    r = await client.get("/", headers=H)
    assert "auth=None" in await r.text()                        # Gateway-Token wird nicht an NOMAD weitergereicht


async def test_proxy_streams_sse_ranges_redirects_and_posts(client):
    r = await client.get("/sse", headers=H)
    assert (await r.text()).count("data:") == 3
    r = await client.get("/pm", headers={**H, "Range": "bytes=10-19"})
    assert r.status == 206 and len(await r.read()) == 10
    r = await client.get("/go", headers=H, allow_redirects=False)
    assert r.status == 302 and r.headers["Location"] == "/home"  # kein Upstream-Host im Location-Header
    r = await client.post("/echo", headers=H, data="hallo")
    assert (await r.json()) == {"body": "hallo", "method": "POST"}


async def test_upstream_down_gives_502(tmp_path):
    cat = Catalog(make_storage(tmp_path))
    c = TestClient(TestServer(build_app(cat, TOKEN, "http://127.0.0.1:9")))
    await c.start_server()
    try:
        assert (await c.get("/", headers=H)).status == 502
        assert (await c.get("/lk/manifest", headers=H)).status == 200  # Offline-Downloads gehen trotzdem
    finally:
        await c.close()


async def test_nomad_knowledge_client(tmp_path):
    from lkbridge.llm import NomadKnowledgeClient, OllamaClient

    seen = {}

    async def chat(request):
        seen["body"] = await request.json()
        return web.json_response({"message": {"role": "assistant", "content": " Antwort aus Wissensbasis "}, "sources": []})

    app = web.Application()
    app.router.add_post("/api/ollama/chat", chat)
    srv = TestServer(app)
    await srv.start_server()
    try:
        ollama = OllamaClient("http://127.0.0.1:1", "llama3.1")
        kb = NomadKnowledgeClient(ollama, str(srv.make_url("/")))
        out = await kb.chat([{"role": "user", "content": "Frage"}], model="qwen")
        assert out == "Antwort aus Wissensbasis"
        assert seen["body"]["model"] == "qwen" and seen["body"]["stream"] is False
    finally:
        await srv.close()
