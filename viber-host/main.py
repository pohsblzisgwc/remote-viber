"""RemoteViber host. Credentials are printed only with --pair-info."""
import argparse
import asyncio
import logging
import os
import signal
import sys
from core.config import HostConfig, HostLock, get_persistent_dir
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
        endpoints = monitor.discover_local_endpoints()
        print("\nSENSITIVE: import only into a trusted local or HTTPS client. Do not share or log.")
        print("\n=== 配对码 (Pairing Code) ===")
        print("请在 Android 客户端【主机管理】中手动粘贴导入，或在 Web 客户端完成安全配对：")
        print(config.generate_pairing_code(endpoints))
        print("\n=== 配对链接 (Pairing URI) ===")
        print(config.generate_pairing_url(endpoints))
        print("\n提示：为避免长期配对凭据被第三方应用截获，Android 外部自定义协议直接拉起已停用，请使用应用内粘贴导入。")
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
    p.add_argument("--rotate-credentials", action="store_true",
                   help="Rotate credentials offline when Host is stopped (invalidates all old pairings)")
    args = p.parse_args()

    config_dir = args.config_dir or get_persistent_dir()
    lock = HostLock(config_dir)

    if args.rotate_credentials:
        if lock.is_locked():
            print("\n[ERROR] 无法在 Host 守护进程运行期间执行离线凭据轮换！", file=sys.stderr)
            print("当前已有运行中的 Host 实例持有内存密钥。在运行期直接轮换磁盘凭据会导致状态不一致，并引发认证故障。\n", file=sys.stderr)
            print("正确操作步骤：", file=sys.stderr)
            print("  1. 停止当前运行的 Host 进程（Ctrl+C 或停止相应守护进程）；", file=sys.stderr)
            print("  2. 执行离线轮换：python main.py --rotate-credentials --pair-info", file=sys.stderr)
            print("  3. 重新启动 Host 守护进程以使新凭据生效、旧凭据作废。\n", file=sys.stderr)
            sys.exit(1)

        if not lock.acquire(non_blocking=True):
            print("[ERROR] 另一个凭据轮换进程正在进行，请稍候重试。", file=sys.stderr)
            sys.exit(1)
        try:
            config = HostConfig(args.config_dir)
            config.rotate_credentials()
            config.save()
            monitor = SystemMonitor()
            print_banner(config, monitor, show_pairing=args.pair_info)
        finally:
            lock.release()
        return

    if args.pair_info:
        config = HostConfig(args.config_dir)
        monitor = SystemMonitor()
        print_banner(config, monitor, show_pairing=True)
        return

    if not lock.acquire(non_blocking=True):
        print(f"\n[ERROR] 该配置目录已有运行中的 RemoteViber Host 实例: {config_dir}", file=sys.stderr)
        print("请勿重复启动多个实例；如需重启，请先停止已有实例。\n", file=sys.stderr)
        sys.exit(1)

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
    config.save()
    monitor = SystemMonitor()
    print_banner(config, monitor, False)
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
        lock.release()


def main():
    try:
        asyncio.run(main_async())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
