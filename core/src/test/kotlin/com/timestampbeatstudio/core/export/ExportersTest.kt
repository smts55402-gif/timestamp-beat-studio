package com.timestampbeatstudio.core

import com.timestampbeatstudio.core.export.BeatSheetExporter
import com.timestampbeatstudio.core.export.Exporters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportersTest {

    private fun twoBucketTimeline(): SecondTimeline =
        SecondBucketizer.bucketize(listOf(Word("hello", 0.12, 0.45)), 2.0)

    @Test
    fun txtExactFormat() {
        val out = Exporters.txt.export(twoBucketTimeline(), "Demo")
        assertEquals("00:00 hello\n00:01 [SILENCE]", out)
    }

    @Test
    fun txtUsesCorrectedText() {
        val timeline = twoBucketTimeline().copy(
            buckets = twoBucketTimeline().buckets.map { bucket ->
                if (bucket.second == 0) bucket.copy(correctedText = "fixed") else bucket
            }
        )
        assertEquals("00:00 fixed\n00:01 [SILENCE]", Exporters.txt.export(timeline, "Demo"))
    }

    @Test
    fun txtCoversFull838SecondTimeline() {
        val words = (0 until 240).map { i -> Word("w$i", i * 3.5 + 0.2, i * 3.5 + 0.8) }
        val timeline = SecondBucketizer.bucketize(words, 838.75)
        val lines = Exporters.txt.export(timeline, "Demo").split("\n")
        assertEquals(838, lines.size)
        assertTrue(lines.first().startsWith("00:00 "))
        assertTrue(lines.last().startsWith("13:57 "))
        assertTrue(lines.all { TimestampFormat.isValidMmss(it.substring(0, 5)) })
    }

    @Test
    fun mdExactFormat() {
        val out = Exporters.md.export(twoBucketTimeline(), "Demo")
        assertEquals(
            "# Demo\n\n```text\n00:00 hello\n00:01 [SILENCE]\n```",
            out
        )
    }

    @Test
    fun jsonExactStructure() {
        val out = Exporters.json.export(twoBucketTimeline(), "Demo")
        val expected = """
            {
              "project": "Demo",
              "durationSec": 2,
              "seconds": [
                {
                  "timestamp": "00:00",
                  "start_seconds": 0,
                  "text": "hello",
                  "status": "speech",
                  "source_word_indexes": [0],
                  "words": [{"text": "hello", "start": 0.12, "end": 0.45}],
                  "extras": {}
                },
                {
                  "timestamp": "00:01",
                  "start_seconds": 1,
                  "text": "[SILENCE]",
                  "status": "silence",
                  "source_word_indexes": [],
                  "words": [],
                  "extras": {}
                }
              ]
            }
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun jsonEscapesSpecialCharacters() {
        val words = listOf(Word("a\"b\\c", 0.10, 0.50))
        val timeline = SecondBucketizer.bucketize(words, 1.0)
        val out = Exporters.json.export(timeline, "Pro\"ject")
        assertTrue(out.contains("\"project\": \"Pro\\\"ject\""))
        assertTrue(out.contains("\"text\": \"a\\\"b\\\\c\""))
    }

    @Test
    fun jsonStatusStringsAreLowercase() {
        val timeline = SecondBucketizer.bucketize(
            words = listOf(Word("mumble", 1.20, 1.60)),
            audioDurationSec = 3.0,
            inaudibleRangesSec = listOf(2..2)
        )
        val out = Exporters.json.export(timeline, "Demo")
        assertTrue(out.contains("\"status\": \"speech\""))
        assertTrue(out.contains("\"status\": \"silence\""))
        assertTrue(out.contains("\"status\": \"inaudible\""))
    }

    private fun srtTimeline(): SecondTimeline =
        SecondBucketizer.bucketize(
            listOf(
                Word("You", 1.84, 2.15),
                Word("go", 2.30, 2.60),
                Word("now", 4.05, 4.50)
            ),
            6.0
        )

    @Test
    fun srtMergesConsecutiveSpeechBucketsWithPreciseTiming() {
        val out = Exporters.srt.export(srtTimeline(), "Demo")
        val expected =
            "1\n" +
                "00:00:01,840 --> 00:00:02,600\n" +
                "You go\n" +
                "\n" +
                "2\n" +
                "00:00:04,050 --> 00:00:04,500\n" +
                "now\n"
        assertEquals(expected, out)
    }

    @Test
    fun srtCueNumbersStartAtOneAndSkipSilence() {
        val out = Exporters.srt.export(srtTimeline(), "Demo")
        assertTrue(out.startsWith("1\n"))
        assertTrue(out.contains("\n2\n"))
        assertTrue(!out.contains("[SILENCE]"))
    }

    @Test
    fun srtFormatsHoursCorrectly() {
        val timeline = SecondBucketizer.bucketize(listOf(Word("late", 3700.25, 3700.75)), 3701.0)
        val out = Exporters.srt.export(timeline, "Demo")
        assertTrue(out.contains("01:01:40,250 --> 01:01:40,750"))
    }

    @Test
    fun safeFileNameBasic() {
        assertEquals(
            "My_Project_stage01.txt",
            Exporters.safeFileName("My Project!", Exporters.txt)
        )
    }

    @Test
    fun safeFileNameSanitizesIllegalCharacters() {
        val name = Exporters.safeFileName("a/b\\c:d*e?f\"g<h>i|j", Exporters.md)
        assertEquals("a_b_c_d_e_f_g_h_i_j_stage01.md", name)
        assertTrue(!name.contains(Regex("[<>:\"/\\\\|?*]")))
    }

    @Test
    fun safeFileNameFallsBackForEmpty() {
        assertEquals("project_stage01.json", Exporters.safeFileName("", Exporters.json))
        assertEquals("project_stage01.srt", Exporters.safeFileName("...   ...", Exporters.srt))
    }

    @Test
    fun safeFileNameAppendsSuffixAndExtensionPerExporter() {
        val exporters: List<BeatSheetExporter> = Exporters.all()
        assertEquals(4, exporters.size)
        assertEquals(
            listOf("txt", "md", "json", "srt"),
            exporters.map { it.extension }
        )
        for (exporter in exporters) {
            assertEquals(
                "Demo_stage01.${exporter.extension}",
                Exporters.safeFileName("Demo", exporter)
            )
            assertEquals("_stage01", exporter.fileSuffix)
        }
    }

    @Test
    fun exporterMimeTypes() {
        assertEquals("text/plain", Exporters.txt.mimeType)
        assertEquals("text/markdown", Exporters.md.mimeType)
        assertEquals("application/json", Exporters.json.mimeType)
        assertEquals("application/x-subrip", Exporters.srt.mimeType)
    }
}
