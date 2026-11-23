package org.nov0caina.sounder.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySearchMatcherTest {

    @Test
    fun `exact and diacritic-free matches score highest`() {
        val exact = FuzzySearchMatcher.scoreMatch("Canción Bonita", "Canción Bonita")
        val accentless = FuzzySearchMatcher.scoreMatch("Canción Bonita", "cancion")
        assertEquals(100, exact)
        assertTrue("Accentless query should match with high score ($accentless)", accentless >= 80)
    }

    @Test
    fun `handles transposed, repeated, and omitted typo characters`() {
        assertTrue(FuzzySearchMatcher.scoreMatch("Coldplay", "coldplayy") > 0)
        assertTrue(FuzzySearchMatcher.scoreMatch("The Beatles", "beatls") > 0)
        assertTrue(FuzzySearchMatcher.scoreMatch("Kendrick Lamar", "kendrik") > 0)
        assertTrue(FuzzySearchMatcher.scoreMatch("Bad Bunny", "bad buny") > 0)
        assertTrue(FuzzySearchMatcher.scoreMatch("Elevation Worship", "eleveishon worship") > 0)
        assertTrue(FuzzySearchMatcher.scoreMatch("Metallica", "metalica") > 0)
    }

    @Test
    fun `exact matches outrank fuzzy matches and unrelated strings return zero`() {
        val exactScore = FuzzySearchMatcher.scoreMatch("Yellow", "yellow")
        val fuzzyScore = FuzzySearchMatcher.scoreMatch("Yellow", "yelow")
        val unrelatedScore = FuzzySearchMatcher.scoreMatch("Yellow", "symphony")
        assertTrue(exactScore > fuzzyScore)
        assertTrue(fuzzyScore > 0)
        assertEquals(0, unrelatedScore)
    }
}
