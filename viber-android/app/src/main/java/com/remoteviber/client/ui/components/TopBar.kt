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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteviber.client.model.ConnectionMode
import com.remoteviber.client.model.ConnectionStatus
import com.remoteviber.client.model.HostProfile
import com.remoteviber.client.ui.theme.*

@Composable
fun TopBar(
    activeHost: HostProfile?,
    connectionStatus: ConnectionStatus,
    connectionMode: ConnectionMode,
    pingMs: Long,
    activeView: String, // "dashboard" | "terminal"
    activeSessionsCount: Int,
    lastError: String? = null,
    onViewChange: (String) -> Unit,
    onQuickTerminal: () -> Unit,
    onOpenLaunch: () -> Unit,
    onOpenHostManager: () -> Unit
) {
    Surface(
        color = ViberSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = ViberBorder)
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Logo & Host Info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenHostManager() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(ViberCyan, ViberIndigo, ViberPurple)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Logo",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "REMOTE",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "VIBER",
                                color = ViberCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Host Name & Status
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = when (connectionStatus) {
                                ConnectionStatus.CONNECTED -> ViberCyan
                                ConnectionStatus.CONNECTING, ConnectionStatus.HANDSHAKE -> ViberAmber
                                ConnectionStatus.RECONNECTING -> ViberAmber
                                else -> ViberRose
                            }
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = activeHost?.name ?: "未配置主机",
                                color = TextSecondary,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Center-Right: Mode / Ping Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (modeText, modeColor) = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> {
                            when (connectionMode) {
                                ConnectionMode.TAILSCALE -> "Tailscale 直连" to ViberCyan
                                ConnectionMode.LAN -> "局域网直连" to ViberEmerald
                                ConnectionMode.LOCALHOST -> "本地连接" to ViberEmerald
                                else -> "中继直连" to ViberPurple
                            }
                        }
                        ConnectionStatus.CONNECTING -> "连接中..." to ViberAmber
                        ConnectionStatus.HANDSHAKE -> "握手中..." to ViberAmber
                        ConnectionStatus.RECONNECTING -> "正在重连..." to ViberAmber
                        ConnectionStatus.ERROR -> (lastError?.substringAfterLast("] ")?.take(8) ?: "连接错误") to ViberRose
                        else -> "未连接" to TextMuted
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(modeColor.copy(alpha = 0.12f))
                            .border(0.8.dp, modeColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .clickable { onOpenHostManager() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = modeText,
                            color = modeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (connectionStatus == ConnectionStatus.CONNECTED && pingMs > 0) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${pingMs}ms",
                            color = ViberEmerald,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Host Settings Button
                    IconButton(
                        onClick = onOpenHostManager,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Host Manager",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Navigation Bar (Dashboard vs Terminal) & Quick Launch Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // View Switcher Tabs
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ViberCard)
                        .border(1.dp, ViberBorder, RoundedCornerShape(8.dp))
                        .padding(2.dp)
                ) {
                    val isDash = activeView == "dashboard"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isDash) ViberCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isDash) ViberCyan.copy(alpha = 0.4f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { onViewChange("dashboard") }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = if (isDash) ViberCyan else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "控制面板",
                                color = if (isDash) ViberCyan else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (isDash) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (!isDash) ViberCyan.copy(alpha = 0.15f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (!isDash) ViberCyan.copy(alpha = 0.4f) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { onViewChange("terminal") }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = if (!isDash) ViberCyan else TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "终端工作台",
                                color = if (!isDash) ViberCyan else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (!isDash) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (activeSessionsCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ViberCyan)
                                )
                            }
                        }
                    }
                }

                // Action Buttons: Quick Terminal & Launch Agent
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // New Terminal
                    OutlinedButton(
                        onClick = onQuickTerminal,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = ViberCard,
                            contentColor = TextPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ViberBorderLight),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = ViberCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(text = "开终端", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Launch Agent
                    Button(
                        onClick = onOpenLaunch,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ViberCyan),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "启动",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
