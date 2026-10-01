# CORE API Contract — Timestamp Beat Studio (`:core`)

Package: `com.timestampbeatstudio.core`
Module type: pure Kotlin/JVM (no Android dependencies). This file is the
**frozen contract**. `:app` and `:whisper` consume it; `:core` implements it.
Do not rename, remove, or change signatures without updating all consumers.

## Data model

```kotlin
package com.timestampbeatstudio.core

/** One recognized word with INTERNAL precise timing (seconds, fractional allowed).
 *  Internal only — never shown to the user. */
data class Word(
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val confidence: Double? = null
)

enum class BucketStatus { SPEECH, SILENCE, INAUDIBLE }

const val SILENCE_PLACEHOLDER = "[SILENCE]"
const val INAUDIBLE_PLACEHOLDER = "[INAUDIBLE]"

/**
 * One fixed one-second window of audio.
 * @param second 0-based second index. Display ONLY via TimestampFormat.mmss(second).
 * @param words words assigned to this second. Words are NEVER split across buckets.
 * @param status SPEECH if words non-empty, else SILENCE or INAUDIBLE.
 * @param correctedText manual user correction; null means "use original words".
 * @param sourceWordIndexes indexes into the original transcription word list, ascending.
 * @param extras reserved for Stage 02 (beat id, characters, camera, ...). Stage 01: always empty.
 */
data class SecondBucket(
    val second: Int,
    val words: List<Word>,
    val status: BucketStatus,
    val correctedText: String? = null,
    val sourceWordIndexes: List<Int> = emptyList(),
    val extras: Map<String, String> = emptyMap()
) {
    /** User-visible text for this second. */
    val displayText: String
        get() = correctedText
            ?: if (words.isNotEmpty()) words.joinToString(" ") { it.text }
               else if (status == BucketStatus.INAUDIBLE) INAUDIBLE_PLACEHOLDER
               else SILENCE_PLACEHOLDER
}

/** Complete second-by-second timeline for one audio file. */
data class SecondTimeline(
    /** Whole-second convention: max(1, floor(audioDurationSec)). */
    val durationSec: Int,
    /** Exactly durationSec buckets; buckets[i].second == i; strictly chronological. */
    val buckets: List<SecondBucket>
)

data class TranscriptionResult(
    val words: List<Word>,                 // chronological by startSec
    val language: String?,                 // BCP-47 or null if unknown
    val durationSec: Double,               // actual media duration
    val inaudibleRangesSec: List<IntRange> = emptyList() // inclusive second ranges
)

/** 16 kHz mono float PCM fed to engines. */
data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int = 16000
) {
    override fun equals(other: Any?): Boolean  // content-based
    override fun hashCode(): Int
}

interface TranscriptionEngine {
    val engineName: String
    @Throws(TranscriptionException::class)
    suspend fun transcribe(
        pcm: PcmAudio,
        durationSec: Double,
        progress: (Float) -> Unit,          // 0f..1f
        isCancelled: () -> Boolean
    ): TranscriptionResult
}
class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

## TimestampFormat

```kotlin
object TimestampFormat {
    /** 0 -> "00:00", 61 -> "01:01", 837 -> "13:57". Hours roll into minutes (MM can exceed 59). */
    fun mmss(totalSeconds: Int): String  // require(totalSeconds >= 0)

    /** "13:57" -> 837. Throws IllegalArgumentException on bad format. */
    fun parseMmss(s: String): Int

    /** Validates visible format ^\d{2,}:\d{2}$ with seconds < 60. */
    fun isValidMmss(s: String): Boolean
}
```

## SecondBucketizer — the deterministic assignment rule

```kotlin
object SecondBucketizer {
    /**
     * Assigns every word to exactly one bucket:
     *   bucket = floor(word.startSec), clamped to [0, durationSec - 1].
     * Words are never split. Seconds with no words become SILENCE,
     * or INAUDIBLE when covered by inaudibleRangesSec.
     * Words must arrive chronological; out-of-order input -> IllegalArgumentException.
     */
    fun bucketize(
        words: List<Word>,
        audioDurationSec: Double,                       // require > 0
        inaudibleRangesSec: List<IntRange> = emptyList()
    ): SecondTimeline
}
```

Whole-second convention: `durationSec = max(1, audioDurationSec.toInt())`
(13:58.75 → 838 buckets `00:00`…`13:57`). A word starting at/after the
duration clamps to the last bucket and is reported as a WARNING, never dropped.

## PipelineValidator

```kotlin
enum class CheckStatus { PASS, WARNING, FAIL }
data class ValidationCheck(val name: String, val status: CheckStatus, val detail: String)
data class ValidationReport(val checks: List<ValidationCheck>) {
    val overall: CheckStatus  // FAIL if any FAIL, else WARNING if any WARNING, else PASS
    // convenience counters derived from checks:
    val missingWords: Int
    val duplicateWords: Int
    val inventedSegments: Int
    val silentSeconds: Int
    val inaudibleSeconds: Int
}

object PipelineValidator {
    /**
     * Required checks (names are stable, UI depends on them):
     *  "Audio Duration"        — timeline.durationSec == max(1, floor(audioDurationSec))
     *  "Every Second Generated"— buckets.size == durationSec, second == index, no gaps
     *  "Transcript Coverage"   — every input word assigned exactly once (0 missing)
     *  "Duplicate Words"       — no word index appears in two buckets
     *  "Invented Content"      — every SPEECH bucket's text == its words joined; placeholders only on empty buckets
     *  "Chronological Order"   — word indexes ascend within and across buckets
     *  "Timestamp Format"      — every mmss(second) passes isValidMmss
     * Warnings (never FAIL): clamped words, empty transcript, fully silent audio.
     */
    fun validate(
        timeline: SecondTimeline,
        words: List<Word>,
        audioDurationSec: Double
    ): ValidationReport
}
```

Coverage failure semantics: if `missingWords > 0` or `duplicateWords > 0`
the report `overall` is FAIL and the UI must show
`COVERAGE ERROR — REVIEW REQUIRED` and refuse a PASS export badge.

## Exporters

```kotlin
interface BeatSheetExporter {
    val extension: String   // "txt" | "md" | "json" | "srt"
    val mimeType: String
    val fileSuffix: String  // "_stage01"
    fun export(timeline: SecondTimeline, projectName: String): String
}

object Exporters {
    val txt: BeatSheetExporter
    val md: BeatSheetExporter
    val json: BeatSheetExporter
    val srt: BeatSheetExporter
    fun all(): List<BeatSheetExporter> = listOf(txt, md, json, srt)
    /** "My Project!" -> "My_Project"; appends suffix+extension: "My_Project_stage01.txt" */
    fun safeFileName(projectName: String, exporter: BeatSheetExporter): String
}
```

- **TXT**: one line per second: `00:00 <displayText>`. Silent → `00:07 [SILENCE]`.
- **MD**: `# <projectName}` title, blank line, then the identical timeline
  inside a fenced `text` code block.
- **JSON**: `{ "project": name, "durationSec": n, "seconds": [ { "timestamp": "00:12",
  "start_seconds": 12, "text": "...", "status": "speech",
  "source_word_indexes": [...], "words": [ {"text","start","end"}... ], "extras": {} } ] }`
  Status strings are lowercase: `speech|silence|inaudible`. Word start/end keep
  full internal precision.
- **SRT**: cues built from INTERNAL precise timing only: merge runs of
  consecutive SPEECH buckets into one cue; cue start = first word's startSec,
  cue end = last word's endSec; text = joined words. `HH:MM:SS,mmm` formatting.
  The primary beat sheet stays whole-second; only SRT uses precise timing.

## Notes for implementers

- No Android imports in `:core`. Pure kotlin-stdlib + coroutines-core (for the
  suspend engine interface).
- `PcmAudio.equals/hashCode` must be content-based (FloatArray!).
- `SecondBucketizer` must be total: it never throws on valid input except
  negative/zero duration or non-chronological words.
- All public functions need KDoc. Keep the implementation honest: no guessing,
  no LLM rewriting — the transcript flows through untouched.
