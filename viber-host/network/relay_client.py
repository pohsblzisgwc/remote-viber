"""
RemoteViber Outbound Relay Client
Connects the Host to the central Linux signaling/relay server when configured.
Enables peer rendezvous and zero-knowledge encrypted traffic forwarding across NATs.
"""

import json
import asyncio
import logging
from typing import Optional
import websockets

from core.config import HostConfig
from core.agent_manager import AgentManager
from core.monitor import SystemMonitor
from network.router import ClientConnectionState

logger = logging.getLogger("viber.relay")


class RelayClient:
    """Manages persistent outbound connection to the Linux relay/signaling server."""

    def __init__(
        self,
        config: HostConfig,
        agent_manager: AgentManager,
        monitor: SystemMonitor,
    ):
        self.config = config
        self.agent_manager = agent_manager
        self.monitor = monitor
        self._running = False
        self._task: Optional[asyncio.Task] = None

    def start(self) -> None:
        if not self.config.relay_url:
            logger.info("No relay URL configured; running in direct Tailscale/LAN mode only.")
            return

        self._running = True
        self._task = asyncio.create_task(self._connection_loop())

    async def _connection_loop(self) -> None:
        """Maintains persistent connection with exponential backoff on network changes."""
        backoff = 2
        while self._running:
            try:
                logger.info(f"Connecting to relay server at {self.config.relay_url}...")
                async with websockets.connect(
                    f"{self.config.relay_url}/register/host",
                    ping_interval=20,
                    ping_timeout=20,
                ) as ws:
                    backoff = 2
                    logger.info("Connected to relay server. Registering host...")

                    # Send registration message
                    stats = self.monitor.get_system_stats()
                    reg_payload = {
                        "type": "REGISTER_HOST",
                        "host_id": self.config.host_id,
                        "host_name": self.config.host_name,
                        "direct_port": self.config.direct_port,
                        "pub_key": self.config.key_manager.public_key_b64,
                        "endpoints": stats["endpoints"],
                    }
                    await ws.send(json.dumps(reg_payload))

                    # Create connection state router for relay clients
                    async def send_via_relay(raw_text: str):
                        try:
                            wrapper = {
                                "type": "RELAY_FORWARD",
                                "payload": raw_text,
                            }
                            await ws.send(json.dumps(wrapper))
                        except Exception:
                            pass

                    state = ClientConnectionState(
                        key_manager=self.config.key_manager,
                        agent_manager=self.agent_manager,
                        monitor=self.monitor,
                        send_raw_func=send_via_relay,
                    )

                    async for message in ws:
                        if isinstance(message, str):
                            try:
                                parsed = json.loads(message)
                                if parsed.get("type") == "CLIENT_DATA":
                                    await state.handle_raw_message(parsed.get("payload", ""))
                            except Exception:
                                pass

                    state.cleanup()

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"Relay connection error ({e}); retrying in {backoff}s...")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60)

    def stop(self) -> None:
        self._running = False
        if self._task:
            self._task.cancel()
