package com.timestampbeatstudio.app.data

import com.timestampbeatstudio.app.json.MiniJson
import com.timestampbeatstudio.core.Word

/**
 * Hand-rolled JSON serialization for persisted transcription data.
 * Dependency-free (see [MiniJson]) so encode/decode is JVM-unit-testable.
 */
object WordJson {

    /** Encodes words as a compact JSON array of {t,s,e,c} objects. */
    fun encodeWords(words: List<Word>): String =
        MiniJson.stringify(words.map { w ->
            mapOf("t" to w.text, "s" to w.startSec, "e" to w.endSec, "c" to w.confidence)
        })

    /** Decodes words previously written by [encodeWords]; malformed entries are skipped. */
    fun decodeWords(json: String): List<Word> {
        if (json.isBlank()) return emptyList()
        val list = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val start = (m["s"] as? Number)?.toDouble() ?: return@mapNotNull null
            val end = (m["e"] as? Number)?.toDouble() ?: return@mapNotNull null
            Word(
                text = m["t"]?.toString() ?: "",
                startSec = start,
                endSec = end,
                confidence = (m["c"] as? Number)?.toDouble()
            )
        }
    }

    /** Encodes inclusive second ranges as JSON [[first,last], ...]. */
    fun encodeRanges(ranges: List<IntRange>): String =
        MiniJson.stringify(ranges.map { listOf(it.first, it.last) })

    /** Decodes ranges previously written by [encodeRanges]. */
    fun decodeRanges(json: String): List<IntRange> {
        if (json.isBlank()) return emptyList()
        val list = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            when (item) {
                is List<*> -> {
                    val a = (item.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
                    val b = (item.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
                    IntRange(a, b)
                }
                else -> null
            }
        }
    }
}
