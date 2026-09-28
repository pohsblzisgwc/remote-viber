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
                         verify_signature, host_id_for, MAX_SEQUENCE)
from core.config import HostConfig, read_private_json
from core.monitor import SystemMonitor
from network.router import ClientConnectionState
from network.direct_server import DirectServer, static_bytes, normalize_origin
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
