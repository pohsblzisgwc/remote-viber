"""RemoteViber protocol v2. No v1 or plaintext fallback.

P-256 identity signatures authenticate fresh ephemeral ECDH keys. A high-entropy
pairing secret authorizes the client with a transcript-bound HMAC. Directional
AES-GCM keys and strictly ordered counters reject reflected/replayed messages.
See SECURITY_UPGRADE.md for the wire format and review limitations.
"""
import base64
import hashlib
import hmac
import json
import os
import time
from typing import Any, Optional

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec, utils
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

VERSION = 2
MAX_CLIENT_FRAME_BYTES = 512 * 1024
MAX_RELAY_FRAME_BYTES = 2 * 1024 * 1024
MAX_FRAME_BYTES = MAX_RELAY_FRAME_BYTES
MAX_SEQUENCE = (1 << 32) - 1
HANDSHAKE_TIMEOUT = 10.0
AUTH_LABEL = b"remote-viber-v2/client-auth\n"
KDF_LABEL = b"remote-viber-v2 traffic keys"


class ProtocolError(ValueError):
    """Fatal protocol violation. The caller must close this connection."""


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(value: str, size: Optional[int] = None) -> bytes:
    if not isinstance(value, str) or len(value) > MAX_FRAME_BYTES:
        raise ProtocolError("Invalid encoding")
    try:
        data = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as exc:
        raise ProtocolError("Invalid encoding") from exc
    if b64(data) != value or (size is not None and len(data) != size):
        raise ProtocolError("Invalid encoding")
    return data


def public_raw(key) -> bytes:
    return key.public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)


def public_key(value: str):
    raw = unb64(value, 65)
    if raw[0] != 4:
        raise ProtocolError("Invalid key")
    try:
        return ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), raw)
    except ValueError as exc:
        raise ProtocolError("Invalid key") from exc


def host_id_for(pub: str) -> str:
    public_key(pub)
    return "host-" + hashlib.sha256(unb64(pub, 65)).hexdigest()


def transcript(host_pub, client_pub, client_nonce, server_pub, server_nonce) -> bytes:
    public_key(host_pub)
    public_key(client_pub)
    public_key(server_pub)
    unb64(client_nonce, 32)
    unb64(server_nonce, 32)
    return "\n".join(("remote-viber-v2", host_pub, client_pub, client_nonce,
                       server_pub, server_nonce)).encode("ascii")


def sign_bytes(key, data: bytes) -> str:
    der = key.sign(data, ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    return b64(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def verify_signature(pub: str, signature: str, data: bytes) -> None:
    raw = unb64(signature, 64)
    der = utils.encode_dss_signature(int.from_bytes(raw[:32], "big"),
                                    int.from_bytes(raw[32:], "big"))
    try:
        public_key(pub).verify(der, data, ec.ECDSA(hashes.SHA256()))
    except Exception as exc:
        raise ProtocolError("Identity verification failed") from exc


def registration_message(challenge: str, host_id: str, pub: str) -> bytes:
    unb64(challenge, 32)
    if host_id != host_id_for(pub):
        raise ProtocolError("Host ID is not bound to its public key")
    return "\n".join(("remote-viber-v2/relay-registration", challenge,
                       host_id, pub)).encode("ascii")


def validate_token(token: str) -> bytes:
    # Generated pairing tokens, NOT human-chosen passwords. Legacy 80-bit tokens
    # must be rotated by HostConfig before v2 is enabled.
    if not isinstance(token, str) or not 32 <= len(token) <= 256:
        raise ProtocolError("Re-pair with a new high-entropy credential")
    try:
        value = token.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ProtocolError("Invalid credential") from exc
    if any(c <= 32 or c >= 127 for c in value):
        raise ProtocolError("Invalid credential")
    return value


def auth_proof(token: str, context: bytes) -> str:
    digest = hashlib.sha256(context).digest()
    return b64(hmac.new(validate_token(token), AUTH_LABEL + digest, hashlib.sha256).digest())


def derive_keys(private_key, peer_pub: str, context: bytes) -> bytes:
    secret = private_key.exchange(ec.ECDH(), public_key(peer_pub))
    return HKDF(algorithm=hashes.SHA256(), length=64,
                salt=hashlib.sha256(context).digest(), info=KDF_LABEL).derive(secret)


class E2EESession:
    """One ordered WebSocket stream; never share between clients or reconnects."""
    def __init__(self, keys: bytes, role: str):
        if len(keys) != 64 or role not in ("host", "client"):
            raise ValueError("Invalid session parameters")
        c2s, s2c = keys[:32], keys[32:]
        self.tx_direction = "s2c" if role == "host" else "c2s"
        self.rx_direction = "c2s" if role == "host" else "s2c"
        self._tx = AESGCM(s2c if role == "host" else c2s)
        self._rx = AESGCM(c2s if role == "host" else s2c)
        self._tx_seq = 0
        self._rx_seq = 0

    @staticmethod
    def _nonce(seq: int) -> bytes:
        return b"\x00" * 4 + seq.to_bytes(8, "big")

    @staticmethod
    def _aad(direction: str, seq: int) -> bytes:
        return f"remote-viber-v2|{direction}|{seq}".encode("ascii")

    def encrypt_json(self, obj: Any) -> dict:
        if not isinstance(obj, dict) or not isinstance(obj.get("type"), str):
            raise ProtocolError("Invalid application message")
        data = json.dumps(obj, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(data) > 700_000 or self._tx_seq >= MAX_SEQUENCE:
            raise ProtocolError("Frame/session limit exceeded")
        seq = self._tx_seq + 1
        ciphertext = self._tx.encrypt(self._nonce(seq), data, self._aad(self.tx_direction, seq))
        self._tx_seq = seq
        return {"v": VERSION, "seq": seq, "data": b64(ciphertext)}

    def decrypt_json(self, frame: dict) -> dict:
        if not isinstance(frame, dict) or set(frame) != {"v", "seq", "data"}:
            raise ProtocolError("Encrypted frame required")
        seq = frame["seq"]
        if type(frame["v"]) is not int or frame["v"] != VERSION or type(seq) is not int:
            raise ProtocolError("Invalid frame version/counter")
        if seq != self._rx_seq + 1 or seq > MAX_SEQUENCE:
            raise ProtocolError("Replay or out-of-order frame")
        ciphertext = unb64(frame["data"])
        if not 16 <= len(ciphertext) <= 700_016:
            raise ProtocolError("Invalid ciphertext length")
        try:
            data = self._rx.decrypt(self._nonce(seq), ciphertext, self._aad(self.rx_direction, seq))
            obj = json.loads(data)
        except Exception as exc:
            raise ProtocolError("Invalid encrypted message") from exc
        if not isinstance(obj, dict) or not isinstance(obj.get("type"), str):
            raise ProtocolError("Invalid application message")
        self._rx_seq = seq  # Only advance after authentication AND parsing succeed.
        return obj


class HostKeyManager:
    def __init__(self, private_key_bytes: Optional[bytes] = None, pairing_secret: Optional[str] = None):
        if private_key_bytes is not None:
            if len(private_key_bytes) != 32:
                raise ValueError("Invalid private key")
            self._private_key = ec.derive_private_key(int.from_bytes(private_key_bytes, "big"), ec.SECP256R1())
        else:
            self._private_key = ec.generate_private_key(ec.SECP256R1())
        self.pairing_secret = pairing_secret or base64.b32encode(os.urandom(32)).decode("ascii").rstrip("=")
        validate_token(self.pairing_secret)

    @property
    def pairing_token(self):
        return self.pairing_secret

    @property
    def public_key_raw(self):
        return public_raw(self._private_key.public_key())

    @property
    def public_key_b64(self):
        return b64(self.public_key_raw)

    def get_fingerprint(self):
        return hashlib.sha256(self.public_key_raw).hexdigest()

    def begin_handshake(self, hello: dict):
        return ServerHandshake(self, hello)

    def sign_registration(self, challenge: str) -> dict:
        pub = self.public_key_b64
        host_id = host_id_for(pub)
        return {"type": "REGISTER_HOST", "v": VERSION, "host_id": host_id,
                "pub_key": pub, "signature": sign_bytes(self._private_key,
                    registration_message(challenge, host_id, pub))}


class ServerHandshake:
    """Single-use challenge. No credential is accepted in HELLO or returned to peers."""
    def __init__(self, identity: HostKeyManager, hello: dict):
        if not isinstance(hello, dict) or set(hello) != {"type", "v", "client_pub", "client_nonce"}:
            raise ProtocolError("Protocol v2 HELLO required")
        if hello["type"] != "HELLO" or type(hello["v"]) is not int or hello["v"] != VERSION:
            raise ProtocolError("Protocol v2 required")
        ephemeral = ec.generate_private_key(ec.SECP256R1())
        server_pub = b64(public_raw(ephemeral.public_key()))
        server_nonce = b64(os.urandom(32))
        context = transcript(identity.public_key_b64, hello["client_pub"], hello["client_nonce"], server_pub, server_nonce)
        self._proof = auth_proof(identity.pairing_secret, context)
        self._session = E2EESession(derive_keys(ephemeral, hello["client_pub"], context), "host")
        self.expires_at = time.monotonic() + HANDSHAKE_TIMEOUT
        self._used = False
        self.challenge = {"type": "CHALLENGE", "v": VERSION, "host_pub": identity.public_key_b64,
                          "server_pub": server_pub, "server_nonce": server_nonce,
                          "signature": sign_bytes(identity._private_key, context)}

    def finish(self, message: dict) -> E2EESession:
        session = self._session
        self._session = None
        used, self._used = self._used, True
        if used or time.monotonic() > self.expires_at:
            raise ProtocolError("Expired or reused handshake")
        if not isinstance(message, dict) or set(message) != {"type", "v", "proof"}:
            raise ProtocolError("AUTH required")
        if message["type"] != "AUTH" or type(message["v"]) is not int or message["v"] != VERSION:
            raise ProtocolError("AUTH required")
        proof = unb64(message["proof"], 32)
        if not hmac.compare_digest(unb64(self._proof, 32), proof):
            raise ProtocolError("Authentication failed")
        return session
