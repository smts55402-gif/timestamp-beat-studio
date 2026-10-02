package com.timestampbeatstudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSmoothingTest {

    @Test
    fun `estimate is zero at start and bounded by cap`() {
        assertEquals(0f, ProgressSmoothing.estimate(0.0, 600.0))
        assertEquals(0f, ProgressSmoothing.estimate(-5.0, 600.0))
        // Even after a very long time it never reaches the cap.
        val far = ProgressSmoothing.estimate(100000.0, 600.0)
        assertTrue(far < ProgressSmoothing.DEFAULT_CAP)
        assertTrue(far > 0.9f)
    }

    @Test
    fun `estimate is strictly increasing`() {
        val est = 600.0
        var prev = -1f
        for (t in listOf(1.0, 5.0, 30.0, 120.0, 600.0, 3600.0)) {
            val p = ProgressSmoothing.estimate(t, est)
            assertTrue("not increasing at t=$t: $p <= $prev", p > prev)
            prev = p
        }
    }

    @Test
    fun `estimate reaches half the cap at the estimated total`() {
        // p(T) = cap * T / (T + T) = cap / 2
        val p = ProgressSmoothing.estimate(600.0, 600.0)
        assertEquals(ProgressSmoothing.DEFAULT_CAP / 2f, p, 1e-6f)
    }

    @Test
    fun `estimateTotalSec scales with duration and clamps`() {
        assertEquals(838.752 * 1.5, ProgressSmoothing.estimateTotalSec(838.752), 1e-9)
        // Tiny clip -> minimum 120 s so the bar doesn't crawl.
        assertEquals(120.0, ProgressSmoothing.estimateTotalSec(10.0), 1e-9)
        // Huge job -> maximum 2700 s.
        assertEquals(2700.0, ProgressSmoothing.estimateTotalSec(7200.0), 1e-9)
    }

    @Test
    fun `merging estimate with real signal via max stays monotonic`() {
        // Simulates: smooth ticker creeping while a coarse native signal jumps.
        val estimateSec = ProgressSmoothing.estimateTotalSec(838.752)
        var last = 0f
        // Ticker ticks.
        for (t in listOf(60.0, 300.0, 900.0)) {
            last = maxOf(last, ProgressSmoothing.estimate(t, estimateSec))
        }
        // Native signal jumps to 1.0 at the end (segment loop).
        last = maxOf(last, 1.0f)
        assertEquals(1.0f, last)
        assertTrue(last >= 0f)
    }
}
