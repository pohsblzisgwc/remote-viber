"""Explicit v2 relay entrypoint. Terminate public TLS at a trusted reverse proxy."""
import argparse
import asyncio
import logging
from server import RelayServer

async def run(host, port):
    server = RelayServer(host, port)
    await server.start()
    logging.getLogger('viber.relay').info('Relay v2 listening on %s:%s', host, server.port)
    try:
        await asyncio.Event().wait()
    finally:
        await server.stop()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8766)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535: parser.error('Port must be between 1 and 65535')
    logging.basicConfig(level=logging.INFO)
    try: asyncio.run(run(args.bind, args.port))
    except KeyboardInterrupt: pass

if __name__ == '__main__': main()
