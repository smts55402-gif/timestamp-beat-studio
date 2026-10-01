package com.timestampbeatstudio.core

/**
 * Whole-second timestamp formatting and parsing for the visible beat sheet.
 *
 * The ONLY visible timestamp format is `MM:SS`. Minutes may exceed 59
 * (hours roll into minutes, e.g. 5400 seconds -> "90:00"). Fractional
 * internal timing is never exposed here.
 */
object TimestampFormat {

    private val MMSS_PATTERN = Regex("""^(\d+):([0-5]\d)$""")

    /**
     * Formats whole seconds as zero-padded `MM:SS`.
     *
     * Examples: 0 -> "00:00", 61 -> "01:01", 837 -> "13:57", 5400 -> "90:00".
     *
     * @param totalSeconds whole seconds to format; must be >= 0.
     * @return the `MM:SS` representation.
     * @throws IllegalArgumentException if [totalSeconds] is negative.
     */
    fun mmss(totalSeconds: Int): String {
        require(totalSeconds >= 0) { "totalSeconds must be >= 0, was $totalSeconds" }
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /**
     * Parses a strict `MM:SS` timestamp back to whole seconds.
     *
     * Accepts `^\d+:[0-5]\d$` only: one or more minute digits, a colon, and
     * exactly two second digits in 00..59.
     *
     * @param s the timestamp string, e.g. "13:57".
     * @return whole seconds, e.g. 837.
     * @throws IllegalArgumentException on any format violation.
     */
    fun parseMmss(s: String): Int {
        val match = MMSS_PATTERN.matchEntire(s)
            ?: throw IllegalArgumentException("Invalid MM:SS timestamp: '$s'")
        val minutes = match.groupValues[1].toInt()
        val seconds = match.groupValues[2].toInt()
        return minutes * 60 + seconds
    }

    /**
     * Validates the visible `MM:SS` format without throwing.
     *
     * Same rule as [parseMmss]: `^\d+:[0-5]\d$`.
     *
     * @param s the candidate timestamp string.
     * @return true if [s] is a valid visible timestamp, false otherwise.
     */
    fun isValidMmss(s: String): Boolean = MMSS_PATTERN.matches(s)
}
