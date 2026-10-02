package com.timestampbeatstudio.core

/**
 * Smooth, monotonic progress estimation for long blocking work (such as the
 * on-device whisper transcription) where the underlying engine only reports
 * progress at coarse milestones.
 *
 * The estimate follows an asymptotic curve: it rises quickly at first and
 * then creeps toward [cap] without ever reaching it, so the UI keeps moving
 * instead of freezing. Real progress signals (when available) always take
 * precedence — callers must merge via `max(reported, estimate)`.
 *
 * Pure Kotlin, no Android dependencies: unit-testable on the JVM.
 */
object ProgressSmoothing {

    /** Default asymptote for the smooth estimate; completion itself drives to 1.0. */
    const val DEFAULT_CAP = 0.93f

    /** Assumed real-time factor for on-device base.en transcription. */
    const val DEFAULT_REALTIME_FACTOR = 1.5

    /**
     * Rough total-duration guess for a transcription job, in seconds.
     * Clamped to [120, 2700] so tiny clips don't crawl and huge jobs don't stall.
     */
    fun estimateTotalSec(
        audioDurationSec: Double,
        realtimeFactor: Double = DEFAULT_REALTIME_FACTOR
    ): Double {
        require(audioDurationSec > 0) { "audioDurationSec must be > 0" }
        require(realtimeFactor > 0) { "realtimeFactor must be > 0" }
        return (audioDurationSec * realtimeFactor).coerceIn(120.0, 2700.0)
    }

    /**
     * Smooth progress estimate in [0, cap] for [elapsedSec] seconds of work
     * against an [estimateSec]-second total guess.
     *
     * Guarantees: 0 at t=0, strictly increasing for t>0, always < cap,
     * and independent of wall-clock speed — a slower device simply climbs
     * the same curve more slowly.
     */
    fun estimate(
        elapsedSec: Double,
        estimateSec: Double,
        cap: Float = DEFAULT_CAP
    ): Float {
        require(cap in 0f..1f) { "cap must be in 0..1" }
        if (elapsedSec <= 0.0 || estimateSec <= 0.0) return 0f
        return (cap * elapsedSec / (elapsedSec + estimateSec)).toFloat()
    }
}
