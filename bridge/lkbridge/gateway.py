"""Gateway: EINE abgesicherte Schnittstelle für die App zu Project NOMAD.

* ``/lk/…``  – Manifest und Dateidownloads (Karten, Bibliotheken) mit Range/ETag für die Offline-Inhalte auf dem Handy
* alles andere – wird unverändert an die NOMAD-Oberfläche (Command Center) durchgeleitet, damit die App dieselbe
  Oberfläche zeigt wie der Browser im Intranet. NOMAD selbst hat keine Anmeldung; das Gateway schaltet ein
  Token davor (Bearer-Header, Cookie ``lk_token`` oder ``?lk_token=``). Im Intranet bleibt NOMAD direkt erreichbar.

Gedacht für WLAN/VPN (WireGuard/Tailscale) bzw. hinter einem TLS-Reverse-Proxy – nicht für LoRa.
"""
from __future__ import annotations

import hmac
import logging
import time
from urllib.parse import urlsplit, urlunsplit

import aiohttp
from aiohttp import web

from .nomad_sync import Catalog

log = logging.getLogger("lkbridge.gateway")

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers",
              "transfer-encoding", "upgrade"}
COOKIE = "lk_token"
SESSION = web.AppKey("session", aiohttp.ClientSession)


class _File(web.FileResponse):
    """FileResponse, das Range/If-Range anhand bereinigter Request-Header auswertet."""

    def __init__(self, path, request_headers, **kw):
        super().__init__(path, **kw)
        self._request_headers = request_headers

    async def prepare(self, request):
        return await super().prepare(request.clone(headers=self._request_headers))


def _token_ok(request: web.Request, token: str) -> bool:
    cands = []
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        cands.append(auth[7:].strip())
    cands.append(request.cookies.get(COOKIE, ""))
    cands.append(request.query.get(COOKIE, ""))
    return any(c and hmac.compare_digest(c, token) for c in cands)


def build_app(catalog: Catalog, token: str, upstream: str | None) -> web.Application:
    if len(token) < 16:
        raise ValueError("gateway.token muss mindestens 16 Zeichen lang sein")

    @web.middleware
    async def auth(request: web.Request, handler):
        if request.path == "/lk/ping":
            return await handler(request)
        if not _token_ok(request, token):
            return web.json_response({"error": "unauthorized"}, status=401)
        resp = await handler(request)
        # Token aus ?lk_token= in ein Cookie übernehmen, damit Unterressourcen im WebView ohne Header funktionieren
        if request.query.get(COOKIE) and not request.cookies.get(COOKIE) and not resp.prepared:
            resp.set_cookie(COOKIE, token, httponly=True, samesite="Lax", max_age=3600 * 24 * 365)
        return resp

    async def ping(_):
        return web.json_response({"ok": True, "service": "lkbridge-gateway", "nomad": bool(upstream)})

    async def manifest(_):
        return web.json_response({"version": 1, "server_time": int(time.time()),
                                  "nomad": bool(upstream), "items": [i.to_json() for i in catalog.items()]})

    async def file(request: web.Request):
        item = catalog.get(request.match_info["id"])
        if item is None:
            return web.json_response({"error": "not found"}, status=404)
        if item.kind == "mapassets":
            path = catalog.bundle_path()
            ctype = "application/zip"
        else:
            path = item.path
            ctype = "application/octet-stream"
        if path is None:
            return web.json_response({"error": "not found"}, status=404)
        st = path.stat()
        etag = f'"{st.st_mtime_ns:x}-{st.st_size:x}"'  # genau das ETag, das aiohttp's FileResponse sendet
        # If-Range selbst prüfen: hat sich die Datei geändert, muss ein fortgesetzter Download von vorn beginnen (200 statt 206)
        headers = request.headers.copy()
        if_range = headers.pop("If-Range", None)
        if if_range is not None and if_range != etag:
            headers.pop("Range", None)
        resp = _File(path, headers, chunk_size=1 << 20)
        resp.content_type = ctype
        return resp

    async def proxy(request: web.Request):
        if not upstream:
            return web.json_response({"error": "NOMAD ist nicht konfiguriert"}, status=503)
        base = urlsplit(upstream)
        query = "&".join(q for q in request.rel_url.raw_query_string.split("&") if q and not q.startswith(COOKIE + "="))
        url = urlunsplit((base.scheme, base.netloc, request.rel_url.raw_path, query, ""))
        headers = {k: v for k, v in request.headers.items()
                   if k.lower() not in HOP_BY_HOP and k.lower() not in ("host", "content-length")}
        # Das Gateway-Token gehört nicht zu NOMAD (weder als Header noch als Cookie)
        if headers.get("Authorization", "").startswith("Bearer ") and hmac.compare_digest(headers["Authorization"][7:].strip(), token):
            del headers["Authorization"]
        method = request.method
        override = headers.pop("X-HTTP-Method-Override", "").upper()
        if method == "POST" and override in ("PATCH", "PUT", "DELETE"):
            method = override  # HttpURLConnection der App kann kein PATCH
        headers["X-Forwarded-Host"] = request.host
        headers["X-Forwarded-Proto"] = request.scheme
        # Token-Cookie nicht an NOMAD weiterreichen
        if "Cookie" in headers:
            rest = "; ".join(c for c in headers["Cookie"].split("; ") if not c.startswith(COOKIE + "="))
            if rest:
                headers["Cookie"] = rest
            else:
                del headers["Cookie"]
        data = request.content if request.can_read_body else None
        session: aiohttp.ClientSession = request.app[SESSION]
        try:
            async with session.request(method, url, headers=headers, data=data, allow_redirects=False) as up:
                out = web.StreamResponse(status=up.status, reason=up.reason)
                for k, v in up.headers.items():
                    lk = k.lower()
                    if lk in HOP_BY_HOP or lk == "content-length" and up.headers.get("Content-Encoding"):
                        continue
                    if lk == "location":  # absolute Weiterleitungen auf NOMAD zurück auf das Gateway biegen
                        loc = urlsplit(v)
                        if loc.netloc == base.netloc:
                            v = urlunsplit(("", "", loc.path, loc.query, loc.fragment)) or "/"
                    if lk == "set-cookie":
                        out.headers.add(k, v)
                    else:
                        out.headers[k] = v
                if up.headers.get("Content-Length") and not up.headers.get("Content-Encoding"):
                    out.headers["Content-Length"] = up.headers["Content-Length"]
                if request.query.get(COOKIE) and not request.cookies.get(COOKIE):
                    out.set_cookie(COOKIE, token, httponly=True, samesite="Lax", max_age=3600 * 24 * 365)
                await out.prepare(request)
                async for chunk in up.content.iter_any():
                    await out.write(chunk)
                await out.write_eof()
                return out
        except aiohttp.ClientError as e:
            log.warning("NOMAD nicht erreichbar: %s", e)
            return web.json_response({"error": "NOMAD nicht erreichbar", "detail": str(e)}, status=502)

    async def on_startup(app: web.Application):
        app[SESSION] = aiohttp.ClientSession(auto_decompress=False,
                                              timeout=aiohttp.ClientTimeout(total=None, sock_connect=10))

    async def on_cleanup(app: web.Application):
        await app[SESSION].close()

    app = web.Application(middlewares=[auth], client_max_size=64 * 1024 * 1024)
    app.router.add_get("/lk/ping", ping)
    app.router.add_get("/lk/manifest", manifest)
    app.router.add_route("*", r"/lk/files/{id:.+}", file)
    app.router.add_route("*", "/{tail:.*}", proxy)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


class Gateway:
    def __init__(self, catalog: Catalog, token: str, upstream: str | None, host: str = "0.0.0.0", port: int = 8765):
        self.app = build_app(catalog, token, upstream)
        self.host, self.port = host, port
        self._runner: web.AppRunner | None = None

    async def start(self) -> None:
        self._runner = web.AppRunner(self.app)
        await self._runner.setup()
        await web.TCPSite(self._runner, self.host, self.port).start()
        log.info("Gateway auf %s:%d gestartet", self.host, self.port)

    async def stop(self) -> None:
        if self._runner:
            await self._runner.cleanup()
