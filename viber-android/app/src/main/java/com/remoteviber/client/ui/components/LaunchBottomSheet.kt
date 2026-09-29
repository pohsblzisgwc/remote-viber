package com.remoteviber.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteviber.client.model.AgentProfile
import com.remoteviber.client.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchBottomSheet(
    profiles: List<AgentProfile>,
    initialProfile: AgentProfile? = null,
    onDismiss: () -> Unit,
    onLaunch: (name: String, command: String, cwd: String, folder: String, profileId: String?, keepAlive: Boolean) -> Unit,
    onSaveProfile: (AgentProfile) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var selectedProfileId by remember { mutableStateOf(initialProfile?.id ?: "custom") }
    var nameInput by remember { mutableStateOf(initialProfile?.name ?: "") }
    var commandInput by remember { mutableStateOf(initialProfile?.command ?: "bash") }
    var cwdInput by remember { mutableStateOf(initialProfile?.defaultCwd ?: "/workspace") }
    var folderInput by remember { mutableStateOf(initialProfile?.folder ?: "") }
    var keepAlive by remember { mutableStateOf(false) }
    var saveSuccessFeedback by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ViberSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = ViberBorderLight) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PlayCircleOutline,
                        contentDescription = null,
                        tint = ViberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "启动 Agent / Docker / 终端",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Presets Horizontal Row
            Text(
                text = "选择或调用已保存预设:",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Blank Custom Chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selectedProfileId == "custom") ViberCyan.copy(alpha = 0.15f) else ViberCard)
                        .border(
                            1.dp,
                            if (selectedProfileId == "custom") ViberCyan else ViberBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            selectedProfileId = "custom"
                            nameInput = ""
                            commandInput = "bash"
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "+ 自定义配置",
                        color = if (selectedProfileId == "custom") ViberCyan else TextSecondary,
                        fontSize = 11.sp
                    )
                }

                // Saved profiles
                profiles.forEach { prof ->
                    val isSelected = prof.id == selectedProfileId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) ViberCyan.copy(alpha = 0.15f) else ViberCard)
                            .border(
                                1.dp,
                                if (isSelected) ViberCyan else ViberBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                selectedProfileId = prof.id
                                nameInput = prof.name
                                commandInput = prof.command
                                cwdInput = prof.defaultCwd
                                folderInput = prof.folder
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = prof.name,
                                color = if (isSelected) ViberCyan else TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete",
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(13.dp)
                                    .clickable { onDeleteProfile(prof.id) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Name & Folder Inputs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "会话 / 预设名称", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    ViberTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = "例如: Docker Agent",
                        singleLine = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "所属项目", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    ViberTextField(
                        value = folderInput,
                        onValueChange = { folderInput = it },
                        placeholder = "例如: main-app",
                        singleLine = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Multi-instruction command editor
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "执行指令 / 多行脚本 (支持 Docker 容器):",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Quick snippet chips
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SnippetChip("+ Codex 防截断") {
                            commandInput = "codex --no-alt-screen"
                        }
                        SnippetChip("+ Docker") {
                            commandInput = "docker run -it --rm -v \"$(pwd):/workspace\" -w /workspace ubuntu bash"
                        }
                        SnippetChip("+ Compose") {
                            commandInput = "docker compose up -d && docker exec -it $(docker compose ps -q | head -n1) bash"
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                ViberTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    placeholder = "# 支持多行与链式指令，例如:\ncd /workspace\ndocker run -it --rm -v $(pwd):/app ubuntu bash",
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = ViberCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "⚡ 在宿主独立 PTY 虚拟终端中运行，完整支持 Docker -it。",
                        color = TextMuted,
                        fontSize = 10.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { keepAlive = !keepAlive }
                    ) {
                        Checkbox(
                            checked = keepAlive,
                            onCheckedChange = { keepAlive = it },
                            colors = CheckboxDefaults.colors(checkedColor = ViberCyan)
                        )
                        Text(text = "保持 Shell 打开", color = TextSecondary, fontSize = 10.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // CWD Input
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "目标工作目录 (CWD)", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                ViberTextField(
                    value = cwdInput,
                    onValueChange = { cwdInput = it },
                    placeholder = "/workspace",
                    singleLine = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Save Preset
                OutlinedButton(
                    onClick = {
                        val profile = AgentProfile(
                            id = if (selectedProfileId == "custom") "" else selectedProfileId,
                            name = nameInput.ifEmpty { "自定义预设" },
                            command = commandInput.ifEmpty { "bash" },
                            defaultCwd = cwdInput.ifEmpty { "/workspace" },
                            folder = folderInput,
                            category = if (commandInput.contains("docker")) "docker" else "agent"
                        )
                        onSaveProfile(profile)
                        saveSuccessFeedback = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (saveSuccessFeedback) ViberEmerald else ViberBorderLight),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ViberCard,
                        contentColor = if (saveSuccessFeedback) ViberEmerald else TextPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (saveSuccessFeedback) Icons.Default.Check else Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (saveSuccessFeedback) "已保存预设!" else "存为预设",
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Cancel
                    TextButton(onClick = onDismiss) {
                        Text(text = "取消", color = TextSecondary, fontSize = 12.sp)
                    }

                    // Launch
                    Button(
                        onClick = {
                            onLaunch(
                                nameInput.ifEmpty { if (commandInput.contains("docker")) "Docker 容器" else "终端 Agent" },
                                commandInput.ifEmpty { "bash" },
                                cwdInput.ifEmpty { "/workspace" },
                                folderInput,
                                if (selectedProfileId != "custom") selectedProfileId else null,
                                keepAlive
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ViberCyan)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "立即启动", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SnippetChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ViberCard)
            .border(0.6.dp, ViberBorderLight, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, color = ViberCyan, fontSize = 9.sp)
    }
}
