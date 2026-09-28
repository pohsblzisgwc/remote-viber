"""Cached metrics. IP classification is display/discovery, NEVER authorization."""
import ipaddress
import socket
import time
import psutil


class SystemMonitor:
    def __init__(self, cache_ttl_seconds=2.0):
        self.cache_ttl = cache_ttl_seconds
        self._last_sample_time = 0.0
        self._cached_metrics = {}
        psutil.cpu_percent(interval=None)

    def get_system_stats(self):
        now = time.monotonic()
        if self._cached_metrics and now - self._last_sample_time < self.cache_ttl:
            return self._cached_metrics
        mem = psutil.virtual_memory()
        self._cached_metrics = {
            "cpu_percent": round(psutil.cpu_percent(interval=None), 1),
            "memory_percent": round(mem.percent, 1),
            "memory_used_mb": int(mem.used / (1024 * 1024)),
            "memory_total_mb": int(mem.total / (1024 * 1024)),
            "hostname": socket.gethostname(), "endpoints": self.discover_local_endpoints(),
            "timestamp": time.time(),
        }
        self._last_sample_time = now
        return self._cached_metrics

    @staticmethod
    def is_tailscale_ip(ip):
        try:
            addr = ipaddress.ip_address(ip.split("%")[0])
            return addr in ipaddress.ip_network("100.64.0.0/10") if addr.version == 4 else addr in ipaddress.ip_network("fd7a:115c:a1e0::/48")
        except (ValueError, AttributeError):
            return False

    @staticmethod
    def discover_local_endpoints():
        endpoints = {"tailscale": [], "lan": [], "loopback": ["127.0.0.1", "::1"]}
        # Do not spawn subprocesses on request paths. On platforms with generic
        # VPN interface names, explicitly configure --bind / direct_url instead.
        for name, addresses in psutil.net_if_addrs().items():
            for entry in addresses:
                if entry.family not in (socket.AF_INET, socket.AF_INET6):
                    continue
                ip = entry.address.split("%")[0]
                try:
                    addr = ipaddress.ip_address(ip)
                except ValueError:
                    continue
                if addr.is_loopback or addr.is_link_local or addr.is_unspecified:
                    continue
                is_ts = name.lower().startswith("tailscale") and SystemMonitor.is_tailscale_ip(ip)
                group = "tailscale" if is_ts else "lan"
                if ip not in endpoints[group]:
                    endpoints[group].append(ip)
        return endpoints
