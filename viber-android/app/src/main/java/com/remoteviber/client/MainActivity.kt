package com.remoteviber.client

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.remoteviber.client.data.HostManager
import com.remoteviber.client.model.TerminalBuffer
import com.remoteviber.client.network.ProtocolV2
import com.remoteviber.client.network.ViberWebSocketClient
import com.remoteviber.client.ui.ViberMainApp
import com.remoteviber.client.ui.theme.RemoteViberTheme

class MainActivity : ComponentActivity() {
    private lateinit var hostManager: HostManager
    private lateinit var terminalBuffer: TerminalBuffer
    private var pairingDialog: AlertDialog? = null
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
        handleDeepLink(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }
    private fun handleDeepLink(intent: Intent?) {
        val raw = intent?.data?.toString() ?: return
        pairingDialog?.dismiss(); pairingDialog = null
        intent.data = null // Do not replay the request after a configuration change.
        val candidate = hostManager.parsePairingUrl(raw)
        if (candidate == null) {
            Toast.makeText(this, "无效或过期的配对链接，请从升级后的主机重新获取", Toast.LENGTH_LONG).show()
            return
        }
        val fingerprint = ProtocolV2.fingerprint(candidate.hostPub)
        pairingDialog = AlertDialog.Builder(this)
            .setTitle("确认信任主机身份")
            .setMessage("主机：${candidate.profile.name}\n\nSHA-256：\n$fingerprint\n\n请在主机终端核对此完整指纹。确认会保存凭据并切换主机；名称不是身份凭证。")
            .setNegativeButton("取消", null)
            .setPositiveButton("已核对，确认信任") { _, _ ->
                try { hostManager.savePairing(candidate) }
                catch (_: Exception) { Toast.makeText(this, "无法保存配对，原配置未更改", Toast.LENGTH_LONG).show() }
            }.show()
    }
    override fun onDestroy() { pairingDialog?.dismiss(); wsClient.disconnect(); super.onDestroy() }
}
