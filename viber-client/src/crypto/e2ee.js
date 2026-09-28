/** Protocol v2 only. See SECURITY_UPGRADE.md. */
export const MAX_SEQUENCE = 0xffffffff;
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });
const AUTH_LABEL = 'remote-viber-v2/client-auth\n';
const KDF_LABEL = 'remote-viber-v2 traffic keys';

export function toBase64(bytes) {
  const data = new Uint8Array(bytes);
  let text = '';
  for (let i = 0; i < data.length; i += 32768) {
    text += String.fromCharCode(...data.subarray(i, i + 32768));
  }
  return btoa(text);
}
export function fromBase64(value, size = null) {
  if (typeof value !== 'string' || value.length > 1024 * 1024 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) {
    throw new Error('Invalid base64');
  }
  const bytes = Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
  if (toBase64(bytes) !== value || (size !== null && bytes.length !== size)) throw new Error('Invalid encoding');
  return bytes;
}
function strictObject(obj, keys) {
  if (!obj || Array.isArray(obj) || typeof obj !== 'object' ||
      Object.keys(obj).sort().join(',') !== [...keys].sort().join(',')) throw new Error('Invalid protocol object');
}
function nonce(seq) {
  const bytes = new Uint8Array(12);
  new DataView(bytes.buffer).setBigUint64(4, BigInt(seq), false);
  return bytes;
}
function requireCrypto() {
  if (globalThis.isSecureContext === false || !globalThis.crypto?.subtle) {
    throw new Error('Authentication requires WebCrypto: use HTTPS or localhost. No plaintext fallback.');
  }
  return globalThis.crypto.subtle;
}
export async function fingerprint(pub) {
  const raw = fromBase64(pub, 65);
  await requireCrypto().importKey('raw', raw, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
  const bytes = new Uint8Array(await requireCrypto().digest('SHA-256', raw));
  return [...bytes].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export class ClientCryptoManager {
  constructor() { this.destroy(); }
  destroy() {
    this.phase = 'closed';
    this.supported = !!globalThis.crypto?.subtle;
    this.aesKey = this.txKey = this.rxKey = null;
    this.keyPair = null;
    this.hello = null;
    this.token = '';
    this.hostPub = '';
    this.txSeq = this.rxSeq = 0;
  }
  async initialize(config) {
    this.destroy();
    const subtle = requireCrypto();
    if (!config || typeof config.token !== 'string' || !/^[!-~]{32,256}$/.test(config.token)) {
      throw new Error('Authentication: re-pair with the new high-entropy credential');
    }
    const pin = fromBase64(config.hostPub, 65);
    if (pin[0] !== 4) throw new Error('Invalid pinned key');
    this.hostPub = config.hostPub;
    this.token = config.token;
    this.keyPair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const pub = await subtle.exportKey('raw', this.keyPair.publicKey);
    this.hello = { type: 'HELLO', v: 2, client_pub: toBase64(pub),
                   client_nonce: toBase64(globalThis.crypto.getRandomValues(new Uint8Array(32))) };
    this.phase = 'hello';
    return this.hello;
  }
  async establishSession(challenge) {
    if (this.phase !== 'hello') throw new Error('Unexpected handshake');
    strictObject(challenge, ['type', 'v', 'host_pub', 'server_pub', 'server_nonce', 'signature']);
    if (challenge.type !== 'CHALLENGE' || challenge.v !== 2 || challenge.host_pub !== this.hostPub) {
      throw new Error('Host identity mismatch; re-pair only after independent verification');
    }
    const subtle = requireCrypto();
    const serverPub = fromBase64(challenge.server_pub, 65);
    if (serverPub[0] !== 4) throw new Error('Invalid ephemeral key');
    fromBase64(challenge.server_nonce, 32);
    const context = encoder.encode(['remote-viber-v2', this.hostPub, this.hello.client_pub,
      this.hello.client_nonce, challenge.server_pub, challenge.server_nonce].join('\n'));
    const identity = await subtle.importKey('raw', fromBase64(this.hostPub, 65),
      { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    const verified = await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, identity,
      fromBase64(challenge.signature, 64), context);
    if (!verified) throw new Error('Host signature verification failed');
    const peer = await subtle.importKey('raw', serverPub, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const shared = await subtle.deriveBits({ name: 'ECDH', public: peer }, this.keyPair.privateKey, 256);
    const salt = await subtle.digest('SHA-256', context);
    const material = await subtle.importKey('raw', shared, 'HKDF', false, ['deriveBits']);
    const keys = new Uint8Array(await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt,
      info: encoder.encode(KDF_LABEL) }, material, 512));
    this.txKey = await subtle.importKey('raw', keys.slice(0, 32), 'AES-GCM', false, ['encrypt']);
    this.rxKey = await subtle.importKey('raw', keys.slice(32), 'AES-GCM', false, ['decrypt']);
    this.aesKey = this.txKey;
    const macKey = await subtle.importKey('raw', encoder.encode(this.token),
      { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    const label = encoder.encode(AUTH_LABEL);
    const macInput = new Uint8Array(label.length + 32);
    macInput.set(label); macInput.set(new Uint8Array(salt), label.length);
    const proof = toBase64(await subtle.sign('HMAC', macKey, macInput));
    keys.fill(0);
    this.token = '';
    this.keyPair = null;
    this.phase = 'welcome';
    return { type: 'AUTH', v: 2, proof };
  }
  async encryptJson(message) {
    if (this.phase !== 'ready' || !this.txKey) throw new Error('Authentication required');
    if (!message || Array.isArray(message) || typeof message.type !== 'string') throw new Error('Invalid message');
    const data = encoder.encode(JSON.stringify(message));
    if (data.length > 700000 || this.txSeq >= MAX_SEQUENCE) throw new Error('Session/frame limit reached');
    const seq = this.txSeq + 1;
    const ciphertext = await requireCrypto().encrypt({ name: 'AES-GCM', iv: nonce(seq),
      additionalData: encoder.encode(`remote-viber-v2|c2s|${seq}`), tagLength: 128 }, this.txKey, data);
    this.txSeq = seq;
    return { v: 2, seq, data: toBase64(ciphertext) };
  }
  async decryptJson(frame) {
    if (!['welcome', 'ready'].includes(this.phase) || !this.rxKey) throw new Error('No encrypted session');
    strictObject(frame, ['v', 'seq', 'data']);
    if (frame.v !== 2 || !Number.isSafeInteger(frame.seq) || frame.seq !== this.rxSeq + 1 || frame.seq > MAX_SEQUENCE) {
      throw new Error('Replay or out-of-order frame');
    }
    const ciphertext = fromBase64(frame.data);
    if (ciphertext.length < 16 || ciphertext.length > 700016) throw new Error('Invalid ciphertext size');
    const plaintext = await requireCrypto().decrypt({ name: 'AES-GCM', iv: nonce(frame.seq),
      additionalData: encoder.encode(`remote-viber-v2|s2c|${frame.seq}`), tagLength: 128 }, this.rxKey, ciphertext);
    const message = JSON.parse(decoder.decode(plaintext));
    if (!message || Array.isArray(message) || typeof message.type !== 'string') throw new Error('Invalid message');
    if (this.phase === 'welcome') {
      if (message.type !== 'WELCOME' || message.v !== 2 || message.e2ee !== true || message.status !== 'authenticated') {
        throw new Error('Authenticated WELCOME required');
      }
      this.phase = 'ready';
    } else if (['WELCOME', 'HELLO', 'AUTH', 'CHALLENGE'].includes(message.type)) {
      throw new Error('Unexpected repeated handshake');
    }
    this.rxSeq = frame.seq;
    return message;
  }
}
