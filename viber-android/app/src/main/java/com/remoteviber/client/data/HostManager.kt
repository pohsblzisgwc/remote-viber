package com.remoteviber.client.data

import android.content.Context
import android.widget.Toast
import com.remoteviber.client.model.HostProfile
import com.remoteviber.client.network.ProtocolV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class HostManager(private val context: Context) {
    data class PairingCandidate(val profile: HostProfile, val hostPub: String, val directUrl: String)
    private val storage = SecureHostStorage(context)
    private val _hosts = MutableStateFlow<List<HostProfile>>(emptyList())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()
    private val _activeHost = MutableStateFlow<HostProfile?>(null)
    val activeHost: StateFlow<HostProfile?> = _activeHost.asStateFlow()

    companion object {
        private val pins = ConcurrentHashMap<String, String>()
        private val directUrls = ConcurrentHashMap<String, String>()
        fun pinnedKey(id: String): String? = pins[id]
        fun directUrl(id: String): String? = directUrls[id]
    }

    init {
        // v1 storage lacks a trusted pin. Never auto-upgrade that trust.
        context.getSharedPreferences("viber_hosts", Context.MODE_PRIVATE).edit().clear().commit()
        loadHosts()
    }
    private fun loadHosts() {
        pins.clear(); directUrls.clear()
        try {
            val raw = storage.read() ?: return
            val root = JSONObject(raw)
            val arr = root.getJSONArray("hosts")
            require(arr.length() <= 32)
            val loadedPins = mutableMapOf<String, String>()
            val loadedUrls = mutableMapOf<String, String>()
            val list = mutableListOf<HostProfile>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                var profile = HostProfile.fromJsonObject(entry.getJSONObject("profile"))
                val pub = entry.getString("pub")
                require(profile.id == ProtocolV2.hostId(pub))
                require(profile.token.length in 32..256)
                val directUrl = entry.optString("direct_url", "").takeIf { it != "null" } ?: profile.directUrl
                if (profile.directUrl.isBlank() && directUrl.isNotBlank()) {
                    profile = profile.copy(directUrl = directUrl)
                }
                list.add(profile)
                loadedPins[profile.id] = pub
                loadedUrls[profile.id] = directUrl
            }
            pins.putAll(loadedPins); directUrls.putAll(loadedUrls)
            _hosts.value = list
            _activeHost.value = list.find { it.id == root.optString("active") } ?: list.firstOrNull()
        } catch (_: Exception) {
            pins.clear(); directUrls.clear()
            Toast.makeText(context, "无法验证已保存的配对信息，请重新配对", Toast.LENGTH_LONG).show()
        }
    }

    @Synchronized private fun persist(list: List<HostProfile>, activeId: String?,
                                      newPins: Map<String, String> = pins,
                                      newUrls: Map<String, String> = directUrls) {
        require(list.size <= 32)
        val arr = JSONArray()
        list.forEach { profile ->
            val pub = newPins[profile.id] ?: error("Missing trusted public key")
            require(ProtocolV2.hostId(pub) == profile.id)
            val dUrl = (newUrls[profile.id] ?: profile.directUrl).takeIf { it != "null" } ?: ""
            arr.put(JSONObject().put("profile", profile.toJsonObject()).put("pub", pub)
                .put("direct_url", dUrl))
        }
        storage.write(JSONObject().put("hosts", arr).put("active", activeId ?: "").toString())
        val retainedPins = list.associate { it.id to (newPins[it.id] ?: error("Missing key")) }
        val retainedUrls = list.associate { it.id to (newUrls[it.id] ?: "") }
        pins.clear(); pins.putAll(retainedPins)
        directUrls.clear(); directUrls.putAll(retainedUrls)
        _hosts.value = list
        _activeHost.value = list.find { it.id == activeId } ?: list.firstOrNull()
    }
    fun saveHost(profile: HostProfile) {
        if (pins[profile.id] == null) {
            Toast.makeText(context, "新主机必须导入 v2 配对链接并确认身份", Toast.LENGTH_LONG).show()
            return
        }
        persist(_hosts.value.filter { it.id != profile.id } + profile, profile.id)
    }
    fun deleteHost(id: String) {
        val next = _hosts.value.filter { it.id != id }
        persist(next, if (_activeHost.value?.id == id) next.firstOrNull()?.id else _activeHost.value?.id)
    }
    fun setActiveHost(id: String) {
        if (_hosts.value.any { it.id == id }) persist(_hosts.value, id)
    }
    fun savePairing(candidate: PairingCandidate): HostProfile {
        val profile = candidate.profile
        require(profile.id == ProtocolV2.hostId(candidate.hostPub))
        val previous = pins[profile.id]
        require(previous == null || previous == candidate.hostPub) { "Existing host identity changed" }
        val updatedProfile = if (profile.directUrl.isBlank() && candidate.directUrl.isNotBlank()) {
            profile.copy(directUrl = candidate.directUrl)
        } else {
            profile
        }
        persist(_hosts.value.filter { it.id != profile.id } + updatedProfile, profile.id,
            pins.toMap() + (profile.id to candidate.hostPub), directUrls.toMap() + (profile.id to candidate.directUrl))
        return updatedProfile
    }
    /** Pure preview parser. No storage, selected-host change or network access. */
    fun parsePairingUrl(url: String): PairingCandidate? = try {
        val trimmed = url.trim().trim('"', '\'')
        require(trimmed.length in 10..16384)
        val encoded = if (trimmed.startsWith("viber://")) {
            val uri = URI(trimmed)
            require(uri.scheme == "viber" && uri.host == "connect" && uri.path.isNullOrEmpty() && uri.fragment == null)
            val parts = uri.rawQuery?.split('&') ?: error("Missing pairing data")
            require(parts.size == 1 && parts[0].startsWith("data="))
            parts[0].substringAfter('=')
        } else {
            trimmed
        }
        require(encoded.matches(Regex("[A-Za-z0-9_-]+={0,2}")))
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val json = JSONObject(String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8))
        require(json.optInt("v") == 2)
        val pub = json.getString("pub")
        val id = json.getString("id")
        require(id == ProtocolV2.hostId(pub))
        val token = json.getString("token")
        require(token.length in 32..256 && token.all { it.code in 33..126 })
        val port = json.getInt("port"); require(port in 1..65535)

        fun addresses(key: String): List<String> {
            val arr = json.optJSONArray(key) ?: JSONArray()
            require(arr.length() <= 16)
            return (0 until arr.length()).map { i ->
                arr.getString(i).also { require(it.isNotEmpty() && it.length <= 253 && !it.contains(Regex("[\\s/?#@%]"))) }
            }
        }

        fun endpoint(value: String?, relay: Boolean): String {
            if (value.isNullOrBlank() || value == "null") return ""
            var norm = value.trim()
            if (norm.startsWith("https://")) {
                norm = "wss://" + norm.removePrefix("https://")
            } else if (norm.startsWith("http://")) {
                norm = "ws://" + norm.removePrefix("http://")
            }
            val parsed = URI(norm)
            require(parsed.scheme in listOf("ws", "wss") && parsed.host != null && parsed.userInfo == null && parsed.query == null && parsed.fragment == null)
            if (relay && parsed.scheme == "ws") {
                require(parsed.host in listOf("localhost", "127.0.0.1", "[::1]", "::1"))
            }
            return norm.trimEnd('/')
        }

        val directRaw = if (json.isNull("direct_url")) "" else json.optString("direct_url", "")
        val directUrl = endpoint(directRaw, false)
        val relayRaw = if (json.isNull("relay")) "" else json.optString("relay", "")
        val relayUrl = endpoint(relayRaw, true)
        val ssl = json.optBoolean("ssl", false) || directUrl.startsWith("wss://")

        val profile = HostProfile(
            id = id,
            name = json.optString("name", "远程主机").take(128),
            port = port,
            token = token,
            tailscaleIps = addresses("tailscale"),
            lanIps = addresses("lan"),
            relayUrl = relayUrl,
            ssl = ssl,
            directUrl = directUrl
        )
        PairingCandidate(profile, pub, directUrl)
    } catch (_: Exception) { null }

    /** Retained for the existing in-app Import button (an explicit user action). */
    fun importPairingUrl(url: String): HostProfile? {
        val candidate = parsePairingUrl(url) ?: return null
        return try { savePairing(candidate) } catch (_: Exception) { null }
    }
}
