package com.remoteviber.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteviber.client.model.AgentSession
import com.remoteviber.client.ui.theme.*

@Composable
fun SessionCard(
    session: AgentSession,
    onAttach: (String) -> Unit,
    onTerminate: (String) -> Unit,
    onRestart: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val isStopped = session.status == "stopped"
    val isWaiting = session.status == "waiting_input"
    val statusColor = when {
        isStopped -> ViberRose
        isWaiting -> ViberAmber
        else -> ViberEmerald
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ViberCard)
            .border(1.dp, ViberBorder, RoundedCornerShape(12.dp))
            .clickable { onAttach(session.sessionId) }
            .padding(12.dp)
    ) {
        Column {
            // Header: Name + Status Dot + Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = session.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pid != null) {
                        Text(
                            text = "PID: ${session.pid}",
                            color = TextMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.15f))
                            .border(0.6.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isStopped) "已退出" else if (isWaiting) "等待输入" else "运行中",
                            color = statusColor,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Command Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF070B14))
                    .border(0.6.dp, ViberBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = session.command.ifEmpty { "bash" },
                    color = ViberCyan.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${session.folder} • ${session.cwd}",
                    color = TextMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Connect Terminal
                    Button(
                        onClick = { onAttach(session.sessionId) },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ViberCyan.copy(alpha = 0.2f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(text = "连接", color = ViberCyan, fontSize = 10.sp)
                    }

                    if (isStopped) {
                        Button(
                            onClick = { onRestart(session.sessionId) },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ViberEmerald.copy(alpha = 0.2f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(text = "重开", color = ViberEmerald, fontSize = 10.sp)
                        }
                    } else {
                        Button(
                            onClick = { onTerminate(session.sessionId) },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ViberRose.copy(alpha = 0.2f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(text = "关闭", color = ViberRose, fontSize = 10.sp)
                        }
                    }

                    IconButton(
                        onClick = { onDelete(session.sessionId) },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}
