"""Bounded live registry. Identity verification happens before register()."""
import time
from secure_protocol import ProtocolError, host_id_for


class RegisteredHost:
    def __init__(self, host_id, host_name, direct_port, pub_key, endpoints, connection):
        self.host_id, self.host_name = host_id, host_name
        self.direct_port, self.pub_key = direct_port, pub_key
        self.endpoints, self.connection = endpoints, connection
        self.registered_at = self.last_ping = time.time()

    def to_public_dict(self):
        # Deliberately omit private endpoints; no unauthenticated discovery API.
        return {"host_id": self.host_id, "online": True}


class HostRegistry:
    def __init__(self, max_hosts=128):
        self.hosts = {}
        self.conn_to_host = {}
        self.max_hosts = max_hosts

    def register(self, host_id, host_name, direct_port, pub_key, endpoints, connection):
        if host_id != host_id_for(pub_key):
            raise ProtocolError("Host ID mismatch")
        if host_id in self.hosts or connection in self.conn_to_host:
            raise ProtocolError("Duplicate registration")
        if len(self.hosts) >= self.max_hosts:
            raise ProtocolError("Registry full")
        host = RegisteredHost(host_id, str(host_name)[:128], direct_port, pub_key, {}, connection)
        self.hosts[host_id] = host
        self.conn_to_host[connection] = host_id
        return host

    def unregister_connection(self, connection):
        host_id = self.conn_to_host.pop(connection, None)
        host = self.hosts.get(host_id)
        if host is not None and host.connection is connection:
            del self.hosts[host_id]
            return host_id
        return None

    def get_host(self, host_id):
        return self.hosts.get(host_id)

    def list_hosts(self):
        return [host.to_public_dict() for host in self.hosts.values()]
