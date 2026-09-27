"""
Cross-platform PTY Factory for RemoteViber
Seamlessly selects POSIX PTY on Linux/macOS or ConPTY on Windows.
"""

import sys
from typing import Callable, Optional, Dict, List

if sys.platform == "win32":
    from core.pty_win import WindowsPtyProcess as PtyProcess
else:
    from core.pty_posix import PosixPtyProcess as PtyProcess


def create_pty_process(
    command: List[str],
    cwd: str,
    env: Optional[Dict[str, str]] = None,
    rows: int = 24,
    cols: int = 80,
    on_data: Optional[Callable[[bytes], None]] = None,
    on_exit: Optional[Callable[[int], None]] = None,
) -> PtyProcess:
    """Instantiates a platform-native pseudo-terminal process."""
    return PtyProcess(
        command=command,
        cwd=cwd,
        env=env,
        rows=rows,
        cols=cols,
        on_data=on_data,
        on_exit=on_exit,
    )
