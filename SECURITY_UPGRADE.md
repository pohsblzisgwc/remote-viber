# RemoteViber protocol-v2 specification and review notes

Status: implemented source overlay, tested locally, not a published standard or independently reviewed cryptographic protocol. Do not represent the passing tests as a proof of security. This file is installed alongside the patched repository. The delivered bundle's README.zh-CN.md contains full deployment instructions.

## Trust and authorization model

This is single-owner remote administration. Every paired client has the authority of the OS account running Host. Per-client encryption/routing isolation does not create per-user OS authorization. The relay is not trusted with credentials, plaintext, identity selection, or protocol downgrade. It still learns routing metadata and can deny service.

Pairing is an explicit out-of-band action. Host generates a P-256 identity signing key and 32 random bytes encoded as unpadded Base32 for the shared credential (52 ASCII characters). Clients must pin the identity public key from a trusted local/otherwise authenticated pairing bundle and verify its full SHA-256 fingerprint. Never use a human-selected password: the HMAC proof is not a PAKE and does not prevent offline guesses against weak credentials.

A Web client must itself be delivered through trusted localhost/HTTPS. Application E2EE cannot protect a browser which received malicious replacement JavaScript. Native clients use normal platform TLS validation for wss; TLS validation is never disabled by this patch.

## Encodings and primitives

All EC keys are NIST P-256. Public keys use SEC1/X9.62 uncompressed form, 65 bytes beginning 0x04, canonical standard Base64 with padding. Private identity values are 32-byte big-endian scalars, protected by OS file permissions on Host. Identity fingerprint is the lowercase hexadecimal SHA-256 of the 65 public-key bytes.

A host ID is the ASCII string `host-` followed by that complete 64-character hex digest. It is public, not a password. Relay must refuse registrations whose ID and key do not match.

ECDSA uses SHA-256. Its wire signature is fixed-width 64 bytes, unsigned big-endian r followed by s, Base64 encoded. Python converts DER to/from raw; WebCrypto uses raw; Kotlin converts raw to DER for JCA. ECDH uses independent fresh ephemeral key pairs on both sides of every connection, not the long-term signing key.

## Direct/application handshake

Every browser/native connection, including loopback and Tailscale, uses the same handshake. There is no e2ee=false, plaintext fallback, local token waiver, or token-bearing HELLO/WELCOME.

1. Client generates a fresh ephemeral P-256 key pair and 32-byte random nonce. It sends exactly:

```json
{"type":"HELLO","v":2,"client_pub":"BASE64_PUBLIC_KEY","client_nonce":"BASE64_32_BYTES"}
```

2. Host generates its fresh ephemeral key pair and 32-byte random nonce. Define the following ASCII transcript, joined by a single LF between fields and with NO trailing LF:

```text
remote-viber-v2
host_pub
client_pub
client_nonce
server_pub
server_nonce
```

Here the five names are replaced by their exact canonical Base64 strings, not raw key bytes. Host signs the transcript and sends exactly:

```json
{"type":"CHALLENGE","v":2,"host_pub":"PINNED_IDENTITY_PUBLIC_KEY","server_pub":"EPHEMERAL_PUBLIC_KEY","server_nonce":"BASE64_32_BYTES","signature":"BASE64_RAW_ECDSA_SIGNATURE"}
```

3. Client must compare host_pub to its previously pinned identity and verify the signature before sending any credential proof. Define `H = SHA256(transcript)` as 32 raw bytes. The client proof is:

```text
Base64(HMAC-SHA256(ASCII(pairing_secret),
                  ASCII("remote-viber-v2/client-auth\n") || H))
```

Client sends exactly:

```json
{"type":"AUTH","v":2,"proof":"BASE64_32_BYTE_HMAC"}
```

Host uses constant-time comparison. An AUTH is single-use whether it succeeds or fails. Total initial handshake deadline is ten seconds at the connection layer, with an additional single-use challenge expiry. Invalid or repeated handshake states close the connection.

4. Both sides derive 64 bytes from the ephemeral ECDH shared secret using HKDF-SHA256:

```text
IKM  = ECDH(ephemeral_private, peer_ephemeral_public)
salt = H
info = ASCII("remote-viber-v2 traffic keys")
L    = 64
c2s_key = first 32 bytes
s2c_key = last 32 bytes
```

5. Host's first encrypted application message must be:

```json
{"type":"WELCOME","v":2,"status":"authenticated","fingerprint":"FULL_SHA256_HEX","e2ee":true}
```

Client does not enter connected/ready state before successfully decrypting and validating this WELCOME. Authentication messages cannot reappear as business messages later.

## Encrypted message format and ordering

Every application message is a JSON object with a string type. The encrypted envelope has exactly three fields:

```json
{"v":2,"seq":1,"data":"BASE64_CIPHERTEXT_AND_16_BYTE_GCM_TAG"}
```

Each direction has its own key and strictly increasing counter beginning at 1. Nonce is 12 bytes: four zero bytes followed by the sequence number as an eight-byte unsigned big-endian integer. Additional authenticated data is:

```text
remote-viber-v2|c2s|1
remote-viber-v2|s2c|1
```

Replace the direction and decimal counter as appropriate; no trailing LF. Receiver accepts only its exact next sequence number, not duplicates, gaps, reordered frames, booleans, negative values or a counter beyond 0xffffffff. GCM authentication must succeed before the receive counter advances. Send counter allocation, encryption and enqueueing are serialized per socket, including asynchronous WebCrypto. Stale socket callbacks cannot mutate a replacement connection's state.

The current connection must be terminated after a protocol violation. Do not catch an AEAD/counter error and continue a business stream. Reconnection means a completely new handshake and fresh keys/counters. Terminal replay positions are a separate application feature and must never substitute for cryptographic anti-replay counters.

Wire messages are capped at 1 MiB; encrypted plaintext is capped at 700,000 bytes. Terminal input is capped at 64 KiB. PTY output is split into at most 64 KiB chunks before entering the replay buffer. Replay is streamed in separate encrypted messages rather than returned as one enormous JSON response. Each connection's queued terminal output is bounded by 64 entries and 256 KiB; overflowing/slow consumers are disconnected.

## Relay registration and multiplexing

Relay sends a fresh connection-specific challenge first:

```json
{"type":"REGISTER_CHALLENGE","v":2,"challenge":"BASE64_32_RANDOM_BYTES"}
```

Host signs this ASCII transcript (LF separators, no trailing LF):

```text
remote-viber-v2/relay-registration
challenge
host_id
pub_key
```

It responds with exactly:

```json
{"type":"REGISTER_HOST","v":2,"host_id":"host-SHA256","pub_key":"IDENTITY_PUBLIC_KEY","signature":"BASE64_RAW_SIGNATURE"}
```

Relay verifies the host-ID binding, challenge freshness and signature, rejects a duplicate active host ID, and binds the registration to its actual socket. Cleanup deletes a record only when it still belongs to the socket being removed. An attacker presenting the same ID without its private key cannot replace the registered route.

Relay assigns every client a separate random 128-bit connection ID (32 lowercase hex characters), maintained internally. Relay-to-Host controls are CLIENT_OPEN, CLIENT_DATA and CLIENT_CLOSE, carrying client_id. CLIENT_DATA also carries payload as the original application-wire text. Host-to-Relay uses RELAY_FORWARD with client_id and payload, or CLIENT_CLOSE. Replies are sent only to the one mapped client, never broadcast to all subscribers. Host keeps a separate ClientConnectionState, timeout, bounded queue, traffic keys and counters for each client ID. An unauthenticated client opening a route does not authenticate any other route.

Remote relay URLs must use wss. Plain ws is allowed only for literal localhost, 127.0.0.1 and ::1 (local proxy/testing). No public host metadata enumeration or unauthenticated endpoint disclosure is provided; /healthz remains. Global/host limits are not a substitute for deployment-layer connection and rate limits. No claim of resistance to distributed denial of service is made.

## HTTP/browser and local storage controls

Host serves a precise Origin allowlist and recognized Host authorities. A Host header never grants credentials or authorization. Missing Origin is permitted for native clients only in the sense that they can attempt the handshake; they still need the same valid identity/credential protocol. Browser null Origin and foreign Origins are rejected.

/api/pairing always rejects automatic pairing. There is no HTML injection of credentials, URL-token import or wildcard CORS credential endpoint. Pairing UI previews the identity before saving. Web uses sessionStorage, not default long-term localStorage, and removes the old v1 saved entry. That storage remains readable by same-origin scripts and may be restored by browser session recovery; XSS/extension compromise remains outside its protection.

POSIX key files are owner-only, opened without following symlinks, validated as single-link regular files, and atomically saved through a private temporary file. New config directories use 0700; config files use 0600. Windows requires explicit appropriate ACLs and was not tested here. Static files use segment-by-segment no-follow openat on POSIX; Windows reparse-point/containment checks also depend on a protected asset directory.

Terminal transcript creation is disabled, not merely hidden. Old logs and backups are not erased. Android pair data is sealed using AES-GCM under an Android Keystore key, with backup exclusions and allowBackup=false. Deep links only parse/preview; a visible confirmation is required before saving or switching. The complete Android implementation has not been built/exercised on a device in this environment.

## Upgrade, rollback and remaining work

Apply all files together on a stopped, reviewable checkout using the delivered installer. Rebuild the Web client and APK; upgrade Relay where used. Do not mix these modules with v1 clients or leave old built assets in service. Loading a v1 Host configuration rotates its credentials once and forces re-pairing. --rotate-credentials invalidates every client for that host; per-device revocation is not implemented.

The installer is a source-only overlay. It was tested against minimal source-structure fixtures, not a complete cloned checkout. It refuses uncommitted changes in affected Git paths and ambiguous anchors, and backs up all modified source. It does not touch runtime credentials, commit, push or deploy anything. Runtime key rotation is not undone by source rollback. Never return to publicly exposing the vulnerable version after a rollback.

Release gates not completed here: full React/Vite build; APK/Keystore/UI/backup testing; real PTY and all agent workflows; Windows ACL/races; end-to-end browser/proxy operation; load tests; complete dependency scanning; independent review of this new protocol composition. The local regression suite supplies evidence for specific behaviors only, not a production security guarantee.
