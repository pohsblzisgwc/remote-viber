"""RemoteViber host. Credentials are printed only with --pair-info."""
import argparse
import asyncio
import logging
import os
import signal
import sys
from core.config import HostConfig
from core.agent_manager import AgentManager
from core.monitor import SystemMonitor
from network.direct_server import DirectServer
from network.relay_client import RelayClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("viber.main")


def print_banner(config, monitor, show_pairing=False):
    print("RemoteViber — protocol v2 (authenticated E2EE required)")
    print(f"Host ID: {config.host_id}")
    print(f"Identity SHA-256: {config.key_manager.get_fingerprint()}")
    print(f"Local UI: http://127.0.0.1:{config.direct_port}/")
    if config.migrated:
        print("Legacy identity/credentials rotated. All clients must be re-paired.")
    if show_pairing:
        print("SENSITIVE: import only into a trusted local or HTTPS client. Do not share or log.")
        print(config.generate_pairing_url(monitor.discover_local_endpoints()))
    else:
        print("Use --pair-info on this host to obtain the private pairing bundle.")


async def main_async():
    p = argparse.ArgumentParser(description="RemoteViber secure host")
    p.add_argument("--port", type=int)
    p.add_argument("--bind")
    p.add_argument("--allow-lan", action="store_true")
    p.add_argument("--relay", help="wss:// relay URL; ws:// permitted only on loopback")
    p.add_argument("--name")
    p.add_argument("--config-dir")
    p.add_argument("--origin", action="append", help="Exact permitted browser origin; repeatable")
    p.add_argument("--direct-url", help="Explicit wss:// endpoint for a trusted TLS reverse proxy")
    p.add_argument("--pair-info", action="store_true")
    p.add_argument("--rotate-credentials", action="store_true", help="Invalidate ALL old pairings")
    args = p.parse_args()
    config = HostConfig(args.config_dir)
    if args.port is not None:
        if not 1 <= args.port <= 65535:
            p.error("port must be between 1 and 65535")
        config.direct_port = args.port
    if args.allow_lan:
        config.allow_lan, config.direct_bind = True, "0.0.0.0"
    elif args.bind:
        config.direct_bind = args.bind
    if args.relay is not None:
        config.relay_url = args.relay or None
    if args.name:
        config.host_name = args.name[:128]
    if args.origin is not None:
        config.allowed_origins = args.origin
    if args.direct_url is not None:
        config.direct_url = args.direct_url
    if args.rotate_credentials:
        config.rotate_credentials()
    config.save()
    monitor = SystemMonitor()
    print_banner(config, monitor, args.pair_info)
    if args.pair_info:
        return
    manager = AgentManager(config_path=os.path.join(config.config_dir, "viber_profiles.json"))
    direct = DirectServer(config, manager, monitor)
    relay = RelayClient(config, manager, monitor)
    await direct.start()
    relay.start()
    stopped = asyncio.Event()
    if sys.platform != "win32":
        for sig in (signal.SIGINT, signal.SIGTERM):
            asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
    else:
        signal.signal(signal.SIGINT, lambda *_: stopped.set())
        signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    try:
        await stopped.wait()
    finally:
        relay.stop()
        await direct.stop()


def main():
    try:
        asyncio.run(main_async())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
