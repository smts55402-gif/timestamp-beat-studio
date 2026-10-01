package com.timestampbeatstudio.app.engine

import com.timestampbeatstudio.core.PcmAudio
import com.timestampbeatstudio.core.TranscriptionException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTranscriptionEngineTest {

    @Test
    fun `parse full response`() {
        val json = """
            {
              "language": "en",
              "duration": 10.5,
              "words": [
                {"text": "hello", "start": 0.1, "end": 0.4, "confidence": 0.9},
                {"text": "world", "start": 1.2, "end": 1.6}
              ],
              "inaudible": [[5, 6], {"start": 8, "end": 8}]
            }
        """.trimIndent()
        val result = RemoteTranscriptionEngine.parseTranscriptionResponse(json, 99.0)
        assertEquals("en", result.language)
        assertEquals(10.5, result.durationSec, 0.0)
        assertEquals(2, result.words.size)
        assertEquals("hello", result.words[0].text)
        assertEquals(0.1, result.words[0].startSec, 0.0)
        assertEquals(0.4, result.words[0].endSec, 0.0)
        assertEquals(0.9, result.words[0].confidence!!, 0.0)
        assertNull(result.words[1].confidence)
        assertEquals(listOf(5..6, 8..8), result.inaudibleRangesSec)
    }

    @Test
    fun `parse minimal response uses defaults`() {
        val result = RemoteTranscriptionEngine.parseTranscriptionResponse("""{"words": []}""", 42.0)
        assertNull(result.language)
        assertEquals(42.0, result.durationSec, 0.0)
        assertTrue(result.words.isEmpty())
        assertTrue(result.inaudibleRangesSec.isEmpty())
    }

    @Test
    fun `parse sorts words chronologically`() {
        val json = """{"words": [
            {"text": "b", "start": 2.0, "end": 2.5},
            {"text": "a", "start": 0.5, "end": 1.0}
        ]}"""
        val result = RemoteTranscriptionEngine.parseTranscriptionResponse(json, 5.0)
        assertEquals("a", result.words[0].text)
        assertEquals("b", result.words[1].text)
    }

    @Test
    fun `parse skips malformed word entries without inventing`() {
        val json = """{"words": [
            {"text": "ok", "start": 0.0, "end": 0.5},
            {"nope": true},
            {"text": "fine", "start": 1.0, "end": 1.5}
        ]}"""
        val result = RemoteTranscriptionEngine.parseTranscriptionResponse(json, 5.0)
        assertEquals(2, result.words.size)
        assertEquals("ok", result.words[0].text)
        assertEquals("fine", result.words[1].text)
    }

    @Test(expected = TranscriptionException::class)
    fun `parse invalid json throws TranscriptionException`() {
        RemoteTranscriptionEngine.parseTranscriptionResponse("this is not json", 1.0)
    }

    @Test(expected = TranscriptionException::class)
    fun `parse non-object json throws TranscriptionException`() {
        RemoteTranscriptionEngine.parseTranscriptionResponse("[1,2,3]", 1.0)
    }

    @Test
    fun `pcmToWav produces valid 16-bit mono header`() {
        val wav = RemoteTranscriptionEngine.pcmToWav(PcmAudio(floatArrayOf(0f, 0.5f, -0.5f, 1f), 16000))
        assertEquals(44 + 8, wav.size)
        // "RIFF....WAVEfmt "
        assertArrayEquals(
            byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()),
            wav.copyOfRange(0, 4)
        )
        assertArrayEquals(
            byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()),
            wav.copyOfRange(8, 12)
        )
        assertArrayEquals(
            byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()),
            wav.copyOfRange(36, 40)
        )
        // sample rate 16000 little-endian at offset 24
        val rate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8) or
            ((wav[26].toInt() and 0xFF) shl 16) or ((wav[27].toInt() and 0xFF) shl 24)
        assertEquals(16000, rate)
        // second sample 0.5 -> ~16383 little-endian at offset 46
        val s1 = (wav[46].toInt() and 0xFF) or (wav[47].toInt() shl 8)
        assertEquals(16383, s1)
    }
}
