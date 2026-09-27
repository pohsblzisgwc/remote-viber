"""
RemoteViber Direct Listener & Embedded Client Server
Provides direct high-speed LAN / Tailscale connection, WebSocket E2EE endpoint,
and embeds the client web app.
"""

import os
import sys
import socket
import mimetypes
import asyncio
import logging
import json
from typing import Optional, Set
import websockets
from websockets.http11 import Response
from websockets.datastructures import Headers

from core.config import HostConfig
from core.agent_manager import AgentManager
from core.monitor import SystemMonitor
from network.router import ClientConnectionState

logger = logging.getLogger("viber.direct")


class DirectServer:
    """Async WebSocket & Static Web Server for direct Tailscale/LAN connectivity."""

    def __init__(
        self,
        config: HostConfig,
        agent_manager: AgentManager,
        monitor: SystemMonitor,
        client_dist_dir: Optional[str] = None,
    ):
        self.config = config
        self.agent_manager = agent_manager
        self.monitor = monitor
        if client_dist_dir:
            self.client_dist_dir = client_dist_dir
        elif hasattr(sys, "_MEIPASS") and os.path.exists(os.path.join(sys._MEIPASS, "dist")):
            self.client_dist_dir = os.path.join(sys._MEIPASS, "dist")
        else:
            candidates = [
                os.path.abspath(os.path.join(os.path.dirname(__file__), "../../viber-client/dist")),
                os.path.abspath(os.path.join(os.path.dirname(__file__), "../dist")),
                os.path.abspath(os.path.join(os.path.dirname(__file__), "dist")),
                os.path.abspath(os.path.join(os.getcwd(), "viber-client/dist")),
                os.path.abspath(os.path.join(os.getcwd(), "dist")),
            ]
            self.client_dist_dir = next((c for c in candidates if os.path.exists(c)), candidates[0])
        self.server = None
        self.active_clients: Set[ClientConnectionState] = set()

    async def start(self) -> None:
        """Starts the combined HTTP/WebSocket listener on Tailscale & Loopback."""
        port = self.config.direct_port
        endpoints = self.monitor.discover_local_endpoints()
        tailscale_ips = endpoints.get("tailscale", [])

        # Determine safe bind hosts
        if self.config.allow_lan or self.config.direct_bind == "0.0.0.0":
            bind_hosts = "0.0.0.0"
            logger.warning("⚠️ Security warning: Binding to 0.0.0.0 (all interfaces enabled via --allow-lan).")
        elif self.config.direct_bind and self.config.direct_bind not in ("tailscale+localhost", "auto"):
            bind_hosts = self.config.direct_bind
        else:
            # Secure default: strictly Tailscale and Loopback
            raw_candidates = ["127.0.0.1"]
            for ip in tailscale_ips:
                clean_ip = ip.split("%")[0].strip()
                # Exclude link-local IPv6 (fe80::) which cannot be bound without scope ID
                if clean_ip and not clean_ip.lower().startswith("fe80:") and clean_ip not in raw_candidates:
                    raw_candidates.append(clean_ip)

            # Test bindability of each candidate IP before attempting server bind
            validated_hosts = []
            for h in raw_candidates:
                family = socket.AF_INET6 if ":" in h else socket.AF_INET
                try:
                    s = socket.socket(family, socket.SOCK_STREAM)
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    s.bind((h, 0))
                    s.close()
                    validated_hosts.append(h)
                except Exception as e:
                    logger.debug(f"Candidate IP {h} cannot be bound ({e}), omitting.")
                    try:
                        s.close()
                    except Exception:
                        pass

            bind_hosts = validated_hosts or ["127.0.0.1"]
            logger.info(f"🔒 Security active: Listening strictly on Localhost & Tailscale: {bind_hosts} (0.0.0.0 disabled).")

        try:
            self.server = await websockets.serve(
                self._handle_ws_connection,
                host=bind_hosts,
                port=port,
                process_request=self._handle_http_request,
                ping_interval=20,
                ping_timeout=20,
                max_size=10 * 1024 * 1024,
            )
        except Exception as e:
            logger.warning(f"Binding to {bind_hosts} encountered error ({e}), falling back to 127.0.0.1.")
            bind_hosts = ["127.0.0.1"]
            self.server = await websockets.serve(
                self._handle_ws_connection,
                host=bind_hosts,
                port=port,
                process_request=self._handle_http_request,
                ping_interval=20,
                ping_timeout=20,
                max_size=10 * 1024 * 1024,
            )

        if isinstance(bind_hosts, list):
            logger.info(f"RemoteViber Direct Server listening on {', '.join(bind_hosts)}:{port}")
        else:
            logger.info(f"RemoteViber Direct Server listening on {bind_hosts}:{port}")

    def _is_trusted_network(self, connection, request=None) -> bool:
        """
        Determines whether a client connection is over a trusted private channel
        (Localhost loopback or Tailscale WireGuard mesh).
        """
        # If server is in strict security mode (allow_lan is False and 0.0.0.0 not bound),
        # only Localhost and Tailscale interfaces are bound in the kernel socket.
        # Any accepted packet is guaranteed to originate from trusted interfaces.
        if not self.config.allow_lan and self.config.direct_bind not in ("0.0.0.0", "lan"):
            return True

        # Check client IP
        remote_addr = getattr(connection, "remote_address", None)
        remote_ip = remote_addr[0] if isinstance(remote_addr, tuple) and len(remote_addr) > 0 else ""
        if remote_ip in ("127.0.0.1", "::1", "localhost"):
            return True
        if self.monitor.is_tailscale_ip(remote_ip):
            return True

        # Check server bound IP that received the packet
        local_addr = getattr(connection, "local_address", None)
        if not local_addr and hasattr(connection, "transport"):
            local_addr = connection.transport.get_extra_info("sockname")
        local_ip = local_addr[0] if isinstance(local_addr, tuple) and len(local_addr) > 0 else ""
        if local_ip in ("127.0.0.1", "::1"):
            return True
        if self.monitor.is_tailscale_ip(local_ip):
            return True

        # Check Host header
        headers = getattr(request, "headers", None)
        if not headers and hasattr(connection, "request"):
            headers = getattr(connection.request, "headers", None)
        if headers:
            host_header = (headers.get("Host", "")).lower().split(":")[0]
            if host_header in ("localhost", "127.0.0.1", "::1"):
                return True
            if self.monitor.is_tailscale_ip(host_header):
                return True
            if ".ts.net" in host_header or "tailscale" in host_header:
                return True

        return False

    def _handle_http_request(self, connection, request):
        """Serves the built client UI and pairing metadata over HTTP."""
        path = request.path
        clean_path = path.rstrip("/").split("?")[0]
        upgrade = request.headers.get("Upgrade", "")

        # Any WebSocket upgrade request must return None to trigger 101 Switching Protocols
        if upgrade.lower() == "websocket" or "sec-websocket-key" in request.headers:
            return None
        if clean_path == "/ws":
            return None

        # REST endpoints for pairing discovery
        if clean_path == "/api/pairing":
            is_trusted = self._is_trusted_network(connection, request)
            stats = self.monitor.get_system_stats()
            payload = self.config.generate_pairing_payload(stats["endpoints"])

            # Security: Allow auto-pairing over trusted private channels (Localhost and Tailscale WireGuard mesh)
            # If request is from an untrusted interface (e.g. unauthenticated LAN), strip token
            if not is_trusted:
                payload["token"] = ""

            body = json.dumps(payload).encode("utf-8")
            return Response(
                200,
                "OK",
                Headers([
                    ("Content-Type", "application/json"),
                    ("Cache-Control", "no-store, no-cache, must-revalidate"),
                    ("Access-Control-Allow-Origin", "*"),
                    ("Content-Length", str(len(body))),
                ]),
                body,
            )

        # Static file delivery for client GUI
        if os.path.exists(self.client_dist_dir):
            rel_path = clean_path.lstrip("/") or "index.html"
            target_file = os.path.join(self.client_dist_dir, rel_path)

            # Security check against directory traversal
            if not os.path.abspath(target_file).startswith(self.client_dist_dir):
                return Response(403, "Forbidden", Headers([("Content-Type", "text/plain")]), b"Forbidden")

            # Fallback for SPA routing
            is_html_request = False
            if not os.path.exists(target_file) or os.path.isdir(target_file):
                target_file = os.path.join(self.client_dist_dir, "index.html")
                is_html_request = True
            elif target_file.endswith("index.html"):
                is_html_request = True

            if os.path.exists(target_file) and os.path.isfile(target_file):
                mime, _ = mimetypes.guess_type(target_file)
                mime = mime or "application/octet-stream"
                try:
                    with open(target_file, "rb") as f:
                        content = f.read()

                    is_trusted = self._is_trusted_network(connection, request)
                    cache_control = "public, max-age=3600"

                    # Dynamically inject active pairing credentials into index.html for zero-latency auto-pairing
                    if is_html_request:
                        cache_control = "no-cache, no-store, must-revalidate"
                        endpoints = self.monitor.discover_local_endpoints()
                        preload_data = {
                            "hostId": self.config.host_id,
                            "hostName": self.config.host_name,
                            "directPort": self.config.direct_port,
                            "tailscaleIps": endpoints.get("tailscale", []),
                            "lanIps": endpoints.get("lan", ["127.0.0.1"]),
                            "token": self.config.pairing_secret if is_trusted else "",
                            "relayUrl": self.config.relay_url or "",
                        }
                        preload_script = f'<script id="__VIBER_PRELOAD_CONFIG__">window.__VIBER_PRELOAD__ = {json.dumps(preload_data)};</script>'
                        html_str = content.decode("utf-8", errors="replace")
                        if "</head>" in html_str:
                            html_str = html_str.replace("</head>", f"{preload_script}\n</head>", 1)
                        else:
                            html_str = preload_script + "\n" + html_str
                        content = html_str.encode("utf-8")

                    return Response(
                        200,
                        "OK",
                        Headers([
                            ("Content-Type", mime),
                            ("Cache-Control", cache_control),
                            ("Access-Control-Allow-Origin", "*"),
                            ("Content-Length", str(len(content))),
                        ]),
                        content,
                    )
                except Exception as e:
                    logger.debug(f"Static file serving error: {e}")

        # Fallback welcome page if dist not built yet
        endpoints = self.monitor.discover_local_endpoints()
        html = f"""<!DOCTYPE html>
<html>
<head><title>RemoteViber Host</title><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body style="background:#090d16;color:#e2e8f0;font-family:sans-serif;padding:2rem;">
  <h2>⚡ RemoteViber Host Online</h2>
  <p>Host ID: <code>{self.config.host_id}</code></p>
  <p>Public Key: <code>{self.config.key_manager.public_key_b64[:24]}...</code></p>
  <p>Tailscale IPs: <code>{', '.join(endpoints.get('tailscale', ['None']))}</code></p>
  <p>LAN IPs: <code>{', '.join(endpoints.get('lan', ['127.0.0.1']))}</code></p>
  <hr style="border-color:#1e293b"/>
  <p>Connect using the RemoteViber Android / Desktop Client or build the client UI.</p>
</body>
</html>""".encode("utf-8")
        return Response(
            200,
            "OK",
            Headers([
                ("Content-Type", "text/html; charset=utf-8"),
                ("Content-Length", str(len(html))),
            ]),
            html,
        )

    async def _handle_ws_connection(self, websocket):
        """Handles an incoming WebSocket connection."""
        async def send_raw(raw_text: str):
            try:
                await websocket.send(raw_text)
            except Exception:
                pass

        is_trusted = self._is_trusted_network(websocket, getattr(websocket, "request", None))
        state = ClientConnectionState(
            key_manager=self.config.key_manager,
            agent_manager=self.agent_manager,
            monitor=self.monitor,
            send_raw_func=send_raw,
            is_trusted_network=is_trusted,
        )
        self.active_clients.add(state)
        logger.info(f"Incoming WebSocket from {websocket.remote_address} (trusted_network={is_trusted})")

        try:
            async for raw_message in websocket:
                if isinstance(raw_message, str):
                    await state.handle_raw_message(raw_message)
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.warning(f"Client connection closed with exception: {e}")
        finally:
            state.cleanup()
            self.active_clients.discard(state)
            logger.info(f"WebSocket connection finished for {websocket.remote_address}")

    async def stop(self) -> None:
        if self.server:
            self.server.close()
            await self.server.wait_closed()
