package com.remoteviber.client.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteviber.client.ui.theme.*

private val QUICK_SNIPPETS = listOf(
    "y",
    "n",
    "ls -la",
    "cd ..",
    "clear",
    "git status",
    "git diff",
    "docker ps",
    "docker compose up -d",
    "htop",
    "pytest",
    "npm test",
    "确认执行，开始跑测试",
    "修复报错并重新构建"
)

@Composable
fun VirtualKeyboardBar(
    onSendKey: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    val context = LocalContext.current
    var ctrlActive by remember { mutableStateOf(false) }
    var isDrawerOpen by remember { mutableStateOf(false) }
    var promptInput by remember { mutableStateOf("") }

    val handleKey: (String) -> Unit = { key ->
        var output = ""
        if (ctrlActive) {
            output = when (key.uppercase()) {
                "C" -> "\u0003"
                "D" -> "\u0004"
                "Z" -> "\u001A"
                "L" -> "\u000C"
                "A" -> "\u0001"
                "E" -> "\u0005"
                else -> key
            }
            ctrlActive = false
        } else {
            output = when (key) {
                "ESC" -> "\u001B"
                "TAB" -> "\t"
                "^C" -> "\u0003"
                "^D" -> "\u0004"
                "^Z" -> "\u001A"
                "^L" -> "\u000C"
                "UP" -> "\u001B[A"
                "DOWN" -> "\u001B[B"
                "RIGHT" -> "\u001B[C"
                "LEFT" -> "\u001B[D"
                "ENTER" -> "\r"
                else -> key
            }
        }
        onSendKey(output)
    }

    val handlePaste = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            if (text.isNotEmpty()) {
                onSendKey(text)
            }
        }
    }

    Surface(
        color = ViberSurface,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .border(1.dp, ViberBorder)
    ) {
        Column {
            // Expandable Drawer for Prompting & Snippets
            if (isDrawerOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF070B14))
                        .padding(8.dp)
                ) {
                    // Snippets bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "常用短语:",
                            color = TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        QUICK_SNIPPETS.forEach { snip ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ViberCard)
                                    .border(0.6.dp, ViberBorderLight, RoundedCornerShape(6.dp))
                                    .clickable {
                                        onSendPrompt(snip + "\n")
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = snip,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Prompt Input Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ViberTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            placeholder = "输入 Agent 提示词或命令...",
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            containerColor = ViberCard,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Button(
                            onClick = {
                                if (promptInput.isNotEmpty()) {
                                    onSendPrompt(promptInput + "\n")
                                    promptInput = ""
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ViberCyan),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Main Accessory Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ESC
                KeyPill("ESC") { handleKey("ESC") }
                // TAB
                KeyPill("TAB") { handleKey("TAB") }

                // CTRL
                KeyPill(
                    label = "CTRL",
                    isActive = ctrlActive,
                    onClick = { ctrlActive = !ctrlActive }
                )

                // Common signals
                KeyPill("^C", textColor = ViberRose) { handleKey("^C") }
                KeyPill("^D") { handleKey("^D") }
                KeyPill("^Z", textColor = ViberAmber) { handleKey("^Z") }
                KeyPill("^L", title = "清屏") { handleKey("^L") }

                Spacer(modifier = Modifier.width(2.dp))
                Divider(
                    color = ViberBorder,
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))

                // Directional Pad
                KeyPill("←") { handleKey("LEFT") }
                KeyPill("↑") { handleKey("UP") }
                KeyPill("↓") { handleKey("DOWN") }
                KeyPill("→") { handleKey("RIGHT") }

                Spacer(modifier = Modifier.width(2.dp))
                Divider(
                    color = ViberBorder,
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))

                // Paste from Android Clipboard
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ViberCard)
                        .border(1.dp, ViberBorderLight, RoundedCornerShape(6.dp))
                        .clickable { handlePaste() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = ViberCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "粘贴",
                            color = ViberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Font size A- / A+
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ViberCard)
                        .border(1.dp, ViberBorderLight, RoundedCornerShape(6.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .clickable { onZoomOut() }
                            .padding(horizontal = 7.dp, vertical = 6.dp)
                    ) {
                        Text(text = "A-", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Divider(
                        color = ViberBorder,
                        modifier = Modifier
                            .height(18.dp)
                            .width(1.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clickable { onZoomIn() }
                            .padding(horizontal = 7.dp, vertical = 6.dp)
                    ) {
                        Text(text = "A+", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                KeyPill("/") { handleKey("/") }
                KeyPill("-") { handleKey("-") }
                KeyPill("|") { handleKey("|") }
                KeyPill("~") { handleKey("~") }
                KeyPill("$") { handleKey("$") }
                KeyPill("&") { handleKey("&") }
                KeyPill("↵", title = "回车") { handleKey("ENTER") }

                // Quick Prompt Drawer Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDrawerOpen) ViberCyan else ViberIndigo.copy(alpha = 0.25f))
                        .border(
                            1.dp,
                            if (isDrawerOpen) ViberCyan else ViberIndigo.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { isDrawerOpen = !isDrawerOpen }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            tint = if (isDrawerOpen) Color.Black else ViberCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "输入框",
                            color = if (isDrawerOpen) Color.Black else ViberCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyPill(
    label: String,
    isActive: Boolean = false,
    textColor: Color = TextPrimary,
    title: String? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) ViberCyan else ViberCard)
            .border(
                1.dp,
                if (isActive) ViberCyan else ViberBorderLight,
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color.Black else textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
