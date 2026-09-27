/**
 * RemoteViber WebCrypto E2EE Engine
 * Provides Curve P-256 ECDH + HKDF-SHA256 + AES-256-GCM authenticated encryption.
 * Hardware-accelerated on Android (ARMv8 Crypto) and Desktop (AES-NI).
 */

function arrayBufferToBase64(buffer) {
  let binary = '';
  const bytes = new Uint8Array(buffer);
  const len = bytes.byteLength;
  for (let i = 0; i < len; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary);
}

function base64ToArrayBuffer(base64) {
  const binary_string = window.atob(base64);
  const len = binary_string.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binary_string.charCodeAt(i);
  }
  return bytes.buffer;
}

export class ClientCryptoManager {
  constructor() {
    this.keyPair = null;
    this.publicKeyB64 = null;
    this.aesKey = null;
    this.isReady = false;
    this.supported = typeof window !== 'undefined' && !!(window.crypto && window.crypto.subtle);
  }

  async initialize() {
    if (!this.supported) {
      this.isReady = true;
      return null;
    }
    // Generate ephemeral ECDH keypair
    this.keyPair = await window.crypto.subtle.generateKey(
      { name: "ECDH", namedCurve: "P-256" },
      true,
      ["deriveBits"]
    );
    const rawPub = await window.crypto.subtle.exportKey("raw", this.keyPair.publicKey);
    this.publicKeyB64 = arrayBufferToBase64(rawPub);
    return this.publicKeyB64;
  }

  async establishSession(hostPublicKeyB64) {
    if (!this.supported || !hostPublicKeyB64) {
      this.isReady = true;
      return;
    }

    if (!this.keyPair) {
      await this.initialize();
    }

    const hostPubRaw = base64ToArrayBuffer(hostPublicKeyB64);
    const hostKey = await window.crypto.subtle.importKey(
      "raw",
      hostPubRaw,
      { name: "ECDH", namedCurve: "P-256" },
      false,
      []
    );

    // Compute shared secret
    const sharedBits = await window.crypto.subtle.deriveBits(
      { name: "ECDH", public: hostKey },
      this.keyPair.privateKey,
      256
    );

    // Derive AES-256-GCM key with HKDF
    const hkdfKey = await window.crypto.subtle.importKey(
      "raw",
      sharedBits,
      "HKDF",
      false,
      ["deriveKey"]
    );

    this.aesKey = await window.crypto.subtle.deriveKey(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: new TextEncoder().encode("remote-viber-v1"),
        info: new TextEncoder().encode("remote-viber-e2ee-session"),
      },
      hkdfKey,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"]
    );

    this.isReady = true;
  }

  async encryptJson(payload) {
    if (!this.supported || !this.aesKey) {
      return payload;
    }

    const jsonText = JSON.stringify(payload);
    const encoded = new TextEncoder().encode(jsonText);
    const iv = window.crypto.getRandomValues(new Uint8Array(12));

    const ciphertext = await window.crypto.subtle.encrypt(
      { name: "AES-GCM", iv },
      this.aesKey,
      encoded
    );

    return {
      iv: arrayBufferToBase64(iv),
      data: arrayBufferToBase64(ciphertext),
    };
  }

  async decryptJson(ivB64, dataB64) {
    if (!this.supported || !this.aesKey) {
      return null;
    }

    const iv = base64ToArrayBuffer(ivB64);
    const data = base64ToArrayBuffer(dataB64);

    const decrypted = await window.crypto.subtle.decrypt(
      { name: "AES-GCM", iv: new Uint8Array(iv) },
      this.aesKey,
      data
    );

    const text = new TextDecoder().decode(decrypted);
    return JSON.parse(text);
  }
}
