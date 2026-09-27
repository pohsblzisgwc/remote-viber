"""
RemoteViber Host Configuration & Pairing Generator
"""

import os
import sys
import json
import base64
import socket
import urllib.parse
from typing import Dict, Any, Optional

from core.crypto import HostKeyManager


def get_persistent_dir() -> str:
    """
    Returns the persistent storage directory parallel to the executable binary.
    If running under PyInstaller (sys.frozen is True), returns the folder containing the binary.
    If running from source, returns the folder containing the entry script or current working directory.
    If that directory is not writable (e.g. read-only container layer), falls back to ~/.viber.
    """
    if getattr(sys, "frozen", False):
        base_dir = os.path.dirname(os.path.abspath(sys.executable))
    else:
        base_dir = os.path.dirname(os.path.abspath(sys.argv[0]))
        if not os.path.isdir(base_dir) or base_dir == "":
            base_dir = os.getcwd()

    try:
        test_file = os.path.join(base_dir, ".viber_test_write")
        with open(test_file, "w") as f:
            f.write("1")
        if os.path.exists(test_file):
            os.remove(test_file)
        return base_dir
    except Exception:
        fallback = os.path.expanduser("~/.viber")
        os.makedirs(fallback, exist_ok=True)
        return fallback


class HostConfig:
    """Manages persistent Host identity, cryptographic keys, and pairing tokens."""

    def __init__(self, config_dir: Optional[str] = None):
        self.config_dir = config_dir or get_persistent_dir()
        self.config_file = os.path.join(self.config_dir, "viber_config.json")

        self.host_id: str = f"host-{socket.gethostname().lower()}"
        self.host_name: str = f"{socket.gethostname()} Agent Host"
        self.direct_port: int = 8765
        self.direct_bind: Optional[str] = None
        self.allow_lan: bool = False
        self.relay_url: Optional[str] = None
        self.pairing_secret: str = ""
        self.private_key_b64: str = ""

        self.key_manager: Optional[HostKeyManager] = None
        self._load_or_create()

    def _load_or_create(self) -> None:
        os.makedirs(self.config_dir, exist_ok=True)
        
        # Support migration from legacy host_config.json or ~/.viber
        load_path = self.config_file
        if not os.path.exists(load_path):
            alt1 = os.path.join(self.config_dir, "host_config.json")
            alt2 = os.path.expanduser("~/.viber/host_config.json")
            if os.path.exists(alt1):
                load_path = alt1
            elif os.path.exists(alt2):
                load_path = alt2

        if os.path.exists(load_path):
            try:
                with open(load_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.host_id = data.get("host_id", self.host_id)
                    self.host_name = data.get("host_name", self.host_name)
                    self.direct_port = data.get("direct_port", self.direct_port)
                    self.allow_lan = data.get("allow_lan", False)
                    bind_val = data.get("direct_bind")
                    # Security: Disallow legacy 0.0.0.0 unless allow_lan is explicitly True
                    if bind_val and bind_val != "0.0.0.0":
                        self.direct_bind = bind_val
                    elif self.allow_lan:
                        self.direct_bind = "0.0.0.0"
                    else:
                        self.direct_bind = None
                    self.relay_url = data.get("relay_url", self.relay_url)
                    self.pairing_secret = data.get("pairing_secret", "")
                    self.private_key_b64 = data.get("private_key_b64", "")
            except Exception:
                pass

        if not self.pairing_secret:
            self.pairing_secret = base64.b32encode(os.urandom(10)).decode("ascii").rstrip("=")

        priv_bytes = base64.b64decode(self.private_key_b64) if self.private_key_b64 else None
        self.key_manager = HostKeyManager(priv_bytes, self.pairing_secret)

        if not self.private_key_b64:
            # Save newly generated private key
            raw_priv = self.key_manager._private_key.private_numbers().private_value.to_bytes(32, "big")
            self.private_key_b64 = base64.b64encode(raw_priv).decode("ascii")
            self.save()

    def save(self) -> None:
        data = {
            "host_id": self.host_id,
            "host_name": self.host_name,
            "direct_port": self.direct_port,
            "direct_bind": self.direct_bind,
            "allow_lan": self.allow_lan,
            "relay_url": self.relay_url,
            "pairing_secret": self.pairing_secret,
            "private_key_b64": self.private_key_b64,
        }
        with open(self.config_file, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)

    def generate_pairing_payload(self, endpoints: Dict[str, list]) -> Dict[str, Any]:
        """Creates the pairing metadata bundle for QR code or 1-click import."""
        return {
            "v": 1,
            "id": self.host_id,
            "name": self.host_name,
            "port": self.direct_port,
            "pub": self.key_manager.public_key_b64,
            "token": self.pairing_secret,
            "relay": self.relay_url or "",
            "tailscale": endpoints.get("tailscale", []),
            "lan": endpoints.get("lan", []) if self.allow_lan else [],
        }

    def generate_pairing_url(self, endpoints: Dict[str, list]) -> str:
        payload = self.generate_pairing_payload(endpoints)
        encoded = base64.urlsafe_b64encode(json.dumps(payload).encode("utf-8")).decode("ascii").rstrip("=")
        return f"viber://connect?data={encoded}"
