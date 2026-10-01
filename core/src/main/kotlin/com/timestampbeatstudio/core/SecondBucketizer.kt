package com.timestampbeatstudio.core

import kotlin.math.floor
import kotlin.math.max

/**
 * Deterministic assignment of recognized words to fixed one-second buckets.
 *
 * The single assignment rule: `bucket = floor(word.startSec)`, clamped to
 * `[0, durationSec - 1]`. Words are never split — a word crossing a second
 * boundary (e.g. start 1.84, end 2.15) belongs wholly to the bucket
 * containing its START time. The transcript text flows through untouched:
 * no rewriting, no summarizing, no guessing.
 */
object SecondBucketizer {

    /**
     * Assigns every word to exactly one second bucket.
     *
     * Whole-second convention: `durationSec = max(1, audioDurationSec.toInt())`,
     * so 13:58.75 of audio yields 838 buckets (`00:00`…`13:57`). Seconds with
     * no words become [BucketStatus.SILENCE], or [BucketStatus.INAUDIBLE] when
     * covered by [inaudibleRangesSec]. A word starting at or after the audio
     * duration clamps to the last bucket — it is never dropped (the validator
     * reports such words as a warning).
     *
     * @param words recognized words; must be chronological by [Word.startSec]
     *   (non-decreasing). Out-of-order input is rejected.
     * @param audioDurationSec actual media duration in seconds; must be > 0.
     * @param inaudibleRangesSec inclusive second ranges where speech exists but
     *   could not be reliably recognized; only affects word-less seconds.
     * @return the complete second-by-second timeline.
     * @throws IllegalArgumentException if [audioDurationSec] <= 0 or [words]
     *   are not chronological by start time.
     */
    fun bucketize(
        words: List<Word>,
        audioDurationSec: Double,
        inaudibleRangesSec: List<IntRange> = emptyList()
    ): SecondTimeline {
        require(audioDurationSec > 0) {
            "audioDurationSec must be > 0, was $audioDurationSec"
        }
        for (i in 1 until words.size) {
            require(words[i].startSec >= words[i - 1].startSec) {
                "words must be chronological by startSec: word $i starts at " +
                    "${words[i].startSec}, previous word at ${words[i - 1].startSec}"
            }
        }

        val durationSec = max(1, audioDurationSec.toInt())
        val bucketWords = Array(durationSec) { mutableListOf<Word>() }
        val bucketIndexes = Array(durationSec) { mutableListOf<Int>() }

        words.forEachIndexed { index, word ->
            val bucket = floor(word.startSec).toInt().coerceIn(0, durationSec - 1)
            bucketWords[bucket].add(word)
            bucketIndexes[bucket].add(index)
        }

        val buckets = (0 until durationSec).map { second ->
            val assigned = bucketWords[second].toList()
            val status = when {
                assigned.isNotEmpty() -> BucketStatus.SPEECH
                inaudibleRangesSec.any { second in it } -> BucketStatus.INAUDIBLE
                else -> BucketStatus.SILENCE
            }
            SecondBucket(
                second = second,
                words = assigned,
                status = status,
                correctedText = null,
                sourceWordIndexes = bucketIndexes[second].toList(),
                extras = emptyMap()
            )
        }
        return SecondTimeline(durationSec = durationSec, buckets = buckets)
    }
}
