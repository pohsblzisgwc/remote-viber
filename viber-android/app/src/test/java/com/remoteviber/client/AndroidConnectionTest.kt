package com.remoteviber.client

import com.remoteviber.client.data.HostManager
import com.remoteviber.client.model.ConnectionMode
import com.remoteviber.client.model.HostProfile
import com.remoteviber.client.model.TerminalBuffer
import com.remoteviber.client.network.ProtocolV2
import com.remoteviber.client.network.ViberWebSocketClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64

class AndroidConnectionTest {

    private fun generateTestHostKeys(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val pair = kpg.generateKeyPair()
        val pub = pair.public as java.security.interfaces.ECPublicKey
        val rawPub = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)
        val pubB64 = Base64.getEncoder().encodeToString(rawPub)
        val token = "A".repeat(32)
        return Pair(pubB64, token)
    }

    private fun fixed(value: BigInteger): ByteArray {
        val source = value.toByteArray()
        val unsigned = if (source.size == 33 && source[0] == 0.toByte()) source.copyOfRange(1, 33) else source
        return ByteArray(32 - unsigned.size) + unsigned
    }

    @Test
    fun testParsePairingWithNullDirectUrl() {
        val (pub, token) = generateTestHostKeys()
        val hostId = ProtocolV2.hostId(pub)
        val payload = JSONObject().apply {
            put("v", 2)
            put("id", hostId)
            put("name", "Test Server")
            put("port", 8765)
            put("pub", pub)
            put("token", token)
            put("relay", "")
            put("direct_url", JSONObject.NULL)
            put("ssl", true)
            put("tailscale", org.json.JSONArray(listOf("100.64.0.1")))
            put("lan", org.json.JSONArray(listOf("192.168.1.100", "127.0.0.1")))
        }

        val b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8))
        val fullUri = "viber://connect?data=$b64Payload"

        // Mock context not needed since parsePairingUrl does not touch storage
        val hm = HostManager(DummyContext())
        val candidate = hm.parsePairingUrl(fullUri)
        assertNotNull("Pairing with null direct_url must succeed", candidate)
        assertEquals(hostId, candidate!!.profile.id)
        assertEquals(8765, candidate.profile.port)
        assertTrue(candidate.profile.ssl)
        assertEquals("", candidate.directUrl)
        assertTrue(candidate.profile.tailscaleIps.contains("100.64.0.1"))
        assertTrue(candidate.profile.lanIps.contains("192.168.1.100"))
    }

    @Test
    fun testParseRawPairingCodeDirectly() {
        val (pub, token) = generateTestHostKeys()
        val hostId = ProtocolV2.hostId(pub)
        val payload = JSONObject().apply {
            put("v", 2)
            put("id", hostId)
            put("name", "Terminal Box")
            put("port", 8991)
            put("pub", pub)
            put("token", token)
            put("relay", "")
            put("direct_url", "https://viber.example.com/")
            put("ssl", false)
            put("tailscale", org.json.JSONArray())
            put("lan", org.json.JSONArray(listOf("127.0.0.1")))
        }

        val b64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8))

        val hm = HostManager(DummyContext())
        // Directly pass raw pairing code without viber:// prefix
        val candidate = hm.parsePairingUrl("  \"$b64Payload\"  ")
        assertNotNull("Pasting raw pairing code directly must succeed", candidate)
        assertEquals(hostId, candidate!!.profile.id)
        assertEquals("wss://viber.example.com", candidate.directUrl)
        assertTrue("https direct_url must upgrade ssl to true", candidate.profile.ssl)
    }

    @Test
    fun testCandidateEndpointsPrioritizesSslWhenEnabled() {
        val profile = HostProfile(
            id = "host-test",
            name = "Dev Server",
            port = 8765,
            token = "T".repeat(32),
            tailscaleIps = listOf("100.1.2.3"),
            lanIps = listOf("192.168.1.50", "127.0.0.1"),
            ssl = true,
            directUrl = "https://custom.host:8765"
        )

        val client = ViberWebSocketClient(TerminalBuffer(100))
        val method = ViberWebSocketClient::class.java.getDeclaredMethod("getCandidateEndpoints", HostProfile::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val candidates = method.invoke(client, profile) as List<ViberWebSocketClient.CandidateEndpoint>

        assertTrue("Candidates must not be empty", candidates.isNotEmpty())
        // Direct URL normalized with /ws
        assertEquals("wss://custom.host:8765/ws", candidates[0].url)
        // Tailscale wss candidates
        assertTrue(candidates.any { it.url == "wss://100.1.2.3:8765/ws" && it.mode == ConnectionMode.TAILSCALE })
        // LAN wss candidates
        assertTrue(candidates.any { it.url == "wss://192.168.1.50:8765/ws" && it.mode == ConnectionMode.LAN })
        // Android emulator localhost candidate
        assertTrue(candidates.any { it.url == "wss://10.0.2.2:8765/ws" && it.mode == ConnectionMode.LOCALHOST })
    }

    @Test
    fun testProtocolV2HandshakeFlow() {
        val (pub, token) = generateTestHostKeys()
        val client = ProtocolV2(pub, token)
        val hello = client.hello()
        assertEquals("HELLO", hello["type"])
        assertEquals(2, hello["v"])
        assertNotNull(hello["client_pub"])
        assertNotNull(hello["client_nonce"])
    }
}

class DummyContext : android.content.ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
        return DummyPreferences()
    }
}

class DummyPreferences : android.content.SharedPreferences {
    private val data = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = DummyEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class DummyEditor(private val data: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { data[key ?: ""] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor = this
    override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor = this
    override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor = this
    override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor = this
    override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor = this
    override fun remove(key: String?): android.content.SharedPreferences.Editor { data.remove(key); return this }
    override fun clear(): android.content.SharedPreferences.Editor { data.clear(); return this }
    override fun commit(): Boolean = true
    override fun apply() {}
}
