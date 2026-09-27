package com.remoteviber.client.network

import android.util.Base64
import android.util.Log
import com.remoteviber.client.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.remoteviber.client.ui.components.XtermController

class ViberWebSocketClient(
    val terminalBuffer: TerminalBuffer,
    val chatProcessor: AgentChatStreamProcessor = AgentChatStreamProcessor(),
    val xtermController: XtermController = XtermController()
) {
    companion object {
        private const val TAG = "ViberWS"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS) // We handle custom PING frames
        .build()

    private var webSocket: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var activeHost: HostProfile? = null
    private var shouldReconnect = true
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    // Reactive StateFlows for Jetpack Compose UI
    private val _status = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val _mode = MutableStateFlow(ConnectionMode.UNKNOWN)
    val mode: StateFlow<ConnectionMode> = _mode.asStateFlow()

    private val _pingMs = MutableStateFlow(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    private val _stats = MutableStateFlow<SystemStats?>(null)
    val stats: StateFlow<SystemStats?> = _stats.asStateFlow()

    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val sessions: StateFlow<List<AgentSession>> = _sessions.asStateFlow()

    private val _profiles = MutableStateFlow<List<AgentProfile>>(emptyList())
    val profiles: StateFlow<List<AgentProfile>> = _profiles.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    private var lastReceivedSeq = 0L

    fun connect(host: HostProfile) {
        disconnect()
        activeHost = host
        shouldReconnect = true
        _status.value = ConnectionStatus.CONNECTING

        scope.launch {
            attemptConnection(host)
        }
    }

    private fun attemptConnection(host: HostProfile) {
        val targetIp = host.getPrimaryAddress()
        val url = "ws://$targetIp:${host.port}/"
        Log.i(TAG, "Connecting to $url with token ${host.token.take(4)}***")

        // Guess mode from IP
        _mode.value = when {
            targetIp.startsWith("100.") -> ConnectionMode.TAILSCALE
            targetIp == "127.0.0.1" || targetIp == "localhost" -> ConnectionMode.LOCALHOST
            targetIp.startsWith("192.168.") || targetIp.startsWith("10.") || targetIp.startsWith("172.") -> ConnectionMode.LAN
            else -> ConnectionMode.UNKNOWN
        }

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, createWebSocketListener())
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened, sending HELLO handshake")
                _status.value = ConnectionStatus.HANDSHAKE
                val hello = JSONObject().apply {
                    put("type", "HELLO")
                    put("client_id", "android-" + java.util.UUID.randomUUID().toString().take(6))
                    put("token", activeHost?.token ?: "")
                    put("e2ee", false) // Direct WireGuard/Tailscale encrypted transport
                }
                ws.send(hello.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    handleMessage(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closed: $code / $reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                handleDisconnect()
            }
        }
    }

    private fun handleMessage(msg: JSONObject) {
        val type = msg.optString("type")
        when (type) {
            "WELCOME" -> {
                Log.i(TAG, "Authenticated with Host!")
                _status.value = ConnectionStatus.CONNECTED
                startHeartbeat()
                getStats()
                listProfiles()

                // Re-attach active session if reconnecting
                _activeSessionId.value?.let { sessId ->
                    attachSession(sessId, lastReceivedSeq)
                }
            }
            "ERROR" -> {
                val error = msg.optString("error")
                Log.e(TAG, "Server error: $error")
                if (error.contains("token") || error.contains("Authentication")) {
                    _status.value = ConnectionStatus.ERROR
                    shouldReconnect = false
                }
            }
            "PONG" -> {
                val ts = msg.optLong("ts", 0L)
                if (ts > 0) {
                    _pingMs.value = (System.currentTimeMillis() - ts).coerceAtLeast(1L)
                }
            }
            "STATS" -> {
                msg.optJSONObject("system")?.let { sysJson ->
                    _stats.value = SystemStats.fromJsonObject(sysJson)
                }
                msg.optJSONArray("sessions")?.let { sessionsArr ->
                    val list = mutableListOf<AgentSession>()
                    for (i in 0 until sessionsArr.length()) {
                        list.add(AgentSession.fromJsonObject(sessionsArr.getJSONObject(i)))
                    }
                    _sessions.value = list
                }
            }
            "PROFILES" -> {
                msg.optJSONArray("profiles")?.let { profArr ->
                    val list = mutableListOf<AgentProfile>()
                    for (i in 0 until profArr.length()) {
                        list.add(AgentProfile.fromJsonObject(profArr.getJSONObject(i)))
                    }
                    _profiles.value = list
                }
            }
            "SESSION_ATTACHED" -> {
                val sessJson = msg.optJSONObject("session")
                if (sessJson != null) {
                    val sess = AgentSession.fromJsonObject(sessJson)
                    _activeSessionId.value = sess.sessionId
                    lastReceivedSeq = msg.optLong("current_seq", 0L)

                    if (msg.optBoolean("needs_reset", false)) {
                        terminalBuffer.clear()
                        chatProcessor.clear()
                        xtermController.clear()
                    }
                    msg.optJSONArray("replay")?.let { replayArr ->
                        for (i in 0 until replayArr.length()) {
                            val chunk = replayArr.getJSONObject(i)
                            val dataB64 = chunk.optString("data")
                            val raw = String(Base64.decode(dataB64, Base64.DEFAULT), Charsets.UTF_8)
                            terminalBuffer.append(raw)
                            chatProcessor.appendStreamText(raw)
                            xtermController.writeBase64(dataB64)
                        }
                    }
                }
            }
            "TERMINAL_OUTPUT" -> {
                val sessId = msg.optString("session_id")
                val seq = msg.optLong("seq", 0L)
                if (sessId == _activeSessionId.value) {
                    if (seq > lastReceivedSeq) {
                        lastReceivedSeq = seq
                    }
                    val dataB64 = msg.optString("data")
                    if (dataB64.isNotEmpty()) {
                        val raw = String(Base64.decode(dataB64, Base64.DEFAULT), Charsets.UTF_8)
                        terminalBuffer.append(raw)
                        chatProcessor.appendStreamText(raw)
                        xtermController.writeBase64(dataB64)
                    }
                }
            }
            "AGENT_LAUNCHED" -> {
                msg.optJSONObject("session")?.let { sessJson ->
                    val newSess = AgentSession.fromJsonObject(sessJson)
                    val curr = _sessions.value.filter { it.sessionId != newSess.sessionId }
                    _sessions.value = curr + newSess
                    _activeSessionId.value = newSess.sessionId
                    attachSession(newSess.sessionId, 0L)
                }
            }
            "AGENT_TERMINATED" -> {
                val termId = msg.optString("session_id")
                _sessions.value = _sessions.value.map {
                    if (it.sessionId == termId) it.copy(status = "stopped") else it
                }
            }
            "SESSION_DELETED" -> {
                val delId = msg.optString("session_id")
                _sessions.value = _sessions.value.filter { it.sessionId != delId }
                if (_activeSessionId.value == delId) {
                    _activeSessionId.value = _sessions.value.firstOrNull()?.sessionId
                }
            }
        }
    }

    private fun handleDisconnect() {
        stopHeartbeat()
        if (!shouldReconnect || _status.value == ConnectionStatus.ERROR) {
            _status.value = ConnectionStatus.DISCONNECTED
            return
        }
        _status.value = ConnectionStatus.RECONNECTING
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000)
            activeHost?.let {
                if (_status.value != ConnectionStatus.CONNECTED) {
                    attemptConnection(it)
                }
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        pingJob = scope.launch {
            while (isActive && _status.value == ConnectionStatus.CONNECTED) {
                delay(4000)
                send(JSONObject().apply {
                    put("type", "PING")
                    put("ts", System.currentTimeMillis())
                })
                getStats()
            }
        }
    }

    private fun stopHeartbeat() {
        pingJob?.cancel()
        pingJob = null
    }

    fun disconnect() {
        shouldReconnect = false
        stopHeartbeat()
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _status.value = ConnectionStatus.DISCONNECTED
    }

    // High-Level Actions
    fun send(json: JSONObject): Boolean {
        return webSocket?.send(json.toString()) ?: false
    }

    fun getStats() {
        send(JSONObject().put("type", "GET_STATS"))
    }

    fun listProfiles() {
        send(JSONObject().put("type", "LIST_PROFILES"))
    }

    fun saveProfile(profile: AgentProfile) {
        val obj = JSONObject().apply {
            put("type", "SAVE_PROFILE")
            put("profile", profile.toJsonObject())
        }
        send(obj)
    }

    fun deleteProfile(profileId: String) {
        val obj = JSONObject().apply {
            put("type", "DELETE_PROFILE")
            put("profile_id", profileId)
        }
        send(obj)
    }

    fun launchAgent(
        name: String,
        command: String,
        cwd: String = "/workspace",
        folder: String = "",
        profileId: String? = null,
        keepAlive: Boolean = false
    ) {
        val obj = JSONObject().apply {
            put("type", "LAUNCH_AGENT")
            put("name", name)
            put("command", command)
            put("cwd", cwd)
            put("folder", folder)
            if (!profileId.isNullOrEmpty()) {
                put("profile_id", profileId)
            }
            put("keep_alive", keepAlive)
        }
        send(obj)
    }

    fun launchTerminal(cwd: String = "/workspace", folder: String = "") {
        val obj = JSONObject().apply {
            put("type", "LAUNCH_TERMINAL")
            put("cwd", cwd)
            put("folder", folder)
        }
        send(obj)
    }

    fun attachSession(sessionId: String, lastSeq: Long = 0L) {
        _activeSessionId.value = sessionId
        val obj = JSONObject().apply {
            put("type", "ATTACH_SESSION")
            put("session_id", sessionId)
            put("last_seq", lastSeq)
        }
        send(obj)
    }

    fun sendInput(data: String, isUserPrompt: Boolean = false) {
        val sessId = _activeSessionId.value ?: return
        if (isUserPrompt && data.isNotBlank()) {
            chatProcessor.appendUserPrompt(data.trimEnd())
        }
        val b64 = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val obj = JSONObject().apply {
            put("type", "TERMINAL_INPUT")
            put("session_id", sessId)
            put("data", b64)
        }
        send(obj)
    }

    fun sendRawInputBase64(b64: String) {
        val sessId = _activeSessionId.value ?: return
        val obj = JSONObject().apply {
            put("type", "TERMINAL_INPUT")
            put("session_id", sessId)
            put("data", b64)
        }
        send(obj)
    }

    fun resizeTerminal(rows: Int, cols: Int) {
        val sessId = _activeSessionId.value ?: return
        val obj = JSONObject().apply {
            put("type", "RESIZE_TERMINAL")
            put("session_id", sessId)
            put("rows", rows)
            put("cols", cols)
        }
        send(obj)
    }

    fun terminateSession(sessionId: String) {
        val obj = JSONObject().apply {
            put("type", "TERMINATE_AGENT")
            put("session_id", sessionId)
        }
        send(obj)
    }

    fun deleteSession(sessionId: String) {
        val obj = JSONObject().apply {
            put("type", "DELETE_SESSION")
            put("session_id", sessionId)
        }
        send(obj)
    }

    fun restartSession(sessionId: String) {
        val obj = JSONObject().apply {
            put("type", "RESTART_SESSION")
            put("session_id", sessionId)
        }
        send(obj)
    }
}
