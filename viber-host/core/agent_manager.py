"""
RemoteViber Agent Profile & Multi-Session Manager
Handles predefined and custom Agent CLI profiles, one-click launches,
and session lifecycle supervision.
"""

import os
import sys
import json
import uuid
from typing import Dict, List, Optional, Any, Union

from core.session import AgentSession
from core.config import get_persistent_dir


DEFAULT_PROFILES: List[Dict[str, Any]] = []


class AgentManager:
    """Manages Agent CLI profiles and active running sessions."""

    def __init__(self, config_path: Optional[str] = None):
        self.config_path = config_path or os.path.join(get_persistent_dir(), "viber_profiles.json")
        self.profiles: Dict[str, Dict[str, Any]] = {}
        self.sessions: Dict[str, AgentSession] = {}
        self._load_profiles()

    def _load_profiles(self) -> None:
        """Loads user presets from disk or starts with clean slate."""
        load_path = self.config_path
        if not os.path.exists(load_path):
            alt1 = os.path.join(os.path.dirname(self.config_path), "profiles.json")
            alt2 = os.path.expanduser("~/.viber/profiles.json")
            if os.path.exists(alt1):
                load_path = alt1
            elif os.path.exists(alt2):
                load_path = alt2

        if os.path.exists(load_path):
            try:
                with open(load_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    for p in data:
                        # Exclude old hardcoded default templates per user request
                        if p.get("id") in {"claude-code", "aider-architect", "antigravity-cli", "quick-shell"}:
                            continue
                        self.profiles[p["id"]] = p
                # If loaded from legacy path, persist to the new parallel file location
                if load_path != self.config_path:
                    self._save_profiles()
                return
            except Exception:
                pass

        self.profiles = {}
        self._save_profiles()

    def _save_profiles(self) -> None:
        """Saves current profiles to disk parallel to executable."""
        try:
            os.makedirs(os.path.dirname(self.config_path), exist_ok=True)
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump(list(self.profiles.values()), f, indent=2)
        except Exception:
            pass

    def list_profiles(self) -> List[Dict[str, Any]]:
        return list(self.profiles.values())

    def get_profile(self, profile_id: str) -> Optional[Dict[str, Any]]:
        return self.profiles.get(profile_id)

    def save_profile(self, profile: Dict[str, Any]) -> Dict[str, Any]:
        if "id" not in profile or not profile["id"] or profile["id"] == "custom":
            profile["id"] = "preset-" + uuid.uuid4().hex[:8]
        if "name" not in profile or not profile["name"]:
            profile["name"] = "自定义预设"
        if "category" not in profile:
            profile["category"] = "agent"
        if "gradient" not in profile:
            profile["gradient"] = "from-cyan-600 via-blue-600 to-indigo-700"
        if "icon" not in profile:
            profile["icon"] = "bot"
        self.profiles[profile["id"]] = profile
        self._save_profiles()
        return profile

    def delete_profile(self, profile_id: str) -> bool:
        if profile_id in self.profiles:
            del self.profiles[profile_id]
            self._save_profiles()
            return True
        return False

    def launch_agent(
        self,
        profile_id: Optional[str] = None,
        custom_name: Optional[str] = None,
        custom_command: Optional[Union[str, List[str]]] = None,
        cwd: Optional[str] = None,
        folder: Optional[str] = None,
        session_type: str = "agent",
        extra_args: Optional[List[str]] = None,
        extra_env: Optional[Dict[str, str]] = None,
        keep_alive: bool = False,
        rows: int = 24,
        cols: int = 80,
    ) -> AgentSession:
        """
        Launches an agent session with full multi-instruction shell fidelity,
        supporting complex Docker runs, chained commands (&&, ||, |), and persistent PTY sessions.
        """
        profile = self.profiles.get(profile_id) if profile_id else None

        if profile:
            name = custom_name or profile.get("name", "Agent")
            raw_cmd = custom_command if custom_command is not None else profile.get("command")
            working_dir = cwd or profile.get("default_cwd") or os.getcwd()
            env = dict(profile.get("env", {}))
            if profile.get("category") == "system":
                session_type = "terminal"
        else:
            name = custom_name or ("终端" if session_type == "terminal" else "自定义 Agent")
            raw_cmd = custom_command
            working_dir = cwd or os.getcwd()
            env = {}

        if raw_cmd is None or raw_cmd == "":
            raw_cmd = ["bash", "-l"] if sys.platform != "win32" else ["powershell.exe"]

        if extra_env:
            env.update(extra_env)

        # Normalize raw_cmd into display string and executable command list
        if isinstance(raw_cmd, list):
            if len(raw_cmd) == 1:
                cmd_str = raw_cmd[0].strip()
            elif len(raw_cmd) == 2 and raw_cmd == ["bash", "-l"]:
                cmd_str = "bash -l"
            else:
                cmd_str = " ".join(raw_cmd).strip()
        else:
            cmd_str = str(raw_cmd).strip()

        if extra_args:
            extra_str = " ".join(extra_args).strip()
            if extra_str:
                cmd_str = f"{cmd_str} {extra_str}"

        # Determine how to execute the command inside the PTY
        is_pure_shell = cmd_str in [
            "bash", "bash -l", "/bin/bash", "/bin/bash -l",
            "sh", "/bin/sh", "zsh", "/bin/zsh",
            "powershell", "powershell.exe", "pwsh"
        ]

        if is_pure_shell:
            if sys.platform == "win32":
                exec_command = ["powershell.exe"]
            else:
                shell_bin = os.environ.get("SHELL", "/bin/bash")
                if not os.path.exists(shell_bin):
                    shell_bin = "/bin/bash" if os.path.exists("/bin/bash") else "/bin/sh"
                exec_command = [shell_bin, "-l"]
        else:
            # Multi-instruction, Docker execution, or shell script
            if keep_alive and not cmd_str.endswith("exec bash") and sys.platform != "win32":
                cmd_str_exec = f"{cmd_str}\nexec bash"
            else:
                cmd_str_exec = cmd_str

            if sys.platform == "win32":
                exec_command = ["powershell.exe", "-NoProfile", "-Command", cmd_str_exec]
            else:
                shell_bin = os.environ.get("SHELL", "/bin/bash")
                if not os.path.exists(shell_bin):
                    shell_bin = "/bin/bash" if os.path.exists("/bin/bash") else "/bin/sh"
                exec_command = [shell_bin, "-l", "-c", cmd_str_exec]

        if extra_args:
            command.extend(extra_args)
        if extra_env:
            env.update(extra_env)

        # Resolve working directory safely
        candidate_cwd = cwd or (profile.get("default_cwd") if profile else None) or os.getcwd()
        if candidate_cwd == "/workspace" and not os.path.exists("/workspace"):
            working_dir = os.getcwd()
        else:
            candidate_cwd = os.path.expanduser(candidate_cwd)
            if not os.path.exists(candidate_cwd):
                try:
                    os.makedirs(candidate_cwd, exist_ok=True)
                    working_dir = candidate_cwd
                except Exception:
                    working_dir = os.getcwd()
            else:
                working_dir = candidate_cwd

        session_id = uuid.uuid4().hex[:12]
        session = AgentSession(
            session_id=session_id,
            name=name,
            command=exec_command,
            cwd=working_dir,
            env=env,
            profile_id=profile_id,
            folder=folder,
            session_type=session_type,
            rows=rows,
            cols=cols,
            raw_command=cmd_str,
        )

        session.start()
        self.sessions[session_id] = session
        return session

    def launch_terminal(
        self,
        cwd: Optional[str] = None,
        folder: Optional[str] = None,
        name: Optional[str] = None,
        rows: int = 24,
        cols: int = 80,
    ) -> AgentSession:
        """Launches a pure interactive remote terminal shell directly."""
        shell_cmd = ["powershell.exe"] if sys.platform == "win32" else [os.environ.get("SHELL", "bash"), "-l"]
        dir_name = os.path.basename(os.path.abspath(cwd or os.getcwd()))
        display_name = name or f"终端 ({dir_name})"
        return self.launch_agent(
            custom_name=display_name,
            custom_command=shell_cmd,
            cwd=cwd,
            folder=folder,
            session_type="terminal",
            rows=rows,
            cols=cols,
        )

    def update_session_folder(self, session_id: str, new_folder: str) -> bool:
        session = self.sessions.get(session_id)
        if session:
            session.folder = new_folder.strip() or "默认项目"
            return True
        return False

    def get_session(self, session_id: str) -> Optional[AgentSession]:
        return self.sessions.get(session_id)

    def list_sessions(self) -> List[Dict[str, Any]]:
        # Clean up very old stopped sessions (> 1 hour dead and no subscribers)
        now = os.path.getmtime(__file__) if os.path.exists(__file__) else 0
        return [session.to_dict() for session in self.sessions.values()]

    def terminate_session(self, session_id: str) -> bool:
        session = self.sessions.get(session_id)
        if session:
            session.terminate(sig=15)
            return True
        return False

    def delete_session(self, session_id: str) -> bool:
        session = self.sessions.get(session_id)
        if session:
            session.terminate(sig=9)
            del self.sessions[session_id]
            return True
        return False

    def restart_session(self, session_id: str) -> Optional[AgentSession]:
        old_session = self.sessions.get(session_id)
        if not old_session:
            return None
        old_session.terminate(sig=9)
        new_session = AgentSession(
            session_id=session_id,
            name=old_session.name,
            command=old_session.command,
            cwd=old_session.cwd,
            env=old_session.env,
            profile_id=old_session.profile_id,
            folder=old_session.folder,
            session_type=old_session.session_type,
            rows=old_session.rows,
            cols=old_session.cols,
            raw_command=old_session.raw_command,
        )
        new_session.start()
        self.sessions[session_id] = new_session
        return new_session
