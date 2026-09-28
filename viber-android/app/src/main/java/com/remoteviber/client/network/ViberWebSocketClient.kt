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
    private val securityLock = Any()
    private var connectionGeneration = 0L
    private var secureSession: ProtocolV2? = null
    private var handshakeJob: Job? = null
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
        val changed = activeHost?.id != host.id
        disconnect()
        synchronized(securityLock) {
            activeHost = host
            if (changed) { _activeSessionId.value = null; lastReceivedSeq = 0L }
            shouldReconnect = true
            _status.value = ConnectionStatus.CONNECTING
            val generation = connectionGeneration
            scope.launch { attemptConnection(host, generation) }
        }
    }

    private fun attemptConnection(host: HostProfile, expectedGeneration: Long = connectionGeneration) {
        synchronized(securityLock) {
            if (!shouldReconnect || activeHost?.id != host.id || connectionGeneration != expectedGeneration) return
            try {
                val pin = com.remoteviber.client.data.HostManager.pinnedKey(host.id) ?: error("Re-pair required")
                val crypto = ProtocolV2(pin, host.token)
                secureSession = crypto
                val generation = ++connectionGeneration
                val address = host.getPrimaryAddress()
                require(address.isNotBlank() && !address.contains(Regex("[\\s/?#@%]")))
                val formatted = if (address.contains(':') && !address.startsWith('[')) "[$address]" else address
                val url = com.remoteviber.client.data.HostManager.directUrl(host.id).orEmpty()
                    .ifEmpty { "ws://$formatted:${host.port}/ws" }
                _mode.value = when {
                    address == "127.0.0.1" || address == "localhost" || address == "::1" -> ConnectionMode.LOCALHOST
                    else -> ConnectionMode.UNKNOWN // Display only; never a trust decision.
                }
                val request = Request.Builder().url(url).build()
                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener(generation, crypto))
            } catch (_: Exception) {
                shouldReconnect = false
                secureSession?.close(); secureSession = null
                _status.value = ConnectionStatus.ERROR
                Log.e(TAG, "Secure connection configuration is invalid; re-pair required")
            }
        }
    }

    private fun createWebSocketListener(generation: Long, crypto: ProtocolV2): WebSocketListener {
        return object : WebSocketListener() {
            private fun current() = generation == connectionGeneration && secureSession === crypto
            private fun reject(ws: WebSocket) {
                if (!current()) return
                shouldReconnect = false
                _status.value = ConnectionStatus.ERROR
                handshakeJob?.cancel(); stopHeartbeat()
                crypto.close(); secureSession = null
                ws.close(1008, "Authentication or protocol failure")
                if (webSocket === ws) webSocket = null
                Log.e(TAG, "Rejected unauthenticated, replayed or invalid protocol message")
            }
            override fun onOpen(ws: WebSocket, response: Response) {
                synchronized(securityLock) {
                    if (!current()) { ws.close(1000, "Superseded"); return }
                    try {
                        _status.value = ConnectionStatus.HANDSHAKE
                        check(ws.send(JSONObject(crypto.hello()).toString()))
                        handshakeJob?.cancel()
                        handshakeJob = scope.launch {
                            delay(10000)
                            synchronized(securityLock) { if (current() && crypto.phase != ProtocolV2.Phase.READY) reject(ws) }
                        }
                    } catch (_: Exception) { reject(ws) }
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                synchronized(securityLock) {
                    if (!current()) return
                    try {
                        require(text.length <= 1024 * 1024)
                        val frame = JSONObject(text)
                        if (crypto.phase == ProtocolV2.Phase.HELLO) {
                            val challenge = frame.keys().asSequence().associateWith { frame.get(it) }
                            check(ws.send(JSONObject(crypto.authenticate(challenge)).toString()))
                            return
                        }
                        require(frame.keys().asSequence().toSet() == setOf("v", "seq", "data") && frame.opt("v") == 2)
                        val seq = when (val value = frame.opt("seq")) {
                            is Int -> value.toLong()
                            is Long -> value
                            else -> error("Invalid sequence")
                        }
                        val encrypted = frame.get("data") as? String ?: error("Invalid ciphertext")
                        val message = JSONObject(String(crypto.decrypt(seq, encrypted), Charsets.UTF_8))
                        if (crypto.phase == ProtocolV2.Phase.WELCOME) {
                            require(message.optString("type") == "WELCOME" && message.opt("v") == 2 &&
                                message.opt("e2ee") == true && message.optString("status") == "authenticated")
                            crypto.acceptWelcome(); handshakeJob?.cancel()
                        } else {
                            require(message.optString("type") !in setOf("HELLO", "AUTH", "CHALLENGE", "WELCOME"))
                        }
                        handleMessage(message) // Only authenticated decrypted application data reaches the UI.
                    } catch (_: Exception) { reject(ws) }
                }
            }
            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                synchronized(securityLock) { reject(ws) }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                synchronized(securityLock) {
                    if (!current()) return
                    if (code == 1008) { shouldReconnect = false; _status.value = ConnectionStatus.ERROR }
                    handleDisconnect()
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                synchronized(securityLock) { if (current()) handleDisconnect() }
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
        handshakeJob?.cancel(); stopHeartbeat()
        secureSession?.close(); secureSession = null
        webSocket = null
        if (_status.value == ConnectionStatus.ERROR) return
        if (!shouldReconnect) { _status.value = ConnectionStatus.DISCONNECTED; return }
        _status.value = ConnectionStatus.RECONNECTING
        scheduleReconnect()
    }
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val generation = connectionGeneration
        reconnectJob = scope.launch {
            delay(3000)
            synchronized(securityLock) {
                val host = activeHost
                if (host != null && shouldReconnect && connectionGeneration == generation && _status.value != ConnectionStatus.CONNECTED) {
                    attemptConnection(host, generation)
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
        synchronized(securityLock) {
            shouldReconnect = false
            ++connectionGeneration
            handshakeJob?.cancel(); handshakeJob = null
            stopHeartbeat(); reconnectJob?.cancel()
            val previous = webSocket
            webSocket = null
            secureSession?.close(); secureSession = null
            previous?.close(1000, "User disconnected")
            _status.value = ConnectionStatus.DISCONNECTED
        }
    }

    // High-Level Actions
    fun send(json: JSONObject): Boolean = synchronized(securityLock) {
        val crypto = secureSession ?: return@synchronized false
        val ws = webSocket ?: return@synchronized false
        if (_status.value != ConnectionStatus.CONNECTED || crypto.phase != ProtocolV2.Phase.READY) return@synchronized false
        try {
            require(ws.queueSize() <= 1024 * 1024)
            // Allocate counter, encrypt and enqueue on the same lock.
            val sent = ws.send(JSONObject(crypto.encrypt(json.toString().toByteArray(Charsets.UTF_8))).toString())
            if (!sent) { ws.cancel(); handleDisconnect() }
            sent
        } catch (_: Exception) {
            shouldReconnect = false; _status.value = ConnectionStatus.ERROR
            crypto.close(); secureSession = null
            ws.close(1008, "Secure send failed")
            false
        }
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
