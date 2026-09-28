"""Authenticated, per-client protocol state and terminal message dispatcher."""
import asyncio
import base64
import collections
import json
import logging
import os
import sys
import threading
import time
import uuid
from core.crypto import ProtocolError, HANDSHAKE_TIMEOUT, MAX_FRAME_BYTES

logger = logging.getLogger("viber.router")


class ClientConnectionState:
    def __init__(self, key_manager, agent_manager, monitor, send_raw_func,
                 is_trusted_network=False, close_func=None):
        self.key_manager = key_manager
        self.agent_manager = agent_manager
        self.monitor = monitor
        self.send_raw = send_raw_func
        self.close_func = close_func
        self.is_trusted_network = False  # Kept for source compatibility; NEVER authorizes.
        self.e2ee = None
        self.is_authenticated = False
        self.client_id = str(uuid.uuid4())
        self.attached_session_id = None
        self._phase = "hello"
        self._handshake = None
        self._deadline = time.monotonic() + HANDSHAKE_TIMEOUT
        self._send_lock = asyncio.Lock()
        self._loop = asyncio.get_running_loop()
        self._output_lock = threading.Lock()
        self._outbox = collections.deque()
        self._outbox_bytes = 0
        self._drain_scheduled = False
        self._overflowed = False
        self._output_task = None
        self._replaying = False
        self._output_cutoff = 0

    async def handle_raw_message(self, raw_msg):
        if not isinstance(raw_msg, str) or len(raw_msg.encode("utf-8")) > MAX_FRAME_BYTES:
            raise ProtocolError("Invalid frame size/type")
        try:
            payload = json.loads(raw_msg)
        except (ValueError, TypeError) as exc:
            raise ProtocolError("Invalid JSON") from exc
        if not isinstance(payload, dict):
            raise ProtocolError("Object required")
        if self._phase != "ready" and time.monotonic() > self._deadline:
            raise ProtocolError("Handshake timed out")
        if self._phase == "hello":
            self._handshake = self.key_manager.begin_handshake(payload)
            self._phase = "auth"
            await asyncio.wait_for(self.send_raw(json.dumps(self._handshake.challenge)), 10)
        elif self._phase == "auth":
            self.e2ee = self._handshake.finish(payload)
            self._handshake = None
            self._phase = "ready"
            self.is_authenticated = True
            await self.send_encrypted({"type": "WELCOME", "v": 2, "status": "authenticated",
                                       "fingerprint": self.key_manager.get_fingerprint(), "e2ee": True})
        elif self._phase == "ready":
            message = self.e2ee.decrypt_json(payload)
            if message["type"] in {"HELLO", "AUTH", "WELCOME", "CHALLENGE"}:
                raise ProtocolError("Handshake already complete")
            await self._handle_decrypted(message)
        else:
            raise ProtocolError("Connection closed")

    async def send_encrypted(self, msg):
        if not self.is_authenticated or self.e2ee is None:
            raise ProtocolError("Authentication required")
        # Keep counter allocation, encryption and socket send in the same lock.
        async with self._send_lock:
            if not self.is_authenticated or self.e2ee is None:
                raise ProtocolError("Connection closed")
            frame = self.e2ee.encrypt_json(msg)
            await asyncio.wait_for(self.send_raw(json.dumps(frame)), 10)

    async def _handle_decrypted(self, msg):
        kind = msg["type"]
        if kind in ("LAUNCH_AGENT", "LAUNCH_TERMINAL", "RESTART_SESSION"):
            if len(self.agent_manager.list_sessions()) >= 32 and kind != "RESTART_SESSION":
                await self.send_encrypted({"type": "AGENT_ERROR", "error": "Session limit reached (32)"})
                return
        if kind in ("LAUNCH_AGENT", "LAUNCH_TERMINAL", "RESIZE_TERMINAL"):
            for name, default in (("rows", 24), ("cols", 80)):
                value = msg.get(name, default)
                if type(value) is not int or not 1 <= value <= 500:
                    raise ProtocolError("Invalid terminal dimensions")
        if kind == "PING":
            await self.send_encrypted({"type": "PONG", "ts": msg.get("ts")})
        elif kind == "GET_STATS":
            await self.send_encrypted({"type": "STATS", "system": self.monitor.get_system_stats(),
                                       "sessions": self.agent_manager.list_sessions()})
        elif kind == "LIST_PROFILES":
            await self.send_encrypted({"type": "PROFILES", "profiles": self.agent_manager.list_profiles()})
        elif kind == "SAVE_PROFILE":
            profile = self.agent_manager.save_profile(msg.get("profile", {}))
            await self.send_encrypted({"type": "PROFILE_SAVED", "profile": profile})
            await self.send_encrypted({"type": "PROFILES", "profiles": self.agent_manager.list_profiles()})
        elif kind == "DELETE_PROFILE":
            ok = self.agent_manager.delete_profile(msg.get("profile_id", ""))
            await self.send_encrypted({"type": "PROFILE_DELETED", "profile_id": msg.get("profile_id"), "success": ok})
            await self.send_encrypted({"type": "PROFILES", "profiles": self.agent_manager.list_profiles()})
        elif kind == "LIST_DIR":
            data = await asyncio.to_thread(self._list_directory, msg.get("path"))
            await self.send_encrypted({"type": "DIR_LIST", "req_id": msg.get("req_id"), "data": data})
        elif kind == "CREATE_DIR":
            path = msg.get("path", "")
            if not isinstance(path, str) or len(path) > 4096:
                raise ProtocolError("Invalid path")
            path = path.strip()
            ok, error = False, None
            try:
                if path:
                    await asyncio.to_thread(os.makedirs, os.path.expanduser(path), exist_ok=True)
                    ok = True
            except OSError as exc:
                error = str(exc)
            await self.send_encrypted({"type": "DIR_CREATED", "success": ok, "error": error, "path": path})
        elif kind in ("LAUNCH_AGENT", "LAUNCH_TERMINAL"):
            try:
                common = {"cwd": msg.get("cwd"), "folder": msg.get("folder"),
                          "rows": msg.get("rows", 24), "cols": msg.get("cols", 80)}
                if kind == "LAUNCH_AGENT":
                    session = self.agent_manager.launch_agent(
                        profile_id=msg.get("profile_id"), custom_name=msg.get("name"),
                        custom_command=msg.get("command"), session_type=msg.get("session_type", "agent"),
                        extra_args=msg.get("extra_args"), extra_env=msg.get("extra_env"),
                        keep_alive=msg.get("keep_alive", False), **common)
                else:
                    session = self.agent_manager.launch_terminal(name=msg.get("name"), **common)
                await self.send_encrypted({"type": "AGENT_LAUNCHED", "session": session.to_dict()})
            except ProtocolError:
                raise
            except Exception as exc:
                await self.send_encrypted({"type": "AGENT_ERROR", "error": str(exc)})
        elif kind == "UPDATE_SESSION_FOLDER":
            sid = msg.get("session_id")
            folder = msg.get("folder", "")
            ok = self.agent_manager.update_session_folder(sid, folder)
            await self.send_encrypted({"type": "SESSION_FOLDER_UPDATED", "session_id": sid, "folder": folder, "success": ok})
        elif kind == "ATTACH_SESSION":
            last_seq = msg.get("last_seq", 0)
            if type(last_seq) is not int or last_seq < 0:
                raise ProtocolError("Invalid replay position")
            await self._attach_session(msg.get("session_id"), last_seq)
        elif kind == "DETACH_SESSION":
            self._detach_session()
            await self.send_encrypted({"type": "DETACHED"})
        elif kind == "TERMINAL_INPUT":
            encoded = msg.get("data", "")
            if not isinstance(encoded, str) or len(encoded) > 90_000:
                raise ProtocolError("Input limit exceeded")
            try:
                data = base64.b64decode(encoded, validate=True)
            except ValueError as exc:
                raise ProtocolError("Invalid terminal input") from exc
            if len(data) > 65536:
                raise ProtocolError("Input limit exceeded")
            session = self.agent_manager.get_session(msg.get("session_id") or self.attached_session_id)
            if session:
                session.write_input(data)
        elif kind == "RESIZE_TERMINAL":
            session = self.agent_manager.get_session(msg.get("session_id") or self.attached_session_id)
            if session:
                session.resize(msg.get("rows", 24), msg.get("cols", 80))
        elif kind in ("TERMINATE_AGENT", "DELETE_SESSION"):
            sid = msg.get("session_id")
            if kind == "DELETE_SESSION" and self.attached_session_id == sid:
                self._detach_session()
            action = self.agent_manager.terminate_session if kind == "TERMINATE_AGENT" else self.agent_manager.delete_session
            ok = action(sid)
            await self.send_encrypted({"type": "AGENT_TERMINATED" if kind == "TERMINATE_AGENT" else "SESSION_DELETED",
                                       "session_id": sid, "success": ok})
        elif kind == "RESTART_SESSION":
            session = self.agent_manager.restart_session(msg.get("session_id"))
            await self.send_encrypted({"type": "SESSION_RESTARTED", "session": session.to_dict()} if session else
                                      {"type": "AGENT_ERROR", "error": "Session not found"})
        else:
            raise ProtocolError("Unknown application message")

    async def _attach_session(self, session_id, last_seq):
        session = self.agent_manager.get_session(session_id)
        if not session:
            await self.send_encrypted({"type": "AGENT_ERROR", "error": "Session not found"})
            return
        self._detach_session()
        self.attached_session_id = session_id
        self._replaying = True
        session.subscribe(self._on_terminal_output)
        needs_reset, chunks, snapshot_seq = session.buffer.snapshot_since(last_seq)
        start_seq = chunks[0][0] - 1 if chunks else snapshot_seq
        with self._output_lock:
            # Output callbacks can race the replay snapshot on Windows PTY threads.
            # Drop only chunks actually represented by that atomic snapshot.
            self._output_cutoff = snapshot_seq
            self._outbox = collections.deque(item for item in self._outbox if item[1] > snapshot_seq)
            self._outbox_bytes = sum(len(item[2]) for item in self._outbox)
        try:
            # Stream bounded frames instead of a multi-megabyte JSON snapshot.
            await self.send_encrypted({"type": "SESSION_ATTACHED", "session": session.to_dict(),
                                       "needs_reset": needs_reset, "replay": [], "current_seq": start_seq})
            for seq, data in chunks:
                if len(data) > 256 * 1024:
                    raise ProtocolError("Replay chunk limit exceeded")
                await self.send_encrypted({"type": "TERMINAL_OUTPUT", "session_id": session_id, "seq": seq,
                                           "data": base64.b64encode(data).decode("ascii")})
        finally:
            self._replaying = False
            self._start_output_drain()

    def _detach_session(self):
        if self.attached_session_id:
            session = self.agent_manager.get_session(self.attached_session_id)
            if session:
                session.unsubscribe(self._on_terminal_output)
        self.attached_session_id = None
        with self._output_lock:
            self._outbox.clear()
            self._outbox_bytes = 0
            self._overflowed = False
            self._output_cutoff = 0

    def _on_terminal_output(self, session_id, seq, chunk_bytes):
        if not self.is_authenticated or self.attached_session_id != session_id:
            return
        with self._output_lock:
            if seq <= self._output_cutoff:
                return
            if len(self._outbox) >= 64 or self._outbox_bytes + len(chunk_bytes) > 256 * 1024:
                self._overflowed = True
            else:
                self._outbox.append((session_id, seq, bytes(chunk_bytes)))
                self._outbox_bytes += len(chunk_bytes)
            if not self._drain_scheduled:
                self._drain_scheduled = True
                self._loop.call_soon_threadsafe(self._start_output_drain)

    def _start_output_drain(self):
        if self.is_authenticated and not self._replaying and (self._output_task is None or self._output_task.done()):
            self._output_task = self._loop.create_task(self._drain_output())

    async def _drain_output(self):
        try:
            while self.is_authenticated:
                with self._output_lock:
                    if self._overflowed:
                        raise ProtocolError("Slow consumer")
                    if not self._outbox:
                        self._drain_scheduled = False
                        return
                    sid, seq, data = self._outbox.popleft()
                    self._outbox_bytes -= len(data)
                if sid == self.attached_session_id:
                    await self.send_encrypted({"type": "TERMINAL_OUTPUT", "session_id": sid, "seq": seq,
                                               "data": base64.b64encode(data).decode("ascii")})
        except asyncio.CancelledError:
            raise
        except Exception:
            self.cleanup()
            if self.close_func:
                await self.close_func()

    def cleanup(self):
        self.is_authenticated = False
        self._phase = "closed"
        self._handshake = None
        self.e2ee = None
        self._detach_session()
        task = self._output_task
        if task and not task.done() and task is not asyncio.current_task():
            task.cancel()

    def _list_directory(self, target_path=None):
        home = os.path.expanduser("~")
        workspace = "/workspace" if os.path.exists("/workspace") else os.getcwd()
        if target_path is not None and (not isinstance(target_path, str) or len(target_path) > 4096):
            raise ProtocolError("Invalid path")
        path = os.path.abspath(os.path.expanduser(target_path.strip())) if target_path and target_path.strip() else workspace
        if not os.path.isdir(path):
            path = workspace
        folders = []
        truncated = False
        try:
            with os.scandir(path) as entries:
                for i, entry in enumerate(entries):
                    if i >= 2000:
                        truncated = True
                        break
                    try:
                        if entry.is_dir(follow_symlinks=False) and not entry.name.startswith("."):
                            folders.append({"name": entry.name, "path": entry.path,
                                            "is_git": os.path.exists(os.path.join(entry.path, ".git"))})
                    except OSError:
                        pass
        except OSError:
            pass
        folders.sort(key=lambda f: f["name"].lower())
        parent = os.path.dirname(path)
        drives = []
        if sys.platform == "win32":
            import string
            drives = [f"{c}:" for c in string.ascii_uppercase if os.path.exists(f"{c}:\\")]
        return {"current_path": path, "parent_path": parent if parent != path else None,
                "folders": folders, "drives": drives, "home": home, "workspace": workspace,
                "truncated": truncated}
