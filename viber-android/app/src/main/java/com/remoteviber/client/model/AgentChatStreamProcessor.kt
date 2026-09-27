package com.remoteviber.client.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * High-fidelity AI Agent Chat & Action Stream Processor.
 * 
 * Replaces broken TUI ASCII characters and 80-column PTY hard-line-breaks
 * with clean, mobile-native conversational cards and natural flowing paragraphs:
 * 
 * 1. Smart Paragraph Stitching: Dissolves 80-col PTY hard line breaks into fluid sentences.
 * 2. TUI Border & Box Stripping: Cleans out ASCII borders (┌─┐│└┘) and decorative boxes.
 * 3. In-place \r Update & Spinner Deduplication: Eliminates spinner and progress bar duplicate spam.
 * 4. Tool Execution Grouping: Automatically identifies shell/CLI runs and presents them as expandable items.
 * 5. Instant Approval Prompt Detection: Surfaces [y/N], (yes/no), or "Proceed?" queries for 1-tap mobile decisions.
 */
class AgentChatStreamProcessor(private val maxMessages: Int = 2000) {

    val messages = mutableStateListOf<ChatMessageItem>()

    // Current in-progress agent line/accumulator
    var isApprovalPending by mutableStateOf(false)
        private set
    var lastApprovalPrompt by mutableStateOf("")
        private set

    private val rawLineBuffer = StringBuilder()
    private val pendingParagraph = ArrayList<String>()

    private val ansiPattern = Pattern.compile(
        "\\u001B\\][^\\u0007\\u001B]*(\\u0007|\\u001B\\\\)|" + // OSC
        "\\u001B\\[[0-9;?='\"`]*[a-zA-Z@]|" +                  // CSI
        "\\u001B[\\(\\)][0-9a-zA-Z]|" +                        // Charset
        "\\u001B[=>NOP]"                                       // Single esc
    )

    private val approvalPattern = Pattern.compile(
        "(?i)(\\[y/N\\]|\\[Y/n\\]|\\(y/n\\)|Are you sure|Proceed\\?|Confirm\\?|Press Enter to continue|\\[yes/no\\])"
    )

    private val borderChars = setOf(
        '─', '│', '┌', '┐', '└', '┘', '├', '┤', '┬', '┴', '┼',
        '═', '║', '╒', '╓', '╔', '╕', '╖', '╗', '╘', '╙', '╚',
        '╛', '╜', '╝', '╭', '╮', '╯', '╰', '-', '=', '_', '~', '|', '+'
    )

    @Synchronized
    fun appendUserPrompt(prompt: String) {
        // Flush any active agent response first
        flushPendingParagraphs()

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        messages.add(
            ChatMessageItem(
                type = ChatMessageType.USER_PROMPT,
                title = "我",
                content = prompt.trim(),
                timestamp = timeStr,
                accentColor = Color(0xFF22D3EE)
            )
        )
        isApprovalPending = false
        lastApprovalPrompt = ""
        trimMessages()
    }

    @Synchronized
    fun appendSystemNotice(text: String, color: Color = Color(0xFFF59E0B)) {
        flushPendingParagraphs()
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        messages.add(
            ChatMessageItem(
                type = ChatMessageType.SYSTEM_EVENT,
                title = "系统提示",
                content = text.trim(),
                timestamp = timeStr,
                accentColor = color
            )
        )
        trimMessages()
    }

    @Synchronized
    fun clear() {
        messages.clear()
        rawLineBuffer.clear()
        pendingParagraph.clear()
        isApprovalPending = false
        lastApprovalPrompt = ""
    }

    @Synchronized
    fun appendStreamText(rawChunk: String) {
        val cleanChunk = ansiPattern.matcher(rawChunk).replaceAll("")

        var i = 0
        val len = cleanChunk.length
        while (i < len) {
            val c = cleanChunk[i]
            when (c) {
                '\r' -> {
                    if (i + 1 < len && cleanChunk[i + 1] == '\n') {
                        processCompletedLine(rawLineBuffer.toString())
                        rawLineBuffer.clear()
                        i++ // skip \n
                    } else {
                        // Carriage return in-place update (e.g. progress bar)
                        rawLineBuffer.clear()
                    }
                }
                '\n' -> {
                    processCompletedLine(rawLineBuffer.toString())
                    rawLineBuffer.clear()
                }
                '\b' -> {
                    if (rawLineBuffer.isNotEmpty()) {
                        rawLineBuffer.deleteCharAt(rawLineBuffer.length - 1)
                    }
                }
                else -> {
                    if (c >= ' ' || c == '\t') {
                        rawLineBuffer.append(c)
                    }
                }
            }
            i++
        }

        // Check if rawLineBuffer contains an interactive approval prompt
        val currentActive = rawLineBuffer.toString()
        if (currentActive.isNotEmpty()) {
            val matcher = approvalPattern.matcher(currentActive)
            if (matcher.find()) {
                isApprovalPending = true
                lastApprovalPrompt = currentActive.trim()
            }
        }
    }

    private fun processCompletedLine(rawLine: String) {
        val cleaned = cleanBoxDrawingLine(rawLine) ?: return

        if (cleaned.isBlank()) {
            // Blank line triggers a paragraph flush
            flushPendingParagraphs()
            return
        }

        // Check if line indicates approval request
        val matcher = approvalPattern.matcher(cleaned)
        if (matcher.find()) {
            isApprovalPending = true
            lastApprovalPrompt = cleaned.trim()
        }

        // Check if line is a bullet, code header, or tool execution line
        if (isBulletOrSpecial(cleaned)) {
            flushPendingParagraphs()
            addOrUpdateAgentMessage(cleaned)
            return
        }

        if (pendingParagraph.isEmpty()) {
            pendingParagraph.add(cleaned)
        } else {
            val prev = pendingParagraph.last()
            if (isBulletOrSpecial(prev) || endsWithSentenceTerminator(prev)) {
                // Previous line was a complete sentence or list item; start new paragraph
                flushPendingParagraphs()
                pendingParagraph.add(cleaned)
            } else {
                // Stitch continuing line into the paragraph!
                val lastChar = if (prev.isNotEmpty()) prev.last() else ' '
                val firstChar = cleaned.first()
                val isCjk = (lastChar in '\u4e00'..'\u9fa5') || (firstChar in '\u4e00'..'\u9fa5')
                val stitched = if (isCjk) prev + cleaned else "$prev $cleaned"
                pendingParagraph[pendingParagraph.size - 1] = stitched
            }
        }

        // Emit stitched paragraph to UI
        if (pendingParagraph.isNotEmpty()) {
            addOrUpdateAgentMessage(pendingParagraph.joinToString("\n"))
        }
    }

    private fun flushPendingParagraphs() {
        if (pendingParagraph.isNotEmpty()) {
            val content = pendingParagraph.joinToString("\n")
            addOrUpdateAgentMessage(content, finalize = true)
            pendingParagraph.clear()
        }
    }

    private fun addOrUpdateAgentMessage(content: String, finalize: Boolean = false) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val isTool = content.startsWith("$ ") || content.startsWith("Running ") || content.startsWith("npm ") || content.startsWith("docker ")

        val last = messages.lastOrNull()
        if (last != null && last.type == ChatMessageType.AGENT_RESPONSE && !finalize && !isTool) {
            // Update last agent response in-place
            val index = messages.size - 1
            messages[index] = last.copy(
                content = content,
                timestamp = timeStr,
                isApprovalPending = isApprovalPending
            )
        } else {
            // New message block
            messages.add(
                ChatMessageItem(
                    type = if (isTool) ChatMessageType.TOOL_EXECUTION else ChatMessageType.AGENT_RESPONSE,
                    title = if (isTool) "命令行工具执行" else "Agent 思考与回复",
                    content = content,
                    timestamp = timeStr,
                    isCollapsible = isTool,
                    isApprovalPending = isApprovalPending,
                    accentColor = if (isTool) Color(0xFFF59E0B) else Color(0xFF10B981)
                )
            )
        }
        trimMessages()
    }

    private fun cleanBoxDrawingLine(line: String): String? {
        val s = line.trim()
        if (s.isEmpty()) return ""

        // Check if line is purely box drawing or border symbols
        if (s.length >= 3) {
            var nonBorderCount = 0
            for (ch in s) {
                if (!borderChars.contains(ch) && !ch.isWhitespace()) {
                    nonBorderCount++
                }
            }
            if (nonBorderCount == 0) {
                return null // Pure border line: strip it completely
            }
        }

        // Strip leading/trailing border columns: │ Text Here │ -> Text Here
        var cleaned = s
        if ((cleaned.startsWith("│") || cleaned.startsWith("|")) && (cleaned.endsWith("│") || cleaned.endsWith("|"))) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        } else if (cleaned.startsWith("│") || cleaned.startsWith("|")) {
            cleaned = cleaned.substring(1).trim()
        } else if (cleaned.endsWith("│") || cleaned.endsWith("|")) {
            cleaned = cleaned.substring(0, cleaned.length - 1).trim()
        }

        return cleaned
    }

    private fun isBulletOrSpecial(l: String): Boolean {
        val sl = l.trimStart()
        return sl.startsWith("- ") ||
               sl.startsWith("* ") ||
               sl.startsWith("+ ") ||
               sl.startsWith("• ") ||
               sl.startsWith("#") ||
               sl.startsWith("> ") ||
               sl.startsWith("```") ||
               sl.startsWith("$ ") ||
               sl.startsWith("➜ ") ||
               Pattern.compile("^\\d+\\.\\s").matcher(sl).find()
    }

    private fun endsWithSentenceTerminator(l: String): Boolean {
        val s = l.trimEnd()
        if (s.isEmpty()) return true
        val c = s.last()
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' ||
               c == ':' || c == '：' || c == ';' || c == '；' || c == '`' || c == '}' || c == ']'
    }

    private fun trimMessages() {
        while (messages.size > maxMessages) {
            messages.removeAt(0)
        }
    }
}
