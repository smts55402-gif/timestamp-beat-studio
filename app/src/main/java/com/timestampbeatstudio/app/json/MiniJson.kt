package com.timestampbeatstudio.app.json

/**
 * Minimal dependency-free JSON parser/serializer.
 *
 * Used instead of org.json so that all serialization logic (word persistence,
 * remote transcription responses) is unit-testable on the plain JVM — the
 * org.json classes in android.jar are runtime stubs that throw in local unit
 * tests. No new dependencies are introduced.
 *
 * parse() returns: Map<String, Any?>, List<Any?>, String, Double, Boolean, or null.
 */
object MiniJson {

    /** Parses a JSON document. Throws IllegalArgumentException on malformed input. */
    fun parse(text: String): Any? = Parser(text).parseTopLevel()

    /** Serializes supported values: Map, List, String, Number, Boolean, null. */
    fun stringify(value: Any?): String = buildString { appendJsonValue(value) }

    private fun StringBuilder.appendJsonValue(v: Any?) {
        when (v) {
            null -> append("null")
            is String -> {
                append('"')
                for (c in v) {
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        '\b' -> append("\\b")
                        '\u000C' -> append("\\f")
                        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }
            is Double -> append(if (v % 1.0 == 0.0 && !v.isInfinite()) v.toLong().toString() else v.toString())
            is Float -> append(if (v % 1f == 0f && !v.isInfinite()) v.toLong().toString() else v.toString())
            is Number -> append(v.toString())
            is Boolean -> append(v.toString())
            is Map<*, *> -> {
                append('{')
                v.entries.forEachIndexed { index, (k, value) ->
                    if (index > 0) append(',')
                    appendJsonValue(k.toString())
                    append(':')
                    appendJsonValue(value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                v.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendJsonValue(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("MiniJson cannot serialize ${v::class}")
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseTopLevel(): Any? {
            val v = parseValue()
            skipWs()
            if (i != s.length) throw IllegalArgumentException("Trailing characters after JSON value at $i")
            return v
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("Unexpected end of JSON input")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("Expected string key at $i")
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
                i++
                map[key] = parseValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("Unterminated object")
                when (s[i]) {
                    ',' -> { i++; continue }
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("Unterminated array")
                when (s[i]) {
                    ',' -> { i++; continue }
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw IllegalArgumentException("Unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            val token = s.substring(start, i)
            return token.toDoubleOrNull()
                ?: throw IllegalArgumentException("Bad number '$token' at $start")
        }

        private fun expectLiteral(literal: String, value: Any?): Any? {
            if (!s.startsWith(literal, i)) throw IllegalArgumentException("Expected '$literal' at $i")
            i += literal.length
            return value
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}
