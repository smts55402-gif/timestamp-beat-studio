package com.timestampbeatstudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampFormatTest {

    @Test
    fun mmss_zero() {
        assertEquals("00:00", TimestampFormat.mmss(0))
    }

    @Test
    fun mmss_oneMinuteOneSecond() {
        assertEquals("01:01", TimestampFormat.mmss(61))
    }

    @Test
    fun mmss_typicalValue() {
        assertEquals("13:57", TimestampFormat.mmss(837))
    }

    @Test
    fun mmss_hoursRollIntoMinutes() {
        assertEquals("60:00", TimestampFormat.mmss(3600))
        assertEquals("59:59", TimestampFormat.mmss(3599))
        assertEquals("90:00", TimestampFormat.mmss(5400))
    }

    @Test
    fun mmss_padsSingleDigits() {
        assertEquals("00:01", TimestampFormat.mmss(1))
        assertEquals("05:07", TimestampFormat.mmss(307))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mmss_negativeThrows() {
        TimestampFormat.mmss(-1)
    }

    @Test
    fun parseMmss_validInputs() {
        assertEquals(837, TimestampFormat.parseMmss("13:57"))
        assertEquals(0, TimestampFormat.parseMmss("00:00"))
        assertEquals(1, TimestampFormat.parseMmss("00:01"))
        assertEquals(5400, TimestampFormat.parseMmss("90:00"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMmss_rejectsNonNumeric() {
        TimestampFormat.parseMmss("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMmss_rejectsSecondsAbove59() {
        TimestampFormat.parseMmss("12:60")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMmss_rejectsSingleSecondDigit() {
        TimestampFormat.parseMmss("1:2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMmss_rejectsThreeSecondDigits() {
        TimestampFormat.parseMmss("12:345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMmss_rejectsEmpty() {
        TimestampFormat.parseMmss("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseMmss_rejectsMissingParts() {
        TimestampFormat.parseMmss("12:")
    }

    @Test
    fun parseMmss_mmssRoundTrip() {
        for (n in listOf(0, 1, 59, 60, 61, 837, 3599, 3600, 5400)) {
            assertEquals(n, TimestampFormat.parseMmss(TimestampFormat.mmss(n)))
        }
    }

    @Test
    fun isValidMmss_acceptsWellFormed() {
        assertTrue(TimestampFormat.isValidMmss("00:00"))
        assertTrue(TimestampFormat.isValidMmss("13:57"))
        assertTrue(TimestampFormat.isValidMmss("90:00"))
    }

    @Test
    fun isValidMmss_rejectsMalformed() {
        assertFalse(TimestampFormat.isValidMmss("abc"))
        assertFalse(TimestampFormat.isValidMmss("12:60"))
        assertFalse(TimestampFormat.isValidMmss(""))
        assertFalse(TimestampFormat.isValidMmss("1:2"))
        assertFalse(TimestampFormat.isValidMmss("12:345"))
        assertFalse(TimestampFormat.isValidMmss("00:00:00"))
    }
}
