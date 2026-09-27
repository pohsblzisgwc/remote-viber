package com.remoteviber.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
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
import com.remoteviber.client.model.AgentChatStreamProcessor
import com.remoteviber.client.model.ChatMessageItem
import com.remoteviber.client.model.ChatMessageType
import com.remoteviber.client.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AgentChatView(
    chatProcessor: AgentChatStreamProcessor,
    modifier: Modifier = Modifier,
    onSendDecision: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val messages = chatProcessor.messages
    val messageCount = messages.size

    // Auto-scroll on new message
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(ViberBg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Message stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { item ->
                    when (item.type) {
                        ChatMessageType.USER_PROMPT -> UserPromptBubble(item)
                        ChatMessageType.AGENT_RESPONSE -> AgentResponseCard(item)
                        ChatMessageType.TOOL_EXECUTION -> ToolExecutionCard(item)
                        ChatMessageType.SYSTEM_EVENT -> SystemEventChip(item)
                    }
                }
            }

            // Approval Drawer (Pops up when Agent is waiting for user confirmation)
            AnimatedVisibility(
                visible = chatProcessor.isApprovalPending,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = Color(0xFF131D31),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = ViberCyan.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = ViberCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Agent 正在等待您的确认与授权",
                                color = ViberCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        if (chatProcessor.lastApprovalPrompt.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = chatProcessor.lastApprovalPrompt,
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF0A0F1C))
                                    .padding(6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Large thumb-friendly decision buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSendDecision("y\n") },
                                colors = ButtonDefaults.buttonColors(containerColor = ViberEmerald),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("同意执行 (y)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { onSendDecision("n\n") },
                                colors = ButtonDefaults.buttonColors(containerColor = ViberRose),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("拒绝/取消 (n)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { onSendDecision("\r") },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text("↵ 回车", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Scroll to bottom FAB
        if (messageCount > 6) {
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(messageCount - 1)
                    }
                },
                containerColor = ViberCyan,
                contentColor = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (chatProcessor.isApprovalPending) 140.dp else 16.dp)
                    .size(36.dp)
            ) {
                Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun UserPromptBubble(item: ChatMessageItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Text(
                text = "您 · ${item.timestamp}",
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 2.dp, end = 4.dp)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp))
                    .background(Color(0xFF083344))
                    .border(width = 1.dp, color = ViberCyan.copy(alpha = 0.5f), shape = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = item.content,
                    color = Color(0xFFE0F2FE),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun AgentResponseCard(item: ChatMessageItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth(0.95f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 3.dp, start = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = ViberEmerald,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Agent · ${item.timestamp}",
                    fontSize = 10.sp,
                    color = ViberEmerald,
                    fontWeight = FontWeight.Medium
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp))
                    .background(ViberCard)
                    .border(width = 1.dp, color = ViberBorder, shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = item.content,
                    color = Color(0xFFF1F5F9),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
private fun ToolExecutionCard(item: ChatMessageItem) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .border(width = 0.5.dp, color = Color(0xFF334155), shape = RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(8.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.title,
                    color = Color(0xFFF59E0B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = item.timestamp,
                    color = TextMuted,
                    fontSize = 10.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.content,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF030712))
                        .padding(8.dp)
                )
            } else {
                Text(
                    text = item.content.lines().firstOrNull() ?: "",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    maxLines = 1,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun SystemEventChip(item: ChatMessageItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "[${item.timestamp}] ${item.content}",
            color = item.accentColor,
            fontSize = 10.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(item.accentColor.copy(alpha = 0.1f))
                .border(width = 0.5.dp, color = item.accentColor.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}
