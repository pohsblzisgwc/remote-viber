"""Private, atomic configuration storage and explicit out-of-band pairing."""
import base64
import json
import os
from pathlib import Path
import socket
import stat
import sys
import tempfile
from typing import Optional, Union, Any
from core.crypto import HostKeyManager, host_id_for, validate_token


def is_safe_config_dir(d: Path) -> bool:
    try:
        if not d.is_dir() or d.is_symlink():
            return False
        if not os.access(d, os.W_OK):
            return False
        if os.name == "posix":
            st = d.stat()
            if os.getuid() != 0 and st.st_uid != os.getuid():
                return False
            cfg = d / "viber_config.json"
            if cfg.exists():
                cst = cfg.stat()
                if os.getuid() != 0 and cst.st_uid != os.getuid():
                    return False
        return True
    except (OSError, PermissionError):
        return False


def get_persistent_dir() -> str:
    # Preserve the source/binary location convention, but never probe it by
    # opening a predictable temporary filename (which could be a symlink).
    override = os.environ.get("VIBER_CONFIG_DIR")
    if override:
        return str(Path(override).expanduser().absolute())
    base = Path(sys.executable if getattr(sys, "frozen", False) else sys.argv[0]).absolute().parent
    if is_safe_config_dir(base):
        return str(base)
    return str(Path.home() / ".viber")


def _check_directory(directory: Path) -> None:
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Configuration directory must be a real directory")
    if os.name == "posix":
        st = directory.stat()
        if os.getuid() != 0 and st.st_uid != os.getuid():
            raise PermissionError("Configuration directory must be owned by this user and not group/world writable")
        if st.st_mode & 0o022:
            try:
                os.chmod(directory, st.st_mode & ~0o022)
                st = directory.stat()
            except OSError:
                pass
            if st.st_mode & 0o002:
                raise PermissionError("Configuration directory must be owned by this user and not group/world writable")


def read_private_json(path: Path) -> dict:
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    if path.is_symlink():
        raise PermissionError("Refusing symlink configuration")
    fd = os.open(path, flags)
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_nlink != 1 or st.st_size > 512 * 1024:
            raise PermissionError("Unsafe configuration file")
        if os.name == "posix":
            if os.getuid() != 0 and st.st_uid != os.getuid():
                raise PermissionError("Configuration has a different owner")
            try:
                os.fchmod(fd, 0o600)
            except OSError:
                pass
        with os.fdopen(fd, "r", encoding="utf-8") as f:
            fd = -1
            value = json.load(f)
        if not isinstance(value, (dict, list)):
            raise ValueError("Configuration must be an object or list")
        return value
    finally:
        if fd >= 0:
            os.close(fd)


def write_private_json(path: Path, data: Any) -> None:
    _check_directory(path.parent)
    if path.is_symlink():
        raise PermissionError("Refusing symlink configuration")
    serialized = json.dumps(data, ensure_ascii=False, indent=2)
    serialized_bytes = serialized.encode("utf-8")
    if len(serialized_bytes) > 512 * 1024:
        raise ValueError(f"Configuration data exceeds maximum allowed size ({len(serialized_bytes)} > {512 * 1024})")
    fd, temp = tempfile.mkstemp(prefix=".viber-config-", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            if os.name == "posix":
                try:
                    os.fchmod(f.fileno(), 0o600)
                except OSError:
                    pass
            f.write(serialized)
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp, path)
        if os.name == "posix":
            dfd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
            try:
                os.fsync(dfd)
            finally:
                os.close(dfd)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


class HostConfig:
    def __init__(self, config_dir: Optional[str] = None):
        self.config_dir = str(Path(config_dir or get_persistent_dir()).expanduser().absolute())
        directory = Path(self.config_dir)
        _check_directory(directory)
        self.config_file = str(directory / "viber_config.json")
        path = Path(self.config_file)
        # Explicitly migrate a legacy file only in the selected directory.
        legacy = directory / "host_config.json"
        load_path = path if path.exists() or path.is_symlink() else legacy
        data = read_private_json(load_path) if load_path.exists() or load_path.is_symlink() else {}
        self.host_name = str(data.get("host_name", f"{socket.gethostname()} Agent Host"))[:128]
        self.direct_port = int(data.get("direct_port", 8765))
        if not 0 <= self.direct_port <= 65535:
            raise ValueError("Invalid port")
        self.direct_bind = data.get("direct_bind")
        self.allow_lan = data.get("allow_lan", False) is True
        self.relay_url = data.get("relay_url") or None
        self.direct_url = data.get("direct_url", "")
        self.allowed_origins = data.get("allowed_origins", [])
        if not isinstance(self.allowed_origins, list) or not all(isinstance(x, str) for x in self.allowed_origins):
            raise ValueError("allowed_origins must be a list of exact origins")
        self.pairing_secret = data.get("pairing_secret", "")
        self.private_key_b64 = data.get("private_key_b64", "")
        # v1 credentials may already have been disclosed. Rotate both identity
        # and token ONCE on migration instead of carrying a compromised secret.
        self.migrated = data.get("protocol_version") != 2
        if self.migrated:
            self.rotate_credentials(save=False)
        else:
            validate_token(self.pairing_secret)
            private = base64.b64decode(self.private_key_b64, validate=True)
            self.key_manager = HostKeyManager(private, self.pairing_secret)
            self.host_id = host_id_for(self.key_manager.public_key_b64)
        self.save()

    def rotate_credentials(self, save: bool = True):
        self.key_manager = HostKeyManager()
        self.pairing_secret = self.key_manager.pairing_secret
        private = self.key_manager._private_key.private_numbers().private_value.to_bytes(32, "big")
        self.private_key_b64 = base64.b64encode(private).decode("ascii")
        self.host_id = host_id_for(self.key_manager.public_key_b64)
        if save:
            self.save()

    def save(self):
        data = {"protocol_version": 2, "host_id": self.host_id, "host_name": self.host_name,
                "direct_port": self.direct_port, "direct_bind": self.direct_bind,
                "allow_lan": self.allow_lan, "relay_url": self.relay_url,
                "direct_url": self.direct_url, "allowed_origins": self.allowed_origins,
                "pairing_secret": self.pairing_secret, "private_key_b64": self.private_key_b64}
        write_private_json(Path(self.config_file), data)

    def generate_pairing_payload(self, endpoints):
        # Sensitive! Only the explicit local --pair-info command may expose this.
        return {"v": 2, "id": self.host_id, "name": self.host_name, "port": self.direct_port,
                "pub": self.key_manager.public_key_b64, "token": self.pairing_secret,
                "relay": self.relay_url or "", "direct_url": self.direct_url,
                "tailscale": endpoints.get("tailscale", []),
                "lan": endpoints.get("lan", []) if self.allow_lan else ["127.0.0.1"]}

    def generate_pairing_code(self, endpoints):
        raw = json.dumps(self.generate_pairing_payload(endpoints)).encode("utf-8")
        return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")

    def generate_pairing_url(self, endpoints):
        return "viber://connect?data=" + self.generate_pairing_code(endpoints)


class HostLock:
    """Advisory process lock to coordinate Host daemon executions and prevent

    desynchronized offline credential rotations while an active Host is running.
    Uses kernel file locking (fcntl.flock on POSIX, msvcrt.locking on Windows)
    so abnormal termination automatically cleans up without leaving stale lockfiles.
    """

    def __init__(self, directory: Union[str, Path]):
        self.lock_path = Path(directory) / ".viber_host.lock"
        self._fd: Optional[int] = None

    def acquire(self, non_blocking: bool = True) -> bool:
        """Attempts to acquire the exclusive lock. Returns True if acquired, False otherwise."""
        _check_directory(self.lock_path.parent)
        flags = os.O_CREAT | os.O_RDWR
        try:
            fd = os.open(str(self.lock_path), flags, 0o600)
        except OSError:
            return False

        try:
            if os.name == "posix":
                import fcntl
                lock_mode = fcntl.LOCK_EX
                if non_blocking:
                    lock_mode |= fcntl.LOCK_NB
                fcntl.flock(fd, lock_mode)
            elif os.name == "nt":
                import msvcrt
                mode = msvcrt.LK_NBLCK if non_blocking else msvcrt.LK_LOCK
                msvcrt.locking(fd, mode, 1)
            self._fd = fd
            try:
                os.ftruncate(fd, 0)
                os.lseek(fd, 0, os.SEEK_SET)
                os.write(fd, f"{os.getpid()}\n".encode("ascii"))
            except OSError:
                pass
            return True
        except (BlockingIOError, OSError):
            os.close(fd)
            return False

    def release(self):
        """Releases the lock."""
        if self._fd is not None:
            try:
                if os.name == "posix":
                    import fcntl
                    fcntl.flock(self._fd, fcntl.LOCK_UN)
                elif os.name == "nt":
                    import msvcrt
                    msvcrt.locking(self._fd, msvcrt.LK_UNLCK, 1)
            except OSError:
                pass
            try:
                os.close(self._fd)
            except OSError:
                pass
            self._fd = None

    def is_locked(self_or_dir: Union[str, Path, "HostLock"]) -> bool:
        """Checks if another process currently holds the lock."""
        if isinstance(self_or_dir, HostLock):
            inst = self_or_dir
        else:
            inst = HostLock(self_or_dir)
        if inst.acquire(non_blocking=True):
            inst.release()
            return False
        return True

    def __enter__(self):
        if not self.acquire(non_blocking=True):
            raise BlockingIOError("Host lock is held by another process")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.release()

