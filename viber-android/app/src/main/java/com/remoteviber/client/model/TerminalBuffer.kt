package com.remoteviber.client.model

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

class TerminalBuffer(private val maxLines: Int = 10000) {
    val lines = mutableStateListOf<AnnotatedString>()

    private var currentLineBuilder = StringBuilder()
    private var currentStyles = mutableListOf<Pair<IntRange, SpanStyle>>()

    private var currentFgColor: Color = Color(0xFFE2E8F0) // slate-200
    private var currentBgColor: Color = Color.Transparent
    private var isBold: Boolean = false

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

    @Synchronized
    fun append(rawText: String) {
        var i = 0
        val len = rawText.length

        while (i < len) {
            val c = rawText[i]

            // ANSI Escape Sequence: \u001B[ ...
            if (c == '\u001B' && i + 1 < len && rawText[i + 1] == '[') {
                val endIdx = rawText.indexOfAny(charArrayOf('m', 'J', 'K', 'H', 'f', 'A', 'B', 'C', 'D'), i + 2)
                if (endIdx != -1) {
                    val codeChar = rawText[endIdx]
                    val params = rawText.substring(i + 2, endIdx)

                    if (codeChar == 'm') {
                        parseSgr(params)
                    } else if (codeChar == 'J') {
                        // In modern terminal emulators, 2J clears viewport without destroying scrollback history.
                        // Only reset active line builders, preserving conversation history.
                        if (currentLineBuilder.isNotEmpty()) {
                            commitLine()
                        }
                    } else if (codeChar == 'K') {
                        // Clear line to end - usually in-place updates
                    }
                    i = endIdx + 1
                    continue
                }
            }

            when (c) {
                '\r' -> {
                    // Carriage return: check if next is '\n'
                    if (i + 1 < len && rawText[i + 1] == '\n') {
                        commitLine()
                        i += 2
                        continue
                    } else {
                        // In-place line update (e.g. progress bar)
                        currentLineBuilder.clear()
                        currentStyles.clear()
                        lastStyle = null
                        lastSpanStart = 0
                    }
                }
                '\n' -> {
                    commitLine()
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
                    if (c >= ' ' || c == '\u001B') {
                        appendChar(c)
                    }
                }
            }
            i++
        }
    }

    private var lastSpanStart = 0
    private var lastStyle: SpanStyle? = null

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

    private fun commitLine() {
        val end = currentLineBuilder.length
        if (lastStyle != null && end > lastSpanStart) {
            currentStyles.add(Pair(lastSpanStart until end, lastStyle!!))
        }
        lastStyle = null
        lastSpanStart = 0

        val lineStr = currentLineBuilder.toString()
        val annotated = buildAnnotatedString {
            append(lineStr)
            for ((range, style) in currentStyles) {
                if (range.first < length && range.last < length) {
                    addStyle(style, range.first, range.last + 1)
                }
            }
        }
        lines.add(annotated)
        if (lines.size > maxLines) {
            lines.removeAt(0)
        }
        currentLineBuilder.clear()
        currentStyles.clear()
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
                // 6x6x6 color cube
                val idx = index - 16
                val r = (idx / 36) * 51
                val g = ((idx % 36) / 6) * 51
                val b = (idx % 6) * 51
                Color(r, g, b)
            }
            in 232..255 -> {
                // Grayscale ramp
                val gray = 8 + (index - 232) * 10
                Color(gray, gray, gray)
            }
            else -> Color.White
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        currentLineBuilder.clear()
        currentStyles.clear()
    }
}
