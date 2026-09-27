"""
RemoteViber Host Daemon Entrypoint (Windows & Linux)
Runs the PTY process manager, E2EE direct listener, and optional relay bridge.
"""

import sys
import os
import signal
import asyncio
import argparse
import logging

from core.config import HostConfig
from core.agent_manager import AgentManager
from core.monitor import SystemMonitor
from network.direct_server import DirectServer
from network.relay_client import RelayClient

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("viber.main")


def print_banner(config: HostConfig, monitor: SystemMonitor):
    stats = monitor.get_system_stats()
    endpoints = stats["endpoints"]
    tailscale_ips = endpoints.get("tailscale", [])
    lan_ips = endpoints.get("lan", [])
    pairing_url = config.generate_pairing_url(endpoints)

    print("\n" + "=" * 65)
    print("   🚀 REMOTE VIBER — AGENT LAUNCHER & HOST DAEMON")
    print("=" * 65)
    print(f"  Host ID       : {config.host_id}")
    print(f"  Host Name     : {config.host_name}")
    print(f"  E2EE Fingerprint: {config.key_manager.get_fingerprint()} (Curve P-256)")
    print(f"  Pairing Secret: {config.pairing_secret}")
    print("-" * 65)
    print("  Direct Web & WebSocket Endpoints (Auto-Paired in Browser):")
    if tailscale_ips:
        for ip in tailscale_ips:
            ip_display = f"[{ip}]" if ":" in ip else ip
            print(f"    ⚡ Tailscale : http://{ip_display}:{config.direct_port}/?token={config.pairing_secret}")
    else:
        print("    ⚡ Tailscale : (No Tailscale interface detected)")
    print(f"    💻 Localhost : http://127.0.0.1:{config.direct_port}/?token={config.pairing_secret}")
    if config.allow_lan and lan_ips:
        for ip in lan_ips:
            print(f"    🏠 LAN       : http://{ip}:{config.direct_port}/?token={config.pairing_secret}")
    else:
        print("    🛡️ LAN (0.0.0.0): 已为安全默认关闭 (仅监听 Tailscale 与本地回环)")
    if config.relay_url:
        print(f"    🌐 Relay Srv : {config.relay_url}")
    print("-" * 65)
    print("  🔗 1-Click Mobile / Remote Pairing URL:")
    print(f"  {pairing_url}")
    print("=" * 65 + "\n")


async def main_async():
    parser = argparse.ArgumentParser(description="RemoteViber Agent Host & Launcher Daemon")
    parser.add_argument("--port", type=int, default=None, help="Port to listen for direct connections (default 8765)")
    parser.add_argument("--bind", type=str, default=None, help="Specific bind address override")
    parser.add_argument("--allow-lan", action="store_true", help="Explicitly enable binding to 0.0.0.0 across all LAN interfaces (default: disabled, Tailscale+Localhost only)")
    parser.add_argument("--relay", type=str, default=None, help="Optional Linux relay server URL (e.g. ws://relay:8766)")
    parser.add_argument("--name", type=str, default=None, help="Host display name override")
    parser.add_argument("--pair-info", action="store_true", help="Print pairing credentials and exit")

    args = parser.parse_args()

    config = HostConfig()
    if args.port:
        config.direct_port = args.port
    if args.allow_lan:
        config.allow_lan = True
        config.direct_bind = "0.0.0.0"
    elif args.bind:
        config.direct_bind = args.bind
    if args.relay:
        config.relay_url = args.relay
    if args.name:
        config.host_name = args.name
    config.save()

    monitor = SystemMonitor()

    if args.pair_info:
        print_banner(config, monitor)
        return

    agent_manager = AgentManager()
    direct_server = DirectServer(config, agent_manager, monitor)
    relay_client = RelayClient(config, agent_manager, monitor)

    print_banner(config, monitor)

    # Start network services
    await direct_server.start()
    relay_client.start()

    logger.info("RemoteViber Daemon initialized. Idle CPU: ~0%, E2EE active.")

    stop_event = asyncio.Event()

    def signal_handler():
        logger.info("Shutdown signal received, stopping...")
        stop_event.set()

    if sys.platform != "win32":
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, signal_handler)
    else:
        # Windows signal handling
        signal.signal(signal.SIGINT, lambda s, f: signal_handler())
        signal.signal(signal.SIGTERM, lambda s, f: signal_handler())

    try:
        await stop_event.wait()
    finally:
        logger.info("Cleaning up sessions and network sockets...")
        relay_client.stop()
        await direct_server.stop()
        logger.info("RemoteViber Daemon stopped cleanly.")


def main():
    try:
        asyncio.run(main_async())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
