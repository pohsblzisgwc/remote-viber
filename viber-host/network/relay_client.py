"""One independent authenticated state, deadline and bounded queue per relay peer."""
import asyncio
import json
import logging
import re
from urllib.parse import urlsplit
from websockets.asyncio.client import connect
from core.crypto import ProtocolError, MAX_FRAME_BYTES, HANDSHAKE_TIMEOUT
from network.router import ClientConnectionState

logger = logging.getLogger("viber.relay")


def validate_relay_url(value):
    url = urlsplit(value)
    if url.scheme not in ("ws", "wss") or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("Invalid relay URL")
    if url.scheme == "ws" and url.hostname not in ("127.0.0.1", "::1", "localhost"):
        raise ValueError("Remote relays require wss://; plaintext relay transport is loopback-only")
    return value.rstrip("/")


class RelayClient:
    def __init__(self, config, agent_manager, monitor):
        self.config, self.agent_manager, self.monitor = config, agent_manager, monitor
        self._running = False
        self._task = None
        self.peers = {}

    def start(self):
        if not self.config.relay_url:
            return
        validate_relay_url(self.config.relay_url)
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._connection_loop())

    async def _connection_loop(self):
        backoff = 2
        while self._running:
            try:
                url = validate_relay_url(self.config.relay_url) + "/register/host"
                async with connect(url, open_timeout=10, close_timeout=3, ping_interval=20,
                                   ping_timeout=20, max_size=MAX_FRAME_BYTES, max_queue=16, compression=None) as ws:
                    await self._serve_relay(ws)
                    backoff = 2
            except asyncio.CancelledError:
                break
            except Exception:
                logger.warning("Relay unavailable; retrying without disclosing credentials")
            finally:
                await self._cleanup_peers()
            if self._running:
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60)

    async def _serve_relay(self, ws):
        send_lock = asyncio.Lock()
        async def send_control(message):
            async with send_lock:
                await asyncio.wait_for(ws.send(json.dumps(message)), 5)

        raw = await asyncio.wait_for(ws.recv(), HANDSHAKE_TIMEOUT)
        message = json.loads(raw)
        if not isinstance(message, dict) or message.get("type") != "REGISTER_CHALLENGE" or message.get("v") != 2:
            raise ProtocolError("Relay v2 required")
        await send_control(self.config.key_manager.sign_registration(message.get("challenge")))
        response = json.loads(await asyncio.wait_for(ws.recv(), HANDSHAKE_TIMEOUT))
        if not isinstance(response, dict) or response.get("type") != "REGISTERED" or response.get("host_id") != self.config.host_id:
            raise ProtocolError("Registration failed")

        async def run_peer(cid, state, queue):
            deadline = asyncio.get_running_loop().time() + HANDSHAKE_TIMEOUT
            try:
                while True:
                    if state.is_authenticated:
                        raw = await queue.get()
                    else:
                        remaining = deadline - asyncio.get_running_loop().time()
                        if remaining <= 0:
                            raise ProtocolError("Handshake timeout")
                        raw = await asyncio.wait_for(queue.get(), remaining)
                    await state.handle_raw_message(raw)
            except asyncio.CancelledError:
                raise
            except Exception:
                try:
                    await send_control({"type": "CLIENT_CLOSE", "client_id": cid})
                except Exception:
                    pass
            finally:
                state.cleanup()
                current = self.peers.get(cid)
                if current and current[0] is state:
                    self.peers.pop(cid, None)

        async for raw in ws:
            if not isinstance(raw, str):
                raise ProtocolError("Text relay frames required")
            message = json.loads(raw)
            if not isinstance(message, dict):
                raise ProtocolError("Invalid relay message")
            cid = message.get("client_id", "")
            if not isinstance(cid, str) or not re.fullmatch(r"[0-9a-f]{32}", cid):
                raise ProtocolError("Invalid client ID")
            kind = message.get("type")
            if kind == "CLIENT_OPEN":
                if cid in self.peers:
                    raise ProtocolError("Duplicate peer")
                if len(self.peers) >= 32:
                    await send_control({"type": "CLIENT_CLOSE", "client_id": cid})
                    continue
                async def send_peer(text, peer_id=cid):
                    await send_control({"type": "RELAY_FORWARD", "client_id": peer_id, "payload": text})
                async def close_peer(peer_id=cid):
                    await send_control({"type": "CLIENT_CLOSE", "client_id": peer_id})
                state = ClientConnectionState(self.config.key_manager, self.agent_manager, self.monitor,
                                              send_peer, close_func=close_peer)
                queue = asyncio.Queue(maxsize=32)
                task = asyncio.create_task(run_peer(cid, state, queue))
                self.peers[cid] = (state, queue, task)
            elif kind == "CLIENT_DATA":
                peer = self.peers.get(cid)
                if not peer:
                    continue
                payload = message.get("payload")
                if not isinstance(payload, str) or len(payload.encode("utf-8")) > MAX_FRAME_BYTES:
                    raise ProtocolError("Invalid payload")
                try:
                    peer[1].put_nowait(payload)
                except asyncio.QueueFull:
                    peer[0].cleanup()
                    peer[2].cancel()
                    await send_control({"type": "CLIENT_CLOSE", "client_id": cid})
            elif kind == "CLIENT_CLOSE":
                peer = self.peers.pop(cid, None)
                if peer:
                    peer[0].cleanup()
                    peer[2].cancel()
                    await asyncio.gather(peer[2], return_exceptions=True)
            else:
                raise ProtocolError("Unexpected relay message")

    async def _cleanup_peers(self):
        peers = list(self.peers.values())
        self.peers.clear()
        for state, _, task in peers:
            state.cleanup()
            task.cancel()
        if peers:
            await asyncio.gather(*(p[2] for p in peers), return_exceptions=True)

    def stop(self):
        self._running = False
        if self._task:
            self._task.cancel()
