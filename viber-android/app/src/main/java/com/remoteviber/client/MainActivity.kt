package com.remoteviber.client

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.remoteviber.client.data.HostManager
import com.remoteviber.client.model.TerminalBuffer
import com.remoteviber.client.network.ViberWebSocketClient
import com.remoteviber.client.ui.ViberMainApp
import com.remoteviber.client.ui.theme.RemoteViberTheme

class MainActivity : ComponentActivity() {
    private lateinit var hostManager: HostManager
    private lateinit var terminalBuffer: TerminalBuffer
    private lateinit var wsClient: ViberWebSocketClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hostManager = HostManager(applicationContext)
        terminalBuffer = TerminalBuffer(maxLines = 4000)
        wsClient = ViberWebSocketClient(terminalBuffer)

        // Handle deep link pairing (viber://connect?data=...)
        handleDeepLink(intent)

        setContent {
            RemoteViberTheme {
                ViberMainApp(
                    hostManager = hostManager,
                    wsClient = wsClient,
                    terminalBuffer = terminalBuffer
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uriStr = intent?.data?.toString()
        if (!uriStr.isNullOrEmpty()) {
            val imported = hostManager.importPairingUrl(uriStr)
            if (imported != null) {
                hostManager.setActiveHost(imported.id)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wsClient.disconnect()
    }
}
