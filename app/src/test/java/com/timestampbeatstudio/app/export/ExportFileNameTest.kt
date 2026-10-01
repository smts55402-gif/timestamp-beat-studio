package com.timestampbeatstudio.app.export

import com.timestampbeatstudio.core.export.Exporters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the safe file names [ExportManager] relies on when writing exports
 * to cacheDir/exports (one name per format, suffix `_stage01`).
 */
class ExportFileNameTest {

    @Test
    fun `txt file name`() {
        assertEquals("My_Project_stage01.txt", Exporters.safeFileName("My Project!", Exporters.txt))
    }

    @Test
    fun `md file name`() {
        assertEquals("My_Project_stage01.md", Exporters.safeFileName("My Project!", Exporters.md))
    }

    @Test
    fun `json file name`() {
        assertEquals("My_Project_stage01.json", Exporters.safeFileName("My Project!", Exporters.json))
    }

    @Test
    fun `srt file name`() {
        assertEquals("My_Project_stage01.srt", Exporters.safeFileName("My Project!", Exporters.srt))
    }

    @Test
    fun `file name contains no illegal characters`() {
        val name = Exporters.safeFileName("a/b\\c:d*e?\"f<g>h|i", Exporters.txt)
        assertTrue("illegal chars in $name", name.none { it in "/\\:*?\"<>|" })
        assertTrue("suffix in $name", name.endsWith("_stage01.txt"))
    }

    @Test
    fun `exporter extensions and mime types`() {
        assertEquals("txt", Exporters.txt.extension)
        assertEquals("md", Exporters.md.extension)
        assertEquals("json", Exporters.json.extension)
        assertEquals("srt", Exporters.srt.extension)
        assertEquals(4, Exporters.all().size)
    }
}
