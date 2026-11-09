package org.nov0caina.sounder.online.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsSanitizerTest {

    @Test
    fun testCleanTitleRemovesVideoTags() {
        val rawTitle = "That Funny Feeling (Official Audio)"
        val cleaned = LyricsSanitizer.cleanTitle(rawTitle, "Bo Burnham")
        assertEquals("That Funny Feeling", cleaned)
    }

    @Test
    fun testCleanTitleRemovesArtistPrefixAndBrackets() {
        val rawTitle = "2Pac - California Love [Official Music Video]"
        val cleaned = LyricsSanitizer.cleanTitle(rawTitle, "2Pac")
        assertEquals("California Love", cleaned)
    }

    @Test
    fun testCleanTitleRemovesFeaturingTags() {
        val rawTitle = "The Box (feat. Guest Artist) [Explicit]"
        val cleaned = LyricsSanitizer.cleanTitle(rawTitle, "Roddy Ricch")
        assertEquals("The Box", cleaned)
    }

    @Test
    fun testCleanTitleWithRemasteredAndLive() {
        val rawTitle = "Bohemian Rhapsody (2011 Remaster)"
        val cleaned = LyricsSanitizer.cleanTitle(rawTitle, "Queen")
        assertEquals("Bohemian Rhapsody", cleaned)
    }

    @Test
    fun testCleanArtistRemovesTopicSuffix() {
        val rawArtist = "Disturbed - Topic"
        val cleaned = LyricsSanitizer.cleanArtist(rawArtist)
        assertEquals("Disturbed", cleaned)
    }

    @Test
    fun testCleanArtistSeparatesFeatured() {
        val rawArtist = "Kendrick Lamar feat. Drake"
        val cleaned = LyricsSanitizer.cleanArtist(rawArtist)
        assertEquals("Kendrick Lamar", cleaned)
    }
}

