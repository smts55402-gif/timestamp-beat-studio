package com.timestampbeatstudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun silenceAndInaudiblePlaceholders() {
        assertEquals("[SILENCE]", SILENCE_PLACEHOLDER)
        assertEquals("[INAUDIBLE]", INAUDIBLE_PLACEHOLDER)
    }

    @Test
    fun displayTextJoinsWordsWithSpaces() {
        val bucket = SecondBucket(
            second = 2,
            words = listOf(Word("You", 2.1, 2.3), Word("are", 2.4, 2.6)),
            status = BucketStatus.SPEECH,
            sourceWordIndexes = listOf(0, 1)
        )
        assertEquals("You are", bucket.displayText)
    }

    @Test
    fun displayTextForSilenceBucket() {
        val bucket = SecondBucket(second = 7, words = emptyList(), status = BucketStatus.SILENCE)
        assertEquals("[SILENCE]", bucket.displayText)
    }

    @Test
    fun displayTextForInaudibleBucket() {
        val bucket = SecondBucket(second = 7, words = emptyList(), status = BucketStatus.INAUDIBLE)
        assertEquals("[INAUDIBLE]", bucket.displayText)
    }

    @Test
    fun correctedTextOverridesDisplayTextButWordsStayUntouched() {
        val original = Word("recieve", 0.2, 0.6)
        val bucket = SecondBucket(
            second = 0,
            words = listOf(original),
            status = BucketStatus.SPEECH,
            correctedText = "receive",
            sourceWordIndexes = listOf(0)
        )
        assertEquals("receive", bucket.displayText)
        assertEquals("recieve", bucket.words[0].text)
        assertEquals(original, bucket.words[0])
    }

    @Test
    fun pcmAudioEqualsIsContentBased() {
        val a = PcmAudio(floatArrayOf(0.1f, 0.2f, 0.3f), 16000)
        val b = PcmAudio(floatArrayOf(0.1f, 0.2f, 0.3f), 16000)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun pcmAudioNotEqualForDifferentContent() {
        val a = PcmAudio(floatArrayOf(0.1f, 0.2f), 16000)
        val b = PcmAudio(floatArrayOf(0.1f, 0.9f), 16000)
        assertNotEquals(a, b)
    }

    @Test
    fun pcmAudioNotEqualForDifferentSampleRate() {
        val a = PcmAudio(floatArrayOf(0.1f), 16000)
        val b = PcmAudio(floatArrayOf(0.1f), 44100)
        assertNotEquals(a, b)
    }

    @Test
    fun pcmAudioDefaultSampleRate() {
        assertEquals(16000, PcmAudio(floatArrayOf()).sampleRate)
    }

    @Test
    fun wordDefaultConfidenceIsNull() {
        assertEquals(null, Word("hi", 0.1, 0.5).confidence)
        assertTrue(Word("hi", 0.1, 0.5, 0.9).confidence == 0.9)
    }
}
