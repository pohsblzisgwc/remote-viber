package com.remoteviber.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
fun SessionTabs(
    sessions: List<AgentSession>,
    activeSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onReturnToDashboard: () -> Unit
) {
    Surface(
        color = ViberSurface,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .border(1.dp, ViberBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Return to Dashboard Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ViberCyan.copy(alpha = 0.15f))
                    .border(0.8.dp, ViberCyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .clickable { onReturnToDashboard() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ViberCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "看板",
                        color = ViberCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Tabs Horizontal Scroll
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sessions.forEach { session ->
                    val isActive = session.sessionId == activeSessionId
                    val isMobile = session.name.contains("📱") || session.name.contains("手机") || session.name.contains("Mobile")
                    val isStopped = session.status == "stopped"
                    val isWaiting = session.status == "waiting_input"
                    val statusDotColor = when {
                        isStopped -> ViberRose
                        isWaiting -> ViberAmber
                        else -> ViberEmerald
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (isActive) ViberCardHover else ViberSurface)
                            .border(
                                1.dp,
                                if (isActive) ViberBorderLight else Color.Transparent,
                                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                            )
                            .clickable { onSelectSession(session.sessionId) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isMobile) "📱 " + session.name.replace("📱", "").trim() else "💻 " + session.name,
                                color = if (isActive) ViberCyan else TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                            if (isActive && !isMobile) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "[保护]",
                                    color = ViberEmerald,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Tab",
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(12.dp)
                                    .clickable { onCloseSession(session.sessionId) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Add Dedicated Mobile Terminal Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ViberCyan.copy(alpha = 0.12f))
                        .border(0.6.dp, ViberCyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .clickable { onNewTerminal() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Terminal",
                            tint = ViberCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "手机终端",
                            color = ViberCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
