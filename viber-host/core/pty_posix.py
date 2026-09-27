"""
RemoteViber POSIX PTY Engine (Linux / macOS)
Provides robust pseudo-terminal allocation, window resizing, non-blocking I/O,
and graceful child process lifecycle management.
"""

import os
import pty
import tty
import termios
import struct
import fcntl
import signal
import asyncio
import subprocess
from typing import Callable, Optional, Dict, List


class PosixPtyProcess:
    """Manages an interactive process spawned inside a POSIX pseudo-terminal."""

    def __init__(
        self,
        command: List[str],
        cwd: str,
        env: Optional[Dict[str, str]] = None,
        rows: int = 24,
        cols: int = 80,
        on_data: Optional[Callable[[bytes], None]] = None,
        on_exit: Optional[Callable[[int], None]] = None,
    ):
        self.command = command
        self.cwd = cwd
        self.env = env or os.environ.copy()
        # Ensure standard terminal environment
        self.env.setdefault("TERM", "xterm-256color")
        self.env.setdefault("COLORTERM", "truecolor")
        self.env.setdefault("LANG", "en_US.UTF-8")

        self.rows = rows
        self.cols = cols
        self.on_data = on_data
        self.on_exit = on_exit

        self.master_fd: Optional[int] = None
        self.slave_fd: Optional[int] = None
        self.process: Optional[subprocess.Popen] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._is_alive = False
        self.exit_code: Optional[int] = None

    def start(self, loop: Optional[asyncio.AbstractEventLoop] = None) -> int:
        """Allocates PTY, spawns child process, and registers non-blocking async reader."""
        self._loop = loop or asyncio.get_running_loop()

        # Open pseudo-terminal master and slave
        self.master_fd, self.slave_fd = pty.openpty()

        # Set initial terminal size
        self.resize(self.rows, self.cols)

        # Set master to non-blocking
        flags = fcntl.fcntl(self.master_fd, fcntl.F_GETFL)
        fcntl.fcntl(self.master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

        try:
            # Spawn process with slave PTY as stdin, stdout, and stderr
            # setsid and TIOCSCTTY ensure the child becomes session leader with a controlling terminal
            def _preexec():
                os.setsid()
                try:
                    fcntl.ioctl(0, termios.TIOCSCTTY, 0)
                except Exception:
                    pass

            self.process = subprocess.Popen(
                self.command,
                stdin=self.slave_fd,
                stdout=self.slave_fd,
                stderr=self.slave_fd,
                cwd=self.cwd,
                env=self.env,
                preexec_fn=_preexec,
                close_fds=True,
            )
            self._is_alive = True
        finally:
            # Always close slave_fd in parent process so EOF can trigger on child termination
            if self.slave_fd is not None:
                os.close(self.slave_fd)
                self.slave_fd = None

        # Register non-blocking reader on master_fd
        self._loop.add_reader(self.master_fd, self._handle_read)
        # Background task to monitor process exit
        self._loop.create_task(self._wait_process())

        return self.process.pid

    def _handle_read(self) -> None:
        """Callback invoked by event loop when data is available on master_fd."""
        if self.master_fd is None:
            return
        try:
            data = os.read(self.master_fd, 8192)
            if data and self.on_data:
                self.on_data(data)
        except (BlockingIOError, InterruptedError):
            pass
        except OSError:
            # EIO is expected on Linux when the child closes the slave end
            self._cleanup_reader()

    def _cleanup_reader(self) -> None:
        if self.master_fd is not None and self._loop:
            try:
                self._loop.remove_reader(self.master_fd)
            except Exception:
                pass

    async def _wait_process(self) -> None:
        """Awaits process termination asynchronously without blocking event loop."""
        if not self.process:
            return
        while self.process.poll() is None:
            await asyncio.sleep(0.1)

        self.exit_code = self.process.returncode
        self._is_alive = False
        self._cleanup_reader()

        # Read any remaining output buffered in master_fd
        if self.master_fd is not None:
            try:
                while True:
                    data = os.read(self.master_fd, 8192)
                    if not data:
                        break
                    if self.on_data:
                        self.on_data(data)
            except Exception:
                pass
            try:
                os.close(self.master_fd)
            except Exception:
                pass
            self.master_fd = None

        if self.on_exit:
            self.on_exit(self.exit_code)

    def write(self, data: bytes) -> None:
        """Writes input bytes directly into master PTY stdin."""
        if self.master_fd is not None and self._is_alive:
            try:
                os.write(self.master_fd, data)
            except Exception:
                pass

    def resize(self, rows: int, cols: int) -> None:
        """Updates PTY terminal dimensions and issues SIGWINCH to child process."""
        self.rows = max(1, rows)
        self.cols = max(1, cols)
        target_fd = self.slave_fd if self.slave_fd is not None else self.master_fd
        if target_fd is not None:
            try:
                winsize = struct.pack("HHHH", self.rows, self.cols, 0, 0)
                fcntl.ioctl(target_fd, termios.TIOCSWINSZ, winsize)
            except Exception:
                pass

    def terminate(self, sig: int = signal.SIGTERM) -> None:
        """Sends signal to child process group, escalating to SIGKILL if necessary."""
        if self.process:
            pid = self.process.pid
            try:
                os.killpg(os.getpgid(pid), sig)
            except Exception:
                try:
                    self.process.send_signal(sig)
                except Exception:
                    pass

            if sig != signal.SIGKILL:
                def kill_if_alive():
                    if self.process and self.process.poll() is None:
                        try:
                            os.killpg(os.getpgid(pid), signal.SIGKILL)
                        except Exception:
                            try:
                                self.process.kill()
                            except Exception:
                                pass
                if self._loop and self._loop.is_running():
                    self._loop.call_later(0.3, kill_if_alive)

    @property
    def is_alive(self) -> bool:
        return self._is_alive and (self.process is not None and self.process.poll() is None)

    @property
    def pid(self) -> Optional[int]:
        return self.process.pid if self.process else None
