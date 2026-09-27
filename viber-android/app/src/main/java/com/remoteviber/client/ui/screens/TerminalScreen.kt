package com.remoteviber.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Sync
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
import com.remoteviber.client.model.AgentSession
import com.remoteviber.client.model.ConnectionStatus
import com.remoteviber.client.model.TerminalBuffer
import com.remoteviber.client.ui.components.SessionTabs
import com.remoteviber.client.ui.components.VirtualKeyboardBar
import com.remoteviber.client.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    sessions: List<AgentSession>,
    activeSessionId: String?,
    connectionStatus: ConnectionStatus,
    terminalBuffer: TerminalBuffer,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onReturnToDashboard: () -> Unit,
    onSendKey: (String) -> Unit,
    onSendPrompt: (String) -> Unit
) {
    var fontSizeSp by remember { mutableStateOf(11) }
    var localCommandInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val lineCount = terminalBuffer.lines.size
    val activeLineText = terminalBuffer.activeLine.text

    // Auto-scroll to bottom when new terminal output arrives
    LaunchedEffect(lineCount, activeLineText) {
        if (lineCount > 0) {
            listState.scrollToItem(lineCount)
        }
    }

    val submitCommand = {
        val text = localCommandInput.trim()
        if (text.isNotEmpty()) {
            terminalBuffer.appendLocalEcho(text)
            onSendPrompt(text + "\n")
            localCommandInput = ""
            coroutineScope.launch {
                if (terminalBuffer.lines.isNotEmpty()) {
                    listState.animateScrollToItem(terminalBuffer.lines.size)
                }
            }
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

        // Low-Latency Stream Mode Sub-header & Quick Action Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF070B14))
                .border(width = 0.5.dp, color = ViberBorder)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ViberCyan.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Fast Stream",
                            tint = ViberCyan,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "极速低延迟降级流",
                            color = ViberCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${lineCount} 行日志",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Clear Buffer Button
                TextButton(
                    onClick = { terminalBuffer.clear() },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear",
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(text = "清屏", color = TextMuted, fontSize = 9.sp)
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

        // Terminal Output Screen
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Committed history lines
                    items(terminalBuffer.lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * 1.25).sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Active in-progress line (live prompt / spinner without trailing newline)
                    if (terminalBuffer.activeLine.text.isNotEmpty()) {
                        item {
                            Text(
                                text = terminalBuffer.activeLine,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp * 1.25).sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Scroll to Bottom FAB
                val isAtBottom = remember {
                    derivedStateOf {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= lineCount - 2
                    }
                }

                if (!isAtBottom.value && lineCount > 0) {
                    SmallFloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(lineCount)
                            }
                        },
                        containerColor = ViberCyan,
                        contentColor = Color.Black,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(18.dp)
                        )
                    }
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
                terminalBuffer.appendLocalEcho("y")
                onSendPrompt("y\n")
            }
            ActionPill("n (取消)", ViberRose) {
                terminalBuffer.appendLocalEcho("n")
                onSendPrompt("n\n")
            }
            ActionPill("↵ 回车", TextPrimary) {
                onSendKey("\r")
            }
            ActionPill("^C 中断", ViberRose) {
                terminalBuffer.appendSystemNotice("[已发送 Ctrl+C 中断信号]", ViberRose)
                onSendKey("\u0003")
            }
            ActionPill("^D EOF", ViberCyan) {
                onSendKey("\u0004")
            }
            ActionPill("git status", TextSecondary) {
                terminalBuffer.appendLocalEcho("git status")
                onSendPrompt("git status\n")
            }
            ActionPill("docker ps", TextSecondary) {
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
                            text = "本地编辑指令/Prompt，回车或点发送...",
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

        // Virtual Accessory Keyboard Bar (ESC, TAB, CTRL, Arrows, Font Zoom)
        VirtualKeyboardBar(
            onSendKey = onSendKey,
            onSendPrompt = onSendPrompt,
            onZoomIn = { if (fontSizeSp < 22) fontSizeSp += 1 },
            onZoomOut = { if (fontSizeSp > 8) fontSizeSp -= 1 }
        )
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
