package com.timestampbeatstudio.core.export

import com.timestampbeatstudio.core.BucketStatus
import com.timestampbeatstudio.core.SecondTimeline
import com.timestampbeatstudio.core.TimestampFormat
import kotlin.math.roundToLong

/**
 * Renders a [SecondTimeline] into one Stage 01 export format.
 *
 * Stage 01 output is strictly second-by-second spoken text with whole-second
 * `MM:SS` timestamps. No word-level or decimal timestamps ever appear in the
 * visible output (only SRT uses internal precise timing for cue boundaries).
 */
interface BeatSheetExporter {
    /** File extension without dot: "txt" | "md" | "json" | "srt". */
    val extension: String

    /** MIME type for sharing/opening the exported file. */
    val mimeType: String

    /** Filename suffix inserted before the extension, e.g. "_stage01". */
    val fileSuffix: String
        get() = "_stage01"

    /**
     * Renders the timeline to a string.
     *
     * @param timeline the second-by-second timeline to export.
     * @param projectName the user-visible project name (used by MD/JSON titles).
     * @return the complete file content.
     */
    fun export(timeline: SecondTimeline, projectName: String): String
}

/**
 * The four Stage 01 exporters plus filename utilities.
 */
object Exporters {

    /** Plain-text beat sheet: one `MM:SS <displayText>` line per second. */
    val txt: BeatSheetExporter = object : BeatSheetExporter {
        override val extension = "txt"
        override val mimeType = "text/plain"

        override fun export(timeline: SecondTimeline, projectName: String): String =
            timeline.buckets.joinToString("\n") { bucket ->
                "${TimestampFormat.mmss(bucket.second)} ${bucket.displayText}"
            }
    }

    /**
     * Markdown beat sheet: `# <projectName>` title, a blank line, then the
     * identical timeline inside a fenced `text` code block.
     */
    val md: BeatSheetExporter = object : BeatSheetExporter {
        override val extension = "md"
        override val mimeType = "text/markdown"

        override fun export(timeline: SecondTimeline, projectName: String): String =
            "# $projectName\n\n```text\n${txt.export(timeline, projectName)}\n```"
    }

    /**
     * JSON beat sheet: project metadata plus per-second records carrying both
     * the display timestamp and internal metadata (precise word timing,
     * status, source word indexes, extras). Status strings are lowercase:
     * `speech|silence|inaudible`. Built by hand — no JSON dependency.
     */
    val json: BeatSheetExporter = object : BeatSheetExporter {
        override val extension = "json"
        override val mimeType = "application/json"

        override fun export(timeline: SecondTimeline, projectName: String): String {
            val sb = StringBuilder()
            sb.append("{\n")
            sb.append("  \"project\": \"${escapeJson(projectName)}\",\n")
            sb.append("  \"durationSec\": ${timeline.durationSec},\n")
            sb.append("  \"seconds\": [")
            timeline.buckets.forEachIndexed { index, bucket ->
                sb.append(if (index == 0) "\n" else ",\n")
                sb.append("    {\n")
                sb.append("      \"timestamp\": \"${TimestampFormat.mmss(bucket.second)}\",\n")
                sb.append("      \"start_seconds\": ${bucket.second},\n")
                sb.append("      \"text\": \"${escapeJson(bucket.displayText)}\",\n")
                sb.append("      \"status\": \"${bucket.status.name.lowercase()}\",\n")
                sb.append("      \"source_word_indexes\": [${bucket.sourceWordIndexes.joinToString(", ")}],\n")
                sb.append("      \"words\": [")
                bucket.words.forEachIndexed { wordIndex, word ->
                    if (wordIndex > 0) sb.append(", ")
                    sb.append("{\"text\": \"${escapeJson(word.text)}\", ")
                    sb.append("\"start\": ${word.startSec}, \"end\": ${word.endSec}}")
                }
                sb.append("],\n")
                sb.append("      \"extras\": {}\n")
                sb.append("    }")
            }
            sb.append("\n  ]\n}")
            return sb.toString()
        }
    }

    /**
     * SRT subtitles. The ONLY format that uses internal precise timing: runs
     * of consecutive SPEECH buckets merge into one cue; cue start = first
     * word's startSec, cue end = last word's endSec; cue text = the merged
     * buckets' display text joined with spaces. Cue numbers start at 1; cues
     * are separated by a blank line. The primary beat sheet stays whole-second.
     */
    val srt: BeatSheetExporter = object : BeatSheetExporter {
        override val extension = "srt"
        override val mimeType = "application/x-subrip"

        override fun export(timeline: SecondTimeline, projectName: String): String {
            val sb = StringBuilder()
            var cueNumber = 1
            var i = 0
            val buckets = timeline.buckets
            while (i < buckets.size) {
                if (buckets[i].status != BucketStatus.SPEECH) {
                    i++
                    continue
                }
                var j = i
                while (j + 1 < buckets.size && buckets[j + 1].status == BucketStatus.SPEECH) {
                    j++
                }
                val run = buckets.subList(i, j + 1)
                val runWords = run.flatMap { it.words }
                val cueStart = runWords.firstOrNull()?.startSec ?: run.first().second.toDouble()
                val cueEnd = runWords.lastOrNull()?.endSec ?: (run.last().second + 1).toDouble()
                val cueText = run.joinToString(" ") { it.displayText }
                if (cueNumber > 1) sb.append('\n')
                sb.append(cueNumber).append('\n')
                sb.append(srtTimestamp(cueStart)).append(" --> ").append(srtTimestamp(cueEnd)).append('\n')
                sb.append(cueText).append('\n')
                cueNumber++
                i = j + 1
            }
            return sb.toString()
        }
    }

    /**
     * All Stage 01 exporters in stable order: txt, md, json, srt.
     */
    fun all(): List<BeatSheetExporter> = listOf(txt, md, json, srt)

    /**
     * Builds a safe filename for an exported beat sheet.
     *
     * Example: `safeFileName("My Project!", txt)` -> `"My_Project_stage01.txt"`.
     * Illegal filesystem characters become underscores; an empty result
     * falls back to `"project"`.
     *
     * @param projectName the user-visible project name.
     * @param exporter the exporter the file is for.
     * @return e.g. `"My_Project_stage01.txt"`.
     */
    fun safeFileName(projectName: String, exporter: BeatSheetExporter): String {
        var base = projectName.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.', ' ')
        if (base.isEmpty()) base = "project"
        return "$base${exporter.fileSuffix}.${exporter.extension}"
    }

    /**
     * Escapes a string for inclusion in a JSON string literal: quotes,
     * backslashes and control characters are escaped per the JSON spec.
     */
    internal fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Formats seconds as an SRT timestamp `HH:MM:SS,mmm`, rounding to the
     * nearest millisecond.
     */
    internal fun srtTimestamp(totalSeconds: Double): String {
        val totalMs = (totalSeconds * 1000).roundToLong()
        val ms = totalMs % 1000
        val seconds = (totalMs / 1000) % 60
        val minutes = (totalMs / 60_000) % 60
        val hours = totalMs / 3_600_000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, ms)
    }
}
