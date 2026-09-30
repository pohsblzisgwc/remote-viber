package com.remoteviber.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.remoteviber.client.data.HostManager
import com.remoteviber.client.model.AgentProfile
import com.remoteviber.client.model.TerminalBuffer
import com.remoteviber.client.network.ViberWebSocketClient
import com.remoteviber.client.ui.components.HostManagerDialog
import com.remoteviber.client.ui.components.LaunchBottomSheet
import com.remoteviber.client.ui.components.TopBar
import com.remoteviber.client.ui.screens.DashboardScreen
import com.remoteviber.client.ui.screens.TerminalScreen
import com.remoteviber.client.ui.theme.ViberBg

@Composable
fun ViberMainApp(
    hostManager: HostManager,
    wsClient: ViberWebSocketClient,
    terminalBuffer: TerminalBuffer
) {
    val hosts by hostManager.hosts.collectAsState()
    val activeHost by hostManager.activeHost.collectAsState()

    val connectionStatus by wsClient.status.collectAsState()
    val connectionMode by wsClient.mode.collectAsState()
    val pingMs by wsClient.pingMs.collectAsState()
    val stats by wsClient.stats.collectAsState()
    val sessions by wsClient.sessions.collectAsState()
    val profiles by wsClient.profiles.collectAsState()
    val activeSessionId by wsClient.activeSessionId.collectAsState()
    val isHistoryTruncated by wsClient.isHistoryTruncated.collectAsState()
    val syncFullHistory by wsClient.syncFullHistory.collectAsState()
    val lastError by wsClient.lastError.collectAsState()
    val currentEndpoint by wsClient.currentEndpoint.collectAsState()

    var activeView by remember { mutableStateOf("dashboard") }
    var isLaunchSheetOpen by remember { mutableStateOf(false) }
    var isHostManagerOpen by remember { mutableStateOf(false) }
    var selectedProfileForLaunch by remember { mutableStateOf<AgentProfile?>(null) }

    // Auto connect whenever active host changes
    LaunchedEffect(activeHost) {
        activeHost?.let {
            wsClient.connect(it)
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                activeHost = activeHost,
                connectionStatus = connectionStatus,
                connectionMode = connectionMode,
                pingMs = pingMs,
                activeView = activeView,
                activeSessionsCount = sessions.count { it.status != "stopped" },
                lastError = lastError,
                onViewChange = { activeView = it },
                onQuickTerminal = {
                    wsClient.launchTerminal()
                    activeView = "terminal"
                },
                onOpenLaunch = {
                    selectedProfileForLaunch = null
                    isLaunchSheetOpen = true
                },
                onOpenHostManager = { isHostManagerOpen = true }
            )
        },
        containerColor = ViberBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(ViberBg)
        ) {
            if (activeView == "dashboard") {
                DashboardScreen(
                    stats = stats,
                    profiles = profiles,
                    sessions = sessions,
                    onQuickTerminal = {
                        wsClient.launchTerminal()
                        activeView = "terminal"
                    },
                    onOpenLaunch = {
                        selectedProfileForLaunch = null
                        isLaunchSheetOpen = true
                    },
                    onQuickLaunchPreset = { prof ->
                        wsClient.launchAgent(
                            name = prof.name,
                            command = prof.command,
                            cwd = prof.defaultCwd,
                            folder = prof.folder,
                            profileId = prof.id
                        )
                        activeView = "terminal"
                    },
                    onConfigurePreset = { prof ->
                        selectedProfileForLaunch = prof
                        isLaunchSheetOpen = true
                    },
                    onDeleteProfile = { id -> wsClient.deleteProfile(id) },
                    onAttachSession = { id ->
                        wsClient.attachSession(id)
                        activeView = "terminal"
                    },
                    onTerminateSession = { id -> wsClient.terminateSession(id) },
                    onRestartSession = { id -> wsClient.restartSession(id) },
                    onDeleteSession = { id -> wsClient.deleteSession(id) }
                )
            } else {
                TerminalScreen(
                    sessions = sessions,
                    activeSessionId = activeSessionId,
                    connectionStatus = connectionStatus,
                    terminalBuffer = terminalBuffer,
                    chatProcessor = wsClient.chatProcessor,
                    xtermController = wsClient.xtermController,
                    isHistoryTruncated = isHistoryTruncated,
                    syncFullHistory = syncFullHistory,
                    onToggleSyncFullHistory = { wsClient.toggleSyncFullHistory() },
                    onLoadFullHistory = { wsClient.loadFullHistoryNow() },
                    onSelectSession = { id -> wsClient.attachSession(id) },
                    onCloseSession = { id -> wsClient.deleteSession(id) },
                    onNewTerminal = { wsClient.launchTerminal() },
                    onReturnToDashboard = { activeView = "dashboard" },
                    onSendKey = { chars -> wsClient.sendInput(chars, false) },
                    onSendPrompt = { prompt -> wsClient.sendInput(prompt, true) },
                    onSendInputBase64 = { b64 -> wsClient.sendRawInputBase64(b64) },
                    onResizeTerminal = { rows, cols -> wsClient.resizeTerminal(rows, cols) }
                )
            }
        }
    }

    // Launch & Preset BottomSheet
    if (isLaunchSheetOpen) {
        LaunchBottomSheet(
            profiles = profiles,
            initialProfile = selectedProfileForLaunch,
            onDismiss = { isLaunchSheetOpen = false },
            onLaunch = { name, command, cwd, folder, profileId, keepAlive ->
                wsClient.launchAgent(
                    name = name,
                    command = command,
                    cwd = cwd,
                    folder = folder,
                    profileId = profileId,
                    keepAlive = keepAlive
                )
                activeView = "terminal"
            },
            onSaveProfile = { prof -> wsClient.saveProfile(prof) },
            onDeleteProfile = { id -> wsClient.deleteProfile(id) }
        )
    }

    // Host Manager Dialog
    if (isHostManagerOpen) {
        HostManagerDialog(
            hosts = hosts,
            activeHost = activeHost,
            currentEndpoint = currentEndpoint,
            lastError = lastError,
            onSelectHost = { id -> hostManager.setActiveHost(id) },
            onSaveHost = { h -> hostManager.saveHost(h) },
            onDeleteHost = { id -> hostManager.deleteHost(id) },
            onImportUrl = { url -> hostManager.importPairingUrl(url) },
            onDismiss = { isHostManagerOpen = false }
        )
    }
}
