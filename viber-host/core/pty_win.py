"""
RemoteViber Windows ConPTY Engine
Provides pseudo-terminal support for Windows 10/11 and Windows Server environments,
interfacing with Windows ConPTY API or portable pipe fallback.
"""

import os
import sys
import asyncio
import subprocess
import threading
from typing import Callable, Optional, Dict, List


class WindowsPtyProcess:
    """Manages an interactive process on Windows with pseudo-console or async pipe I/O."""

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
        self.rows = rows
        self.cols = cols
        self.on_data = on_data
        self.on_exit = on_exit

        self.process: Optional[subprocess.Popen] = None
        self._is_alive = False
        self.exit_code: Optional[int] = None
        self._reader_thread: Optional[threading.Thread] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._conpty = None

    def start(self, loop: Optional[asyncio.AbstractEventLoop] = None) -> int:
        self._loop = loop or asyncio.get_running_loop()

        # Check if pywinpty is available for native ConPTY
        has_pywinpty = False
        try:
            import winpty
            has_pywinpty = True
        except ImportError:
            pass

        if has_pywinpty:
            cmd_line = subprocess.list2cmdline(self.command)
            self._conpty = winpty.PTY(self.cols, self.rows)
            self._conpty.spawn(cmd_line, cwd=self.cwd, env=self.env)
            self._is_alive = True
            self._reader_thread = threading.Thread(target=self._read_conpty_loop, daemon=True)
            self._reader_thread.start()
            return self._conpty.pid
        else:
            # Fallback to standard subprocess pipe on Windows
            self.process = subprocess.Popen(
                self.command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                cwd=self.cwd,
                env=self.env,
                bufsize=0,
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0,
            )
            self._is_alive = True
            self._reader_thread = threading.Thread(target=self._read_pipe_loop, daemon=True)
            self._reader_thread.start()
            self._loop.create_task(self._wait_process())
            return self.process.pid

    def _read_conpty_loop(self) -> None:
        try:
            while self._is_alive and self._conpty and self._conpty.isalive():
                data = self._conpty.read(4096, blocking=True)
                if data and self.on_data and self._loop:
                    b_data = data.encode("utf-8", errors="replace") if isinstance(data, str) else data
                    self._loop.call_soon_threadsafe(self.on_data, b_data)
        except Exception:
            pass
        finally:
            self._is_alive = False
            code = self._conpty.get_exitstatus() if self._conpty else 0
            if self.on_exit and self._loop:
                self._loop.call_soon_threadsafe(self.on_exit, code)

    def _read_pipe_loop(self) -> None:
        try:
            while self._is_alive and self.process and self.process.stdout:
                data = self.process.stdout.read(4096)
                if not data:
                    break
                if self.on_data and self._loop:
                    self._loop.call_soon_threadsafe(self.on_data, data)
        except Exception:
            pass
        finally:
            self._is_alive = False

    async def _wait_process(self) -> None:
        if not self.process:
            return
        while self.process.poll() is None:
            await asyncio.sleep(0.1)
        self.exit_code = self.process.returncode
        self._is_alive = False
        if self.on_exit:
            self.on_exit(self.exit_code)

    def write(self, data: bytes) -> None:
        if not self._is_alive:
            return
        if self._conpty:
            try:
                text = data.decode("utf-8", errors="replace")
                self._conpty.write(text)
            except Exception:
                pass
        elif self.process and self.process.stdin:
            try:
                self.process.stdin.write(data)
                self.process.stdin.flush()
            except Exception:
                pass

    def resize(self, rows: int, cols: int) -> None:
        self.rows = max(1, rows)
        self.cols = max(1, cols)
        if self._conpty:
            try:
                self._conpty.set_size(self.cols, self.rows)
            except Exception:
                pass

    def terminate(self, sig: int = 15) -> None:
        self._is_alive = False
        if self._conpty:
            try:
                self._conpty.close()
            except Exception:
                pass
        elif self.process:
            try:
                self.process.terminate()
            except Exception:
                pass

    @property
    def is_alive(self) -> bool:
        if self._conpty:
            return self._is_alive and self._conpty.isalive()
        return self._is_alive and (self.process is not None and self.process.poll() is None)

    @property
    def pid(self) -> Optional[int]:
        if self._conpty:
            return self._conpty.pid
        return self.process.pid if self.process else None
