package com.timestampbeatstudio.core

/**
 * One recognized word with INTERNAL precise timing (seconds, fractional allowed).
 * Internal only — never shown to the user. The visible beat sheet only ever
 * shows whole-second [MM:SS][TimestampFormat.mmss] timestamps.
 *
 * @param text the exact recognized word text; never rewritten by downstream stages.
 * @param startSec word start time in seconds; drives bucket assignment.
 * @param endSec word end time in seconds.
 * @param confidence optional recognizer confidence in 0.0..1.0, or null if unknown.
 */
data class Word(
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val confidence: Double? = null
)

/** Classification of a single one-second audio window. */
enum class BucketStatus {
    /** The second contains at least one recognized word. */
    SPEECH,

    /** The second contains no detected speech. */
    SILENCE,

    /** The second contains speech that could not be reliably recognized. */
    INAUDIBLE
}

/** Placeholder shown for a second with no detected speech. */
const val SILENCE_PLACEHOLDER = "[SILENCE]"

/** Placeholder shown for a second with speech that could not be reliably recognized. */
const val INAUDIBLE_PLACEHOLDER = "[INAUDIBLE]"

/**
 * One fixed one-second window of audio.
 *
 * @param second 0-based second index. Display ONLY via [TimestampFormat.mmss].
 * @param words words assigned to this second. Words are NEVER split across buckets.
 * @param status [BucketStatus.SPEECH] if words are non-empty, else
 *   [BucketStatus.SILENCE] or [BucketStatus.INAUDIBLE].
 * @param correctedText manual user correction; null means "use original words".
 *   Never alters [words] — the original transcription is preserved untouched.
 * @param sourceWordIndexes indexes into the original transcription word list, ascending.
 * @param extras reserved for Stage 02 (beat id, characters, camera, ...).
 *   Stage 01: always empty.
 */
data class SecondBucket(
    val second: Int,
    val words: List<Word>,
    val status: BucketStatus,
    val correctedText: String? = null,
    val sourceWordIndexes: List<Int> = emptyList(),
    val extras: Map<String, String> = emptyMap()
) {
    /**
     * User-visible text for this second: the manual correction when present,
     * otherwise the original words joined with spaces, otherwise the status
     * placeholder.
     */
    val displayText: String
        get() = correctedText
            ?: if (words.isNotEmpty()) words.joinToString(" ") { it.text }
            else if (status == BucketStatus.INAUDIBLE) INAUDIBLE_PLACEHOLDER
            else SILENCE_PLACEHOLDER
}

/**
 * Complete second-by-second timeline for one audio file.
 *
 * @param durationSec whole-second convention: max(1, floor(audioDurationSec)).
 * @param buckets exactly [durationSec] buckets with buckets[i].second == i,
 *   strictly chronological.
 */
data class SecondTimeline(
    val durationSec: Int,
    val buckets: List<SecondBucket>
)

/**
 * Raw output of a speech recognition pass.
 *
 * @param words recognized words, chronological by [Word.startSec].
 * @param language BCP-47 language tag, or null if unknown.
 * @param durationSec actual media duration in seconds.
 * @param inaudibleRangesSec inclusive second ranges where speech exists but
 *   could not be reliably recognized.
 */
data class TranscriptionResult(
    val words: List<Word>,
    val language: String?,
    val durationSec: Double,
    val inaudibleRangesSec: List<IntRange> = emptyList()
)

/**
 * 16 kHz mono float PCM fed to transcription engines.
 *
 * Equality and hashing are content-based: two instances with equal sample
 * content and equal sample rate are equal.
 *
 * @param samples raw PCM samples.
 * @param sampleRate sample rate in Hz; 16000 by default.
 */
data class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int = 16000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmAudio) return false
        return sampleRate == other.sampleRate && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

/**
 * Speech recognition engine. Implementations convert PCM audio into a
 * [TranscriptionResult] with internal precise word timing. The transcript
 * text flows through untouched — engines must not rewrite or "improve" it.
 */
interface TranscriptionEngine {
    /** Human-readable engine identifier, e.g. "whisper-small". */
    val engineName: String

    /**
     * Transcribes [pcm] audio.
     *
     * @param pcm audio samples to transcribe.
     * @param durationSec actual media duration in seconds.
     * @param progress callback receiving 0f..1f progress updates.
     * @param isCancelled polled for cooperative cancellation.
     * @return chronological words with internal precise timing.
     * @throws TranscriptionException when recognition fails.
     */
    @Throws(TranscriptionException::class)
    suspend fun transcribe(
        pcm: PcmAudio,
        durationSec: Double,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): TranscriptionResult
}

/**
 * Failure of a [TranscriptionEngine.transcribe] call (model load failure,
 * decoding failure, unsupported audio, ...).
 */
class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
