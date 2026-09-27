"""
RemoteViber Lightweight Resource Monitor
Provides cached, near-zero-overhead metrics on system and active agent processes.
"""

import time
import socket
import psutil
from typing import Dict, Any, List


class SystemMonitor:
    """Non-blocking, cached resource sampler ensuring near-zero idle CPU footprint."""

    def __init__(self, cache_ttl_seconds: float = 2.0):
        self.cache_ttl = cache_ttl_seconds
        self._last_sample_time = 0.0
        self._cached_metrics: Dict[str, Any] = {}
        # Prime psutil cpu measurement
        try:
            psutil.cpu_percent(interval=None)
        except Exception:
            pass

    def get_system_stats(self) -> Dict[str, Any]:
        """Returns cached CPU, RAM, and Host network metrics."""
        now = time.time()
        if now - self._last_sample_time < self.cache_ttl and self._cached_metrics:
            return self._cached_metrics

        try:
            cpu_pct = psutil.cpu_percent(interval=None)
            mem = psutil.virtual_memory()
            mem_pct = mem.percent
            mem_used_mb = int(mem.used / (1024 * 1024))
            mem_total_mb = int(mem.total / (1024 * 1024))
        except Exception:
            cpu_pct = 0.0
            mem_pct = 0.0
            mem_used_mb = 0
            mem_total_mb = 0

        endpoints = self.discover_local_endpoints()

        self._cached_metrics = {
            "cpu_percent": round(cpu_pct, 1),
            "memory_percent": round(mem_pct, 1),
            "memory_used_mb": mem_used_mb,
            "memory_total_mb": mem_total_mb,
            "hostname": socket.gethostname(),
            "endpoints": endpoints,
            "timestamp": now,
        }
        self._last_sample_time = now
        return self._cached_metrics

    @staticmethod
    def discover_local_endpoints() -> Dict[str, List[str]]:
        """Identifies Tailscale, LAN, and Loopback IP addresses."""
        endpoints: Dict[str, List[str]] = {
            "tailscale": [],
            "lan": [],
            "loopback": ["127.0.0.1"],
        }

        try:
            net_if_addrs = psutil.net_if_addrs()
            for iface_name, addrs in net_if_addrs.items():
                is_ts_iface = "tailscale" in iface_name.lower() or "utun" in iface_name.lower()
                for addr in addrs:
                    ip = addr.address.split("%")[0]  # Strip zone index for IPv6
                    if addr.family == socket.AF_INET:
                        if ip.startswith("127."):
                            continue
                        # Tailscale standard CGNAT subnet: 100.64.0.0/10 (100.64.x.x - 100.127.x.x)
                        if ip.startswith("100.") or is_ts_iface:
                            if ip not in endpoints["tailscale"]:
                                endpoints["tailscale"].append(ip)
                        else:
                            endpoints["lan"].append(ip)
                    elif hasattr(socket, "AF_INET6") and addr.family == socket.AF_INET6:
                        # Exclude loopback and link-local (fe80::/10) addresses
                        if ip.lower().startswith("fe80:") or ip in ("::1", "fe80::1"):
                            continue
                        # Tailscale IPv6 ULA prefix is strictly fd7a:115c:a1e0::/48
                        if ip.lower().startswith("fd7a:115c:a1e0:"):
                            if ip not in endpoints["tailscale"]:
                                endpoints["tailscale"].append(ip)
        except Exception:
            pass

        # Also probe Tailscale CLI if present
        try:
            import subprocess
            res4 = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=1.0)
            if res4.returncode == 0:
                for line in res4.stdout.strip().split():
                    line = line.strip().split("%")[0]
                    if line and line not in endpoints["tailscale"]:
                        endpoints["tailscale"].append(line)
            res6 = subprocess.run(["tailscale", "ip", "-6"], capture_output=True, text=True, timeout=1.0)
            if res6.returncode == 0:
                for line in res6.stdout.strip().split():
                    line = line.strip().split("%")[0]
                    if line and not line.lower().startswith("fe80:") and line not in endpoints["tailscale"]:
                        endpoints["tailscale"].append(line)
        except Exception:
            pass

        return endpoints

    @classmethod
    def is_tailscale_ip(cls, ip: str) -> bool:
        """Determines if an IP address belongs to the Tailscale overlay network."""
        if not ip:
            return False
        clean_ip = ip.strip().split("%")[0].lower()
        if clean_ip.startswith("fe80:"):
            return False
        # Tailscale IPv4: 100.64.0.0/10
        if clean_ip.startswith("100."):
            return True
        # Tailscale IPv6: fd7a:115c:a1e0::/48
        if clean_ip.startswith("fd7a:115c:a1e0:"):
            return True
        # Check against discovered tailscale endpoints
        known_ts = cls.discover_local_endpoints().get("tailscale", [])
        return clean_ip in [k.lower() for k in known_ts if not k.lower().startswith("fe80:")]
