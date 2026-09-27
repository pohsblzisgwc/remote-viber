"""
RemoteViber Linux Signaling & Zero-Trust Relay Server
Facilitates peer discovery, direct Tailscale/LAN signaling, and E2EE encrypted data relay.
"""

import json
import logging
import urllib.parse
from typing import Dict, Any, Optional, Set
import websockets
from websockets.http11 import Response
from websockets.datastructures import Headers

from registry import HostRegistry

logger = logging.getLogger("viber.server")


class RelayServer:
    """Async signaling and zero-knowledge encrypted traffic router."""

    def __init__(self, host: str = "0.0.0.0", port: int = 8766):
        self.host = host
        self.port = port
        self.registry = HostRegistry()
        # Map client connection to host connection for bidirectional relay
        self.client_to_host: Dict[Any, Any] = {}
        self.host_to_clients: Dict[Any, Set[Any]] = {}
        self.server = None

    async def start(self) -> None:
        self.server = await websockets.serve(
            self._handle_connection,
            host=self.host,
            port=self.port,
            process_request=self._handle_http,
            ping_interval=25,
            ping_timeout=25,
            max_size=10 * 1024 * 1024,
        )
        logger.info(f"RemoteViber Relay Server listening on {self.host}:{self.port}")

    def _handle_http(self, connection, request):
        path = request.path
        if path.startswith("/register/") or path.startswith("/connect/"):
            return None  # Upgrade to WebSocket

        if path == "/healthz":
            return Response(
                200,
                "OK",
                Headers([("Content-Type", "text/plain"), ("Content-Length", "2")]),
                b"OK",
            )

        if path == "/api/hosts":
            hosts = self.registry.list_hosts()
            body = json.dumps({"hosts": hosts}).encode("utf-8")
            return Response(
                200,
                "OK",
                Headers([
                    ("Content-Type", "application/json"),
                    ("Access-Control-Allow-Origin", "*"),
                    ("Content-Length", str(len(body))),
                ]),
                body,
            )

        # Default info page
        info = {
            "service": "RemoteViber Relay Server",
            "version": "1.0.0",
            "active_hosts": len(self.registry.hosts),
        }
        body = json.dumps(info).encode("utf-8")
        return Response(
            200,
            "OK",
            Headers([
                ("Content-Type", "application/json"),
                ("Content-Length", str(len(body))),
            ]),
            body,
        )

    async def _handle_connection(self, websocket):
        path = getattr(getattr(websocket, "request", None), "path", "")
        if not path:
            path = getattr(websocket, "path", "")

        parsed_url = urllib.parse.urlparse(path)
        route = parsed_url.path
        query = urllib.parse.parse_qs(parsed_url.query)

        if route == "/register/host":
            await self._handle_host_stream(websocket)
        elif route == "/connect/client":
            host_id = query.get("host_id", [""])[0]
            await self._handle_client_stream(websocket, host_id)
        else:
            await websocket.close(1000, "Unknown route")

    async def _handle_host_stream(self, websocket):
        """Manages registration and relay frames from an Agent Host."""
        host_id = None
        try:
            async for raw in websocket:
                try:
                    msg = json.loads(raw)
                except Exception:
                    continue

                msg_type = msg.get("type")

                if msg_type == "REGISTER_HOST":
                    host_id = msg.get("host_id")
                    self.registry.register(
                        host_id=host_id,
                        host_name=msg.get("host_name", "Host"),
                        direct_port=msg.get("direct_port", 8765),
                        pub_key=msg.get("pub_key", ""),
                        endpoints=msg.get("endpoints", {}),
                        connection=websocket,
                    )
                    self.host_to_clients[websocket] = set()
                    await websocket.send(json.dumps({
                        "type": "REGISTERED",
                        "host_id": host_id,
                        "status": "ready",
                    }))
                    logger.info(f"Host '{host_id}' successfully registered on relay server.")

                elif msg_type == "RELAY_FORWARD":
                    # Forward encrypted payload to all subscribed clients of this host
                    payload = msg.get("payload")
                    clients = self.host_to_clients.get(websocket, set())
                    for c_ws in list(clients):
                        try:
                            await c_ws.send(payload)
                        except Exception:
                            pass

        finally:
            self.registry.unregister_connection(websocket)
            clients = self.host_to_clients.pop(websocket, set())
            for c_ws in clients:
                try:
                    await c_ws.send(json.dumps({"type": "HOST_DISCONNECTED"}))
                except Exception:
                    pass
            if host_id:
                logger.info(f"Host '{host_id}' disconnected from relay.")

    async def _handle_client_stream(self, websocket, host_id: str):
        """Manages a remote client session querying or relaying to a host."""
        host = self.registry.get_host(host_id)
        if not host:
            await websocket.send(json.dumps({
                "type": "ERROR",
                "error": f"Host '{host_id}' is offline or not registered",
            }))
            await websocket.close()
            return

        # Notify client of host endpoints (Direct LAN / Tailscale)
        await websocket.send(json.dumps({
            "type": "HOST_CANDIDATE",
            "host_id": host.host_id,
            "host_name": host.host_name,
            "pub_key": host.pub_key,
            "direct_port": host.direct_port,
            "endpoints": host.endpoints,
        }))

        # Register client under host for relay fallback
        host_ws = host.connection
        if host_ws in self.host_to_clients:
            self.host_to_clients[host_ws].add(websocket)
        self.client_to_host[websocket] = host_ws

        try:
            async for raw in websocket:
                try:
                    if host_ws:
                        wrapper = json.dumps({"type": "CLIENT_DATA", "payload": raw})
                        await host_ws.send(wrapper)
                except Exception:
                    break
        finally:
            if host_ws in self.host_to_clients:
                self.host_to_clients[host_ws].discard(websocket)
            self.client_to_host.pop(websocket, None)

    async def stop(self) -> None:
        if self.server:
            self.server.close()
            await self.server.wait_closed()
