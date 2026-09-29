import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
const parent = path.resolve(import.meta.dirname, '..');
const root = process.env.VIBER_SOURCE_ROOT || (fs.existsSync(path.join(parent, 'viber-host')) ? parent : path.join(parent, 'payload'));
const { ViberConnection } = await import(pathToFileURL(path.join(root, 'viber-client/src/services/viber_connection.js')));
const { parsePairingBundle, loadSessionConfig } = await import(pathToFileURL(path.join(root, 'viber-client/src/services/pairing.js')));
const { fingerprint, toBase64 } = await import(pathToFileURL(path.join(root, 'viber-client/src/crypto/e2ee.js')));
const key = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
const pub = toBase64(await crypto.subtle.exportKey('raw', key.publicKey));
const id = 'host-' + await fingerprint(pub);
const bundle = { v: 2, id, pub, token: 'T'.repeat(52), name: 'test', port: 8765, tailscale: [], lan: ['127.0.0.1'], relay: '' };
const link = (value) => 'viber://connect?data=' + Buffer.from(JSON.stringify(value)).toString('base64url');
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('pairing retains host pin and binds ID to identity', async () => {
  const parsed = await parsePairingBundle(link(bundle));
  assert.equal(parsed.hostPub, pub); assert.equal(parsed.hostId, id); assert.equal(parsed.protocolVersion, 2);
});
test('legacy pairing rejected', async () => { await assert.rejects(parsePairingBundle(link({ ...bundle, v: 1 }))); });
test('unrelated host ID rejected', async () => { await assert.rejects(parsePairingBundle(link({ ...bundle, id: 'host-other' }))); });
test('extra deep-link parameters and userinfo rejected', async () => {
  await assert.rejects(parsePairingBundle(link(bundle) + '&token=other'));
  await assert.rejects(parsePairingBundle(link(bundle).replace('viber://', 'viber://attacker@')));
});
test('remote ws relay and URL credential rejected', async () => {
  await assert.rejects(parsePairingBundle(link({ ...bundle, relay: 'ws://relay.example' })));
  await assert.rejects(parsePairingBundle(link({ ...bundle, direct_url: 'wss://user:password@host.example/ws' })));
});
test('legacy browser storage is removed; only explicit v2 session config accepted', () => {
  let removed;
  globalThis.localStorage = { removeItem(key) { removed = key; } };
  globalThis.sessionStorage = { getItem() { return JSON.stringify({ protocolVersion: 1, token: bundle.token }); } };
  assert.equal(loadSessionConfig('v2').token, ''); assert.equal(removed, 'viber_host_config');
  globalThis.sessionStorage = { getItem() { return JSON.stringify({ protocolVersion: 2, token: bundle.token, hostPub: pub }); } };
  assert.equal(loadSessionConfig('v2').hostPub, pub);
  delete globalThis.sessionStorage; delete globalThis.localStorage;
});
test('HTTPS current-origin candidate does not silently change port 443 to 8765', () => {
  globalThis.window = { location: new URL('https://terminal.example/') };
  try { assert.equal(new ViberConnection({})._buildCandidateUrls()[0].url, 'wss://terminal.example/ws'); }
  finally { delete globalThis.window; }
});
test('unpaired connect fails before creating any socket', async () => {
  const conn = new ViberConnection({});
  await conn.connect(); assert.equal(conn.status, 'error'); assert.equal(conn.ws, null); conn.disconnect();
});
test('40 concurrent outbound operations preserve nonce/send order', async () => {
  const conn = new ViberConnection();
  const sent = []; let seq = 0;
  const socket = { readyState: 1, bufferedAmount: 0, send(raw) { sent.push(JSON.parse(raw)); } };
  const ctx = { socket, pending: 0, txTail: Promise.resolve(), crypto: { async encryptJson(msg) {
    const assigned = ++seq; await pause(msg.delay); return { seq: assigned, id: msg.id };
  } }, fail(error) { throw error; } };
  conn._ctx = ctx; conn.status = 'connected';
  const values = await Promise.all(Array.from({ length: 40 }, (_, id) => conn.send({ id, delay: id % 3 })));
  assert.ok(values.every(Boolean)); assert.deepEqual(sent.map((x) => x.seq), Array.from({ length: 40 }, (_, n) => n + 1));
  assert.deepEqual(sent.map((x) => x.id), Array.from({ length: 40 }, (_, n) => n));
  conn._ctx = null; conn.disconnect();
});
test('superseded async encryption is not sent on a new socket', async () => {
  const conn = new ViberConnection(); let complete; let sends = 0;
  const old = { socket: { readyState: 1, bufferedAmount: 0, send() { sends++; } }, pending: 0, txTail: Promise.resolve(),
    crypto: { encryptJson() { return new Promise((resolve) => { complete = resolve; }); } }, fail() {} };
  conn._ctx = old; conn.status = 'connected'; const work = conn.send({ type: 'PING' });
  await pause(0); conn._ctx = { socket: { readyState: 1, send() { sends++; } } };
  complete({ seq: 1 }); assert.equal(await work, false); assert.equal(sends, 0);
  conn._ctx = null; conn.disconnect();
});
test('stale socket events cannot clear a replacement connection', async () => {
  const Original = globalThis.WebSocket;
  class FakeWebSocket {
    static OPEN = 1;
    constructor() { this.readyState = 1; }
    close() { this.readyState = 3; }
  }
  globalThis.WebSocket = FakeWebSocket;
  const conn = new ViberConnection({ hostPub: pub, token: bundle.token });
  try {
    const first = conn._attemptSocket('ws://localhost/ws', 'localhost', conn._generation).catch(() => {});
    const old = conn.ws; conn.disconnect(); await first;
    conn.shouldReconnect = true;
    const second = conn._attemptSocket('ws://localhost/ws', 'localhost', conn._generation).catch(() => {});
    const current = conn.ws;
    old.onmessage({ data: '{"type":"WELCOME"}' }); old.onerror(); old.onclose();
    await pause(0); assert.equal(conn.ws, current); assert.notEqual(conn.status, 'connected');
    conn.disconnect(); await second;
  } finally { conn.disconnect(); globalThis.WebSocket = Original; }
});
test('HTTPS context uses wss for remote endpoints avoiding mixed content', () => {
  globalThis.window = { location: new URL('https://app.example.com/') };
  try {
    const conn = new ViberConnection({ lanIps: ['192.168.1.50'], tailscaleIps: ['100.64.0.10'], directPort: 8765 });
    const candidates = conn._buildCandidateUrls();
    for (const c of candidates) {
      assert.ok(c.url.startsWith('wss://'), `Expected wss:// in HTTPS page, got ${c.url}`);
    }
  } finally { delete globalThis.window; }
});
test('pairing bundle accepts https direct_url and normalizes to wss', async () => {
  const parsed = await parsePairingBundle(link({ ...bundle, direct_url: 'https://viber.example.com/ws', ssl: true }));
  assert.equal(parsed.directUrl, 'wss://viber.example.com/ws');
  assert.equal(parsed.ssl, true);
});
