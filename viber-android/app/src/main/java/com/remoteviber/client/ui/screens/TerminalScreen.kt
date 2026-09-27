package com.remoteviber.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom when new terminal output arrives
    val lineCount = terminalBuffer.lines.size
    LaunchedEffect(lineCount) {
        if (lineCount > 0) {
            listState.scrollToItem(lineCount - 1)
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
                    items(terminalBuffer.lines) { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSizeSp.sp,
                            lineHeight = (fontSizeSp * 1.25).sp,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                                listState.animateScrollToItem(lineCount - 1)
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
                        Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Virtual Accessory Keyboard Bar
        VirtualKeyboardBar(
            onSendKey = onSendKey,
            onSendPrompt = onSendPrompt,
            onZoomIn = { if (fontSizeSp < 20) fontSizeSp += 1 },
            onZoomOut = { if (fontSizeSp > 8) fontSizeSp -= 1 }
        )
    }
}
