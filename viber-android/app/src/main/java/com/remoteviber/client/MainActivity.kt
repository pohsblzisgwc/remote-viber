package com.remoteviber.client

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
        setContent {
            RemoteViberTheme { ViberMainApp(hostManager = hostManager, wsClient = wsClient, terminalBuffer = terminalBuffer) }
        }
        checkExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkExternalIntent(intent)
    }

    private fun checkExternalIntent(intent: Intent?) {
        if (intent?.data != null) {
            intent.data = null
            Toast.makeText(
                this,
                "为防止配对密钥被第三方截获，外部链接自动配对已停用。请在应用内【主机管理】手动粘贴配对码以安全导入。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        wsClient.disconnect()
        super.onDestroy()
    }
}
