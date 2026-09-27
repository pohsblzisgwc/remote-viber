package com.remoteviber.client.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.remoteviber.client.model.HostProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class HostManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("viber_hosts", Context.MODE_PRIVATE)

    private val _hosts = MutableStateFlow<List<HostProfile>>(emptyList())
    val hosts: StateFlow<List<HostProfile>> = _hosts.asStateFlow()

    private val _activeHost = MutableStateFlow<HostProfile?>(null)
    val activeHost: StateFlow<HostProfile?> = _activeHost.asStateFlow()

    init {
        loadHosts()
    }

    private fun loadHosts() {
        val raw = prefs.getString("saved_hosts", "[]") ?: "[]"
        val list = mutableListOf<HostProfile>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(HostProfile.fromJsonObject(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (list.isEmpty()) {
            // Default fallback
            val default = HostProfile(
                id = "host-devbox",
                name = "开发主机 (Tailscale / 本地)",
                port = 8765,
                token = "",
                tailscaleIps = listOf("100.125.28.78"),
                lanIps = listOf("127.0.0.1")
            )
            list.add(default)
        }

        _hosts.value = list
        val activeId = prefs.getString("active_host_id", list.firstOrNull()?.id)
        _activeHost.value = list.find { it.id == activeId } ?: list.firstOrNull()
    }

    fun saveHost(profile: HostProfile) {
        val current = _hosts.value.toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        persist(current, profile.id)
    }

    fun deleteHost(id: String) {
        val current = _hosts.value.filter { it.id != id }
        val newActive = if (_activeHost.value?.id == id) current.firstOrNull()?.id else _activeHost.value?.id
        persist(current, newActive)
    }

    fun setActiveHost(id: String) {
        val found = _hosts.value.find { it.id == id }
        if (found != null) {
            _activeHost.value = found
            prefs.edit().putString("active_host_id", id).apply()
        }
    }

    private fun persist(list: List<HostProfile>, activeId: String?) {
        _hosts.value = list
        _activeHost.value = list.find { it.id == activeId } ?: list.firstOrNull()

        val arr = JSONArray()
        list.forEach { arr.put(it.toJsonObject()) }
        prefs.edit()
            .putString("saved_hosts", arr.toString())
            .putString("active_host_id", activeId)
            .apply()
    }

    /**
     * Parses pairing URL:
     * - "viber://connect?data=..."
     * - "http://100.x.x.x:8765/?token=..."
     */
    fun importPairingUrl(url: String): HostProfile? {
        try {
            val uri = URI(url.trim())

            // 1. viber://connect?data=...
            if (uri.scheme == "viber") {
                val query = uri.query ?: ""
                val dataParam = query.split('&').find { it.startsWith("data=") }?.substringAfter("data=")
                if (!dataParam.isNullOrEmpty()) {
                    val padded = dataParam + "=".repeat((4 - (dataParam.length % 4)) % 4)
                    val decodedBytes = try {
                        Base64.decode(padded, Base64.URL_SAFE)
                    } catch (e: Exception) {
                        Base64.decode(padded, Base64.DEFAULT)
                    }
                    val json = JSONObject(String(decodedBytes, Charsets.UTF_8))

                    val tsList = mutableListOf<String>()
                    json.optJSONArray("tailscale")?.let { arr ->
                        for (i in 0 until arr.length()) tsList.add(arr.getString(i))
                    }
                    val lanList = mutableListOf<String>()
                    json.optJSONArray("lan")?.let { arr ->
                        for (i in 0 until arr.length()) lanList.add(arr.getString(i))
                    }

                    val profile = HostProfile(
                        id = json.optString("id", "host-" + System.currentTimeMillis()),
                        name = json.optString("name", "远程开发机"),
                        port = json.optInt("port", 8765),
                        token = json.optString("token", ""),
                        tailscaleIps = tsList,
                        lanIps = lanList,
                        relayUrl = json.optString("relay", "")
                    )
                    saveHost(profile)
                    return profile
                }
            }

            // 2. http://100.x.x.x:8765/?token=...
            if (uri.scheme == "http" || uri.scheme == "https") {
                val host = uri.host ?: "127.0.0.1"
                val port = if (uri.port != -1) uri.port else 8765
                var token = ""
                uri.query?.split('&')?.forEach { part ->
                    if (part.startsWith("token=")) token = part.substringAfter("token=")
                }

                val profile = HostProfile(
                    id = "host-" + host.replace(".", "-"),
                    name = "主机 ($host)",
                    port = port,
                    token = token,
                    tailscaleIps = if (host.startsWith("100.")) listOf(host) else emptyList(),
                    lanIps = if (!host.startsWith("100.")) listOf(host) else emptyList()
                )
                saveHost(profile)
                return profile
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
