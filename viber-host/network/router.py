"""
RemoteViber Protocol Router & E2EE Message Dispatcher
"""

import os
import json
import base64
import logging
from typing import Dict, Any, Optional, Callable, Awaitable

from core.crypto import HostKeyManager, E2EESession
from core.agent_manager import AgentManager
from core.monitor import SystemMonitor

logger = logging.getLogger("viber.router")


class ClientConnectionState:
    """Tracks state and encryption context for a single connected client session."""

    def __init__(
        self,
        key_manager: HostKeyManager,
        agent_manager: AgentManager,
        monitor: SystemMonitor,
        send_raw_func: Callable[[str], Awaitable[None]],
        is_trusted_network: bool = False,
    ):
        self.key_manager = key_manager
        self.agent_manager = agent_manager
        self.monitor = monitor
        self.send_raw = send_raw_func
        self.is_trusted_network = is_trusted_network

        self.e2ee: Optional[E2EESession] = None
        self.is_authenticated = False
        self.client_id: Optional[str] = None
        self.attached_session_id: Optional[str] = None

    async def handle_raw_message(self, raw_msg: str) -> None:
        """Processes an incoming raw text message (either unencrypted handshake or encrypted frame)."""
        try:
            payload = json.loads(raw_msg)
        except Exception:
            return

        # Handshake phase: HELLO
        if not self.is_authenticated:
            if payload.get("type") == "HELLO":
                await self._handle_hello(payload)
            return

        # Post-handshake: Decrypt AES-GCM frame if E2EE active, or direct JSON over trusted channel
        if "iv" in payload and "data" in payload and self.e2ee:
            try:
                decrypted = self.e2ee.decrypt_json(payload["iv"], payload["data"])
            except Exception as e:
                logger.warning(f"Decryption failed: {e}")
                return
            await self._handle_decrypted(decrypted)
        elif not self.e2ee and "type" in payload:
            await self._handle_decrypted(payload)

    async def _handle_hello(self, payload: Dict[str, Any]) -> None:
        client_pub_b64 = payload.get("client_pub")
        token = payload.get("token", "")
        e2ee_requested = payload.get("e2ee", True) if client_pub_b64 else False
        self.client_id = payload.get("client_id", "anonymous")

        # Verify pairing token
        is_token_valid = self.key_manager.verify_pairing_token(token)
        if not is_token_valid:
            if self.is_trusted_network:
                logger.info(
                    f"Client {self.client_id} connected over trusted Tailscale/Localhost network without matching token. "
                    "Auto-authorizing session and syncing active token."
                )
            else:
                logger.warning(
                    f"Client {self.client_id} authentication failed: invalid pairing token "
                    f"(token: '{token[:4] if token else ''}***')"
                )
                await self.send_raw(json.dumps({
                    "type": "ERROR",
                    "error": "Authentication failed: invalid pairing token",
                }))
                return

        if e2ee_requested and client_pub_b64:
            try:
                client_pub_raw = base64.b64decode(client_pub_b64)
                self.e2ee = self.key_manager.derive_session(client_pub_raw)
                self.is_authenticated = True

                welcome_msg = {
                    "type": "WELCOME",
                    "host_pub": self.key_manager.public_key_b64,
                    "status": "authenticated",
                    "fingerprint": self.key_manager.get_fingerprint(),
                    "token": self.key_manager.pairing_secret,
                    "e2ee": True,
                }
                await self.send_raw(json.dumps(welcome_msg))
                logger.info(f"Client {self.client_id} successfully authenticated with WebCrypto E2EE")
            except Exception as e:
                logger.error(f"Handshake error: {e}")
                await self.send_raw(json.dumps({
                    "type": "ERROR",
                    "error": f"Handshake failed: {e}",
                }))
        else:
            # Non-secure HTTP browser context (WebCrypto unavailable on mobile http://100.x.x.x)
            if not self.is_trusted_network:
                logger.warning(f"Client {self.client_id} rejected: E2EE is required on untrusted networks")
                await self.send_raw(json.dumps({
                    "type": "ERROR",
                    "error": "E2EE is required on untrusted networks",
                }))
                return

            self.e2ee = None
            self.is_authenticated = True
            welcome_msg = {
                "type": "WELCOME",
                "host_pub": self.key_manager.public_key_b64,
                "status": "authenticated",
                "fingerprint": self.key_manager.get_fingerprint(),
                "token": self.key_manager.pairing_secret,
                "e2ee": False,
            }
            await self.send_raw(json.dumps(welcome_msg))
            logger.info(
                f"Client {self.client_id} successfully authenticated via trusted Tailscale/Localhost direct channel "
                "(WireGuard transport security active)"
            )

    async def send_encrypted(self, msg: Dict[str, Any]) -> None:
        """Transmits an outgoing message (encrypted with AES-GCM if E2EE active, or direct JSON over trusted channel)."""
        if not self.is_authenticated:
            return
        if self.e2ee:
            frame = self.e2ee.encrypt_json(msg)
            await self.send_raw(json.dumps(frame))
        else:
            await self.send_raw(json.dumps(msg))

    async def _handle_decrypted(self, msg: Dict[str, Any]) -> None:
        msg_type = msg.get("type")

        if msg_type == "PING":
            await self.send_encrypted({"type": "PONG", "ts": msg.get("ts")})

        elif msg_type == "GET_STATS":
            stats = self.monitor.get_system_stats()
            sessions = self.agent_manager.list_sessions()
            await self.send_encrypted({
                "type": "STATS",
                "system": stats,
                "sessions": sessions,
            })

        elif msg_type == "LIST_PROFILES":
            profiles = self.agent_manager.list_profiles()
            await self.send_encrypted({
                "type": "PROFILES",
                "profiles": profiles,
            })

        elif msg_type == "SAVE_PROFILE":
            profile = self.agent_manager.save_profile(msg.get("profile", {}))
            await self.send_encrypted({
                "type": "PROFILE_SAVED",
                "profile": profile,
            })
            profiles = self.agent_manager.list_profiles()
            await self.send_encrypted({
                "type": "PROFILES",
                "profiles": profiles,
            })

        elif msg_type == "DELETE_PROFILE":
            ok = self.agent_manager.delete_profile(msg.get("profile_id", ""))
            await self.send_encrypted({
                "type": "PROFILE_DELETED",
                "profile_id": msg.get("profile_id"),
                "success": ok,
            })
            profiles = self.agent_manager.list_profiles()
            await self.send_encrypted({
                "type": "PROFILES",
                "profiles": profiles,
            })

        elif msg_type == "LIST_DIR":
            target_path = msg.get("path")
            data = self._list_directory(target_path)
            await self.send_encrypted({
                "type": "DIR_LIST",
                "req_id": msg.get("req_id"),
                "data": data,
            })

        elif msg_type == "CREATE_DIR":
            target_path = msg.get("path", "").strip()
            success = False
            error = None
            if target_path:
                try:
                    os.makedirs(os.path.expanduser(target_path), exist_ok=True)
                    success = True
                except Exception as e:
                    error = str(e)
            await self.send_encrypted({
                "type": "DIR_CREATED",
                "success": success,
                "error": error,
                "path": target_path,
            })

        elif msg_type == "LAUNCH_AGENT":
            try:
                logger.info(f"Client {self.client_id} requested LAUNCH_AGENT: profile={msg.get('profile_id')} cwd={msg.get('cwd')}")
                session = self.agent_manager.launch_agent(
                    profile_id=msg.get("profile_id"),
                    custom_name=msg.get("name"),
                    custom_command=msg.get("command"),
                    cwd=msg.get("cwd"),
                    folder=msg.get("folder"),
                    session_type=msg.get("session_type", "agent"),
                    extra_args=msg.get("extra_args"),
                    extra_env=msg.get("extra_env"),
                    keep_alive=msg.get("keep_alive", False),
                    rows=msg.get("rows", 24),
                    cols=msg.get("cols", 80),
                )
                pid = session.pty.process.pid if session.pty and session.pty.process else 'unknown'
                logger.info(f"Agent session {session.session_id} successfully launched (pid={pid})")
                await self.send_encrypted({
                    "type": "AGENT_LAUNCHED",
                    "session": session.to_dict(),
                })
            except Exception as e:
                logger.error(f"Error launching agent: {e}", exc_info=True)
                await self.send_encrypted({
                    "type": "AGENT_ERROR",
                    "error": str(e),
                })

        elif msg_type == "LAUNCH_TERMINAL":
            try:
                logger.info(f"Client {self.client_id} requested LAUNCH_TERMINAL: cwd={msg.get('cwd')}")
                session = self.agent_manager.launch_terminal(
                    cwd=msg.get("cwd"),
                    folder=msg.get("folder"),
                    name=msg.get("name"),
                    rows=msg.get("rows", 24),
                    cols=msg.get("cols", 80),
                )
                logger.info(f"Terminal session {session.session_id} successfully launched")
                await self.send_encrypted({
                    "type": "AGENT_LAUNCHED",
                    "session": session.to_dict(),
                })
            except Exception as e:
                logger.error(f"Error launching terminal: {e}", exc_info=True)
                await self.send_encrypted({
                    "type": "AGENT_ERROR",
                    "error": str(e),
                })

        elif msg_type == "UPDATE_SESSION_FOLDER":
            session_id = msg.get("session_id")
            folder = msg.get("folder", "")
            ok = self.agent_manager.update_session_folder(session_id, folder)
            await self.send_encrypted({
                "type": "SESSION_FOLDER_UPDATED",
                "session_id": session_id,
                "folder": folder,
                "success": ok,
            })

        elif msg_type == "ATTACH_SESSION":
            session_id = msg.get("session_id")
            last_seq = msg.get("last_seq", 0)
            await self._attach_session(session_id, last_seq)

        elif msg_type == "DETACH_SESSION":
            self._detach_session()
            await self.send_encrypted({"type": "DETACHED"})

        elif msg_type == "TERMINAL_INPUT":
            session_id = msg.get("session_id") or self.attached_session_id
            session = self.agent_manager.get_session(session_id)
            if session:
                data_b64 = msg.get("data", "")
                data = base64.b64decode(data_b64)
                session.write_input(data)

        elif msg_type == "RESIZE_TERMINAL":
            session_id = msg.get("session_id") or self.attached_session_id
            session = self.agent_manager.get_session(session_id)
            if session:
                session.resize(msg.get("rows", 24), msg.get("cols", 80))

        elif msg_type == "TERMINATE_AGENT":
            session_id = msg.get("session_id")
            ok = self.agent_manager.terminate_session(session_id)
            logger.info(f"Terminated session {session_id} result={ok}")
            await self.send_encrypted({
                "type": "AGENT_TERMINATED",
                "session_id": session_id,
                "success": ok,
            })

        elif msg_type == "DELETE_SESSION":
            session_id = msg.get("session_id")
            if self.attached_session_id == session_id:
                self._detach_session()
            ok = self.agent_manager.delete_session(session_id)
            logger.info(f"Deleted session {session_id} result={ok}")
            await self.send_encrypted({
                "type": "SESSION_DELETED",
                "session_id": session_id,
                "success": ok,
            })

        elif msg_type == "RESTART_SESSION":
            session_id = msg.get("session_id")
            session = self.agent_manager.restart_session(session_id)
            if session:
                logger.info(f"Restarted session {session_id}")
                await self.send_encrypted({
                    "type": "SESSION_RESTARTED",
                    "session": session.to_dict(),
                })
            else:
                await self.send_encrypted({
                    "type": "AGENT_ERROR",
                    "error": f"Session {session_id} not found to restart",
                })

    async def _attach_session(self, session_id: str, last_seq: int) -> None:
        """Attaches to session and immediately streams missed output buffer."""
        session = self.agent_manager.get_session(session_id)
        if not session:
            await self.send_encrypted({
                "type": "AGENT_ERROR",
                "error": f"Session {session_id} not found",
            })
            return

        self._detach_session()
        self.attached_session_id = session_id

        # Register live output subscriber
        session.subscribe(self._on_terminal_output)

        # Retrieve buffer replay
        needs_reset, chunks = session.buffer.get_since(last_seq)

        # Batch missed chunks or send snapshot
        replay_payload = []
        for seq, chunk_bytes in chunks:
            replay_payload.append({
                "seq": seq,
                "data": base64.b64encode(chunk_bytes).decode("ascii"),
            })

        await self.send_encrypted({
            "type": "SESSION_ATTACHED",
            "session": session.to_dict(),
            "needs_reset": needs_reset,
            "replay": replay_payload,
            "current_seq": session.buffer.current_seq,
        })

    def _detach_session(self) -> None:
        """Detaches without stopping the agent."""
        if self.attached_session_id:
            session = self.agent_manager.get_session(self.attached_session_id)
            if session:
                session.unsubscribe(self._on_terminal_output)
            self.attached_session_id = None

    def _on_terminal_output(self, session_id: str, seq: int, chunk_bytes: bytes) -> None:
        """Invoked asynchronously whenever the PTY produces new data."""
        if not self.is_authenticated or self.attached_session_id != session_id:
            return

        import asyncio
        loop = asyncio.get_event_loop()
        if loop.is_running():
            msg = {
                "type": "TERMINAL_OUTPUT",
                "session_id": session_id,
                "seq": seq,
                "data": base64.b64encode(chunk_bytes).decode("ascii"),
            }
            loop.create_task(self.send_encrypted(msg))

    def cleanup(self) -> None:
        """Invoked when the client socket disconnects."""
        self._detach_session()
        self.is_authenticated = False

    def _list_directory(self, target_path: Optional[str] = None) -> Dict[str, Any]:
        """Scans directories on the host for the remote directory picker."""
        import os
        import sys

        home = os.path.expanduser("~")
        workspace = "/workspace" if os.path.exists("/workspace") else os.getcwd()

        if not target_path or not target_path.strip():
            raw_path = workspace
        else:
            raw_path = os.path.expanduser(target_path.strip())

        try:
            abs_path = os.path.abspath(raw_path)
            if not os.path.exists(abs_path):
                abs_path = workspace if os.path.exists(workspace) else home
        except Exception:
            abs_path = home

        folders = []
        try:
            with os.scandir(abs_path) as entries:
                for entry in entries:
                    try:
                        if entry.is_dir(follow_symlinks=False) and not entry.name.startswith("."):
                            entry_path = entry.path
                            is_git = os.path.exists(os.path.join(entry_path, ".git"))
                            folders.append({
                                "name": entry.name,
                                "path": entry_path,
                                "is_git": is_git,
                            })
                    except (PermissionError, OSError):
                        pass
        except (PermissionError, OSError):
            pass

        folders.sort(key=lambda x: x["name"].lower())

        parent_path = os.path.dirname(abs_path)
        if parent_path == abs_path:
            parent_path = None

        drives = []
        if sys.platform == "win32":
            import string
            for letter in string.ascii_uppercase:
                drive = f"{letter}:\\"
                if os.path.exists(drive):
                    drives.append(f"{letter}:")

        return {
            "current_path": abs_path,
            "parent_path": parent_path,
            "folders": folders,
            "drives": drives,
            "home": home,
            "workspace": workspace,
        }

