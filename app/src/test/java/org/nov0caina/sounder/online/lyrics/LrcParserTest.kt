package org.nov0caina.sounder.online.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun testStandardLrcParsing() {
        val lrc = """
            [00:12.34] First line
            [00:15.67] Second line
            [01:02.00] Third line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(3, lines.size)

        assertEquals(12340L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)

        assertEquals(15670L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)

        assertEquals(62000L, lines[2].timeMs)
        assertEquals("Third line", lines[2].text)
    }

    @Test
    fun testMultipleTimestampsOnSameLine() {
        val lrc = "[00:05.00][00:20.00] Repeated chorus line"

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)

        assertEquals(5000L, lines[0].timeMs)
        assertEquals("Repeated chorus line", lines[0].text)

        assertEquals(20000L, lines[1].timeMs)
        assertEquals("Repeated chorus line", lines[1].text)
    }

    @Test
    fun testVaryingMillisecondPrecision() {
        val lrc = """
            [00:01.500] 3-digit millis
            [00:03.5] 1-digit millis
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)

        assertEquals(1500L, lines[0].timeMs)
        assertEquals("3-digit millis", lines[0].text)

        assertEquals(3500L, lines[1].timeMs)
        assertEquals("1-digit millis", lines[1].text)
    }

    @Test
    fun testMetadataTagsIgnored() {
        val lrc = """
            [ti:Test Title]
            [ar:Test Artist]
            [al:Test Album]
            [by:Test Contributor]
            [offset:0]
            [00:10.00] Actual lyrics line
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals(10000L, lines[0].timeMs)
        assertEquals("Actual lyrics line", lines[0].text)
    }

    @Test
    fun testOutOfOrderSorting() {
        val lrc = """
            [00:30.00] Line 2
            [00:10.00] Line 1
        """.trimIndent()

        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].timeMs)
        assertEquals("Line 1", lines[0].text)
        assertEquals(30000L, lines[1].timeMs)
        assertEquals("Line 2", lines[1].text)
    }

    @Test
    fun testEmptyAndBlankLrc() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   \n\n\t  ").isEmpty())
    }
}

