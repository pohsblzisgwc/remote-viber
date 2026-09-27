"""
RemoteViber Server Ephemeral Host Registry
Tracks registered agent hosts and active relay sessions with zero persistence for maximum confidentiality.
"""

import time
from typing import Dict, Any, Optional


class RegisteredHost:
    def __init__(
        self,
        host_id: str,
        host_name: str,
        direct_port: int,
        pub_key: str,
        endpoints: Dict[str, list],
        connection: Any,
    ):
        self.host_id = host_id
        self.host_name = host_name
        self.direct_port = direct_port
        self.pub_key = pub_key
        self.endpoints = endpoints
        self.connection = connection
        self.registered_at = time.time()
        self.last_ping = time.time()

    def to_public_dict(self) -> Dict[str, Any]:
        """Returns metadata for peer discovery and direct connection candidates."""
        return {
            "host_id": self.host_id,
            "host_name": self.host_name,
            "direct_port": self.direct_port,
            "pub_key": self.pub_key,
            "endpoints": self.endpoints,
            "online": True,
            "registered_at": self.registered_at,
        }


class HostRegistry:
    """Manages active host connections in-memory."""

    def __init__(self):
        self.hosts: Dict[str, RegisteredHost] = {}
        # Map websocket connection to host_id for fast disconnect cleanup
        self.conn_to_host: Dict[Any, str] = {}

    def register(
        self,
        host_id: str,
        host_name: str,
        direct_port: int,
        pub_key: str,
        endpoints: Dict[str, list],
        connection: Any,
    ) -> RegisteredHost:
        host = RegisteredHost(
            host_id=host_id,
            host_name=host_name,
            direct_port=direct_port,
            pub_key=pub_key,
            endpoints=endpoints,
            connection=connection,
        )
        self.hosts[host_id] = host
        self.conn_to_host[connection] = host_id
        return host

    def unregister_connection(self, connection: Any) -> Optional[str]:
        host_id = self.conn_to_host.pop(connection, None)
        if host_id and host_id in self.hosts:
            del self.hosts[host_id]
            return host_id
        return None

    def get_host(self, host_id: str) -> Optional[RegisteredHost]:
        return self.hosts.get(host_id)

    def list_hosts(self) -> list:
        return [h.to_public_dict() for h in self.hosts.values()]
