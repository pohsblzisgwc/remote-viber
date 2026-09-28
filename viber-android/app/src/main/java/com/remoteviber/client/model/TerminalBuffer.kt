package com.remoteviber.client.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-performance, low-latency Terminal Buffer with clean stream sanitization.
 * 
 * Specifically designed for mobile networks and AI Agent CLIs:
 * 1. Complete ANSI/VT100 Sanitizer (strips OSC titles, DEC private modes, and cursor jumps).
 * 2. In-place line updates for progress bars and spinner animations (no duplicate spam).
 * 3. Batch commit to eliminate Compose recomposition thrashing under high latency/bursts.
 * 4. Optimistic Local Echo for immediate command feedback without waiting for network round-trip.
 * 5. Run-Length Span Encoding to keep memory footprint minimal.
 */
class TerminalBuffer(private val maxLines: Int = 2500) {
    // Committed lines in the scrollback buffer
    val lines = mutableStateListOf<AnnotatedString>()

    // Current active in-progress line (e.g. prompt, spinner, progress bar before newline)
    var activeLine by mutableStateOf(AnnotatedString(""))
        private set

    // Stream mode flag (clean log rendering vs raw)
    var isStreamMode by mutableStateOf(true)

    private val currentLineBuilder = StringBuilder()
    private val currentStyles = mutableListOf<Pair<IntRange, SpanStyle>>()

    private var currentFgColor: Color = Color(0xFFE2E8F0) // slate-200
    private var currentBgColor: Color = Color.Transparent
    private var isBold: Boolean = false

    private var lastSpanStart = 0
    private var lastStyle: SpanStyle? = null

    // Parser State Machine
    private var parserState = STATE_NORMAL
    private val csiParams = StringBuilder()

    companion object {
        private const val STATE_NORMAL = 0
        private const val STATE_ESC = 1
        private const val STATE_CSI = 2
        private const val STATE_OSC = 3
        private const val STATE_CHARSET = 4

        private val ansiColorMap = mapOf(
            30 to Color(0xFF1E293B), // black
            31 to Color(0xFFF43F5E), // red
            32 to Color(0xFF10B981), // green
            33 to Color(0xFFF59E0B), // yellow
            34 to Color(0xFF3B82F6), // blue
            35 to Color(0xFFA855F7), // magenta
            36 to Color(0xFF06B6D4), // cyan
            37 to Color(0xFFF8FAFC), // white
            90 to Color(0xFF475569), // bright black
            91 to Color(0xFFFB7185), // bright red
            92 to Color(0xFF34D399), // bright green
            93 to Color(0xFFFBBF24), // bright yellow
            94 to Color(0xFF60A5FA), // bright blue
            95 to Color(0xFFC084FC), // bright magenta
            96 to Color(0xFF22D3EE), // bright cyan
            97 to Color(0xFFFFFFFF)  // bright white
        )
    }

    /**
     * Appends an optimistic local echo of the user's sent command.
     * Provides instantaneous visual confirmation without waiting 200ms+ for network round-trip.
     */
    fun appendLocalEcho(command: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val annotated = buildAnnotatedString {
            // Timestamp in slate-500
            pushStyle(SpanStyle(color = Color(0xFF64748B), fontWeight = FontWeight.Normal))
            append("[$timeStr] ")
            pop()
            // Prompt arrow in cyan
            pushStyle(SpanStyle(color = Color(0xFF22D3EE), fontWeight = FontWeight.Bold))
            append("➜ ")
            pop()
            // Command in white
            pushStyle(SpanStyle(color = Color(0xFFFFFFFF), fontWeight = FontWeight.SemiBold))
            append(command.trimEnd())
            pop()
        }

        synchronized(this) {
            // If there was an active line, commit it first
            if (currentLineBuilder.isNotEmpty()) {
                commitCurrentLineInternal()
            }
            lines.add(annotated)
            trimLines()
            activeLine = AnnotatedString("")
        }
    }

    /**
     * Appends notification or signal messages directly to the buffer.
     */
    fun appendSystemNotice(text: String, color: Color = Color(0xFFF59E0B)) {
        val annotated = buildAnnotatedString {
            pushStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium))
            append(text)
            pop()
        }
        synchronized(this) {
            lines.add(annotated)
            trimLines()
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        activeLine = AnnotatedString("")
        currentLineBuilder.clear()
        currentStyles.clear()
        lastStyle = null
        lastSpanStart = 0
        parserState = STATE_NORMAL
        csiParams.clear()
    }

    @Synchronized
    fun append(rawText: String) {
        var i = 0
        val len = rawText.length
        val newCommittedLines = ArrayList<AnnotatedString>()

        while (i < len) {
            val c = rawText[i]

            when (parserState) {
                STATE_NORMAL -> {
                    when (c) {
                        '\u001B' -> {
                            parserState = STATE_ESC
                        }
                        '\r' -> {
                            // Check if followed by '\n'
                            if (i + 1 < len && rawText[i + 1] == '\n') {
                                newCommittedLines.add(buildCurrentAnnotatedLine())
                                resetCurrentLine()
                                i++ // skip '\n'
                            } else {
                                // Carriage return: in-place line update (e.g. progress bar)
                                resetCurrentLine()
                            }
                        }
                        '\n' -> {
                            newCommittedLines.add(buildCurrentAnnotatedLine())
                            resetCurrentLine()
                        }
                        '\b' -> {
                            if (currentLineBuilder.isNotEmpty()) {
                                currentLineBuilder.deleteCharAt(currentLineBuilder.length - 1)
                            }
                        }
                        '\t' -> {
                            appendChar(' ')
                            appendChar(' ')
                            appendChar(' ')
                            appendChar(' ')
                        }
                        else -> {
                            // Ignore non-displaying control codes (< 0x20)
                            if (c >= ' ') {
                                appendChar(c)
                            }
                        }
                    }
                }

                STATE_ESC -> {
                    when (c) {
                        '[' -> {
                            parserState = STATE_CSI
                            csiParams.clear()
                        }
                        ']' -> {
                            parserState = STATE_OSC
                        }
                        '(', ')' -> {
                            parserState = STATE_CHARSET
                        }
                        else -> {
                            // Lone or unhandled escape: return to normal
                            parserState = STATE_NORMAL
                        }
                    }
                }

                STATE_CHARSET -> {
                    // Swallows single charset character (e.g., 'B', '0')
                    parserState = STATE_NORMAL
                }

                STATE_OSC -> {
                    // Operating System Command (e.g. window title: \u001B]0;title\u0007 or \u001B]0;title\u001B\)
                    if (c == '\u0007') {
                        parserState = STATE_NORMAL
                    } else if (c == '\u001B' && i + 1 < len && rawText[i + 1] == '\\') {
                        parserState = STATE_NORMAL
                        i++ // skip '\\'
                    }
                }

                STATE_CSI -> {
                    // Control Sequence Introducer: \u001B[ ...
                    if (c in '0'..'9' || c == ';' || c == '?' || c == '=' || c == ' ' || c == '\"' || c == '\'') {
                        csiParams.append(c)
                    } else if (c in 'a'..'z' || c in 'A'..'Z' || c == '@' || c == '`') {
                        // Final terminating command character
                        handleCsiCommand(c, csiParams.toString(), newCommittedLines)
                        parserState = STATE_NORMAL
                    } else {
                        // Unexpected character in CSI, abort to normal
                        parserState = STATE_NORMAL
                    }
                }
            }
            i++
        }

        // Batch commit lines into Compose state to prevent recomposition stutter
        if (newCommittedLines.isNotEmpty()) {
            lines.addAll(newCommittedLines)
            trimLines()
        }

        // Update active in-progress line for live display (e.g., interactive prompt without newline)
        activeLine = buildCurrentAnnotatedLine()
    }

    private fun handleCsiCommand(command: Char, params: String, newLines: ArrayList<AnnotatedString>) {
        when (command) {
            'm' -> {
                // Select Graphic Rendition (Colors, bold, etc.)
                parseSgr(params)
            }
            'K' -> {
                // Erase in Line: clear current line builder
                resetCurrentLine()
            }
            'J' -> {
                // Erase in Display: in stream mode, commit current line
                if (currentLineBuilder.isNotEmpty()) {
                    newLines.add(buildCurrentAnnotatedLine())
                    resetCurrentLine()
                }
            }
            'H', 'f' -> {
                // Cursor Home / Position: commit line in stream mode
                if (currentLineBuilder.isNotEmpty()) {
                    newLines.add(buildCurrentAnnotatedLine())
                    resetCurrentLine()
                }
            }
            'A', 'B', 'C', 'D' -> {
                // Cursor movements: silently absorb to avoid visual layout corruption
            }
            'h', 'l' -> {
                // Mode set/reset (e.g. bracketed paste, show/hide cursor): safely absorb
            }
        }
    }

    private fun appendChar(c: Char) {
        val currentStyle = SpanStyle(
            color = currentFgColor,
            background = currentBgColor,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal
        )
        if (lastStyle == null) {
            lastStyle = currentStyle
            lastSpanStart = currentLineBuilder.length
        } else if (lastStyle != currentStyle) {
            val end = currentLineBuilder.length
            if (end > lastSpanStart) {
                currentStyles.add(Pair(lastSpanStart until end, lastStyle!!))
            }
            lastStyle = currentStyle
            lastSpanStart = end
        }
        currentLineBuilder.append(c)
    }

    private fun resetCurrentLine() {
        currentLineBuilder.clear()
        currentStyles.clear()
        lastStyle = null
        lastSpanStart = 0
    }

    private fun buildCurrentAnnotatedLine(): AnnotatedString {
        val end = currentLineBuilder.length
        if (lastStyle != null && end > lastSpanStart) {
            currentStyles.add(Pair(lastSpanStart until end, lastStyle!!))
            lastStyle = null
            lastSpanStart = end
        }

        val lineStr = currentLineBuilder.toString()
        if (lineStr.isEmpty()) return AnnotatedString("")

        return buildAnnotatedString {
            append(lineStr)
            for ((range, style) in currentStyles) {
                if (range.first < length && range.last < length) {
                    addStyle(style, range.first, minOf(range.last + 1, length))
                }
            }
        }
    }

    private fun commitCurrentLineInternal() {
        lines.add(buildCurrentAnnotatedLine())
        resetCurrentLine()
    }

    private fun trimLines() {
        while (lines.size > maxLines) {
            val overflow = lines.size - maxLines
            val batchRemove = minOf(overflow, 100)
            for (k in 0 until batchRemove) {
                lines.removeAt(0)
            }
        }
    }

    private fun parseSgr(params: String) {
        if (params.isEmpty() || params == "0") {
            currentFgColor = Color(0xFFE2E8F0)
            currentBgColor = Color.Transparent
            isBold = false
            return
        }

        val tokens = params.split(';').mapNotNull { it.toIntOrNull() }
        var idx = 0
        while (idx < tokens.size) {
            val code = tokens[idx]
            when (code) {
                0 -> {
                    currentFgColor = Color(0xFFE2E8F0)
                    currentBgColor = Color.Transparent
                    isBold = false
                }
                1 -> isBold = true
                22 -> isBold = false
                in 30..37, in 90..97 -> {
                    currentFgColor = ansiColorMap[code] ?: Color(0xFFE2E8F0)
                }
                39 -> currentFgColor = Color(0xFFE2E8F0) // default fg
                in 40..47, in 100..107 -> {
                    val baseCode = if (code >= 100) code - 70 else code - 10
                    currentBgColor = (ansiColorMap[baseCode] ?: Color.Transparent).copy(alpha = 0.35f)
                }
                49 -> currentBgColor = Color.Transparent // default bg
                38 -> {
                    // Extended FG: 38;5;n or 38;2;r;g;b
                    if (idx + 2 < tokens.size && tokens[idx + 1] == 5) {
                        val colorIdx = tokens[idx + 2]
                        currentFgColor = get256Color(colorIdx)
                        idx += 2
                    } else if (idx + 4 < tokens.size && tokens[idx + 1] == 2) {
                        val r = tokens[idx + 2]
                        val g = tokens[idx + 3]
                        val b = tokens[idx + 4]
                        currentFgColor = Color(r, g, b)
                        idx += 4
                    }
                }
            }
            idx++
        }
    }

    private fun get256Color(index: Int): Color {
        return when (index) {
            in 0..15 -> ansiColorMap[if (index < 8) 30 + index else 90 + (index - 8)] ?: Color.White
            in 16..231 -> {
                val idx = index - 16
                val r = (idx / 36) * 51
                val g = ((idx % 36) / 6) * 51
                val b = (idx % 6) * 51
                Color(r, g, b)
            }
            in 232..255 -> {
                val gray = (index - 232) * 10 + 8
                Color(gray, gray, gray)
            }
            else -> Color(0xFFE2E8F0)
        }
    }
}
