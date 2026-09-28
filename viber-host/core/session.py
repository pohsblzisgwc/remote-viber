"""
RemoteViber Session Manager & Persistent Ring Buffer
Ensures continuous agent execution detached from client network state,
providing monotonic sequence tracking and instant reconnection replay.
"""

import os
import time
import uuid
import re
import collections
from typing import Callable, Optional, Dict, List, Set, Tuple, Any

from core.pty_factory import create_pty_process, PtyProcess


class TerminalRingBuffer:
    """Bounded thread-safe replay window; network replay counters are separate."""
    def __init__(self, max_bytes: int = 16 * 1024 * 1024):
        import threading
        if max_bytes <= 0:
            raise ValueError("Positive replay budget required")
        self.max_bytes = max_bytes
        self.current_bytes = 0
        self.chunks = collections.deque()
        self.current_seq = 0
        self.min_seq = 0
        self._lock = threading.RLock()

    def append(self, data: bytes) -> int:
        if len(data) > self.max_bytes:
            raise ValueError("Replay chunk exceeds buffer capacity")
        with self._lock:
            self.current_seq += 1
            self.chunks.append((self.current_seq, time.time(), bytes(data)))
            self.current_bytes += len(data)
            while self.current_bytes > self.max_bytes:
                self.current_bytes -= len(self.chunks.popleft()[2])
            self.min_seq = self.chunks[0][0] if self.chunks else self.current_seq
            return self.current_seq

    def get_since(self, last_seq: int):
        reset, chunks, _ = self.snapshot_since(last_seq)
        return reset, chunks

    def snapshot_since(self, last_seq: int):
        with self._lock:
            if not self.chunks:
                return False, [], self.current_seq
            reset = last_seq <= 0 or last_seq < self.min_seq - 1 or last_seq > self.current_seq
            return reset, [(seq, data) for seq, _, data in self.chunks if reset or seq > last_seq], self.current_seq

class AgentSession:
    """
    Supervises a long-running Agent CLI process.
    Detached from client sockets so network dropouts will never interrupt execution.
    """

    WAITING_PATTERNS = [
        re.compile(rb"\[y/n\]", re.IGNORECASE),
        re.compile(rb"\(y/n\)", re.IGNORECASE),
        re.compile(rb"press (enter|return|any key)", re.IGNORECASE),
        re.compile(rb"continue\? ", re.IGNORECASE),
        re.compile(rb"\?\s+[A-Z]", re.IGNORECASE),
        re.compile(rb"anthropic.*prompt.*>", re.IGNORECASE),
    ]

    def __init__(
        self,
        session_id: str,
        name: str,
        command: List[str],
        cwd: str,
        env: Optional[Dict[str, str]] = None,
        profile_id: Optional[str] = None,
        folder: Optional[str] = None,
        session_type: str = "agent",
        rows: int = 24,
        cols: int = 80,
        raw_command: Optional[str] = None,
    ):
        self.session_id = session_id
        self.name = name
        self.command = command
        self.raw_command = raw_command or (" ".join(command) if isinstance(command, list) else str(command))
        self.cwd = cwd
        self.env = env
        self.profile_id = profile_id
        self.folder = folder or (os.path.basename(os.path.abspath(cwd)) if cwd else "默认项目")
        self.session_type = session_type  # "agent" or "terminal"
        self.rows = rows
        self.cols = cols

        self.created_at = time.time()
        self.last_active_at = time.time()
        self.status = "starting"  # starting, running, waiting_input, idle, stopped
        self.exit_code: Optional[int] = None

        self.buffer = TerminalRingBuffer()
        self.subscribers: Set[Callable[[str, int, bytes], None]] = set()

        self.pty: Optional[PtyProcess] = None
        self.total_bytes_out = 0
        self.total_bytes_in = 0

        # Terminal transcripts can contain credentials. No automatic disk logging.
        self._log_file = None
        self.log_file_path = None

    def start(self) -> int:
        """Launches the agent in its own isolated PTY."""
        self.pty = create_pty_process(
            command=self.command,
            cwd=self.cwd,
            env=self.env,
            rows=self.rows,
            cols=self.cols,
            on_data=self._on_pty_data,
            on_exit=self._on_pty_exit,
        )
        pid = self.pty.start()
        self.status = "running"
        return pid

    def _on_pty_data(self, data: bytes) -> None:
        """Invoked when agent outputs data."""
        # Bound each replay/output frame. Existing transcript files are not erased.
        if len(data) > 65536:
            for offset in range(0, len(data), 65536):
                self._on_pty_data(data[offset:offset + 65536])
            return
        self.total_bytes_out += len(data)
        self.last_active_at = time.time()
        seq = self.buffer.append(data)

        if self._log_file:
            try:
                self._log_file.write(data)
            except Exception:
                pass

        # Check heuristics for waiting input
        is_waiting = False
        tail = data[-256:]
        for pat in self.WAITING_PATTERNS:
            if pat.search(tail):
                is_waiting = True
                break

        if is_waiting:
            self.status = "waiting_input"
        elif self.status != "stopped":
            self.status = "running"

        # Broadcast to all currently connected subscribers
        for subscriber in list(self.subscribers):
            try:
                subscriber(self.session_id, seq, data)
            except Exception:
                pass

    def _on_pty_exit(self, exit_code: int) -> None:
        """Invoked when agent process terminates."""
        self.exit_code = exit_code
        self.status = "stopped"
        if self._log_file:
            try:
                self._log_file.close()
            except Exception:
                pass
            self._log_file = None

        # Notify subscribers of termination with terminal notification
        msg = f"\r\n\x1b[1;33m[Viber] Agent process exited with code {exit_code}.\x1b[0m\r\n".encode("utf-8")
        seq = self.buffer.append(msg)
        for subscriber in list(self.subscribers):
            try:
                subscriber(self.session_id, seq, msg)
            except Exception:
                pass

    def write_input(self, data: bytes) -> None:
        """Sends user keystrokes/prompts into the running agent."""
        if self.pty and self.pty.is_alive:
            self.total_bytes_in += len(data)
            self.last_active_at = time.time()
            self.pty.write(data)
            if self.status == "waiting_input":
                self.status = "running"

    def resize(self, rows: int, cols: int) -> None:
        """Resizes the terminal viewport."""
        self.rows = rows
        self.cols = cols
        if self.pty:
            self.pty.resize(rows, cols)

    def subscribe(self, callback: Callable[[str, int, bytes], None]) -> None:
        """Registers a live subscriber for real-time terminal output."""
        self.subscribers.add(callback)

    def unsubscribe(self, callback: Callable[[str, int, bytes], None]) -> None:
        """Unregisters subscriber without interrupting the agent process."""
        self.subscribers.discard(callback)

    def terminate(self, sig: int = 15) -> None:
        """Stops the agent process."""
        if self._log_file:
            try:
                self._log_file.close()
            except Exception:
                pass
            self._log_file = None
        if self.pty:
            self.pty.terminate(sig)
            self.status = "stopped"

    def to_dict(self) -> Dict[str, Any]:
        """Serializes session summary for the client GUI."""
        # Check idle timeout (quiet for > 10 seconds while running)
        if self.status == "running" and (time.time() - self.last_active_at > 10.0):
            status = "idle"
        else:
            status = self.status

        return {
            "session_id": self.session_id,
            "name": self.name,
            "profile_id": self.profile_id,
            "folder": self.folder,
            "session_type": self.session_type,
            "command": self.raw_command,
            "cwd": self.cwd,
            "pid": self.pty.pid if self.pty else None,
            "status": status,
            "uptime_seconds": int(time.time() - self.created_at),
            "created_at": self.created_at,
            "subscribers_count": len(self.subscribers),
            "total_bytes_out": self.total_bytes_out,
            "total_bytes_in": self.total_bytes_in,
            "exit_code": self.exit_code,
            "current_seq": self.buffer.current_seq,
            "log_file": self.log_file_path,
        }
