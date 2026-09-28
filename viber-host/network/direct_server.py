"""Direct endpoint: explicit Origins, no auto-pairing, bounded authenticated streams."""
import asyncio
import ipaddress
import logging
import mimetypes
import os
from pathlib import Path
import stat
import sys
from urllib.parse import unquote, urlsplit
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Response
from websockets.datastructures import Headers
from core.crypto import ProtocolError, MAX_FRAME_BYTES, HANDSHAKE_TIMEOUT
from network.router import ClientConnectionState

logger = logging.getLogger("viber.direct")


def normalize_origin(value):
    if not isinstance(value, str) or value in ("*", "null") or len(value) > 1024:
        raise ValueError("An exact http(s) Origin is required")
    u = urlsplit(value)
    if u.scheme not in ("http", "https") or not u.hostname or u.username is not None or u.password is not None or u.path or u.query or u.fragment:
        raise ValueError("An exact http(s) Origin is required, without a path")
    name = u.hostname.lower()
    if "*" in name:
        raise ValueError("Wildcard Origins are forbidden")
    authority = f"[{name}]" if ":" in name else name
    if u.port is not None and u.port != (443 if u.scheme == "https" else 80):
        authority += f":{u.port}"
    return f"{u.scheme}://{authority}"


def static_bytes(root: Path, relative: str):
    """Read a regular file without following symlinks; openat protects POSIX races."""
    parts = relative.split("/")
    if any(x in ("", ".", "..") or "\\" in x or ":" in x or "\x00" in x for x in parts):
        raise PermissionError("Invalid path")
    if root.is_symlink():
        raise PermissionError("Symlink root")
    root = root.resolve(strict=True)
    if os.name == "posix":
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        fd = None
        try:
            for part in parts[:-1]:
                nxt = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                os.close(directory)
                directory = nxt
            fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_size > 16 * 1024 * 1024:
                raise PermissionError("Not a permitted static file")
            with os.fdopen(fd, "rb") as f:
                fd = None
                data = f.read(16 * 1024 * 1024 + 1)
        finally:
            if fd is not None:
                os.close(fd)
            os.close(directory)
    else:
        # Windows deployments must also protect the asset directory with ACLs.
        target = root
        for part in parts:
            target = target / part
            if target.is_symlink() or getattr(os.path, "isjunction", lambda _: False)(target):
                raise PermissionError("Reparse point")
        target.resolve(strict=True).relative_to(root)
        with target.open("rb") as f:
            if not stat.S_ISREG(os.fstat(f.fileno()).st_mode):
                raise PermissionError("Not a regular file")
            data = f.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024:
        raise PermissionError("Static file too large")
    return data


class DirectServer:
    def __init__(self, config, agent_manager, monitor, client_dist_dir=None):
        self.config, self.agent_manager, self.monitor = config, agent_manager, monitor
        if client_dist_dir:
            self.client_dist_dir = str(client_dist_dir)
        elif hasattr(sys, "_MEIPASS"):
            self.client_dist_dir = str(Path(sys._MEIPASS) / "dist")
        else:
            candidates = [Path(__file__).resolve().parents[2] / "viber-client" / "dist",
                          Path(__file__).resolve().parents[1] / "dist", Path.cwd() / "dist"]
            self.client_dist_dir = str(next((p for p in candidates if p.is_dir()), candidates[0]))
        self.server = None
        self.active_clients = set()
        self.allowed_origins = []
        self.allowed_hosts = set()
        self._refresh_origins()

    def _refresh_origins(self):
        port = self.config.direct_port
        names = {"127.0.0.1", "localhost", "::1"}
        explicit = self.config.direct_bind
        if explicit and explicit not in ("auto", "tailscale+localhost", "0.0.0.0", "::", "lan"):
            names.add(explicit)
        endpoints = self.monitor.discover_local_endpoints()
        names.update(endpoints.get("tailscale", []))
        if self.config.allow_lan:
            names.update(endpoints.get("lan", []))
        origins = set()
        for name in names:
            if "%" in name:
                continue
            host = f"[{name}]" if ":" in name and not name.startswith("[") else name
            origins.add(normalize_origin(f"http://{host}:{port}"))
        origins.update(normalize_origin(x) for x in getattr(self.config, "allowed_origins", []))
        self.allowed_origins[:] = [None] + sorted(origins)
        self.allowed_hosts = {urlsplit(x).netloc for x in origins}
        # Explicit default ports are legal Host forms as well.
        for x in origins:
            u = urlsplit(x)
            if u.port is None:
                self.allowed_hosts.add(u.netloc + (":443" if u.scheme == "https" else ":80"))

    async def start(self):
        bind = self.config.direct_bind
        if self.config.allow_lan:
            hosts = "0.0.0.0" if not bind or bind in ("auto", "tailscale+localhost", "lan") else bind
        elif bind in ("0.0.0.0", "::", "lan"):
            raise ValueError("Wildcard binding requires --allow-lan")
        elif bind and bind not in ("auto", "tailscale+localhost"):
            hosts = bind
        else:
            hosts = ["127.0.0.1"] + self.monitor.discover_local_endpoints().get("tailscale", [])
        self.server = await serve(self._handle_ws_connection, hosts, self.config.direct_port,
                                  process_request=self._handle_http_request, origins=self.allowed_origins,
                                  open_timeout=10, close_timeout=3, ping_interval=20, ping_timeout=20,
                                  max_size=MAX_FRAME_BYTES, max_queue=16, compression=None)
        if self.config.direct_port == 0:
            self.config.direct_port = self.server.sockets[0].getsockname()[1]
            self._refresh_origins()
        logger.info("Direct endpoint started; all connections require protocol-v2 authentication")

    def _is_trusted_network(self, connection, request=None):
        return False  # There is no network-based authorization exception.

    @staticmethod
    def _response(code, body, mime="text/plain; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        reason = {200: "OK", 400: "Bad Request", 403: "Forbidden", 404: "Not Found", 503: "Service Unavailable"}[code]
        return Response(code, reason, Headers([
            ("Content-Type", mime), ("Content-Length", str(len(body))), ("Cache-Control", "no-store"),
            ("X-Content-Type-Options", "nosniff"), ("Referrer-Policy", "no-referrer"),
            ("X-Frame-Options", "DENY"),
            ("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; img-src 'self' data:; font-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")]), body)

    def _handle_http_request(self, connection, request):
        try:
            hosts = request.headers.get_all("Host")
            origins = request.headers.get_all("Origin")
            if len(hosts) != 1 or hosts[0].lower() not in self.allowed_hosts:
                return self._response(403, "Unrecognized Host")
            if len(origins) > 1 or (origins and origins[0] not in self.allowed_origins):
                return self._response(403, "Origin not permitted")
            path = unquote(urlsplit(request.path).path, errors="strict")
            if "\x00" in path or "\\" in path or any(x in (".", "..") for x in path.split("/")):
                return self._response(403, "Invalid path")
            upgrade = request.headers.get("Upgrade", "").lower() == "websocket"
            if upgrade:
                if path != "/ws":
                    return self._response(404, "Unknown WebSocket endpoint")
                if len(self.active_clients) >= 32:
                    return self._response(503, "Connection limit reached")
                return None
            if path == "/api/pairing":
                return self._response(403, "Automatic pairing is disabled. Use --pair-info locally.")
            if path.startswith("/api/") or path == "/ws":
                return self._response(404, "Not found")
            root = Path(self.client_dist_dir)
            if not root.is_dir():
                return self._response(200, "RemoteViber protocol v2. Build the Web client; use --pair-info locally.")
            relative = path.lstrip("/") or "index.html"
            try:
                body = static_bytes(root, relative)
            except FileNotFoundError:
                # SPA navigation only. Missing assets do not turn into HTML.
                if "." in relative.rsplit("/", 1)[-1]:
                    return self._response(404, "Not found")
                relative = "index.html"
                body = static_bytes(root, relative)
            mime = mimetypes.guess_type(relative)[0] or "application/octet-stream"
            return self._response(200, body, mime)
        except (PermissionError, ValueError, UnicodeError, OSError):
            return self._response(403, "Forbidden")
        except Exception:
            return self._response(400, "Bad request")

    async def _handle_ws_connection(self, websocket):
        if len(self.active_clients) >= 32:
            await websocket.close(1013, "Connection limit reached")
            return
        async def close_client():
            await websocket.close(1008, "Protocol violation")
        state = ClientConnectionState(self.config.key_manager, self.agent_manager, self.monitor,
                                      websocket.send, close_func=close_client)
        self.active_clients.add(state)
        deadline = asyncio.get_running_loop().time() + HANDSHAKE_TIMEOUT
        try:
            while not state.is_authenticated:
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    raise ProtocolError("Handshake timeout")
                raw = await asyncio.wait_for(websocket.recv(), remaining)
                await state.handle_raw_message(raw)
            async for raw in websocket:
                await state.handle_raw_message(raw)
        except ConnectionClosed:
            pass
        except (ProtocolError, asyncio.TimeoutError):
            await close_client()
        except Exception:
            logger.warning("Closing invalid client session", exc_info=False)
            await websocket.close(1011, "Session error")
        finally:
            state.cleanup()
            self.active_clients.discard(state)

    async def stop(self):
        if self.server:
            self.server.close()
            await self.server.wait_closed()
