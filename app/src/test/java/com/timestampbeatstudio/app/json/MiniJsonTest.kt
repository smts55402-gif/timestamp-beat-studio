package com.timestampbeatstudio.app.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `round trip preserves nested structure`() {
        val value = mapOf(
            "text" to "he said \"hi\"",
            "start" to 0.25,
            "whole" to 12.0,
            "flag" to true,
            "missing" to null,
            "list" to listOf(1.0, "two", false, null),
            "obj" to mapOf("n" to -3.5e2)
        )
        val parsed = MiniJson.parse(MiniJson.stringify(value))
        assertEquals(value, parsed)
    }

    @Test
    fun `parses primitives`() {
        assertEquals("abc", MiniJson.parse("\"abc\""))
        assertEquals(12.0, MiniJson.parse("12"))
        assertEquals(0.5, MiniJson.parse("0.5"))
        assertEquals(true, MiniJson.parse("true"))
        assertNull(MiniJson.parse("null"))
        assertTrue((MiniJson.parse("[1,2]") as List<*>).size == 2)
    }

    @Test
    fun `stringify escapes control characters`() {
        val s = MiniJson.stringify("a\nb\t\"q\"\\")
        assertEquals("\"a\\nb\\t\\\"q\\\"\\\\\"", s)
        assertEquals("a\nb\t\"q\"\\", MiniJson.parse(s))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed object throws`() {
        MiniJson.parse("{bad")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated input throws`() {
        MiniJson.parse("[1, 2")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trailing characters throw`() {
        MiniJson.parse("{} extra")
    }
}
