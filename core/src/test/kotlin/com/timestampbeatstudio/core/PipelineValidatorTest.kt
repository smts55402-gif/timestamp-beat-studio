package com.timestampbeatstudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineValidatorTest {

    private val requiredCheckNames = listOf(
        "Audio Duration",
        "Every Second Generated",
        "Transcript Coverage",
        "Duplicate Words",
        "Invented Content",
        "Chronological Order",
        "Timestamp Format"
    )

    private fun sampleWords(): List<Word> = listOf(
        Word("You're", 0.10, 0.40),
        Word("standing", 0.50, 0.90),
        Word("at", 2.10, 2.20),
        Word("the", 2.25, 2.40),
        Word("pond.", 2.45, 2.80),
        Word("Listen.", 5.10, 5.60)
    )

    private fun checkOf(report: ValidationReport, name: String): ValidationCheck =
        report.checks.first { it.name == name }

    @Test
    fun validTimelinePassesAllRequiredChecks() {
        val words = sampleWords()
        val timeline = SecondBucketizer.bucketize(words, 10.0)
        val report = PipelineValidator.validate(timeline, words, 10.0)

        assertEquals(CheckStatus.PASS, report.overall)
        for (name in requiredCheckNames) {
            assertEquals("check '$name' should PASS", CheckStatus.PASS, checkOf(report, name).status)
        }
        assertEquals(0, report.missingWords)
        assertEquals(0, report.duplicateWords)
        assertEquals(0, report.inventedSegments)
        assertEquals(7, report.silentSeconds)
        assertEquals(0, report.inaudibleSeconds)
    }

    @Test
    fun inaudibleRangesReflectedInCounters() {
        val words = sampleWords()
        val timeline = SecondBucketizer.bucketize(words, 10.0, listOf(7..8))
        val report = PipelineValidator.validate(timeline, words, 10.0)
        assertEquals(CheckStatus.PASS, report.overall)
        assertEquals(5, report.silentSeconds)
        assertEquals(2, report.inaudibleSeconds)
    }

    @Test
    fun droppedWordIsCaughtAsCoverageFailure() {
        val words = sampleWords()
        val valid = SecondBucketizer.bucketize(words, 10.0)
        // drop "the" (word index 3) from bucket 2, keeping everything else intact
        val tampered = valid.copy(
            buckets = valid.buckets.map { bucket ->
                if (bucket.second == 2) {
                    bucket.copy(
                        words = bucket.words.filter { it.text != "the" },
                        sourceWordIndexes = bucket.sourceWordIndexes.filter { it != 3 }
                    )
                } else bucket
            }
        )
        val report = PipelineValidator.validate(tampered, words, 10.0)
        assertEquals(CheckStatus.FAIL, report.overall)
        assertEquals(CheckStatus.FAIL, checkOf(report, "Transcript Coverage").status)
        assertEquals(1, report.missingWords)
    }

    @Test
    fun duplicatedWordIsCaught() {
        val words = sampleWords()
        val valid = SecondBucketizer.bucketize(words, 10.0)
        // word index 3 ("the", bucket 2) also claimed by bucket 5 -> duplicate assignment
        // (this also breaks chronological order; the assertions below target the duplicate check)
        val tampered = valid.copy(
            buckets = valid.buckets.map { bucket ->
                if (bucket.second == 5) bucket.copy(sourceWordIndexes = listOf(3, 5)) else bucket
            }
        )
        val report = PipelineValidator.validate(tampered, words, 10.0)
        assertEquals(CheckStatus.FAIL, report.overall)
        assertEquals(CheckStatus.FAIL, checkOf(report, "Duplicate Words").status)
        assertEquals(1, report.duplicateWords)
    }

    @Test
    fun inventedSpeechBucketIsCaught() {
        val words = listOf(Word("hi", 0.20, 0.60))
        val buckets = listOf(
            SecondBucket(0, listOf(words[0]), BucketStatus.SPEECH, sourceWordIndexes = listOf(0)),
            // claims speech but has no words: invented content
            SecondBucket(1, emptyList(), BucketStatus.SPEECH),
            SecondBucket(2, emptyList(), BucketStatus.SILENCE)
        )
        val timeline = SecondTimeline(durationSec = 3, buckets = buckets)
        val report = PipelineValidator.validate(timeline, words, 3.0)
        assertEquals(CheckStatus.FAIL, report.overall)
        assertEquals(CheckStatus.FAIL, checkOf(report, "Invented Content").status)
        assertTrue(report.inventedSegments >= 1)
    }

    @Test
    fun chronologicalOrderViolationIsCaught() {
        val words = sampleWords()
        val valid = SecondBucketizer.bucketize(words, 10.0)
        // swap the index lists of buckets 0 and 2: flat order becomes 2,3,4,0,1,5
        val tampered = valid.copy(
            buckets = valid.buckets.map { bucket ->
                when (bucket.second) {
                    0 -> bucket.copy(
                        words = valid.buckets[2].words,
                        sourceWordIndexes = valid.buckets[2].sourceWordIndexes
                    )
                    2 -> bucket.copy(
                        words = valid.buckets[0].words,
                        sourceWordIndexes = valid.buckets[0].sourceWordIndexes
                    )
                    else -> bucket
                }
            }
        )
        val report = PipelineValidator.validate(tampered, words, 10.0)
        assertEquals(CheckStatus.FAIL, checkOf(report, "Chronological Order").status)
        assertEquals(CheckStatus.FAIL, report.overall)
    }

    @Test
    fun audioDurationMismatchIsCaught() {
        val words = sampleWords()
        val timeline = SecondBucketizer.bucketize(words, 10.0)
            .copy(durationSec = 999)
        val report = PipelineValidator.validate(timeline, words, 10.0)
        assertEquals(CheckStatus.FAIL, checkOf(report, "Audio Duration").status)
        assertEquals(CheckStatus.FAIL, report.overall)
    }

    @Test
    fun clampedWordProducesWarningNotFailure() {
        val words = sampleWords() + Word("late", 15.00, 15.50)
        val timeline = SecondBucketizer.bucketize(words, 10.0)
        val report = PipelineValidator.validate(timeline, words, 10.0)
        assertEquals(CheckStatus.WARNING, report.overall)
        assertEquals(CheckStatus.WARNING, checkOf(report, "Clamped Words").status)
        // the word was clamped to the last bucket, never dropped
        assertEquals(CheckStatus.PASS, checkOf(report, "Transcript Coverage").status)
        assertEquals(0, report.missingWords)
    }

    @Test
    fun emptyTranscriptProducesWarning() {
        val timeline = SecondBucketizer.bucketize(emptyList(), 5.0)
        val report = PipelineValidator.validate(timeline, emptyList(), 5.0)
        assertEquals(CheckStatus.WARNING, report.overall)
        assertEquals(CheckStatus.WARNING, checkOf(report, "Transcript Coverage").status)
        assertEquals(5, report.silentSeconds)
    }

    @Test
    fun fullySilentAudioProducesWarning() {
        val timeline = SecondBucketizer.bucketize(emptyList(), 5.0)
        val report = PipelineValidator.validate(timeline, emptyList(), 5.0)
        assertEquals(CheckStatus.WARNING, checkOf(report, "Silent Audio").status)
    }

    @Test
    fun manualCorrectionIsNotFlaggedAsInvented() {
        val words = sampleWords()
        val valid = SecondBucketizer.bucketize(words, 10.0)
        val corrected = valid.copy(
            buckets = valid.buckets.map { bucket ->
                if (bucket.second == 0) bucket.copy(correctedText = "You are") else bucket
            }
        )
        val report = PipelineValidator.validate(corrected, words, 10.0)
        assertEquals(CheckStatus.PASS, checkOf(report, "Invented Content").status)
        assertEquals(0, report.inventedSegments)
    }

    @Test
    fun timestampFormatCheckPassesForValidTimeline() {
        val words = sampleWords()
        val timeline = SecondBucketizer.bucketize(words, 10.0)
        val report = PipelineValidator.validate(timeline, words, 10.0)
        assertEquals(CheckStatus.PASS, checkOf(report, "Timestamp Format").status)
    }
}
