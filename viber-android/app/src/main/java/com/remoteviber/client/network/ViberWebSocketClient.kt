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
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

import com.remoteviber.client.ui.components.XtermController

class ViberWebSocketClient(
    val terminalBuffer: TerminalBuffer,
    val chatProcessor: AgentChatStreamProcessor = AgentChatStreamProcessor(),
    val xtermController: XtermController = XtermController()
) {
    companion object {
        private const val TAG = "ViberWS"
    }

    private val okHttpClient: OkHttpClient = run {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS) // We handle custom PING frames
            .build()
    }

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

    private val _isHistoryTruncated = MutableStateFlow(false)
    val isHistoryTruncated: StateFlow<Boolean> = _isHistoryTruncated.asStateFlow()

    private val _syncFullHistory = MutableStateFlow(false)
    val syncFullHistory: StateFlow<Boolean> = _syncFullHistory.asStateFlow()

    fun toggleSyncFullHistory() {
        val next = !_syncFullHistory.value
        _syncFullHistory.value = next
        _activeSessionId.value?.let { sessId ->
            if (next) {
                attachSession(sessId, 0L, true)
            }
        }
    }

    fun loadFullHistoryNow() {
        _activeSessionId.value?.let { sessId ->
            attachSession(sessId, 0L, true)
        }
    }

    private var lastReceivedSeq = 0L
    private var candidateIndex = 0

    data class CandidateEndpoint(val url: String, val mode: ConnectionMode)

    private fun getCandidateEndpoints(host: HostProfile): List<CandidateEndpoint> {
        val list = mutableListOf<CandidateEndpoint>()
        val seen = mutableSetOf<String>()

        fun add(url: String, mode: ConnectionMode) {
            val trimmed = url.trim()
            if (trimmed.isNotBlank() && seen.add(trimmed)) {
                list.add(CandidateEndpoint(trimmed, mode))
            }
        }

        val direct = host.directUrl.ifBlank { com.remoteviber.client.data.HostManager.directUrl(host.id).orEmpty() }
        if (direct.isNotBlank() && direct != "null") {
            var directWs = direct
            if (directWs.startsWith("https://")) {
                directWs = "wss://" + directWs.removePrefix("https://")
            } else if (directWs.startsWith("http://")) {
                directWs = "ws://" + directWs.removePrefix("http://")
            }
            try {
                val uri = URI(directWs)
                if (uri.path.isNullOrEmpty() || uri.path == "/") {
                    val portPart = if (uri.port > 0) ":${uri.port}" else ""
                    val hostPart = if (uri.host.contains(':') && !uri.host.startsWith('[')) "[${uri.host}]" else uri.host
                    directWs = "${uri.scheme}://$hostPart$portPart/ws"
                }
            } catch (_: Exception) {}
            add(directWs, ConnectionMode.UNKNOWN)
        }

        val useSsl = host.ssl || direct.startsWith("wss://") || direct.startsWith("https://")

        val addHost = { ip: String, mode: ConnectionMode ->
            if (ip.isNotBlank() && !ip.contains(Regex("[\\s/?#@%]"))) {
                val formatted = if (ip.contains(':') && !ip.startsWith('[')) "[$ip]" else ip
                if (useSsl) {
                    add("wss://$formatted:${host.port}/ws", mode)
                    add("ws://$formatted:${host.port}/ws", mode)
                } else {
                    add("ws://$formatted:${host.port}/ws", mode)
                    add("wss://$formatted:${host.port}/ws", mode)
                }
            }
        }

        for (ip in host.tailscaleIps) {
            addHost(ip, ConnectionMode.TAILSCALE)
        }

        for (ip in host.lanIps) {
            if (ip != "127.0.0.1" && ip != "localhost" && ip != "::1") {
                addHost(ip, ConnectionMode.LAN)
            }
        }

        if (host.relayUrl.isNotBlank() && host.relayUrl != "null") {
            val relayBase = host.relayUrl.trimEnd('/').let {
                if (it.startsWith("https://")) "wss://" + it.removePrefix("https://")
                else if (it.startsWith("http://")) "ws://" + it.removePrefix("http://")
                else it
            }
            add("$relayBase/connect/client?host_id=${host.id}", ConnectionMode.RELAY)
        }

        for (ip in host.lanIps) {
            if (ip == "127.0.0.1" || ip == "localhost" || ip == "::1") {
                addHost("10.0.2.2", ConnectionMode.LOCALHOST)
                addHost(ip, ConnectionMode.LOCALHOST)
            }
        }

        if (list.isEmpty()) {
            val address = host.getPrimaryAddress()
            addHost(address, ConnectionMode.UNKNOWN)
        }
        return list
    }

    fun connect(host: HostProfile) {
        val changed = activeHost?.id != host.id
        disconnect()
        synchronized(securityLock) {
            activeHost = host
            _activeSessionId.value = null
            lastReceivedSeq = 0L
            candidateIndex = 0
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
                val candidates = getCandidateEndpoints(host)
                val target = candidates[candidateIndex % candidates.size]
                _mode.value = target.mode
                Log.d(TAG, "Attempting connection to ${target.url} [${target.mode}] (candidate $candidateIndex/${candidates.size})")
                val request = Request.Builder().url(target.url).build()
                webSocket = okHttpClient.newWebSocket(request, createWebSocketListener(generation, crypto))
            } catch (e: Exception) {
                if (e.message == "Re-pair required") {
                    shouldReconnect = false
                    secureSession?.close(); secureSession = null
                    _status.value = ConnectionStatus.ERROR
                    Log.e(TAG, "Secure connection configuration is invalid; re-pair required", e)
                } else {
                    Log.w(TAG, "Connection initiation failed for current candidate, trying next", e)
                    handleDisconnect()
                }
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
                        try {
                            handleMessage(message) // Only authenticated decrypted application data reaches the UI.
                        } catch (appEx: Exception) {
                            Log.e(TAG, "Error handling decrypted application message: ${message.optString("type")}", appEx)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Protocol or authentication failure: ${e.message}", e)
                        reject(ws)
                    }
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
                Log.w(TAG, "WebSocket connection failed: ${t.message} (response: $response)")
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
                    if (_activeSessionId.value == null && list.isNotEmpty()) {
                        attachSession(list.first().sessionId, 0L)
                    } else if (_activeSessionId.value != null && list.none { it.sessionId == _activeSessionId.value }) {
                        if (list.isNotEmpty()) {
                            attachSession(list.first().sessionId, 0L)
                        } else {
                            _activeSessionId.value = null
                            terminalBuffer.clear()
                            chatProcessor.clear()
                            xtermController.clear()
                        }
                    }
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
                    _isHistoryTruncated.value = msg.optBoolean("is_truncated", false)

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
        candidateIndex++
        _status.value = ConnectionStatus.RECONNECTING
        scheduleReconnect()
    }
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val generation = connectionGeneration
        reconnectJob = scope.launch {
            delay(1500)
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

    fun attachSession(sessionId: String, lastSeq: Long = 0L, fullHistory: Boolean = _syncFullHistory.value) {
        val switching = _activeSessionId.value != sessionId
        _activeSessionId.value = sessionId
        if (switching) {
            terminalBuffer.clear()
            chatProcessor.clear()
            xtermController.clear()
            lastReceivedSeq = 0L
        }
        val obj = JSONObject().apply {
            put("type", "ATTACH_SESSION")
            put("session_id", sessionId)
            put("last_seq", if (switching) 0L else lastSeq)
            put("full_history", fullHistory)
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
