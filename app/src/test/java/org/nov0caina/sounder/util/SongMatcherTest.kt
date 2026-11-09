package org.nov0caina.sounder.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMatcherTest {

    @Test
    fun testExactMatch() {
        assertTrue(SongMatcher.matches("Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testCaseAndPunctuationMatch() {
        assertTrue(SongMatcher.matches("Thunderstruck", "AC/DC", "thunderstruck", "ac-dc"))
        assertTrue(SongMatcher.matches("XO Tour Llif3", "Lil Uzi Vert", "XO TOUR Llif3", "Lil Uzi Vert"))
    }

    @Test
    fun testTrackNumberPrefixMatch() {
        assertTrue(SongMatcher.matches("01 - Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("01. Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("1 - Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testVideoTagsAndRemasteredDescriptors() {
        assertTrue(SongMatcher.matches("Thunderstruck (Official Music Video)", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("Thunderstruck [Remastered]", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("Thunderstruck (Audio)", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testFeaturingArtistMatch() {
        assertTrue(SongMatcher.matches("UnFazed", "Lil Uzi Vert feat. The Weeknd", "UnFazed", "Lil Uzi Vert"))
    }

    @Test
    fun testDifferentTitlesDoNotMatch() {
        assertFalse(SongMatcher.matches("Thunderstruck", "AC/DC", "Moneytalks", "AC/DC"))
        assertFalse(SongMatcher.matches("Fire Your Guns", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testDifferentArtistsDoNotMatch() {
        assertFalse(SongMatcher.matches("One", "Metallica", "One", "U2"))
    }
}
