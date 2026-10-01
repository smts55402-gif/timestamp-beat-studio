package com.timestampbeatstudio.whisper

/**
 * One word produced by the native whisper.cpp layer.
 *
 * Timing is INTERNAL precise timing in seconds (fractional), derived from
 * whisper.cpp token timestamps (10 ms units). Never shown to the user
 * directly — `:core`'s [com.timestampbeatstudio.core.SecondBucketizer]
 * assigns each word to a whole-second bucket using its start time.
 *
 * @param text the spoken word; never blank.
 * @param startSec word start in seconds, >= 0.
 * @param endSec word end in seconds, >= [startSec].
 * @param confidence mean token probability in [0, 1], or `-1.0` when the
 *   native layer could not provide one.
 */
data class NativeWord(
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val confidence: Double = -1.0
)
