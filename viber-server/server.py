"""Protocol-v2 blind relay with signed registration and isolated client routes."""
import asyncio
import json
import logging
import os
import re
import secrets
from urllib.parse import parse_qs, urlsplit
from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed
from websockets.http11 import Response
from websockets.datastructures import Headers
from registry import HostRegistry
from secure_protocol import (ProtocolError, b64, registration_message, verify_signature,
                             MAX_CLIENT_FRAME_BYTES, MAX_RELAY_FRAME_BYTES,
                             MAX_FRAME_BYTES, HANDSHAKE_TIMEOUT)

logger = logging.getLogger("viber.server")


def parse_message(raw):
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > MAX_RELAY_FRAME_BYTES:
        raise ProtocolError("Invalid relay frame")
    try:
        msg = json.loads(raw)
    except ValueError as exc:
        raise ProtocolError("Invalid JSON") from exc
    if not isinstance(msg, dict):
        raise ProtocolError("Object required")
    return msg


class RelayServer:
    def __init__(self, host="127.0.0.1", port=8766):
        self.host, self.port = host, port
        self.registry = HostRegistry()
        self.client_to_host = {}
        self.host_to_clients = {}
        self._send_locks = {}
        self._connections = set()
        self.server = None

    async def start(self):
        self.server = await serve(self._handle_connection, self.host, self.port,
                                  process_request=self._handle_http, open_timeout=10, close_timeout=3,
                                  ping_interval=25, ping_timeout=25,
                                  max_size=MAX_RELAY_FRAME_BYTES, max_queue=16, compression=None)
        if self.port == 0:
            self.port = self.server.sockets[0].getsockname()[1]

    @staticmethod
    def _response(code, body):
        raw = body.encode("utf-8")
        return Response(code, {200: "OK", 404: "Not Found", 503: "Service Unavailable"}[code],
                        Headers([("Content-Type", "text/plain"), ("Content-Length", str(len(raw))),
                                 ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff")]), raw)

    def _handle_http(self, connection, request):
        path = urlsplit(request.path).path
        if path == "/healthz":
            return self._response(200, "OK")
        if path in ("/register/host", "/connect/client") and request.headers.get("Upgrade", "").lower() == "websocket":
            if len(self._connections) >= 512:
                return self._response(503, "Connection limit reached")
            return None
        # No public host enumeration, endpoints or credentials.
        return self._response(404, "Not found")

    async def _send(self, ws, msg):
        lock = self._send_locks.get(ws)
        if lock is None:
            raise ProtocolError("Unknown relay connection")
        async with lock:
            await asyncio.wait_for(ws.send(msg if isinstance(msg, str) else json.dumps(msg)), 5)

    async def _handle_connection(self, ws):
        if len(self._connections) >= 512:
            await ws.close(1013, "Connection limit reached")
            return
        self._connections.add(ws)
        self._send_locks[ws] = asyncio.Lock()
        try:
            parsed = urlsplit(ws.request.path)
            if parsed.path == "/register/host":
                await self._handle_host_stream(ws)
            elif parsed.path == "/connect/client":
                query = parse_qs(parsed.query, max_num_fields=4)
                ids = query.get("host_id", [])
                if len(ids) != 1 or not re.fullmatch(r"host-[0-9a-f]{64}", ids[0]):
                    raise ProtocolError("Invalid host ID")
                await self._handle_client_stream(ws, ids[0])
            else:
                raise ProtocolError("Unknown route")
        except ConnectionClosed:
            pass
        except (ProtocolError, ValueError, asyncio.TimeoutError):
            await ws.close(1008, "Invalid relay session")
        except Exception:
            logger.warning("Relay connection failed", exc_info=False)
            await ws.close(1011, "Relay error")
        finally:
            self._send_locks.pop(ws, None)
            self._connections.discard(ws)

    async def _handle_host_stream(self, ws):
        challenge = b64(os.urandom(32))
        await self._send(ws, {"type": "REGISTER_CHALLENGE", "v": 2, "challenge": challenge})
        try:
            msg = parse_message(await asyncio.wait_for(ws.recv(), HANDSHAKE_TIMEOUT))
            if set(msg) != {"type", "v", "host_id", "pub_key", "signature"} or msg["type"] != "REGISTER_HOST" or type(msg["v"]) is not int or msg["v"] != 2:
                raise ProtocolError("Signed v2 registration required")
            context = registration_message(challenge, msg["host_id"], msg["pub_key"])
            verify_signature(msg["pub_key"], msg["signature"], context)
            self.registry.register(msg["host_id"], "Host", 0, msg["pub_key"], {}, ws)
            self.host_to_clients[ws] = {}
            await self._send(ws, {"type": "REGISTERED", "v": 2, "host_id": msg["host_id"]})
            async for raw in ws:
                frame = parse_message(raw)
                cid = frame.get("client_id")
                if not isinstance(cid, str):
                    raise ProtocolError("Missing client ID")
                target = self.host_to_clients.get(ws, {}).get(cid)
                if target is None:
                    # A peer can close while an encrypted response is in flight.
                    # Unknown IDs are dropped, never re-routed or broadcast.
                    continue
                if frame.get("type") == "RELAY_FORWARD":
                    payload = frame.get("payload")
                    if not isinstance(payload, str) or len(payload.encode("utf-8")) > MAX_CLIENT_FRAME_BYTES:
                        logger.warning("Host forwarded oversized payload for client %s", cid)
                        if target:
                            await target.close(1009, "Message too big")
                        continue
                    try:
                        await self._send(target, payload)
                    except (ConnectionClosed, asyncio.TimeoutError):
                        await target.close(1013, "Slow client")
                elif frame.get("type") == "CLIENT_CLOSE":
                    await target.close(1008, "Host closed session")
                else:
                    raise ProtocolError("Invalid relay control message")
        finally:
            self.registry.unregister_connection(ws)
            clients = self.host_to_clients.pop(ws, {})
            if clients:
                await asyncio.gather(*(c.close(1012, "Host disconnected") for c in clients.values()), return_exceptions=True)

    async def _handle_client_stream(self, ws, host_id):
        host = self.registry.get_host(host_id)
        if host is None:
            await ws.close(1013, "Host unavailable")
            return
        host_ws = host.connection
        peers = self.host_to_clients.get(host_ws)
        if peers is None or len(peers) >= 32:
            await ws.close(1013, "Host connection limit reached")
            return
        cid = secrets.token_hex(16)
        peers[cid] = ws
        self.client_to_host[ws] = (host_ws, cid)
        try:
            await self._send(host_ws, {"type": "CLIENT_OPEN", "client_id": cid})
            async for raw in ws:
                if not isinstance(raw, str):
                    await ws.close(1003, "Text frames required")
                    break
                raw_bytes = raw.encode("utf-8")
                if len(raw_bytes) > MAX_CLIENT_FRAME_BYTES:
                    logger.warning("Client %s payload exceeds client limit (%d > %d)", cid, len(raw_bytes), MAX_CLIENT_FRAME_BYTES)
                    await ws.close(1009, "Message too big")
                    break
                envelope = {"type": "CLIENT_DATA", "client_id": cid, "payload": raw}
                serialized = json.dumps(envelope)
                serialized_bytes = serialized.encode("utf-8")
                if len(serialized_bytes) > MAX_RELAY_FRAME_BYTES:
                    logger.warning("Client %s envelope exceeds relay limit (%d > %d)", cid, len(serialized_bytes), MAX_RELAY_FRAME_BYTES)
                    await ws.close(1009, "Message too big")
                    break
                try:
                    await self._send(host_ws, serialized)
                except (ConnectionClosed, asyncio.TimeoutError):
                    break
        finally:
            self.host_to_clients.get(host_ws, {}).pop(cid, None)
            self.client_to_host.pop(ws, None)
            try:
                await self._send(host_ws, {"type": "CLIENT_CLOSE", "client_id": cid})
            except Exception:
                pass

    async def stop(self):
        if self.server:
            self.server.close()
            await self.server.wait_closed()
