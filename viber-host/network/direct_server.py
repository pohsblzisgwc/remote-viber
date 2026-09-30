"""Direct endpoint: explicit Origins, no auto-pairing, bounded authenticated streams."""
import asyncio
import datetime
import ipaddress
import logging
import mimetypes
import os
from pathlib import Path
import ssl
import stat
import sys
from urllib.parse import unquote, urlsplit
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Response
from websockets.datastructures import Headers
from core.crypto import ProtocolError, MAX_FRAME_BYTES, HANDSHAKE_TIMEOUT
from network.router import ClientConnectionState

logger = logging.getLogger("viber.direct")


def get_or_create_tls_context(config, san_hosts=None):
    """Retrieve custom SSL/TLS context or auto-generate a private self-signed certificate."""
    cert_file = getattr(config, "ssl_cert", None)
    key_file = getattr(config, "ssl_key", None)

    if cert_file and key_file:
        cert_p = Path(cert_file)
        key_p = Path(key_file)
        if not cert_p.is_file():
            raise FileNotFoundError(f"SSL certificate file not found: {cert_file}")
        if not key_p.is_file():
            raise FileNotFoundError(f"SSL key file not found: {key_file}")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(certfile=str(cert_p), keyfile=str(key_p))
        return ctx

    config_dir = Path(getattr(config, "config_dir", "."))
    auto_cert = config_dir / "viber_cert.pem"
    auto_key = config_dir / "viber_key.pem"

    if auto_cert.is_file() and auto_key.is_file():
        try:
            cert_bytes = auto_cert.read_bytes()
            existing_cert = x509.load_pem_x509_certificate(cert_bytes)
            bc = existing_cert.extensions.get_extension_for_oid(x509.ExtensionOID.BASIC_CONSTRAINTS).value
            if not bc.ca:
                raise ValueError("Self-signed root certificate must have ca=True for strict TLS stacks")
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile=str(auto_cert), keyfile=str(auto_key))
            return ctx
        except Exception:
            pass  # regenerate if invalid or ca=False

    private_key = ec.generate_private_key(ec.SECP256R1())
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "RemoteViber Host"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "RemoteViber"),
    ])

    san_list = []
    seen = set()
    hosts_to_add = san_hosts or ["localhost", "127.0.0.1", "::1"]
    for h in hosts_to_add:
        if not h or h in seen:
            continue
        clean = h.strip("[]")
        seen.add(clean)
        try:
            ip = ipaddress.ip_address(clean)
            san_list.append(x509.IPAddress(ip))
        except ValueError:
            if not any(c in clean for c in "/:?#@*"):
                san_list.append(x509.DNSName(clean))

    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName(san_list), critical=False)
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(private_key, hashes.SHA256())
    )

    cert_pem = cert.public_bytes(serialization.Encoding.PEM)
    key_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.TraditionalOpenSSL,
        encryption_algorithm=serialization.NoEncryption(),
    )

    for p, b in [(auto_cert, cert_pem), (auto_key, key_pem)]:
        tmp = p.with_suffix(".tmp")
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with open(fd, "wb") as f:
            f.write(b)
        tmp.replace(p)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile=str(auto_cert), keyfile=str(auto_key))
    return ctx


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


MAX_STATIC_BYTES = 32 * 1024 * 1024


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
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_STATIC_BYTES:
                raise PermissionError("Not a permitted static file")
            with os.fdopen(fd, "rb") as f:
                fd = None
                data = f.read(MAX_STATIC_BYTES + 1)
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
            data = f.read(MAX_STATIC_BYTES + 1)
    if len(data) > MAX_STATIC_BYTES:
        raise PermissionError("Static file too large")
    return data


class DirectServer:
    def __init__(self, config, agent_manager, monitor, client_dist_dir=None, ssl_context=None):
        self.config, self.agent_manager, self.monitor = config, agent_manager, monitor
        self.ssl_context = ssl_context
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
            origins.add(normalize_origin(f"https://{host}:{port}"))

        direct_url = getattr(self.config, "direct_url", None)
        if direct_url:
            raw = direct_url
            if raw.startswith("wss://"):
                raw = "https://" + raw[6:]
            elif raw.startswith("ws://"):
                raw = "http://" + raw[5:]
            try:
                u = urlsplit(raw)
                if u.hostname:
                    port_str = f":{u.port}" if u.port and u.port not in (80, 443) else ""
                    h_fmt = f"[{u.hostname}]" if ":" in u.hostname and not u.hostname.startswith("[") else u.hostname
                    origins.add(normalize_origin(f"https://{h_fmt}{port_str}"))
                    origins.add(normalize_origin(f"http://{h_fmt}{port_str}"))
            except Exception:
                pass

        origins.update(normalize_origin(x) for x in getattr(self.config, "allowed_origins", []))
        self.allowed_origins[:] = [None] + sorted(origins)
        self.allowed_hosts = {urlsplit(x).netloc for x in origins}
        # Explicit default ports and bare hostnames are legal Host forms as well.
        for x in origins:
            u = urlsplit(x)
            if u.port is None:
                self.allowed_hosts.add(u.netloc + (":443" if u.scheme == "https" else ":80"))
            elif (u.scheme == "https" and u.port == 443) or (u.scheme == "http" and u.port == 80):
                self.allowed_hosts.add(u.hostname)

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
                                  ssl=self.ssl_context,
                                  process_request=self._handle_http_request, origins=self.allowed_origins,
                                  open_timeout=10, close_timeout=3, ping_interval=20, ping_timeout=20,
                                  max_size=MAX_FRAME_BYTES, max_queue=16, compression=None)
        if self.config.direct_port == 0:
            self.config.direct_port = self.server.sockets[0].getsockname()[1]
            self._refresh_origins()
        mode_str = "TLS/WSS" if self.ssl_context else "HTTP/WS"
        logger.info("Direct endpoint started (%s); all connections require protocol-v2 authentication", mode_str)

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
            fwd_hosts = request.headers.get_all("X-Forwarded-Host")
            candidate_hosts = [h.lower() for h in hosts]
            if fwd_hosts:
                candidate_hosts.extend(h.lower() for h in fwd_hosts)
            if len(hosts) != 1 or not any(h in self.allowed_hosts for h in candidate_hosts):
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
