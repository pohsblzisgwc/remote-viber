"""
RemoteViber Linux Relay Server Entrypoint
"""

import asyncio
import argparse
import signal
import logging

from server import RelayServer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("viber.server.main")


async def main_async():
    parser = argparse.ArgumentParser(description="RemoteViber Linux Signaling & Relay Server")
    parser.add_argument("--host", type=str, default="0.0.0.0", help="Address to bind (default 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8766, help="Port to listen (default 8766)")
    args = parser.parse_args()

    server = RelayServer(host=args.host, port=args.port)
    await server.start()

    print("\n" + "=" * 60)
    print("   🌐 REMOTE VIBER — LINUX SIGNALING & RELAY SERVER")
    print("=" * 60)
    print(f"  Listening on: {args.host}:{args.port}")
    print("  Zero-Knowledge E2EE Forwarding: ACTIVE")
    print("  Peer Discovery & Tailscale Signaling: ACTIVE")
    print("=" * 60 + "\n")

    stop_event = asyncio.Event()

    def signal_handler():
        logger.info("Shutdown signal received, stopping...")
        stop_event.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, signal_handler)

    try:
        await stop_event.wait()
    finally:
        await server.stop()
        logger.info("Relay Server stopped cleanly.")


def main():
    try:
        asyncio.run(main_async())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
