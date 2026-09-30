package com.remoteviber.client.ui.components

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remoteviber.client.model.HostProfile
import com.remoteviber.client.ui.theme.*

@Composable
fun HostManagerDialog(
    hosts: List<HostProfile>,
    activeHost: HostProfile?,
    currentEndpoint: String? = null,
    lastError: String? = null,
    onSelectHost: (String) -> Unit,
    onSaveHost: (HostProfile) -> Unit,
    onDeleteHost: (String) -> Unit,
    onImportUrl: (String) -> HostProfile?,
    onDismiss: () -> Unit
) {
    var importInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<HostProfile?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ViberSurface)
                .border(1.dp, ViberBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = ViberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "目标主机与网络管理",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Connection diagnostics status if error or connecting
                if (!lastError.isNullOrBlank() || !currentEndpoint.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (lastError != null) ViberRose.copy(alpha = 0.1f) else ViberCyan.copy(alpha = 0.08f))
                            .border(0.8.dp, if (lastError != null) ViberRose.copy(alpha = 0.35f) else ViberCyan.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column {
                            if (!currentEndpoint.isNullOrBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Sensors,
                                        contentDescription = null,
                                        tint = ViberCyan,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "当前尝试端点: $currentEndpoint",
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (!lastError.isNullOrBlank()) {
                                if (!currentEndpoint.isNullOrBlank()) Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = ViberRose,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = lastError,
                                        color = ViberRose,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Text(
                    text = "快速导入配对码、链接或直连 IP:",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViberTextField(
                        value = importInput,
                        onValueChange = {
                            importInput = it
                            importError = false
                        },
                        placeholder = "配对码 (eyJ...) 或 IP (192.168.x.x)...",
                        singleLine = true,
                        isError = importError,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Default
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 42.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Button(
                        onClick = {
                            val trimmed = importInput.trim().trim('"', '\'')
                            if (trimmed.isNotEmpty()) {
                                val imported = onImportUrl(trimmed)
                                if (imported != null) {
                                    importInput = ""
                                    onSelectHost(imported.id)
                                    onDismiss()
                                } else if (activeHost != null && (trimmed.contains('.') || trimmed.contains(':'))) {
                                    // Treat as direct IP or URL configuration for the active paired host
                                    val portPart = trimmed.substringAfterLast(':', "").toIntOrNull()
                                    val updated = activeHost.copy(
                                        directUrl = trimmed,
                                        port = portPart ?: activeHost.port
                                    )
                                    onSaveHost(updated)
                                    onSelectHost(updated.id)
                                    importInput = ""
                                    onDismiss()
                                } else {
                                    importError = true
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ViberCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Text(text = "导入/配置", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (importError) {
                    Text(
                        text = "无法解析配对信息，请检查完整复制配对码或输入有效 IP",
                        color = ViberRose,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "已配置的主机列表 (点击切换，支持编辑网络地址):",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(hosts) { host ->
                        val isActive = host.id == activeHost?.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isActive) ViberCyan.copy(alpha = 0.12f) else ViberCard)
                                .border(
                                    1.dp,
                                    if (isActive) ViberCyan else ViberBorder,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    onSelectHost(host.id)
                                    onDismiss()
                                }
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = host.name,
                                            color = if (isActive) ViberCyan else TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (isActive) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "当前激活",
                                                color = ViberCyan,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    val displayAddr = if (host.directUrl.isNotBlank()) host.directUrl else "${host.getPrimaryAddress()}:${host.port}"
                                    Text(
                                        text = displayAddr,
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { editingHost = host },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit Host",
                                            tint = ViberCyan,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }

                                    if (hosts.size > 1) {
                                        IconButton(
                                            onClick = { onDeleteHost(host.id) },
                                            modifier = Modifier.size(28.dp)
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
                }
            }
        }
    }

    // Edit Host Dialog
    editingHost?.let { host ->
        EditHostDialog(
            host = host,
            onDismiss = { editingHost = null },
            onSave = { updated ->
                onSaveHost(updated)
                onSelectHost(updated.id)
                editingHost = null
            }
        )
    }
}

@Composable
fun EditHostDialog(
    host: HostProfile,
    onDismiss: () -> Unit,
    onSave: (HostProfile) -> Unit
) {
    var name by remember { mutableStateOf(host.name) }
    var directUrl by remember { mutableStateOf(host.directUrl) }
    var portStr by remember { mutableStateOf(host.port.toString()) }
    var ssl by remember { mutableStateOf(host.ssl) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ViberSurface)
                .border(1.dp, ViberCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "编辑主机网络地址",
                        color = ViberCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(text = "主机名称:", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(3.dp))
                ViberTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "开发主机",
                    singleLine = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(text = "直连 IP / 域名 / 完整 URL:", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(3.dp))
                ViberTextField(
                    value = directUrl,
                    onValueChange = { directUrl = it },
                    placeholder = "如 192.168.1.100 或 http://...",
                    singleLine = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "端口:", color = TextSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(3.dp))
                        ViberTextField(
                            value = portStr,
                            onValueChange = { portStr = it },
                            placeholder = "8765",
                            singleLine = true,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "原生 TLS/SSL:", color = TextSecondary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(3.dp))
                        Switch(
                            checked = ssl,
                            onCheckedChange = { ssl = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ViberCyan,
                                checkedTrackColor = ViberCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = ViberCard
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "取消", color = TextMuted)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val parsedPort = portStr.toIntOrNull() ?: host.port
                            val updated = host.copy(
                                name = name.ifBlank { host.name },
                                directUrl = directUrl.trim(),
                                port = parsedPort.coerceIn(1, 65535),
                                ssl = ssl
                            )
                            onSave(updated)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ViberCyan)
                    ) {
                        Text(text = "保存并重连", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
