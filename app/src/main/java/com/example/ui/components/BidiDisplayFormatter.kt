package com.example.ui.components

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Display-only Bidirectional (Bidi) & JSON formatter.
 *
 * Guarantees:
 * - Never modifies underlying network payloads or stored values.
 * - Keeps JSON syntax (`keys`, `:`, quotes, braces, commas) strictly LTR.
 * - Isolates Arabic / mixed RTL string values inside Unicode Directional Isolates (`RLI`/`LRI`/`FSI`..`PDI`)
 *   so punctuation and quotes stay properly ordered even across multi-line values or long URLs.
 * - Provides helper to isolate English tokens / URLs embedded inside Arabic RTL diagnostic sentences.
 */
object BidiDisplayFormatter {

    const val LRI = "\u2066" // Left-to-Right Isolate
    const val RLI = "\u2067" // Right-to-Left Isolate
    const val FSI = "\u2068" // First-Strong Isolate
    const val PDI = "\u2069" // Pop Directional Isolate

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Formats JSON (or plain text fallback) for display in an LTR Monospace code container.
     * Original data sent to server MUST NEVER pass through this formatter.
     */
    fun formatJsonForDisplay(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "{}"

        val parsed = runCatching { prettyJson.parseToJsonElement(trimmed) }.getOrNull()
            ?: return isolateRawTextLinesForLtr(trimmed)

        return buildString {
            appendFormattedElement(parsed, indent = 0, out = this)
        }
    }

    /**
     * Formats raw server excerpt (JSON if valid, otherwise isolated raw lines) for LTR code box.
     */
    fun formatCodeExcerptForLtr(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching { prettyJson.parseToJsonElement(trimmed) }.getOrNull()
        return if (parsed != null) {
            buildString { appendFormattedElement(parsed, indent = 0, out = this) }
        } else {
            isolateRawTextLinesForLtr(trimmed)
        }
    }

    /**
     * Isolates LTR tokens (identifiers, HTTP codes, URLs) inside Arabic RTL prose
     * so parentheses and colons do not flip visually.
     */
    fun isolateTokensInRtlText(arabicText: String): String {
        if (arabicText.isEmpty()) return arabicText
        return LTR_TOKEN_REGEX.replace(arabicText) { match ->
            "$LRI${match.value}$PDI"
        }
    }

    /**
     * Strips Unicode directional isolate characters (useful for verification / clipboard copy).
     */
    fun stripIsolates(text: String): String =
        text.replace(LRI, "")
            .replace(RLI, "")
            .replace(FSI, "")
            .replace(PDI, "")

    private fun appendFormattedElement(
        element: JsonElement,
        indent: Int,
        out: StringBuilder
    ) {
        when (element) {
            is JsonObject -> {
                if (element.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                val entries = element.entries.toList()
                entries.forEachIndexed { idx, (key, value) ->
                    appendIndent(indent + 2, out)
                    out.append(formatJsonKeyToken(key))
                    out.append(": ")
                    appendFormattedElement(value, indent + 2, out)
                    if (idx < entries.lastIndex) out.append(",")
                    out.append("\n")
                }
                appendIndent(indent, out)
                out.append("}")
            }
            is JsonArray -> {
                if (element.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                element.forEachIndexed { idx, item ->
                    appendIndent(indent + 2, out)
                    appendFormattedElement(item, indent + 2, out)
                    if (idx < element.lastIndex) out.append(",")
                    out.append("\n")
                }
                appendIndent(indent, out)
                out.append("]")
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    out.append(formatJsonStringToken(element.content))
                } else {
                    out.append(LRI).append(element.content).append(PDI)
                }
            }
        }
    }

    private fun formatJsonKeyToken(key: String): String {
        val escaped = escapeJsonString(key)
        return if (containsRtlChar(escaped)) {
            "${LRI}\"${RLI}$escaped${PDI}\"${PDI}"
        } else {
            "${LRI}\"$escaped\"${PDI}"
        }
    }

    private fun formatJsonStringToken(value: String): String {
        val escaped = escapeJsonString(value)
        if (!containsRtlChar(escaped)) {
            return "${LRI}\"$escaped\"${PDI}"
        }
        // Isolate inner RTL/mixed text cleanly while keeping outer quotes and trailing comma strictly LTR.
        val innerIsolated = if (containsLtrLetterOrDigit(escaped)) {
            val withLtrTokens = LTR_TOKEN_REGEX.replace(escaped) { m -> "$LRI${m.value}$PDI" }
            "${RLI}$withLtrTokens${PDI}"
        } else {
            "${RLI}$escaped${PDI}"
        }
        return "${LRI}\"$innerIsolated\"${PDI}"
    }

    private fun isolateRawTextLinesForLtr(raw: String): String {
        return raw.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                containsRtlChar(line) -> "${FSI}$line${PDI}"
                else -> "${LRI}$line${PDI}"
            }
        }
    }

    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun appendIndent(spaces: Int, out: StringBuilder) {
        repeat(spaces) { out.append(' ') }
    }

    internal fun containsRtlChar(text: String): Boolean {
        for (ch in text) {
            val dir = Character.getDirectionality(ch)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
            ) {
                return true
            }
        }
        return false
    }

    private fun containsLtrLetterOrDigit(text: String): Boolean =
        text.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

    private val LTR_TOKEN_REGEX = Regex(
        """https?://[^\s"'<>]+|[A-Za-z0-9_#./:-]{2,}"""
    )
}
