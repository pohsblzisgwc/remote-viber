package com.remoteviber.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteviber.client.model.AgentChatStreamProcessor
import com.remoteviber.client.model.AgentSession
import com.remoteviber.client.model.ConnectionStatus
import com.remoteviber.client.model.TerminalBuffer
import com.remoteviber.client.ui.components.AgentChatView
import com.remoteviber.client.ui.components.SessionTabs
import com.remoteviber.client.ui.components.VirtualKeyboardBar
import com.remoteviber.client.ui.components.Xterm2DView
import com.remoteviber.client.ui.components.XtermController
import com.remoteviber.client.ui.theme.*

@Composable
fun TerminalScreen(
    sessions: List<AgentSession>,
    activeSessionId: String?,
    connectionStatus: ConnectionStatus,
    terminalBuffer: TerminalBuffer,
    chatProcessor: AgentChatStreamProcessor,
    xtermController: XtermController,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onReturnToDashboard: () -> Unit,
    onSendKey: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onSendInputBase64: (String) -> Unit,
    onResizeTerminal: (Int, Int) -> Unit
) {
    // Mode switcher: "chat" (Agent 对话卡片流 - 默认推荐) vs "xterm" (2D 虚拟终端)
    var displayMode by remember { mutableStateOf("chat") }
    var fontSizeSp by remember { mutableStateOf(12) }
    var localCommandInput by remember { mutableStateOf("") }

    val submitCommand = {
        val text = localCommandInput.trim()
        if (text.isNotEmpty()) {
            chatProcessor.appendUserPrompt(text)
            terminalBuffer.appendLocalEcho(text)
            onSendPrompt(text + "\n")
            localCommandInput = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ViberBg)
    ) {
        // Session Tabs Bar
        SessionTabs(
            sessions = sessions,
            activeSessionId = activeSessionId,
            onSelectSession = onSelectSession,
            onCloseSession = onCloseSession,
            onNewTerminal = onNewTerminal,
            onReturnToDashboard = onReturnToDashboard
        )

        // Dual-Mode Segmented Controller & Status Sub-header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF070B14))
                .border(width = 0.5.dp, color = ViberBorder)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Segmented Switcher Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .border(width = 0.5.dp, color = ViberBorder, shape = RoundedCornerShape(8.dp))
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode A: Agent Chat & Action Stream (Default)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (displayMode == "chat") ViberCyan else Color.Transparent)
                        .clickable { displayMode = "chat" }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = "Chat Mode",
                            tint = if (displayMode == "chat") Color.Black else TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "智能对话流",
                            color = if (displayMode == "chat") Color.Black else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Mode B: Full 2D xterm Virtual Terminal
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (displayMode == "xterm") ViberCyan else Color.Transparent)
                        .clickable {
                            displayMode = "xterm"
                            xtermController.refit()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "xterm 2D Mode",
                            tint = if (displayMode == "xterm") Color.Black else TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "2D 终端",
                            color = if (displayMode == "xterm") Color.Black else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Quick Info & Actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (displayMode == "chat") {
                    Text(
                        text = "自动去破损换行",
                        color = ViberEmerald,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ViberEmerald.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                } else {
                    // Zoom buttons for 2D Terminal
                    TextButton(
                        onClick = { if (fontSizeSp > 8) fontSizeSp -= 1 },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("A-", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(
                        onClick = { if (fontSizeSp < 22) fontSizeSp += 1 },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("A+", color = ViberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Clear current view
                TextButton(
                    onClick = {
                        if (displayMode == "chat") {
                            chatProcessor.clear()
                        } else {
                            xtermController.clear()
                            terminalBuffer.clear()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear",
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(text = "清屏", color = TextMuted, fontSize = 10.sp)
                }
            }
        }

        // Reconnection Notice Banner
        if (connectionStatus != ConnectionStatus.CONNECTED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ViberAmber.copy(alpha = 0.15f))
                    .border(1.dp, ViberAmber.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync",
                        tint = ViberAmber,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "连接中断 — 宿主会话持续保活中，正在自动恢复...",
                        color = ViberAmber,
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "后台持续保活",
                    color = ViberAmber.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Viewport Area (Switchable between Chat & 2D xterm)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (activeSessionId == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "未选中终端会话", color = TextSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onNewTerminal) {
                            Text("开启新终端")
                        }
                    }
                }
            } else {
                if (displayMode == "chat") {
                    AgentChatView(
                        chatProcessor = chatProcessor,
                        modifier = Modifier.fillMaxSize(),
                        onSendDecision = { decision ->
                            chatProcessor.appendUserPrompt(decision.trimEnd())
                            terminalBuffer.appendLocalEcho(decision.trimEnd())
                            onSendPrompt(decision)
                        }
                    )
                } else {
                    Xterm2DView(
                        controller = xtermController,
                        fontSizeSp = fontSizeSp,
                        modifier = Modifier.fillMaxSize(),
                        onSendInputBase64 = onSendInputBase64,
                        onResize = onResizeTerminal
                    )
                }
            }
        }

        // Quick Signal & Approval Chips (One-tap for mobile thumbs)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0A0F1C))
                .border(width = 0.5.dp, color = ViberBorder)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ActionPill("y (确定)", ViberEmerald) {
                chatProcessor.appendUserPrompt("y")
                terminalBuffer.appendLocalEcho("y")
                onSendPrompt("y\n")
            }
            ActionPill("n (取消)", ViberRose) {
                chatProcessor.appendUserPrompt("n")
                terminalBuffer.appendLocalEcho("n")
                onSendPrompt("n\n")
            }
            ActionPill("↵ 回车", TextPrimary) {
                onSendKey("\r")
            }
            ActionPill("^C 中断", ViberRose) {
                chatProcessor.appendSystemNotice("[已发送 Ctrl+C 中断信号]", ViberRose)
                terminalBuffer.appendSystemNotice("[已发送 Ctrl+C 中断信号]", ViberRose)
                onSendKey("\u0003")
            }
            ActionPill("^D EOF", ViberCyan) {
                onSendKey("\u0004")
            }
            ActionPill("git status", TextSecondary) {
                chatProcessor.appendUserPrompt("git status")
                terminalBuffer.appendLocalEcho("git status")
                onSendPrompt("git status\n")
            }
            ActionPill("docker ps", TextSecondary) {
                chatProcessor.appendUserPrompt("docker ps")
                terminalBuffer.appendLocalEcho("docker ps")
                onSendPrompt("docker ps\n")
            }
        }

        // Local Optimistic Command Bar (Eliminates single-key network latency)
        Surface(
            color = ViberSurface,
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = ViberBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = localCommandInput,
                    onValueChange = { localCommandInput = it },
                    placeholder = {
                        Text(
                            text = if (displayMode == "chat") "向 Agent 发送指令，回车发送..." else "本地编辑指令，回车发送...",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submitCommand() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ViberCyan,
                        unfocusedBorderColor = ViberBorder,
                        focusedContainerColor = Color(0xFF070B14),
                        unfocusedContainerColor = Color(0xFF070B14)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = { submitCommand() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (localCommandInput.isNotBlank()) ViberCyan else ViberCard)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Command",
                        tint = if (localCommandInput.isNotBlank()) Color.Black else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Virtual Accessory Keyboard Bar (Only displayed in xterm 2D mode where terminal keys are critical)
        if (displayMode == "xterm") {
            VirtualKeyboardBar(
                onSendKey = onSendKey,
                onSendPrompt = onSendPrompt,
                onZoomIn = { if (fontSizeSp < 22) fontSizeSp += 1 },
                onZoomOut = { if (fontSizeSp > 8) fontSizeSp -= 1 }
            )
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ViberCard)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.height(18.dp)
        ) {
            Text(
                text = label,
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
