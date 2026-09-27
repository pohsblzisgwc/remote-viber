package com.remoteviber.client.model

import androidx.compose.ui.graphics.Color
import java.util.UUID

enum class ChatMessageType {
    USER_PROMPT,      // User entered prompt/command
    AGENT_RESPONSE,   // Assistant prose, reasoning, answers
    TOOL_EXECUTION,   // Shell commands, git, docker, tests
    SYSTEM_EVENT      // Connection, interrupts, exit codes
}

data class ChatMessageItem(
    val id: String = UUID.randomUUID().toString(),
    val type: ChatMessageType,
    val title: String = "",
    val content: String,
    val timestamp: String,
    val isCollapsible: Boolean = false,
    val isExpanded: Boolean = false,
    val isApprovalPending: Boolean = false,
    val accentColor: Color = Color(0xFF22D3EE)
)
