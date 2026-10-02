package com.timestampbeatstudio.core

import com.timestampbeatstudio.core.export.Exporters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

/**
 * End-to-end verification on the REAL 13:58 narration audio
 * (`15_Goldusalexis_3_d0nz.m4a`, 838.751995 s).
 *
 * Input: `e2e/words.json`, produced by `e2e/transcribe_e2e.py`
 * (faster-whisper `small`, word-level timestamps).
 *
 * The test is skipped gracefully when the input file is absent so that
 * regular CI runs stay green without the 14-minute transcription.
 *
 * Verifies the fundamental contract: ONE AUDIO SECOND = ONE TIMESTAMP RECORD.
 */
class RealAudioE2ETest {

    // ------------------------------------------------------------------
    // Minimal JSON reader for the known e2e/words.json shape.
    // ------------------------------------------------------------------

    private sealed interface JVal
    private data class JObj(val map: Map<String, JVal>) : JVal
    private data class JArr(val items: List<JVal>) : JVal
    private data class JStr(val v: String) : JVal
    private data class JNum(val v: Double) : JVal
    private data class JBool(val v: Boolean) : JVal
    private object JNull : JVal

    private class JParser(val s: String) {
        var i = 0
        fun parse(): JVal {
            val v = parseValue()
            skipWs()
            check(i == s.length) { "trailing chars at $i" }
            return v
        }
        private fun parseValue(): JVal {
            skipWs()
            check(i < s.length) { "unexpected end" }
            return when (s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> JStr(parseStr())
                't' -> { expect("true"); JBool(true) }
                'f' -> { expect("false"); JBool(false) }
                'n' -> { expect("null"); JNull }
                else -> parseNum()
            }
        }
        private fun parseObj(): JObj {
            i++; val m = LinkedHashMap<String, JVal>()
            skipWs()
            if (s[i] == '}') { i++; return JObj(m) }
            while (true) {
                skipWs(); val k = parseStr(); skipWs()
                check(s[i] == ':') { "expected : at $i" }; i++
                m[k] = parseValue(); skipWs()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JObj(m) }
                    else -> throw IllegalStateException("expected , or } at $i")
                }
            }
        }
        private fun parseArr(): JArr {
            i++; val l = ArrayList<JVal>()
            skipWs()
            if (s[i] == ']') { i++; return JArr(l) }
            while (true) {
                l.add(parseValue()); skipWs()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JArr(l) }
                    else -> throw IllegalStateException("expected , or ] at $i")
                }
            }
        }
        private fun parseStr(): String {
            i++; val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        '/' -> sb.append('/'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                        }
                        else -> throw IllegalStateException("bad escape $e")
                    }
                    else -> sb.append(c)
                }
            }
        }
        private fun parseNum(): JNum {
            val st = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            return JNum(s.substring(st, i).toDouble())
        }
        private fun expect(lit: String) {
            check(s.startsWith(lit, i)) { "expected $lit at $i" }; i += lit.length
        }
        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    private data class E2EInput(
        val duration: Double,
        val language: String?,
        val words: List<Word>
    )

    private fun loadInput(): E2EInput? {
        // Gradle runs :core tests with the module directory as working dir.
        val file = listOf(
            File("../e2e/words.json"),
            File("e2e/words.json")
        ).firstOrNull { it.isFile } ?: return null
        val root = (JParser(file.readText()).parse() as JObj).map
        val duration = (root["duration"] as JNum).v
        val language = (root["language"] as? JStr)?.v
        val words = ((root["words"] as JArr).items).map { w ->
            val m = (w as JObj).map
            Word(
                text = (m["text"] as JStr).v,
                startSec = (m["start"] as JNum).v,
                endSec = (m["end"] as JNum).v,
                confidence = (m["probability"] as? JNum)?.v
            )
        }
        return E2EInput(duration, language, words)
    }

    // ------------------------------------------------------------------
    // The E2E verification.
    // ------------------------------------------------------------------

    @Test
    fun `real 838s audio yields exactly 838 whole-second records`() {
        val input = loadInput()
        assumeTrue(
            "e2e/words.json not present — run e2e/transcribe_e2e.py first",
            input != null
        )
        input!!

        println("duration=${input.duration}s language=${input.language} words=${input.words.size}")

        // Production sorts chronologically before bucketing; mirror that here.
        val words = input.words.sortedBy { it.startSec }
        val timeline = SecondBucketizer.bucketize(words, input.duration)

        // --- ONE AUDIO SECOND = ONE TIMESTAMP RECORD ---
        val expectedBuckets = maxOf(1, floor(input.duration).toInt())
        println("expectedBuckets=$expectedBuckets")
        assertEquals(
            "838.75s audio must yield exactly 838 buckets",
            838, expectedBuckets
        )
        assertEquals(expectedBuckets, timeline.durationSec)
        assertEquals(expectedBuckets, timeline.buckets.size)

        // --- timestamps 00:00 .. 13:57, strictly one per second ---
        timeline.buckets.forEachIndexed { idx, bucket ->
            assertEquals("bucket index == second", idx, bucket.second)
            // Visible timestamp must be whole-second MM:SS; parseMmss throws otherwise.
            val visible = TimestampFormat.mmss(bucket.second)
            assertEquals(idx, TimestampFormat.parseMmss(visible))
        }
        assertEquals("00:00", TimestampFormat.mmss(timeline.buckets.first().second))
        assertEquals("13:57", TimestampFormat.mmss(timeline.buckets.last().second))

        // --- every recognized word appears exactly once ---
        val allIndexes = timeline.buckets.flatMap { it.sourceWordIndexes }
        assertEquals("no missing/duplicated word assignments", words.size, allIndexes.size)
        assertEquals(
            (0 until words.size).toSet(), allIndexes.toSet()
        )
        val assignedWords = timeline.buckets.sumOf { it.words.size }
        assertEquals(words.size, assignedWords)

        // --- assignment rule: floor(word.startSec) ---
        timeline.buckets.forEach { bucket ->
            bucket.words.forEach { w ->
                val expected = floor(w.startSec).toInt().coerceIn(0, expectedBuckets - 1)
                assertEquals(
                    "word '${w.text}' @${w.startSec} belongs to $expected",
                    expected, bucket.second
                )
            }
        }

        // --- silence buckets retained ---
        val silence = timeline.buckets.count { it.status == BucketStatus.SILENCE }
        println("silenceBuckets=$silence speechBuckets=${timeline.buckets.count { it.status == BucketStatus.SPEECH }}")
        assertTrue("some seconds must be silence in a 14-min narration", silence > 0)

        // --- validation: no FAIL allowed ---
        val report = PipelineValidator.validate(timeline, words, input.duration)
        report.checks.forEach { println("check ${it.name}: ${it.status} — ${it.detail}") }
        val failures = report.checks.filter { it.status == CheckStatus.FAIL }
        assertTrue(
            "validation failures: ${failures.map { it.name to it.detail }}",
            failures.isEmpty()
        )

        // --- exports: TXT/MD carry whole-second timestamps only ---
        val outDir = File("../e2e/out").also { it.mkdirs() }
        val txt = Exporters.txt.export(timeline, "E2E_Real_Audio")
        val md = Exporters.md.export(timeline, "E2E_Real_Audio")
        val json = Exporters.json.export(timeline, "E2E_Real_Audio")
        val srt = Exporters.srt.export(timeline, "E2E_Real_Audio")

        // No fractional timestamps in the visible beat sheet.
        val fractional = Regex("""\d{2}:\d{2}[.,]\d""")
        assertTrue("TXT must not contain fractional timestamps", !fractional.containsMatchIn(txt))
        assertTrue("MD must not contain fractional timestamps", !fractional.containsMatchIn(md))

        // Every bucket line present in TXT.
        val txtLines = txt.lines().filter { it.isNotBlank() }
        assertEquals(expectedBuckets, txtLines.size)
        assertTrue(txtLines.first().startsWith("00:00 "))
        assertTrue(txtLines.last().startsWith("13:57 "))

        // JSON keeps precise internal timing metadata per word.
        assertTrue("JSON must carry internal word timing", json.contains("\"start\":"))

        // SRT is non-empty and well-formed-ish.
        assertTrue(srt.isNotBlank())
        assertTrue(srt.contains("-->"))

        File(outDir, "E2E_Real_Audio_stage01.txt").writeText(txt)
        File(outDir, "E2E_Real_Audio_stage01.md").writeText(md)
        File(outDir, "E2E_Real_Audio_stage01.json").writeText(json)
        File(outDir, "E2E_Real_Audio_stage01.srt").writeText(srt)
        println("exports written to ${outDir.absolutePath}")

        println("E2E PASS: $expectedBuckets buckets, ${words.size} words, 0 missing, 0 duplicated")
    }
}
