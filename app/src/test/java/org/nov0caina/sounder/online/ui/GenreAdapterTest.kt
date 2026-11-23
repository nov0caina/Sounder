package org.nov0caina.sounder.online.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreAdapterTest {

    @Test
    fun `DEFAULT_GENRES contains 12 curated genres including Christian and K-Pop`() {
        val names = GenreAdapter.DEFAULT_GENRES.map { it.name }
        assertEquals(12, GenreAdapter.DEFAULT_GENRES.size)
        assertTrue(GenreAdapter.GENRE_CHRISTIAN_WORSHIP in names)
        assertTrue(GenreAdapter.GENRE_KPOP in names)
        val christianItem = GenreAdapter.DEFAULT_GENRES.first { it.name == GenreAdapter.GENRE_CHRISTIAN_WORSHIP }
        assertTrue(christianItem.nameRes != 0)
    }

    @Test
    fun `filterGenres returns all 12 when both optional genres are enabled`() {
        val filtered = GenreAdapter.filterGenres(showChristian = true, showKpop = true)
        val names = filtered.map { it.name }
        assertEquals(12, filtered.size)
        assertTrue(GenreAdapter.GENRE_CHRISTIAN_WORSHIP in names)
        assertTrue(GenreAdapter.GENRE_KPOP in names)
    }

    @Test
    fun `filterGenres omits only Christian & Worship when disabled`() {
        val filtered = GenreAdapter.filterGenres(showChristian = false, showKpop = true)
        val names = filtered.map { it.name }
        assertEquals(11, filtered.size)
        assertFalse(GenreAdapter.GENRE_CHRISTIAN_WORSHIP in names)
        assertTrue(GenreAdapter.GENRE_KPOP in names)
    }

    @Test
    fun `filterGenres omits only K-Pop when disabled`() {
        val filtered = GenreAdapter.filterGenres(showChristian = true, showKpop = false)
        val names = filtered.map { it.name }
        assertEquals(11, filtered.size)
        assertTrue(GenreAdapter.GENRE_CHRISTIAN_WORSHIP in names)
        assertFalse(GenreAdapter.GENRE_KPOP in names)
    }

    @Test
    fun `filterGenres returns base 10 genres when both optional genres are disabled`() {
        val filtered = GenreAdapter.filterGenres(showChristian = false, showKpop = false)
        val names = filtered.map { it.name }
        assertEquals(10, filtered.size)
        assertFalse(GenreAdapter.GENRE_CHRISTIAN_WORSHIP in names)
        assertFalse(GenreAdapter.GENRE_KPOP in names)
    }
}
