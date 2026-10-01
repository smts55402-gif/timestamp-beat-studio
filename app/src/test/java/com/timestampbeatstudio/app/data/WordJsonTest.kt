package com.timestampbeatstudio.app.data

import com.timestampbeatstudio.core.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordJsonTest {

    @Test
    fun `words round trip exactly`() {
        val words = listOf(
            Word("You're", 0.0, 0.42, 0.93),
            Word("standing", 0.5, 0.9),
            Word("pond.", 1.84, 2.15, 0.5)
        )
        val decoded = WordJson.decodeWords(WordJson.encodeWords(words))
        assertEquals(words, decoded)
    }

    @Test
    fun `empty words round trip`() {
        assertTrue(WordJson.decodeWords(WordJson.encodeWords(emptyList())).isEmpty())
        assertTrue(WordJson.decodeWords("").isEmpty())
        assertTrue(WordJson.decodeWords("[]").isEmpty())
    }

    @Test
    fun `words with punctuation and quotes survive`() {
        val words = listOf(Word("say \"hi\", ok?", 1.0, 1.5, null))
        assertEquals(words, WordJson.decodeWords(WordJson.encodeWords(words)))
    }

    @Test
    fun `ranges round trip`() {
        val ranges = listOf(0..0, 5..7, 12..12)
        assertEquals(ranges, WordJson.decodeRanges(WordJson.encodeRanges(ranges)))
    }

    @Test
    fun `empty ranges round trip`() {
        assertTrue(WordJson.decodeRanges(WordJson.encodeRanges(emptyList())).isEmpty())
        assertTrue(WordJson.decodeRanges("").isEmpty())
    }
}
