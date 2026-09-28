package com.remoteviber.client.network

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure JVM/Android protocol-v2 core. Never accepts v1 or plaintext business messages. */
class ProtocolV2(private val pinnedHostPub: String, private var token: String) {
    enum class Phase { NEW, HELLO, WELCOME, READY, CLOSED }
    @Volatile var phase = Phase.NEW
        private set
    private var pair: KeyPair? = null
    private var clientPub = ""
    private var clientNonce = ""
    private var tx: ByteArray? = null
    private var rx: ByteArray? = null
    private var txSeq = 0L
    private var rxSeq = 0L

    init {
        require(token.length in 32..256 && token.all { it.code in 33..126 }) { "Re-pair with v2 credentials" }
        readPublicKey(pinnedHostPub)
    }

    @Synchronized fun hello(): Map<String, Any> {
        check(phase == Phase.NEW) { "Handshake already started" }
        pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = pair!!.public as ECPublicKey
        clientPub = encode(byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY))
        clientNonce = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        phase = Phase.HELLO
        return mapOf("type" to "HELLO", "v" to 2, "client_pub" to clientPub, "client_nonce" to clientNonce)
    }

    @Synchronized fun authenticate(challenge: Map<String, Any?>): Map<String, Any> {
        check(phase == Phase.HELLO) { "Unexpected handshake" }
        require(challenge.keys == setOf("type", "v", "host_pub", "server_pub", "server_nonce", "signature")) { "Invalid challenge" }
        require(challenge["type"] == "CHALLENGE" && challenge["v"] == 2 && challenge["host_pub"] == pinnedHostPub) { "Host identity mismatch" }
        val serverPub = challenge["server_pub"] as? String ?: error("Invalid key")
        val serverNonce = challenge["server_nonce"] as? String ?: error("Invalid nonce")
        decode(serverNonce, 32)
        val signature = decode(challenge["signature"] as? String ?: error("Missing signature"), 64)
        val context = listOf("remote-viber-v2", pinnedHostPub, clientPub, clientNonce, serverPub, serverNonce).joinToString("\n").toByteArray(StandardCharsets.US_ASCII)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(readPublicKey(pinnedHostPub))
        verifier.update(context)
        require(verifier.verify(rawSignatureToDer(signature))) { "Host signature verification failed" }
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(pair!!.private)
        agreement.doPhase(readPublicKey(serverPub), true)
        val shared = agreement.generateSecret()
        val salt = MessageDigest.getInstance("SHA-256").digest(context)
        val prk = mac(salt, shared)
        val info = "remote-viber-v2 traffic keys".toByteArray(StandardCharsets.US_ASCII)
        tx = mac(prk, info + byteArrayOf(1))
        rx = mac(prk, tx!! + info + byteArrayOf(2))
        val proof = encode(mac(token.toByteArray(StandardCharsets.US_ASCII),
            "remote-viber-v2/client-auth\n".toByteArray(StandardCharsets.US_ASCII) + salt))
        shared.fill(0); prk.fill(0)
        pair = null; token = ""
        phase = Phase.WELCOME
        return mapOf("type" to "AUTH", "v" to 2, "proof" to proof)
    }

    /** Caller must parse and validate the first encrypted JSON as WELCOME. */
    @Synchronized fun acceptWelcome() {
        check(phase == Phase.WELCOME && rxSeq == 1L) { "Authenticated WELCOME required" }
        phase = Phase.READY
    }

    @Synchronized fun encrypt(data: ByteArray): Map<String, Any> {
        check(phase == Phase.READY) { "Authentication required" }
        require(data.size <= 700000 && txSeq < MAX_SEQUENCE) { "Frame/session limit exceeded" }
        val seq = txSeq + 1
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(tx!!, "AES"), GCMParameterSpec(128, nonce(seq)))
        cipher.updateAAD("remote-viber-v2|c2s|$seq".toByteArray(StandardCharsets.US_ASCII))
        val encrypted = cipher.doFinal(data)
        txSeq = seq
        return mapOf("v" to 2, "seq" to seq, "data" to encode(encrypted))
    }

    @Synchronized fun decrypt(seq: Long, data: String): ByteArray {
        check(phase == Phase.WELCOME || phase == Phase.READY) { "No encrypted session" }
        require(seq == rxSeq + 1 && seq <= MAX_SEQUENCE) { "Replay or out-of-order frame" }
        val ciphertext = decode(data)
        require(ciphertext.size in 16..700016) { "Invalid frame size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rx!!, "AES"), GCMParameterSpec(128, nonce(seq)))
        cipher.updateAAD("remote-viber-v2|s2c|$seq".toByteArray(StandardCharsets.US_ASCII))
        val plaintext = cipher.doFinal(ciphertext)
        rxSeq = seq
        return plaintext
    }

    @Synchronized fun close() {
        phase = Phase.CLOSED
        pair = null; token = ""
        tx?.fill(0); rx?.fill(0)
        tx = null; rx = null
    }

    companion object {
        private const val MAX_SEQUENCE = 0xffffffffL
        fun fingerprint(pub: String): String = MessageDigest.getInstance("SHA-256")
            .digest(decode(pub, 65)).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun hostId(pub: String): String { readPublicKey(pub); return "host-" + fingerprint(pub) }
        private fun encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
        private fun decode(value: String, size: Int? = null): ByteArray {
            require(value.length <= 1024 * 1024) { "Invalid encoding size" }
            val bytes = Base64.getDecoder().decode(value)
            require(encode(bytes) == value && (size == null || size == bytes.size)) { "Invalid encoding" }
            return bytes
        }
        private fun fixed(value: BigInteger): ByteArray {
            val source = value.toByteArray()
            val unsigned = if (source.size == 33 && source[0] == 0.toByte()) source.copyOfRange(1, 33) else source
            require(unsigned.size <= 32)
            return ByteArray(32 - unsigned.size) + unsigned
        }
        private fun readPublicKey(value: String): ECPublicKey {
            val bytes = decode(value, 65)
            require(bytes[0] == 4.toByte()) { "Invalid key" }
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
            val prime = (params.curve.field as ECFieldFp).p
            val x = point.affineX; val y = point.affineY
            require(x < prime && y < prime && y.multiply(y).mod(prime) ==
                x.multiply(x).multiply(x).add(params.curve.a.multiply(x)).add(params.curve.b).mod(prime)) { "Invalid curve point" }
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
        }
        private fun rawSignatureToDer(raw: ByteArray): ByteArray {
            val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
            val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
            return byteArrayOf(0x30, (r.size + s.size + 4).toByte(), 0x02, r.size.toByte()) + r +
                byteArrayOf(0x02, s.size.toByte()) + s
        }
        private fun mac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        private fun nonce(seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()
    }
}
