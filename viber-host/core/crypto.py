"""
RemoteViber E2EE Crypto Module
Implements ECDH (P-256) key exchange + HKDF-SHA256 + AES-256-GCM authenticated encryption.
Fully compatible with W3C WebCrypto API on Android, Windows, and Linux browsers/webviews.
"""

import os
import base64
import json
import hmac
import hashlib
from typing import Tuple, Dict, Any, Optional

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


class E2EESession:
    """Manages an End-to-End Encrypted session with a remote peer."""

    def __init__(self, session_key: bytes):
        self.session_key = session_key
        self.aesgcm = AESGCM(session_key)

    def encrypt(self, data: bytes) -> Dict[str, str]:
        """Encrypts plaintext bytes, returning base64 encoded IV and ciphertext with auth tag."""
        nonce = os.urandom(12)  # 96-bit nonce for AES-GCM
        ciphertext = self.aesgcm.encrypt(nonce, data, None)
        return {
            "iv": base64.b64encode(nonce).decode("ascii"),
            "data": base64.b64encode(ciphertext).decode("ascii"),
        }

    def encrypt_json(self, obj: Any) -> Dict[str, str]:
        """Convenience method to encrypt a JSON-serializable object."""
        payload = json.dumps(obj).encode("utf-8")
        return self.encrypt(payload)

    def decrypt(self, iv_b64: str, data_b64: str) -> bytes:
        """Decrypts base64 encoded IV and ciphertext, verifying the GCM authentication tag."""
        nonce = base64.b64decode(iv_b64)
        ciphertext = base64.b64decode(data_b64)
        return self.aesgcm.decrypt(nonce, ciphertext, None)

    def decrypt_json(self, iv_b64: str, data_b64: str) -> Any:
        """Convenience method to decrypt and parse JSON."""
        plaintext = self.decrypt(iv_b64, data_b64)
        return json.loads(plaintext.decode("utf-8"))


class HostKeyManager:
    """Generates and manages Host ECDH identity keys and pairing credentials."""

    def __init__(self, private_key_bytes: Optional[bytes] = None, pairing_secret: Optional[str] = None):
        if private_key_bytes:
            self._private_key = ec.derive_private_key(
                int.from_bytes(private_key_bytes, "big"), ec.SECP256R1()
            )
        else:
            self._private_key = ec.generate_private_key(ec.SECP256R1())

        self.pairing_secret = pairing_secret or base64.b32encode(os.urandom(15)).decode("ascii").rstrip("=")

    @property
    def pairing_token(self) -> str:
        return self.pairing_secret

    @property
    def public_key_raw(self) -> bytes:
        """Exports 65-byte uncompressed X9.62 point (0x04 || X || Y) compatible with WebCrypto."""
        return self._private_key.public_key().public_bytes(
            Encoding.X962, PublicFormat.UncompressedPoint
        )

    @property
    def public_key_b64(self) -> str:
        return base64.b64encode(self.public_key_raw).decode("ascii")

    def derive_session(self, peer_pub_raw: bytes, salt: bytes = b"remote-viber-v1") -> E2EESession:
        """Derives a symmetric AES-256-GCM session key using ECDH + HKDF-SHA256."""
        peer_pub = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_pub_raw)
        shared_secret = self._private_key.exchange(ec.ECDH(), peer_pub)

        session_key = HKDF(
            algorithm=hashes.SHA256(),
            length=32,  # 256 bits for AES-256
            salt=salt,
            info=b"remote-viber-e2ee-session",
        ).derive(shared_secret)

        return E2EESession(session_key)

    def verify_pairing_token(self, provided_token: str) -> bool:
        """Constant-time token verification to prevent timing attacks."""
        if not self.pairing_secret or not provided_token:
            return False
        return hmac.compare_digest(self.pairing_secret.strip(), provided_token.strip())

    def get_fingerprint(self) -> str:
        """Generates a human-friendly visual fingerprint of the host public key."""
        digest = hashlib.sha256(self.public_key_raw).digest()
        return ":".join(f"{b:02X}" for b in digest[:8])
