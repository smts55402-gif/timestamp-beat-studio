package com.timestampbeatstudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondBucketizerTest {

    @Test
    fun bucketCountFor838SecondAudio() {
        val words = listOf(
            Word("You're", 0.10, 0.40),
            Word("standing", 100.20, 100.60),
            Word("back.", 837.90, 838.30)
        )
        val timeline = SecondBucketizer.bucketize(words, 838.75)
        assertEquals(838, timeline.durationSec)
        assertEquals(838, timeline.buckets.size)
        assertEquals(0, timeline.buckets.first().second)
        assertEquals(837, timeline.buckets.last().second)
        assertEquals("00:00", TimestampFormat.mmss(timeline.buckets.first().second))
        assertEquals("13:57", TimestampFormat.mmss(timeline.buckets.last().second))
        // word starting in the final partial second lands in the last bucket
        assertEquals(listOf("back."), timeline.buckets[837].words.map { it.text })
    }

    @Test
    fun full838TimelineAssignsEveryWord() {
        // one word every ~3.5s across a 13:58.75 documentary narration
        val words = (0 until 240).map { i ->
            Word("w$i", i * 3.5 + 0.2, i * 3.5 + 0.8)
        }
        val timeline = SecondBucketizer.bucketize(words, 838.75)
        assertEquals(838, timeline.buckets.size)
        val assigned = timeline.buckets.flatMap { it.sourceWordIndexes }
        assertEquals((0 until 240).toList(), assigned.sorted())
        assertEquals(240, assigned.toSet().size)
    }

    @Test
    fun assignsWordByStartSecond() {
        val words = listOf(
            Word("early", 2.84, 3.10),
            Word("late", 3.02, 3.50)
        )
        val timeline = SecondBucketizer.bucketize(words, 10.0)
        assertEquals(listOf("early"), timeline.buckets[2].words.map { it.text })
        assertEquals(listOf("late"), timeline.buckets[3].words.map { it.text })
    }

    @Test
    fun wordCrossingSecondBoundaryStaysIntactInStartBucket() {
        val word = Word("hello", 1.84, 2.15)
        val timeline = SecondBucketizer.bucketize(listOf(word), 10.0)
        assertTrue(timeline.buckets[1].words.size == 1)
        val assigned = timeline.buckets[1].words[0]
        assertEquals("hello", assigned.text)
        assertEquals(1.84, assigned.startSec, 0.0)
        assertEquals(2.15, assigned.endSec, 0.0)
        assertTrue(timeline.buckets[2].words.isEmpty())
        assertEquals("hello", timeline.buckets[1].displayText)
    }

    @Test
    fun multipleWordsInSameSecondAreJoined() {
        val words = listOf(
            Word("You", 2.10, 2.30),
            Word("are", 2.35, 2.50),
            Word("standing", 2.55, 2.90)
        )
        val timeline = SecondBucketizer.bucketize(words, 10.0)
        assertEquals(BucketStatus.SPEECH, timeline.buckets[2].status)
        assertEquals("You are standing", timeline.buckets[2].displayText)
        assertEquals(listOf(0, 1, 2), timeline.buckets[2].sourceWordIndexes)
    }

    @Test
    fun emptySecondIsSilence() {
        val timeline = SecondBucketizer.bucketize(listOf(Word("hi", 0.50, 0.90)), 3.0)
        assertEquals(BucketStatus.SILENCE, timeline.buckets[1].status)
        assertEquals(SILENCE_PLACEHOLDER, timeline.buckets[1].displayText)
        assertEquals("[SILENCE]", timeline.buckets[1].displayText)
    }

    @Test
    fun inaudibleRangesMarkEmptySeconds() {
        val timeline = SecondBucketizer.bucketize(
            words = listOf(Word("hi", 0.50, 0.90)),
            audioDurationSec = 10.0,
            inaudibleRangesSec = listOf(5..7)
        )
        assertEquals(BucketStatus.INAUDIBLE, timeline.buckets[5].status)
        assertEquals(BucketStatus.INAUDIBLE, timeline.buckets[6].status)
        assertEquals(BucketStatus.INAUDIBLE, timeline.buckets[7].status)
        assertEquals(INAUDIBLE_PLACEHOLDER, timeline.buckets[6].displayText)
        assertEquals("[INAUDIBLE]", timeline.buckets[6].displayText)
        assertEquals(BucketStatus.SILENCE, timeline.buckets[4].status)
        assertEquals(BucketStatus.SILENCE, timeline.buckets[8].status)
    }

    @Test
    fun inaudibleRangeWithWordsIsStillSpeech() {
        val timeline = SecondBucketizer.bucketize(
            words = listOf(Word("mumble", 5.50, 5.90)),
            audioDurationSec = 10.0,
            inaudibleRangesSec = listOf(5..7)
        )
        assertEquals(BucketStatus.SPEECH, timeline.buckets[5].status)
        assertEquals("mumble", timeline.buckets[5].displayText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonChronologicalWordsThrow() {
        SecondBucketizer.bucketize(
            listOf(Word("b", 2.0, 2.5), Word("a", 1.0, 1.5)),
            10.0
        )
    }

    @Test
    fun equalStartTimesAreAllowed() {
        val timeline = SecondBucketizer.bucketize(
            listOf(Word("a", 1.0, 1.5), Word("b", 1.0, 1.4)),
            10.0
        )
        assertEquals("a b", timeline.buckets[1].displayText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroDurationThrows() {
        SecondBucketizer.bucketize(emptyList(), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeDurationThrows() {
        SecondBucketizer.bucketize(emptyList(), -5.0)
    }

    @Test
    fun shortAudioProducesExactlyOneBucket() {
        val timeline = SecondBucketizer.bucketize(listOf(Word("go", 0.10, 0.30)), 0.4)
        assertEquals(1, timeline.durationSec)
        assertEquals(1, timeline.buckets.size)
        assertEquals("go", timeline.buckets[0].displayText)
    }

    @Test
    fun longAudio3600Seconds() {
        val timeline = SecondBucketizer.bucketize(
            listOf(Word("start", 0.10, 0.50), Word("end", 3599.10, 3599.50)),
            3600.0
        )
        assertEquals(3600, timeline.buckets.size)
        assertEquals("00:00", TimestampFormat.mmss(0))
        assertEquals("59:59", TimestampFormat.mmss(3599))
        assertEquals(listOf("end"), timeline.buckets[3599].words.map { it.text })
    }

    @Test
    fun wordAtOrAfterDurationClampsToLastBucket() {
        val timeline = SecondBucketizer.bucketize(listOf(Word("late", 900.0, 900.50)), 10.0)
        assertEquals(10, timeline.buckets.size)
        assertEquals(listOf("late"), timeline.buckets[9].words.map { it.text })
        assertEquals(listOf(0), timeline.buckets[9].sourceWordIndexes)
    }

    @Test
    fun wordWithNegativeStartClampsToFirstBucket() {
        val timeline = SecondBucketizer.bucketize(listOf(Word("early", -0.50, 0.10)), 10.0)
        assertEquals(listOf("early"), timeline.buckets[0].words.map { it.text })
    }

    @Test
    fun sourceWordIndexesAreAscendingAcrossBuckets() {
        val words = (0 until 20).map { i -> Word("w$i", i * 0.5, i * 0.5 + 0.3) }
        val timeline = SecondBucketizer.bucketize(words, 10.0)
        val flat = timeline.buckets.flatMap { it.sourceWordIndexes }
        assertEquals((0 until 20).toList(), flat)
    }
}
