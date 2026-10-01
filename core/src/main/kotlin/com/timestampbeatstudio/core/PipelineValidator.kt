package com.timestampbeatstudio.core

import kotlin.math.max

/** Outcome of a single validation check. */
enum class CheckStatus {
    /** The check passed. */
    PASS,

    /** Non-critical issue; the timeline is still usable. */
    WARNING,

    /** Critical issue; the timeline must not be exported as passing. */
    FAIL
}

/**
 * One named validation check result.
 *
 * @param name stable check name; UI depends on the seven required names.
 * @param status the check outcome.
 * @param detail human-readable detail; also carries machine-parseable
 *   `key=value` counters used by [ValidationReport].
 */
data class ValidationCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String
)

/**
 * Result of validating a [SecondTimeline] against its source transcript.
 *
 * @param checks the individual check results, in stable order.
 */
data class ValidationReport(val checks: List<ValidationCheck>) {

    /**
     * Overall outcome: [CheckStatus.FAIL] if any check failed, else
     * [CheckStatus.WARNING] if any check warned, else [CheckStatus.PASS].
     */
    val overall: CheckStatus
        get() = when {
            checks.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            checks.any { it.status == CheckStatus.WARNING } -> CheckStatus.WARNING
            else -> CheckStatus.PASS
        }

    private fun counter(checkName: String, key: String): Int =
        checks.firstOrNull { it.name == checkName }
            ?.let { Regex("$key=(\\d+)").find(it.detail)?.groupValues?.get(1)?.toInt() }
            ?: 0

    /** Number of input words not assigned to any bucket. */
    val missingWords: Int
        get() = counter("Transcript Coverage", "missing")

    /** Number of duplicate word-index assignments across buckets. */
    val duplicateWords: Int
        get() = counter("Duplicate Words", "duplicates")

    /** Number of buckets whose content cannot be traced to the transcript. */
    val inventedSegments: Int
        get() = counter("Invented Content", "invented")

    /** Number of buckets with [BucketStatus.SILENCE]. */
    val silentSeconds: Int
        get() = counter("Every Second Generated", "silent")

    /** Number of buckets with [BucketStatus.INAUDIBLE]. */
    val inaudibleSeconds: Int
        get() = counter("Every Second Generated", "inaudible")
}

/**
 * Validates a second-by-second timeline against its source transcript.
 *
 * Required checks (names are stable; UI depends on them):
 * - "Audio Duration" — timeline.durationSec == max(1, floor(audioDurationSec))
 * - "Every Second Generated" — buckets.size == durationSec, second == index, no gaps
 * - "Transcript Coverage" — every input word assigned exactly once (0 missing)
 * - "Duplicate Words" — no word index appears in two buckets
 * - "Invented Content" — every SPEECH bucket's text == its words joined;
 *   placeholders only on empty buckets
 * - "Chronological Order" — word indexes ascend within and across buckets
 * - "Timestamp Format" — every mmss(second) passes [TimestampFormat.isValidMmss]
 *
 * Warnings (never FAIL): clamped words (a word with startSec >= audioDurationSec
 * or < 0), empty transcript, fully-silent audio.
 *
 * Coverage failure semantics: if [ValidationReport.missingWords] > 0 or
 * [ValidationReport.duplicateWords] > 0 the report [ValidationReport.overall]
 * is FAIL and the UI must show `COVERAGE ERROR — REVIEW REQUIRED` and refuse
 * a PASS export badge.
 */
object PipelineValidator {

    /**
     * Runs all validation checks.
     *
     * @param timeline the timeline to validate.
     * @param words the original transcription word list the timeline was built from.
     * @param audioDurationSec actual media duration in seconds.
     * @return the validation report.
     */
    fun validate(
        timeline: SecondTimeline,
        words: List<Word>,
        audioDurationSec: Double
    ): ValidationReport {
        val checks = mutableListOf<ValidationCheck>()
        checks += checkAudioDuration(timeline, audioDurationSec)
        checks += checkEverySecondGenerated(timeline)
        checks += checkTranscriptCoverage(timeline, words)
        checks += checkDuplicateWords(timeline)
        checks += checkInventedContent(timeline, words)
        checks += checkChronologicalOrder(timeline)
        checks += checkTimestampFormat(timeline)
        checks += checkClampedWords(words, audioDurationSec)
        checks += checkSilentAudio(timeline)
        return ValidationReport(checks)
    }

    private fun checkAudioDuration(
        timeline: SecondTimeline,
        audioDurationSec: Double
    ): ValidationCheck {
        val expected = max(1, audioDurationSec.toInt())
        return if (timeline.durationSec == expected) {
            ValidationCheck(
                "Audio Duration",
                CheckStatus.PASS,
                "durationSec=$expected matches floor(audioDurationSec)"
            )
        } else {
            ValidationCheck(
                "Audio Duration",
                CheckStatus.FAIL,
                "expected durationSec=$expected, got ${timeline.durationSec}"
            )
        }
    }

    private fun checkEverySecondGenerated(timeline: SecondTimeline): ValidationCheck {
        val sizeOk = timeline.buckets.size == timeline.durationSec
        val firstGap = timeline.buckets.indices.firstOrNull { timeline.buckets[it].second != it }
        val silent = timeline.buckets.count { it.status == BucketStatus.SILENCE }
        val inaudible = timeline.buckets.count { it.status == BucketStatus.INAUDIBLE }
        val counters = "buckets=${timeline.buckets.size}/${timeline.durationSec}; silent=$silent; inaudible=$inaudible"
        return when {
            !sizeOk -> ValidationCheck(
                "Every Second Generated", CheckStatus.FAIL,
                "bucket count ${timeline.buckets.size} != durationSec ${timeline.durationSec}; $counters"
            )
            firstGap != null -> ValidationCheck(
                "Every Second Generated", CheckStatus.FAIL,
                "gap at index $firstGap (second=${timeline.buckets[firstGap].second}); $counters"
            )
            else -> ValidationCheck(
                "Every Second Generated", CheckStatus.PASS,
                "all ${timeline.buckets.size} seconds present in order; $counters"
            )
        }
    }

    private fun checkTranscriptCoverage(
        timeline: SecondTimeline,
        words: List<Word>
    ): ValidationCheck {
        val assigned = timeline.buckets.flatMap { it.sourceWordIndexes }.toSet()
        val missing = words.indices.filter { it !in assigned }
        val detail = "missing=${missing.size}; words=${words.size}"
        return when {
            missing.isNotEmpty() -> ValidationCheck(
                "Transcript Coverage", CheckStatus.FAIL,
                "$detail; first missing word indexes: ${missing.take(10)}"
            )
            words.isEmpty() -> ValidationCheck(
                "Transcript Coverage", CheckStatus.WARNING,
                "$detail (empty transcript)"
            )
            else -> ValidationCheck(
                "Transcript Coverage", CheckStatus.PASS,
                "$detail; every word assigned exactly once"
            )
        }
    }

    private fun checkDuplicateWords(timeline: SecondTimeline): ValidationCheck {
        val assigned = timeline.buckets.flatMap { it.sourceWordIndexes }
        val duplicates = assigned.groupingBy { it }.eachCount().values
            .sumOf { (it - 1).coerceAtLeast(0) }
        return if (duplicates > 0) {
            ValidationCheck(
                "Duplicate Words", CheckStatus.FAIL,
                "duplicates=$duplicates; assignments=${assigned.size}; a word index appears in more than one bucket"
            )
        } else {
            ValidationCheck(
                "Duplicate Words", CheckStatus.PASS,
                "duplicates=0; assignments=${assigned.size}; no word index repeated"
            )
        }
    }

    private fun checkInventedContent(
        timeline: SecondTimeline,
        words: List<Word>
    ): ValidationCheck {
        var invented = 0
        for (bucket in timeline.buckets) {
            // Manual user corrections are explicit user input, not invented content.
            if (bucket.correctedText != null) continue
            val danglingIndexes = bucket.sourceWordIndexes.any { it < 0 || it >= words.size }
            val expectedText = if (bucket.words.isNotEmpty()) {
                bucket.words.joinToString(" ") { it.text }
            } else if (bucket.status == BucketStatus.INAUDIBLE) {
                INAUDIBLE_PLACEHOLDER
            } else {
                SILENCE_PLACEHOLDER
            }
            val textOk = bucket.displayText == expectedText
            val statusOk = (bucket.words.isNotEmpty() && bucket.status == BucketStatus.SPEECH) ||
                (bucket.words.isEmpty() && bucket.status != BucketStatus.SPEECH)
            if (danglingIndexes || !textOk || !statusOk) invented++
        }
        return if (invented > 0) {
            ValidationCheck(
                "Invented Content", CheckStatus.FAIL,
                "invented=$invented; checked=${timeline.buckets.size}; " +
                    "bucket content not traceable to the transcript"
            )
        } else {
            ValidationCheck(
                "Invented Content", CheckStatus.PASS,
                "invented=0; checked=${timeline.buckets.size}; all text traced to transcript words or placeholders"
            )
        }
    }

    private fun checkChronologicalOrder(timeline: SecondTimeline): ValidationCheck {
        val flat = timeline.buckets.flatMap { it.sourceWordIndexes }
        val firstDecrease = flat.zipWithNext().indexOfFirst { (a, b) -> b < a }
        return if (firstDecrease >= 0) {
            ValidationCheck(
                "Chronological Order", CheckStatus.FAIL,
                "word index decreases at position $firstDecrease " +
                    "(${flat[firstDecrease]} -> ${flat[firstDecrease + 1]})"
            )
        } else {
            ValidationCheck(
                "Chronological Order", CheckStatus.PASS,
                "word indexes ascend within and across ${timeline.buckets.size} buckets"
            )
        }
    }

    private fun checkTimestampFormat(timeline: SecondTimeline): ValidationCheck {
        val bad = timeline.buckets.firstOrNull { bucket ->
            bucket.second < 0 || !TimestampFormat.isValidMmss(TimestampFormat.mmss(bucket.second))
        }
        return if (bad != null) {
            ValidationCheck(
                "Timestamp Format", CheckStatus.FAIL,
                "invalid timestamp at bucket index ${timeline.buckets.indexOf(bad)} (second=${bad.second})"
            )
        } else {
            ValidationCheck(
                "Timestamp Format", CheckStatus.PASS,
                "all ${timeline.buckets.size} timestamps are valid MM:SS"
            )
        }
    }

    private fun checkClampedWords(
        words: List<Word>,
        audioDurationSec: Double
    ): ValidationCheck {
        val clamped = words.count { it.startSec < 0 || it.startSec >= audioDurationSec }
        return if (clamped > 0) {
            ValidationCheck(
                "Clamped Words", CheckStatus.WARNING,
                "$clamped word(s) start outside [0, $audioDurationSec); clamped to timeline bounds, never dropped"
            )
        } else {
            ValidationCheck(
                "Clamped Words", CheckStatus.PASS,
                "no clamped words; all word starts within [0, $audioDurationSec)"
            )
        }
    }

    private fun checkSilentAudio(timeline: SecondTimeline): ValidationCheck {
        val speech = timeline.buckets.count { it.status == BucketStatus.SPEECH }
        return if (speech == 0) {
            ValidationCheck(
                "Silent Audio", CheckStatus.WARNING,
                "timeline contains no speech (${timeline.buckets.size} silent/inaudible seconds)"
            )
        } else {
            ValidationCheck(
                "Silent Audio", CheckStatus.PASS,
                "speech present in $speech/${timeline.buckets.size} seconds"
            )
        }
    }
}
