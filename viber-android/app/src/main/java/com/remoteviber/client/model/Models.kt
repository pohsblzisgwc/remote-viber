package com.remoteviber.client.model

import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKE,
    CONNECTED,
    RECONNECTING,
    ERROR
}

enum class ConnectionMode {
    TAILSCALE,
    LAN,
    LOCALHOST,
    RELAY,
    UNKNOWN
}

data class HostProfile(
    val id: String,
    val name: String,
    val port: Int = 8765,
    val token: String = "",
    val tailscaleIps: List<String> = emptyList(),
    val lanIps: List<String> = emptyList(),
    val relayUrl: String = "",
    val ssl: Boolean = false,
    val directUrl: String = "",
    val lastConnected: Long = System.currentTimeMillis()
) {
    fun getPrimaryAddress(): String {
        return tailscaleIps.firstOrNull() ?: lanIps.firstOrNull() ?: "127.0.0.1"
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("port", port)
            put("token", token)
            put("tailscaleIps", JSONArray(tailscaleIps))
            put("lanIps", JSONArray(lanIps))
            put("relayUrl", relayUrl)
            put("ssl", ssl)
            put("directUrl", directUrl)
            put("lastConnected", lastConnected)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): HostProfile {
            val tsIps = mutableListOf<String>()
            json.optJSONArray("tailscaleIps")?.let { arr ->
                for (i in 0 until arr.length()) tsIps.add(arr.getString(i))
            }
            val lanList = mutableListOf<String>()
            json.optJSONArray("lanIps")?.let { arr ->
                for (i in 0 until arr.length()) lanList.add(arr.getString(i))
            }
            return HostProfile(
                id = json.optString("id", "host-default"),
                name = json.optString("name", "开发主机"),
                port = json.optInt("port", 8765),
                token = json.optString("token", ""),
                tailscaleIps = tsIps,
                lanIps = lanList,
                relayUrl = json.optString("relayUrl", "").takeIf { it != "null" } ?: "",
                ssl = json.optBoolean("ssl", false),
                directUrl = json.optString("directUrl", "").takeIf { it != "null" } ?: "",
                lastConnected = json.optLong("lastConnected", System.currentTimeMillis())
            )
        }
    }
}

data class AgentSession(
    val sessionId: String,
    val name: String,
    val profileId: String?,
    val folder: String,
    val sessionType: String,
    val command: String,
    val cwd: String,
    val pid: Int?,
    val status: String, // starting, running, waiting_input, idle, stopped
    val uptimeSeconds: Long,
    val currentSeq: Long
) {
    companion object {
        fun fromJsonObject(json: JSONObject): AgentSession {
            return AgentSession(
                sessionId = json.optString("session_id"),
                name = json.optString("name", "终端"),
                profileId = json.optString("profile_id").takeIf { it.isNotEmpty() },
                folder = json.optString("folder", "默认项目"),
                sessionType = json.optString("session_type", "agent"),
                command = json.optString("command", ""),
                cwd = json.optString("cwd", "/workspace"),
                pid = if (json.has("pid") && !json.isNull("pid")) json.getInt("pid") else null,
                status = json.optString("status", "running"),
                uptimeSeconds = json.optLong("uptime_seconds", 0L),
                currentSeq = json.optLong("current_seq", 0L)
            )
        }
    }
}

data class AgentProfile(
    val id: String,
    val name: String,
    val command: String,
    val defaultCwd: String = "/workspace",
    val folder: String = "",
    val category: String = "agent",
    val gradient: String = "from-cyan-600 via-blue-600 to-indigo-700",
    val icon: String = "bot"
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("command", command)
            put("default_cwd", defaultCwd)
            put("folder", folder)
            put("category", category)
            put("gradient", gradient)
            put("icon", icon)
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): AgentProfile {
            val cmdVal = json.opt("command")
            val cmdStr = when (cmdVal) {
                is JSONArray -> {
                    val list = mutableListOf<String>()
                    for (i in 0 until cmdVal.length()) list.add(cmdVal.getString(i))
                    list.joinToString(" ")
                }
                else -> cmdVal?.toString() ?: "bash"
            }
            return AgentProfile(
                id = json.optString("id", ""),
                name = json.optString("name", "自定义预设"),
                command = cmdStr,
                defaultCwd = json.optString("default_cwd", "/workspace"),
                folder = json.optString("folder", ""),
                category = json.optString("category", "agent"),
                gradient = json.optString("gradient", "from-cyan-600 via-blue-600 to-indigo-700"),
                icon = json.optString("icon", "bot")
            )
        }
    }
}

data class SystemStats(
    val cpuPercent: Double = 0.0,
    val ramPercent: Double = 0.0,
    val ramUsedGb: Double = 0.0,
    val ramTotalGb: Double = 0.0,
    val diskPercent: Double = 0.0,
    val netSentMb: Double = 0.0,
    val netRecvMb: Double = 0.0,
    val processCount: Int = 0
) {
    companion object {
        fun fromJsonObject(json: JSONObject): SystemStats {
            val cpu = json.optJSONObject("cpu")?.optDouble("percent", 0.0) ?: 0.0
            val mem = json.optJSONObject("memory")
            val ramPercent = mem?.optDouble("percent", 0.0) ?: 0.0
            val ramUsed = (mem?.optLong("used", 0L) ?: 0L) / (1024.0 * 1024.0 * 1024.0)
            val ramTotal = (mem?.optLong("total", 0L) ?: 0L) / (1024.0 * 1024.0 * 1024.0)
            val disk = json.optJSONObject("disk")?.optDouble("percent", 0.0) ?: 0.0
            val net = json.optJSONObject("network")
            val netSent = (net?.optLong("bytes_sent", 0L) ?: 0L) / (1024.0 * 1024.0)
            val netRecv = (net?.optLong("bytes_recv", 0L) ?: 0L) / (1024.0 * 1024.0)
            val procCount = json.optInt("process_count", 0)

            return SystemStats(
                cpuPercent = cpu,
                ramPercent = ramPercent,
                ramUsedGb = ramUsed,
                ramTotalGb = ramTotal,
                diskPercent = disk,
                netSentMb = netSent,
                netRecvMb = netRecv,
                processCount = procCount
            )
        }
    }
}
