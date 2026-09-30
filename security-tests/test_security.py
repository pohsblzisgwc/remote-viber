"""Local-only regression tests; the process manager is a non-executing fake."""
import asyncio
import base64
import copy
import importlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import types
import unittest

BUNDLE = Path(__file__).resolve().parents[1]
TEST_DIR = Path(__file__).resolve().parent
SOURCE = Path(os.environ.get('VIBER_SOURCE_ROOT', BUNDLE if (BUNDLE / 'viber-host').exists() else BUNDLE / 'payload')).resolve()
sys.path[:0] = [str(SOURCE / 'viber-host'), str(SOURCE / 'viber-server')]
from core.crypto import (HostKeyManager, ServerHandshake, E2EESession, ProtocolError,
                         public_raw, transcript, derive_keys, auth_proof, b64,
                         verify_signature, host_id_for, MAX_SEQUENCE,
                         MAX_CLIENT_FRAME_BYTES, MAX_RELAY_FRAME_BYTES)
from core.config import HostConfig, read_private_json, write_private_json, is_safe_config_dir, HostLock
from core.agent_manager import AgentManager
import ssl
from core.monitor import SystemMonitor
from network.router import ClientConnectionState
from network.direct_server import DirectServer, static_bytes, normalize_origin, get_or_create_tls_context
from network.relay_client import RelayClient, validate_relay_url
from secure_protocol import ProtocolError as RelayProtocolError
from registry import HostRegistry
from server import RelayServer
from cryptography.hazmat.primitives.asymmetric import ec
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed, InvalidStatus

class Monitor:
    def get_system_stats(self): return {'cpu_percent': 1, 'endpoints': self.discover_local_endpoints()}
    def discover_local_endpoints(self): return {'tailscale': [], 'lan': [], 'loopback': ['127.0.0.1']}
class Manager:
    def __init__(self): self.calls = []
    def list_sessions(self): return []
    def list_profiles(self): return []
    def get_session(self, sid): return None

class PythonClient:
    def __init__(self, identity):
        self.identity = identity
        self.private = ec.generate_private_key(ec.SECP256R1())
        self.hello = {'type':'HELLO','v':2,'client_pub':b64(public_raw(self.private.public_key())), 'client_nonce': b64(os.urandom(32))}
    def challenge(self, msg, token=None):
        assert msg['host_pub'] == self.identity.public_key_b64
        context = transcript(msg['host_pub'], self.hello['client_pub'], self.hello['client_nonce'], msg['server_pub'], msg['server_nonce'])
        verify_signature(msg['host_pub'], msg['signature'], context)
        self.session = E2EESession(derive_keys(self.private, msg['server_pub'], context), 'client')
        return {'type': 'AUTH', 'v':2, 'proof':auth_proof(token or self.identity.pairing_secret, context)}

class CryptoTests(unittest.TestCase):
    def setUp(self): self.identity = HostKeyManager()
    def pair(self):
        client = PythonClient(self.identity)
        hs = self.identity.begin_handshake(client.hello)
        host = hs.finish(client.challenge(hs.challenge))
        return host, client.session, client, hs
    def test_authentication_and_roundtrip(self):
        host, client, _, _ = self.pair()
        self.assertEqual(host.decrypt_json(client.encrypt_json({'type':'PING','ts':123})), {'type':'PING','ts':123})
        self.assertEqual(client.decrypt_json(host.encrypt_json({'type':'PONG','ts':123})), {'type':'PONG','ts':123})
    def test_token_not_in_handshake(self):
        c = PythonClient(self.identity); hs = self.identity.begin_handshake(c.hello)
        raw = json.dumps([c.hello, hs.challenge, c.challenge(hs.challenge)])
        self.assertNotIn(self.identity.pairing_secret, raw)
        self.assertNotIn('"token"', raw)
    def test_legacy_hello_rejected(self):
        for msg in ({'type':'HELLO','token':self.identity.pairing_secret}, {'type':'HELLO','e2ee':False}, {'type':'HELLO','v':1}, []):
            with self.subTest(msg=msg), self.assertRaises(ProtocolError): self.identity.begin_handshake(msg)
    def test_wrong_credential_rejected(self):
        c = PythonClient(self.identity); hs = self.identity.begin_handshake(c.hello)
        with self.assertRaises(ProtocolError): hs.finish(c.challenge(hs.challenge, 'Z'*52))
    def test_single_use_authentication(self):
        c = PythonClient(self.identity); hs = self.identity.begin_handshake(c.hello); auth = c.challenge(hs.challenge)
        hs.finish(auth)
        with self.assertRaises(ProtocolError): hs.finish(auth)
    def test_bad_auth_cannot_retry(self):
        c=PythonClient(self.identity); hs=self.identity.begin_handshake(c.hello)
        good=c.challenge(hs.challenge); bad={**good,'proof':b64(b'\0'*32)}
        with self.assertRaises(ProtocolError): hs.finish(bad)
        with self.assertRaises(ProtocolError): hs.finish(good)
    def test_expired_handshake(self):
        c=PythonClient(self.identity); hs=self.identity.begin_handshake(c.hello); auth=c.challenge(hs.challenge)
        hs.expires_at=0
        with self.assertRaises(ProtocolError): hs.finish(auth)
    def test_replay_rejected(self):
        host, client, _, _ = self.pair(); frame=client.encrypt_json({'type':'PING'})
        host.decrypt_json(frame)
        with self.assertRaises(ProtocolError): host.decrypt_json(frame)
    def test_reorder_rejected(self):
        host, client, _, _=self.pair(); client.encrypt_json({'type':'PING'}); second=client.encrypt_json({'type':'PING'})
        with self.assertRaises(ProtocolError): host.decrypt_json(second)
    def test_reflection_rejected(self):
        host, client, _, _=self.pair()
        with self.assertRaises(ProtocolError): host.decrypt_json(host.encrypt_json({'type':'PING'}))
    def test_replayed_hello_has_new_session(self):
        c=PythonClient(self.identity); hs1=self.identity.begin_handshake(c.hello); hs2=self.identity.begin_handshake(c.hello)
        self.assertNotEqual(hs1.challenge['server_nonce'], hs2.challenge['server_nonce'])
        self.assertNotEqual(hs1.challenge['server_pub'], hs2.challenge['server_pub'])
        auth1=c.challenge(hs1.challenge)
        with self.assertRaises(ProtocolError): hs2.finish(auth1)
    def test_old_ciphertext_rejected_in_new_session(self):
        host1,c1,_,_=self.pair(); frame=c1.encrypt_json({'type':'PING'})
        host2,_,_,_=self.pair()
        with self.assertRaises(ProtocolError): host2.decrypt_json(frame)
    def test_no_plaintext_frame(self):
        host,client,_,_=self.pair()
        for frame in ({'type':'TERMINAL_INPUT'}, {'iv':'a','data':'b'}, {}, [], {'v':2,'seq':True,'data':''}):
            with self.subTest(frame=frame), self.assertRaises(ProtocolError): host.decrypt_json(frame)
    def test_tamper_does_not_advance_counter(self):
        host,client,_,_=self.pair(); good=client.encrypt_json({'type':'PING'}); bad={**good,'data':b64(b'\0'*32)}
        with self.assertRaises(ProtocolError): host.decrypt_json(bad)
        self.assertEqual(host.decrypt_json(good)['type'],'PING')
    def test_session_limit(self):
        host,client,_,_=self.pair(); client._tx_seq=MAX_SEQUENCE
        with self.assertRaises(ProtocolError): client.encrypt_json({'type':'PING'})
    def test_invalid_public_point(self):
        c=PythonClient(self.identity); c.hello['client_pub']=b64(b'\4'+b'\0'*64)
        with self.assertRaises(ProtocolError): self.identity.begin_handshake(c.hello)
    def test_protocol_copies_identical(self):
        self.assertEqual((SOURCE/'viber-host/core/crypto.py').read_bytes(), (SOURCE/'viber-server/secure_protocol.py').read_bytes())

class StorageAndPathTests(unittest.TestCase):
    def test_config_private_and_stable(self):
        with tempfile.TemporaryDirectory() as td:
            config=HostConfig(td); original=config.host_id; token=config.pairing_secret
            if os.name=='posix': self.assertEqual(stat.S_IMODE(Path(config.config_file).stat().st_mode),0o600)
            again=HostConfig(td); self.assertEqual(again.host_id,original); self.assertEqual(again.pairing_secret,token)
            self.assertEqual(again.generate_pairing_payload({})['v'],2)
    def test_legacy_credentials_rotated(self):
        with tempfile.TemporaryDirectory() as td:
            old=HostKeyManager(); key=b64(old._private_key.private_numbers().private_value.to_bytes(32,'big'))
            (Path(td)/'viber_config.json').write_text(json.dumps({'private_key_b64':key,'pairing_secret':'OLD-TOKEN'}))
            new=HostConfig(td)
            self.assertNotEqual(new.key_manager.public_key_b64,old.public_key_b64)
            self.assertNotEqual(new.pairing_secret,'OLD-TOKEN')
    @unittest.skipUnless(os.name=='posix','POSIX permissions')
    def test_config_symlink_refused(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td); victim=p/'victim'; victim.write_text('{}'); (p/'viber_config.json').symlink_to(victim)
            with self.assertRaises(PermissionError): HostConfig(td)
            self.assertEqual(victim.read_text(),'{}')
    @unittest.skipUnless(os.name=='posix','POSIX permissions')
    def test_config_hardlink_refused(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td); (p/'victim').write_text('{}'); os.link(p/'victim',p/'viber_config.json')
            with self.assertRaises(PermissionError): HostConfig(td)
    def test_invalid_config_not_silently_overwritten(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'viber_config.json'; path.write_text('broken')
            with self.assertRaises(ValueError): HostConfig(td)
            self.assertEqual(path.read_text(),'broken')
    @unittest.skipUnless(os.name=='posix','POSIX permissions')
    def test_config_group_writable_directory_auto_tightened(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            p.chmod(0o775)
            self.assertTrue(bool(p.stat().st_mode & 0o020))
            config = HostConfig(td)
            self.assertIsNotNone(config.host_id)
            self.assertFalse(bool(p.stat().st_mode & 0o022))
    def test_is_safe_config_dir(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            self.assertTrue(is_safe_config_dir(p))
            symlink = p / 'symlink_dir'
            symlink.symlink_to(p, target_is_directory=True)
            self.assertFalse(is_safe_config_dir(symlink))
    def test_static_regular_file(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td); (p/'index.html').write_bytes(b'OK')
            self.assertEqual(static_bytes(p,'index.html'),b'OK')
    def test_prefix_sibling_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td); (p/'dist').mkdir(); (p/'dist_old').mkdir(); (p/'dist_old/secret').write_text('secret')
            with self.assertRaises(PermissionError): static_bytes(p/'dist','../dist_old/secret')
    def test_static_symlink_refused(self):
        with tempfile.TemporaryDirectory() as td:
            p=Path(td); (p/'dist').mkdir(); (p/'secret').write_text('secret'); (p/'dist/link').symlink_to(p/'secret')
            with self.assertRaises(OSError): static_bytes(p/'dist','link')
    def test_origin_normalization_and_rejection(self):
        self.assertEqual(normalize_origin('https://Example.COM:443'),'https://example.com')
        for value in ('*','null','https://*.example.com','https://example.com/path','http://u:p@example.com'):
            with self.subTest(value=value), self.assertRaises(ValueError): normalize_origin(value)
    def test_tailscale_range_is_exact(self):
        for ip in ('100.64.0.1','100.127.255.254','fd7a:115c:a1e0::1'): self.assertTrue(SystemMonitor.is_tailscale_ip(ip))
        for ip in ('100.1.1.1','100.128.0.1','100.255.1.1','not-an-ip','::1'): self.assertFalse(SystemMonitor.is_tailscale_ip(ip))
    def test_remote_plaintext_relay_refused(self):
        self.assertEqual(validate_relay_url('ws://127.0.0.1:8766/'),'ws://127.0.0.1:8766')
        for url in ('ws://relay.example','http://relay.example','wss://user:pass@example.com'):
            with self.assertRaises(ValueError): validate_relay_url(url)
    def test_registry_duplicate_and_stale_cleanup(self):
        registry=HostRegistry(); identity=HostKeyManager(); hid=host_id_for(identity.public_key_b64); old=object(); new=object()
        registry.register(hid,'name',8765,identity.public_key_b64,{},old)
        with self.assertRaises(RelayProtocolError): registry.register(hid,'name',8765,identity.public_key_b64,{},new)
        self.assertIs(registry.get_host(hid).connection,old)
        # Simulate a stale cleanup record to enforce owner-aware deletion.
        registry.hosts[hid].connection=new
        registry.unregister_connection(old)
        self.assertIs(registry.get_host(hid).connection,new)

    # --- RV-03: Profile permissions, atomic write, migration ---
    @unittest.skipUnless(os.name == 'posix', 'POSIX permissions')
    def test_profile_permissions_0600_under_loose_umask(self):
        old_umask = os.umask(0o022)
        try:
            with tempfile.TemporaryDirectory() as td:
                mgr = AgentManager(td)
                profile = mgr.save_profile({
                    'name': 'SecureEnvProfile',
                    'command': 'bash',
                    'env': {'SUPER_SECRET_KEY': '12345-secret'}
                })
                profile_path = Path(td) / 'viber_profiles.json'
                self.assertTrue(profile_path.exists())
                mode = stat.S_IMODE(profile_path.stat().st_mode)
                self.assertEqual(mode, 0o600)
                
                mgr2 = AgentManager(td)
                loaded = mgr2.list_profiles()
                self.assertEqual(len(loaded), 1)
                self.assertEqual(loaded[0]['env']['SUPER_SECRET_KEY'], '12345-secret')
        finally:
            os.umask(old_umask)

    @unittest.skipUnless(os.name == 'posix', 'POSIX permissions')
    def test_profile_auto_migration_from_0644_to_0600(self):
        with tempfile.TemporaryDirectory() as td:
            profile_path = Path(td) / 'viber_profiles.json'
            profile_path.write_text(json.dumps([{'id': 'p1', 'name': 'MigrateMe', 'command': 'bash', 'env': {'SECRET': 'xyz'}}]))
            profile_path.chmod(0o644)
            self.assertEqual(stat.S_IMODE(profile_path.stat().st_mode), 0o644)
            
            mgr = AgentManager(td)
            self.assertEqual(stat.S_IMODE(profile_path.stat().st_mode), 0o600)
            profiles = mgr.list_profiles()
            self.assertEqual(len(profiles), 1)
            self.assertEqual(profiles[0]['env']['SECRET'], 'xyz')

    @unittest.skipUnless(os.name == 'posix', 'POSIX permissions')
    def test_profile_symlink_refused(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)
            victim = p / 'victim.txt'
            victim.write_text('SAFE_VICTIM_CONTENT')
            symlink = p / 'viber_profiles.json'
            symlink.symlink_to(victim)
            
            with self.assertRaises(PermissionError):
                AgentManager(td)
            self.assertEqual(victim.read_text(), 'SAFE_VICTIM_CONTENT')

    def test_profile_atomic_write_preserves_original_on_failure(self):
        with tempfile.TemporaryDirectory() as td:
            mgr = AgentManager(td)
            mgr.save_profile({'name': 'OriginalProfile', 'command': 'bash'})
            profile_path = Path(td) / 'viber_profiles.json'
            original_content = profile_path.read_text()
            
            class NonSerializable:
                pass
            with self.assertRaises(TypeError):
                write_private_json(profile_path, [{'bad': NonSerializable()}])
            
            self.assertEqual(profile_path.read_text(), original_content)

    def test_write_private_json_rejects_oversized(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / 'oversized.json'
            oversized_data = [{'big': 'x' * (600 * 1024)}]
            with self.assertRaises(ValueError):
                write_private_json(p, oversized_data)

    # --- RV-04: HostLock and offline credential rotation coordination ---
    def test_host_lock_mutual_exclusion(self):
        with tempfile.TemporaryDirectory() as td:
            lock1 = HostLock(td)
            self.assertTrue(lock1.acquire())
            self.assertTrue(HostLock.is_locked(td))
            
            lock2 = HostLock(td)
            self.assertFalse(lock2.acquire())
            
            lock1.release()
            self.assertFalse(HostLock.is_locked(td))
            
            self.assertTrue(lock2.acquire())
            lock2.release()
            self.assertFalse(HostLock.is_locked(td))

    def test_offline_rotation_refused_while_host_locked(self):
        with tempfile.TemporaryDirectory() as td:
            config = HostConfig(td)
            original_secret = config.pairing_secret
            original_pub = config.key_manager.public_key_b64
            
            daemon_lock = HostLock(td)
            self.assertTrue(daemon_lock.acquire())
            self.assertTrue(HostLock.is_locked(td))
            
            env = {**os.environ, 'VIBER_CONFIG_DIR': td}
            proc = subprocess.run([sys.executable, str(SOURCE / 'viber-host/main.py'), '--rotate-credentials'],
                                  capture_output=True, text=True, env=env)
            self.assertNotEqual(proc.returncode, 0)
            self.assertIn("无法在 Host 守护进程运行期间执行离线凭据轮换", proc.stderr + proc.stdout)
            
            disk_config = HostConfig(td)
            self.assertEqual(disk_config.pairing_secret, original_secret)
            self.assertEqual(disk_config.key_manager.public_key_b64, original_pub)
            
            daemon_lock.release()
            self.assertFalse(HostLock.is_locked(td))
            
            proc2 = subprocess.run([sys.executable, str(SOURCE / 'viber-host/main.py'), '--rotate-credentials'],
                                   capture_output=True, text=True, env=env)
            self.assertEqual(proc2.returncode, 0)
            
            rotated_config = HostConfig(td)
            self.assertNotEqual(rotated_config.pairing_secret, original_secret)
            self.assertNotEqual(rotated_config.key_manager.public_key_b64, original_pub)

    def test_host_lock_released_on_process_crash(self):
        with tempfile.TemporaryDirectory() as td:
            code = (
                f"import sys, os; sys.path.insert(0, '{SOURCE}/viber-host'); "
                f"from core.config import HostLock; "
                f"lock = HostLock(r'{td}'); "
                f"assert lock.acquire(); "
                f"print('LOCKED', flush=True); "
                f"import time; time.sleep(30)"
            )
            proc = subprocess.Popen([sys.executable, '-c', code], stdout=subprocess.PIPE, text=True)
            try:
                line = proc.stdout.readline()
                self.assertIn('LOCKED', line)
                self.assertTrue(HostLock.is_locked(td))
                proc.kill()
                proc.wait()
                self.assertFalse(HostLock.is_locked(td))
                new_lock = HostLock(td)
                self.assertTrue(new_lock.acquire())
                new_lock.release()
            finally:
                if proc.stdout:
                    proc.stdout.close()
                if proc.poll() is None:
                    proc.kill()
                    proc.wait()

    # --- RV-02: Android Manifest verification ---
    def test_android_manifest_no_external_pairing_scheme(self):
        manifest_path = SOURCE / 'viber-android/app/src/main/AndroidManifest.xml'
        if manifest_path.exists():
            import xml.etree.ElementTree as ET
            tree = ET.parse(manifest_path)
            root = tree.getroot()
            for data_elem in root.iter('data'):
                scheme = data_elem.attrib.get('{http://schemas.android.com/apk/res/android}scheme')
                self.assertNotEqual(scheme, 'viber', 'AndroidManifest.xml must not expose viber:// scheme to external intents')

class RouterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.identity=HostKeyManager(); self.raw=[]; self.manager=Manager()
        async def send(raw): self.raw.append(json.loads(raw))
        self.state=ClientConnectionState(self.identity,self.manager,Monitor(),send,is_trusted_network=True)
    async def asyncTearDown(self): self.state.cleanup()
    async def authenticate(self):
        client=PythonClient(self.identity)
        await self.state.handle_raw_message(json.dumps(client.hello))
        await self.state.handle_raw_message(json.dumps(client.challenge(self.raw[-1])))
        self.assertEqual(client.session.decrypt_json(self.raw[-1])['type'],'WELCOME')
        return client
    async def test_trusted_network_flag_never_authorizes(self):
        self.assertFalse(self.state.is_trusted_network)
        with self.assertRaises(ProtocolError): await self.state.handle_raw_message(json.dumps({'type':'HELLO','token':'','e2ee':False}))
        self.assertFalse(self.state.is_authenticated)
    async def test_stats_after_authentication(self):
        client=await self.authenticate()
        await self.state.handle_raw_message(json.dumps(client.session.encrypt_json({'type':'GET_STATS'})))
        self.assertEqual(client.session.decrypt_json(self.raw[-1])['type'],'STATS')
    async def test_plaintext_after_auth_is_rejected(self):
        await self.authenticate()
        with self.assertRaises(ProtocolError): await self.state.handle_raw_message('{"type":"GET_STATS"}')
    async def test_replayed_request_rejected(self):
        client=await self.authenticate(); frame=json.dumps(client.session.encrypt_json({'type':'GET_STATS'}))
        await self.state.handle_raw_message(frame); before=len(self.raw)
        with self.assertRaises(ProtocolError): await self.state.handle_raw_message(frame)
        self.assertEqual(len(self.raw),before)
    async def test_parallel_sends_are_ordered(self):
        client=await self.authenticate(); start=len(self.raw)
        await asyncio.gather(*(self.state.send_encrypted({'type':'PONG','ts':i}) for i in range(40)))
        received=[client.session.decrypt_json(frame)['ts'] for frame in self.raw[start:]]
        self.assertEqual(received,list(range(40)))
    async def test_expired_initial_connection(self):
        self.state._deadline=0
        with self.assertRaises(ProtocolError): await self.state.handle_raw_message(json.dumps(PythonClient(self.identity).hello))
    async def test_output_queue_is_bounded(self):
        await self.authenticate(); self.state.attached_session_id='s'; self.state._replaying=True
        for i in range(1000): self.state._on_terminal_output('s',i,b'x'*4096)
        self.assertLessEqual(len(self.state._outbox),64)
        self.assertLessEqual(self.state._outbox_bytes,256*1024)
        self.assertTrue(self.state._overflowed)

    async def test_replay_snapshot_does_not_duplicate_concurrent_output(self):
        client = await self.authenticate()
        state = self.state
        class Session:
            def __init__(self): self.buffer = self; self.callback = None
            def subscribe(self, callback): self.callback = callback
            def unsubscribe(self, callback): self.callback = None
            def to_dict(self): return {'session_id':'s'}
            def snapshot_since(self, last):
                # Output arrives after subscribing but is also included in replay.
                self.callback('s',1,b'first')
                return True, [(1,b'first')], 1
        session=Session(); self.manager.get_session=lambda sid: session if sid=='s' else None
        start=len(self.raw)
        await state._attach_session('s',0)
        await asyncio.sleep(0)
        session.callback('s',2,b'live')
        await asyncio.sleep(.02)
        messages=[client.session.decrypt_json(frame) for frame in self.raw[start:]]
        self.assertEqual([m['seq'] for m in messages if m['type']=='TERMINAL_OUTPUT'],[1,2])

    async def test_attach_session_default_truncation(self):
        client = await self.authenticate()
        state = self.state
        from core.session import TerminalRingBuffer
        class RealBufferSession:
            def __init__(self):
                self.buffer = TerminalRingBuffer(max_bytes=1024*1024)
                self.callback = None
            def subscribe(self, callback): self.callback = callback
            def unsubscribe(self, callback): self.callback = None
            def to_dict(self): return {'session_id':'s_trunc'}
        session = RealBufferSession()
        # Add 20 chunks of 10 KB each (total ~200 KiB > default truncated limit of 64 KiB)
        for i in range(20):
            session.buffer.append(f"chunk_{i:02d}_".encode() * 1000)
        self.manager.get_session = lambda sid: session if sid == 's_trunc' else None

        # 1. Default attach (full_history=False)
        start = len(self.raw)
        await state.handle_raw_message(json.dumps(client.session.encrypt_json({'type': 'ATTACH_SESSION', 'session_id': 's_trunc', 'last_seq': 0})))
        await asyncio.sleep(0.01)
        messages = [client.session.decrypt_json(frame) for frame in self.raw[start:]]
        attached = next(m for m in messages if m['type'] == 'SESSION_ATTACHED')
        self.assertTrue(attached.get('is_truncated'))
        self.assertFalse(attached.get('full_history'))
        replayed_chunks = [m for m in messages if m['type'] == 'TERMINAL_OUTPUT']
        self.assertLess(len(replayed_chunks), 20)
        self.assertGreater(len(replayed_chunks), 0)

        # 2. Explicit full_history=True attach
        start = len(self.raw)
        await state.handle_raw_message(json.dumps(client.session.encrypt_json({'type': 'ATTACH_SESSION', 'session_id': 's_trunc', 'last_seq': 0, 'full_history': True})))
        await asyncio.sleep(0.01)
        messages = [client.session.decrypt_json(frame) for frame in self.raw[start:]]
        attached_full = next(m for m in messages if m['type'] == 'SESSION_ATTACHED')
        self.assertFalse(attached_full.get('is_truncated'))
        self.assertTrue(attached_full.get('full_history'))
        replayed_chunks_full = [m for m in messages if m['type'] == 'TERMINAL_OUTPUT']
        self.assertEqual(len(replayed_chunks_full), 20)


class DirectIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.config=HostConfig(self.temp.name); self.config.direct_port=0
        self.server=DirectServer(self.config,Manager(),Monitor(),str(Path(self.temp.name)/'missing'))
        await self.server.start(); self.port=self.config.direct_port
    async def asyncTearDown(self):
        await self.server.stop(); self.temp.cleanup()
    async def pair(self,ws):
        c=PythonClient(self.config.key_manager); await ws.send(json.dumps(c.hello)); challenge=json.loads(await ws.recv())
        await ws.send(json.dumps(c.challenge(challenge)))
        self.assertEqual(c.session.decrypt_json(json.loads(await ws.recv()))['type'],'WELCOME')
        return c
    async def test_real_websocket_roundtrip(self):
        async with connect(f'ws://127.0.0.1:{self.port}/ws',origin=f'http://127.0.0.1:{self.port}') as ws:
            c=await self.pair(ws); await ws.send(json.dumps(c.session.encrypt_json({'type':'PING','ts':42})))
            self.assertEqual(c.session.decrypt_json(json.loads(await ws.recv())),{'type':'PONG','ts':42})
    async def test_foreign_origin_rejected_before_handshake(self):
        with self.assertRaises(InvalidStatus) as cm:
            async with connect(f'ws://127.0.0.1:{self.port}/ws',origin='https://evil.example'): pass
        self.assertEqual(cm.exception.response.status_code,403)
    async def test_null_origin_rejected(self):
        with self.assertRaises(InvalidStatus):
            async with connect(f'ws://127.0.0.1:{self.port}/ws',origin='null'): pass
    async def test_replay_closes_real_socket(self):
        async with connect(f'ws://127.0.0.1:{self.port}/ws') as ws:
            c=await self.pair(ws); raw=json.dumps(c.session.encrypt_json({'type':'PING'}))
            await ws.send(raw); await ws.recv(); await ws.send(raw)
            with self.assertRaises(ConnectionClosed): await ws.recv()
            self.assertEqual(ws.close_code,1008)
    async def test_legacy_localhost_client_is_not_authorized(self):
        async with connect(f'ws://127.0.0.1:{self.port}/ws') as ws:
            await ws.send('{"type":"HELLO","e2ee":false,"token":""}')
            with self.assertRaises(ConnectionClosed): await ws.recv()
            self.assertEqual(ws.close_code,1008)
    async def request(self,path,host=None):
        reader,writer=await asyncio.open_connection('127.0.0.1',self.port)
        writer.write(f'GET {path} HTTP/1.1\r\nHost: {host or "127.0.0.1:"+str(self.port)}\r\nConnection: close\r\n\r\n'.encode()); await writer.drain()
        raw=await reader.read(); writer.close(); await writer.wait_closed(); return raw.decode()
    async def test_pairing_endpoint_never_reveals_secret(self):
        text=await self.request('/api/pairing'); self.assertIn('403',text.splitlines()[0]); self.assertNotIn(self.config.pairing_secret,text)
        self.assertNotIn('Access-Control-Allow-Origin',text)
    async def test_forged_host_header_refused(self):
        text=await self.request('/api/pairing','attacker.tailscale.example'); self.assertIn('403',text.splitlines()[0])
        self.assertNotIn(self.config.pairing_secret,text)
    async def test_direct_url_and_reverse_proxy_https_origin_accepted(self):
        self.config.direct_url = 'https://viber.example.com'
        self.server._refresh_origins()
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port)
        req = (f'GET / HTTP/1.1\r\n'
               f'Host: viber.example.com\r\n'
               f'Origin: https://viber.example.com\r\n'
               f'Connection: close\r\n\r\n').encode()
        writer.write(req); await writer.drain()
        raw = await reader.read()
        writer.close(); await writer.wait_closed()
        status = raw.decode().splitlines()[0]
        self.assertNotIn('403', status)
        self.assertIn('200', status)
    async def test_tls_direct_server_roundtrip(self):
        tls_ctx = get_or_create_tls_context(self.config, ['localhost', '127.0.0.1'])
        tls_cfg = HostConfig(self.temp.name)
        tls_cfg.direct_port = 0
        tls_server = DirectServer(tls_cfg, Manager(), Monitor(), str(Path(self.temp.name) / 'missing'), ssl_context=tls_ctx)
        await tls_server.start()
        tls_port = tls_cfg.direct_port
        client_ctx = ssl.create_default_context()
        client_ctx.check_hostname = False
        client_ctx.verify_mode = ssl.CERT_NONE
        try:
            async with connect(f'wss://127.0.0.1:{tls_port}/ws', ssl=client_ctx, origin=f'https://127.0.0.1:{tls_port}') as ws:
                c = await self.pair(ws)
                await ws.send(json.dumps(c.session.encrypt_json({'type': 'PING', 'ts': 99})))
                self.assertEqual(c.session.decrypt_json(json.loads(await ws.recv())), {'type': 'PONG', 'ts': 99})
        finally:
            await tls_server.stop()

class RelayIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.config=HostConfig(self.temp.name)
        self.server=RelayServer('127.0.0.1',0); await self.server.start()
        self.config.relay_url=f'ws://127.0.0.1:{self.server.port}'
        self.bridge=RelayClient(self.config,Manager(),Monitor()); self.bridge.start()
        for _ in range(100):
            if self.server.registry.get_host(self.config.host_id): break
            await asyncio.sleep(.01)
        self.assertIsNotNone(self.server.registry.get_host(self.config.host_id))
        self.url=f'{self.config.relay_url}/connect/client?host_id={self.config.host_id}'
    async def asyncTearDown(self):
        self.bridge.stop()
        if self.bridge._task: await asyncio.gather(self.bridge._task,return_exceptions=True)
        await self.server.stop(); self.temp.cleanup()
    async def pair(self,ws):
        c=PythonClient(self.config.key_manager); await ws.send(json.dumps(c.hello)); challenge=json.loads(await ws.recv())
        await ws.send(json.dumps(c.challenge(challenge)))
        welcome=c.session.decrypt_json(json.loads(await ws.recv())); self.assertEqual(welcome['type'],'WELCOME'); return c
    async def test_two_simultaneous_clients_have_separate_keys_and_routes(self):
        async with connect(self.url) as ws1, connect(self.url) as ws2:
            c1,c2=await asyncio.gather(self.pair(ws1),self.pair(ws2))
            await ws1.send(json.dumps(c1.session.encrypt_json({'type':'PING','ts':1})))
            self.assertEqual(c1.session.decrypt_json(json.loads(await ws1.recv()))['ts'],1)
            with self.assertRaises(asyncio.TimeoutError): await asyncio.wait_for(ws2.recv(),.08)
            await ws2.send(json.dumps(c2.session.encrypt_json({'type':'PING','ts':2})))
            self.assertEqual(c2.session.decrypt_json(json.loads(await ws2.recv()))['ts'],2)
    async def test_unauthed_subscriber_does_not_receive_welcome(self):
        async with connect(self.url) as observer, connect(self.url) as client:
            await self.pair(client)
            with self.assertRaises(asyncio.TimeoutError): await asyncio.wait_for(observer.recv(),.08)
    async def test_disconnect_removes_only_own_state(self):
        async with connect(self.url) as ws1, connect(self.url) as ws2:
            c1,c2=await asyncio.gather(self.pair(ws1),self.pair(ws2)); await ws1.close()
            await asyncio.sleep(.03); self.assertEqual(len(self.bridge.peers),1)
            await ws2.send(json.dumps(c2.session.encrypt_json({'type':'PING','ts':9})))
            self.assertEqual(c2.session.decrypt_json(json.loads(await ws2.recv()))['ts'],9)
    async def test_unsigned_registration_does_not_replace_host(self):
        original=self.server.registry.get_host(self.config.host_id).connection
        async with connect(self.config.relay_url+'/register/host') as rogue:
            await rogue.recv()
            await rogue.send(json.dumps({'type':'REGISTER_HOST','host_id':self.config.host_id}))
            with self.assertRaises(ConnectionClosed): await rogue.recv()
        self.assertIs(self.server.registry.get_host(self.config.host_id).connection,original)
    async def test_duplicate_signed_registration_refused(self):
        original=self.server.registry.get_host(self.config.host_id).connection
        async with connect(self.config.relay_url+'/register/host') as duplicate:
            challenge=json.loads(await duplicate.recv())
            await duplicate.send(json.dumps(self.config.key_manager.sign_registration(challenge['challenge'])))
            with self.assertRaises(ConnectionClosed): await duplicate.recv()
        self.assertIs(self.server.registry.get_host(self.config.host_id).connection,original)

    # --- RV-01: Envelope expansion & isolation tests ---
    async def test_client_oversized_payload_rejected_isolated(self):
        async with connect(self.url) as ws1, connect(self.url) as ws2:
            c2 = await self.pair(ws2)
            oversized_raw = "A" * (MAX_CLIENT_FRAME_BYTES + 1024)
            await ws1.send(oversized_raw)
            with self.assertRaises(ConnectionClosed) as cm:
                await ws1.recv()
            code = cm.exception.rcvd.code if hasattr(cm.exception, 'rcvd') and cm.exception.rcvd else ws1.close_code
            self.assertEqual(code, 1009)
            
            # Shared host remains connected and registered
            self.assertIsNotNone(self.server.registry.get_host(self.config.host_id))
            
            # ws2 continues unaffected
            await ws2.send(json.dumps(c2.session.encrypt_json({'type': 'PING', 'ts': 123})))
            res = c2.session.decrypt_json(json.loads(await ws2.recv()))
            self.assertEqual(res['type'], 'PONG')
            self.assertEqual(res['ts'], 123)

    async def test_client_escape_expansion_isolation(self):
        async with connect(self.url) as ws1, connect(self.url) as ws2:
            c1, c2 = await asyncio.gather(self.pair(ws1), self.pair(ws2))
            escape_text = '\\"\\n\\r\\t' * 1000 + "中文测试" * 1000
            await ws1.send(json.dumps(c1.session.encrypt_json({'type': 'PING', 'ts': 111, 'data': escape_text})))
            res1 = c1.session.decrypt_json(json.loads(await ws1.recv()))
            self.assertEqual(res1['type'], 'PONG')
            self.assertEqual(res1['ts'], 111)
            
            # Now ws1 sends payload exceeding client frame limit
            await ws1.send(json.dumps({'type': 'OVERSIZED', 'data': 'x' * (MAX_CLIENT_FRAME_BYTES + 10)}))
            with self.assertRaises(ConnectionClosed) as cm:
                await ws1.recv()
            code = cm.exception.rcvd.code if hasattr(cm.exception, 'rcvd') and cm.exception.rcvd else ws1.close_code
            self.assertEqual(code, 1009)
            
            # Shared host is still registered and active
            self.assertIsNotNone(self.server.registry.get_host(self.config.host_id))
            
            # ws2 can still complete message roundtrip
            await ws2.send(json.dumps(c2.session.encrypt_json({'type': 'PING', 'ts': 999})))
            res2 = c2.session.decrypt_json(json.loads(await ws2.recv()))
            self.assertEqual(res2['type'], 'PONG')
            self.assertEqual(res2['ts'], 999)

    async def test_unauthenticated_client_oversized_frame_rejected(self):
        async with connect(self.url) as bad_client, connect(self.url) as good_client:
            await bad_client.send("X" * (MAX_CLIENT_FRAME_BYTES + 2048))
            with self.assertRaises(ConnectionClosed) as cm:
                await bad_client.recv()
            code = cm.exception.rcvd.code if hasattr(cm.exception, 'rcvd') and cm.exception.rcvd else bad_client.close_code
            self.assertEqual(code, 1009)
            
            c = await self.pair(good_client)
            await good_client.send(json.dumps(c.session.encrypt_json({'type': 'PING', 'ts': 777})))
            res = c.session.decrypt_json(json.loads(await good_client.recv()))
            self.assertEqual(res['type'], 'PONG')
            self.assertEqual(res['ts'], 777)
            self.assertIsNotNone(self.server.registry.get_host(self.config.host_id))

    async def test_near_limit_multibyte_utf8_relayed(self):
        async with connect(self.url) as ws:
            c = await self.pair(ws)
            # Create a UTF-8 payload with multi-byte Chinese chars that produces ~400 KiB wire frame
            chunk = "测试中文数据字符串" * 10
            repeat = (180 * 1024) // len(chunk.encode('utf-8'))
            payload_str = chunk * repeat
            wire_msg = json.dumps(c.session.encrypt_json({'type': 'PING', 'ts': 888, 'content': payload_str}))
            self.assertGreater(len(wire_msg.encode('utf-8')), 350 * 1024)
            self.assertLess(len(wire_msg.encode('utf-8')), MAX_CLIENT_FRAME_BYTES)
            await ws.send(wire_msg)
            res = c.session.decrypt_json(json.loads(await ws.recv()))
            self.assertEqual(res['type'], 'PONG')
            self.assertEqual(res['ts'], 888)

@unittest.skipUnless(shutil.which('node'),'Node.js is required for JS interop tests')
class JavaScriptInteropTests(unittest.TestCase):
    def setUp(self):
        self.identity=HostKeyManager()
        env={**os.environ,'VIBER_SOURCE_ROOT':str(SOURCE)}
        self.process=subprocess.Popen(['node',str(TEST_DIR/'js_peer.mjs')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    def tearDown(self):
        self.process.stdin.close()
        self.process.wait(timeout=5)
        self.process.stdout.close(); self.process.stderr.close()
    def rpc(self,op,**kw):
        self.process.stdin.write(json.dumps({'op':op,**kw})+'\n'); self.process.stdin.flush()
        raw=self.process.stdout.readline()
        self.assertTrue(raw,'Node process terminated')
        return json.loads(raw)
    def handshake(self):
        hello=self.rpc('hello',config={'hostPub':self.identity.public_key_b64,'token':self.identity.pairing_secret})['result']
        hs=self.identity.begin_handshake(hello)
        response=self.rpc('challenge',data=hs.challenge); self.assertTrue(response['ok'],response)
        host=hs.finish(response['result'])
        welcome=host.encrypt_json({'type':'WELCOME','v':2,'status':'authenticated','e2ee':True})
        self.assertTrue(self.rpc('decrypt',data=welcome)['ok'])
        return host,hs
    def test_js_python_bidirectional_interop(self):
        host,_=self.handshake()
        frame=self.rpc('encrypt',data={'type':'PING','ts':7})['result']
        self.assertEqual(host.decrypt_json(frame),{'type':'PING','ts':7})
        result=self.rpc('decrypt',data=host.encrypt_json({'type':'PONG','ts':7}))
        self.assertEqual(result['result'],{'type':'PONG','ts':7})
    def test_js_rejects_plaintext_after_auth(self):
        self.handshake(); self.assertFalse(self.rpc('decrypt',data={'type':'TERMINAL_OUTPUT','data':'spoof'})['ok'])
    def test_js_rejects_replay(self):
        host,_=self.handshake(); frame=host.encrypt_json({'type':'PONG'})
        self.assertTrue(self.rpc('decrypt',data=frame)['ok']); self.assertFalse(self.rpc('decrypt',data=frame)['ok'])
    def test_js_rejects_wrong_host_pin(self):
        hello=self.rpc('hello',config={'hostPub':self.identity.public_key_b64,'token':self.identity.pairing_secret})['result']
        other=HostKeyManager(); challenge=other.begin_handshake(hello).challenge
        self.assertFalse(self.rpc('challenge',data=challenge)['ok'])
    def test_js_rejects_forged_identity_signature(self):
        hello=self.rpc('hello',config={'hostPub':self.identity.public_key_b64,'token':self.identity.pairing_secret})['result']
        hs=self.identity.begin_handshake(hello); challenge={**hs.challenge,'signature':b64(b'\0'*64)}
        self.assertFalse(self.rpc('challenge',data=challenge)['ok'])
    def test_js_refuses_missing_pairing_pin(self):
        self.assertFalse(self.rpc('hello',config={'token':self.identity.pairing_secret})['ok'])
    def test_js_refuses_business_before_authenticated_welcome(self):
        hello=self.rpc('hello',config={'hostPub':self.identity.public_key_b64,'token':self.identity.pairing_secret})['result']
        hs=self.identity.begin_handshake(hello); auth=self.rpc('challenge',data=hs.challenge)['result']; host=hs.finish(auth)
        self.assertFalse(self.rpc('decrypt',data=host.encrypt_json({'type':'TERMINAL_OUTPUT'}))['ok'])



@unittest.skipUnless(shutil.which('kotlinc') and shutil.which('java'), 'JDK and kotlinc required for Kotlin protocol tests')
class KotlinInteropTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.jar = Path(cls.temp.name) / 'peer.jar'
        core = SOURCE / 'viber-android/app/src/main/java/com/remoteviber/client/network/ProtocolV2.kt'
        result = subprocess.run(['kotlinc', str(core), str(TEST_DIR/'KotlinPeer.kt'), '-include-runtime', '-d', str(cls.jar)], capture_output=True, text=True, timeout=90)
        if result.returncode: raise RuntimeError(result.stderr)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def setUp(self):
        self.identity = HostKeyManager()
        self.process = subprocess.Popen(['java', '-jar', str(self.jar)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    def tearDown(self):
        self.process.stdin.close(); self.process.wait(timeout=5)
        self.process.stdout.close(); self.process.stderr.close()
    def rpc(self, *parts):
        self.process.stdin.write('\t'.join(map(str, parts))+'\n'); self.process.stdin.flush()
        raw = self.process.stdout.readline(); self.assertTrue(raw, 'Kotlin process terminated')
        return raw.strip().split('\t')
    def hello(self, pub=None):
        answer = self.rpc('HELLO', pub or self.identity.public_key_b64, self.identity.pairing_secret)
        self.assertEqual(answer[0], 'OK', answer)
        return {'type':'HELLO','v':2,'client_pub':answer[1],'client_nonce':answer[2]}
    def auth(self, c): return self.rpc('AUTH', c['host_pub'], c['server_pub'], c['server_nonce'], c['signature'])
    def handshake(self):
        hs=self.identity.begin_handshake(self.hello())
        answer=self.auth(hs.challenge); self.assertEqual(answer[0], 'OK', answer)
        host=hs.finish({'type':'AUTH','v':2,'proof':answer[1]})
        frame=host.encrypt_json({'type':'WELCOME','v':2,'status':'authenticated','e2ee':True})
        self.assertEqual(self.rpc('DECRYPT',frame['seq'],frame['data'])[0], 'OK')
        self.assertEqual(self.rpc('READY')[0], 'OK')
        return host
    def test_kotlin_python_bidirectional_interop(self):
        host=self.handshake()
        answer=self.rpc('ENCRYPT',b64(b'{"type":"PING","ts":17}'))
        self.assertEqual(answer[0], 'OK', answer)
        self.assertEqual(host.decrypt_json({'v':2,'seq':int(answer[1]),'data':answer[2]}), {'type':'PING','ts':17})
        frame=host.encrypt_json({'type':'PONG','ts':17})
        answer=self.rpc('DECRYPT',frame['seq'],frame['data'])
        self.assertEqual(json.loads(base64.b64decode(answer[1])), {'type':'PONG','ts':17})
    def test_kotlin_wrong_pin_rejected(self):
        hs=HostKeyManager().begin_handshake(self.hello())
        self.assertEqual(self.auth(hs.challenge)[0], 'ERROR')
    def test_kotlin_forged_signature_rejected(self):
        hs=self.identity.begin_handshake(self.hello())
        self.assertEqual(self.auth({**hs.challenge,'signature':b64(b'\0'*64)})[0], 'ERROR')
    def test_kotlin_replay_rejected(self):
        host=self.handshake(); frame=host.encrypt_json({'type':'PONG'})
        self.assertEqual(self.rpc('DECRYPT',frame['seq'],frame['data'])[0], 'OK')
        self.assertEqual(self.rpc('DECRYPT',frame['seq'],frame['data'])[0], 'ERROR')
    def test_kotlin_no_encryption_before_auth(self):
        self.hello(); self.assertEqual(self.rpc('ENCRYPT',b64(b'{"type":"PING"}'))[0], 'ERROR')
    def test_kotlin_reorder_rejected(self):
        host=self.handshake(); host.encrypt_json({'type':'PONG'}); frame=host.encrypt_json({'type':'PONG'})
        self.assertEqual(self.rpc('DECRYPT',frame['seq'],frame['data'])[0], 'ERROR')
    def test_kotlin_invalid_curve_rejected(self):
        self.assertEqual(self.rpc('HELLO',b64(b'\x04'+b'\0'*64),self.identity.pairing_secret)[0], 'ERROR')
    def test_kotlin_fingerprint_matches_python(self):
        self.assertEqual(self.rpc('FINGERPRINT', self.identity.public_key_b64)[1], host_id_for(self.identity.public_key_b64).removeprefix('host-'))

if __name__=='__main__': unittest.main(verbosity=2)
