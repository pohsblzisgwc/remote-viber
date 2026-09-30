package com.remoteviber.client.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteviber.client.model.AgentProfile
import com.remoteviber.client.model.AgentSession
import com.remoteviber.client.model.SystemStats
import com.remoteviber.client.ui.components.PresetCard
import com.remoteviber.client.ui.components.SessionCard
import com.remoteviber.client.ui.components.SystemMonitorCard
import com.remoteviber.client.ui.theme.*

@Composable
fun DashboardScreen(
    stats: SystemStats?,
    profiles: List<AgentProfile>,
    sessions: List<AgentSession>,
    onQuickTerminal: () -> Unit,
    onOpenLaunch: () -> Unit,
    onQuickLaunchPreset: (AgentProfile) -> Unit,
    onConfigurePreset: (AgentProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onAttachSession: (String) -> Unit,
    onTerminateSession: (String) -> Unit,
    onRestartSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    // Group active sessions by folder
    val groupedSessions = remember(sessions) {
        sessions.groupBy { it.folder.ifEmpty { "默认项目" } }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ViberBg),
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. System Monitor Card
        item {
            SystemMonitorCard(stats = stats)
        }

        // 2. Custom Presets Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = ViberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "自定义预设 (快捷拉起)",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onOpenLaunch,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = ViberCyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = "新增预设", color = ViberCyan, fontSize = 11.sp)
                    }
                }
            }
        }

        if (profiles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ViberCard)
                        .border(1.dp, ViberBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "暂无自定义预设",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "点击【新增预设】保存常用的 Docker 容器或多指令脚本",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        } else {
            items(profiles) { profile ->
                PresetCard(
                    profile = profile,
                    onQuickLaunch = onQuickLaunchPreset,
                    onConfigureLaunch = onConfigurePreset,
                    onDeleteProfile = onDeleteProfile
                )
            }
        }

        // 3. Active Sessions Matrix
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Window,
                        contentDescription = null,
                        tint = ViberEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "活动终端矩阵 (${sessions.size} 个会话)",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onQuickTerminal,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ViberCyan),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "📱 手机专属终端",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0A1526))
                    .border(0.6.dp, ViberCyan.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = ViberCyan,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "手机终端拥有独立 PTY 会话，严格屏蔽尺寸干扰，手机操作绝不影响电脑。",
                    color = TextSecondary,
                    fontSize = 10.sp
                )
            }
        }

        if (sessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ViberCard)
                        .border(1.dp, ViberBorder, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "当前无运行中的会话", color = TextSecondary, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = onQuickTerminal,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ViberCyan),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(text = "立即开启首个终端", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            groupedSessions.forEach { (folderName, sessList) ->
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = ViberPurple,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = folderName,
                            color = ViberPurple,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "(${sessList.size})",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                items(sessList) { session ->
                    SessionCard(
                        session = session,
                        onAttach = onAttachSession,
                        onTerminate = onTerminateSession,
                        onRestart = onRestartSession,
                        onDelete = onDeleteSession
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
