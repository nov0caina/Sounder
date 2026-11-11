package org.nov0caina.sounder.home.tabs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.IntegerTable
import org.nov0caina.sounder.home.HomeSettingsImpl
import org.nov0caina.sounder.list.EditClickListListener
import org.nov0caina.sounder.music.MusicType
import androidx.recyclerview.widget.RecyclerView

class TabTest {

    @Test
    fun `MusicType DOWNLOADS resolves from 0xA10F code`() {
        assertEquals(0xA10F, IntegerTable.MUSIC_MODE_DOWNLOADS)
        assertEquals(0xA10F, MusicType.DOWNLOADS.intCode)
        assertEquals(MusicType.DOWNLOADS, MusicType.fromIntCode(0xA10F))
        assertEquals(MusicType.DOWNLOADS, MusicType.fromCode(0xA10F))
    }

    @Test
    fun `Tab default sequence decodes to all 6 visible tabs including DOWNLOADS`() {
        assertEquals(5, Tab.MAX_SEQUENCE_IDX)
        assertEquals(6, Tab.NUM_ITEMS)

        val tabs = Tab.fromIntCode(Tab.SEQUENCE_DEFAULT)
        assertNotNull(tabs)
        val expected = arrayOf<Tab>(
            Tab.Visible(MusicType.SONGS),
            Tab.Visible(MusicType.ALBUMS),
            Tab.Visible(MusicType.ARTISTS),
            Tab.Visible(MusicType.GENRES),
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Visible(MusicType.DOWNLOADS),
        )
        assertArrayEquals(expected, tabs)
    }

    @Test
    fun `Tab toIntCode and fromIntCode round-trip preserves custom 6-tab order and visibility`() {
        val customTabs = arrayOf<Tab>(
            Tab.Visible(MusicType.DOWNLOADS),
            Tab.Invisible(MusicType.SONGS),
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Invisible(MusicType.GENRES),
            Tab.Visible(MusicType.ALBUMS),
            Tab.Visible(MusicType.ARTISTS),
        )

        val encoded = Tab.toIntCode(customTabs)
        val decoded = Tab.fromIntCode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(customTabs, decoded)
    }

    @Test
    fun `Tab round-trip works when Invisible SONGS is at index 0`() {
        val customTabs = arrayOf<Tab>(
            Tab.Invisible(MusicType.SONGS),
            Tab.Visible(MusicType.ALBUMS),
            Tab.Visible(MusicType.ARTISTS),
            Tab.Visible(MusicType.GENRES),
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Invisible(MusicType.DOWNLOADS),
        )

        val encoded = Tab.toIntCode(customTabs)
        val decoded = Tab.fromIntCode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(customTabs, decoded)
    }

    @Test
    fun `Legacy 5-tab bitmask automatically migrates by appending Visible DOWNLOADS`() {
        val legacyDefault = 0b1000_1001_1010_1011_1100
        val migrated = Tab.fromIntCode(legacyDefault)
        assertNotNull(migrated)

        val expected = arrayOf<Tab>(
            Tab.Visible(MusicType.SONGS),
            Tab.Visible(MusicType.ALBUMS),
            Tab.Visible(MusicType.ARTISTS),
            Tab.Visible(MusicType.GENRES),
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Visible(MusicType.DOWNLOADS),
        )
        assertArrayEquals(expected, migrated)
    }

    @Test
    fun `HomeSettings migrateTabsIfNeeded appends DOWNLOADS when missing`() {
        val fiveTabs = arrayOf<Tab>(
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Visible(MusicType.SONGS),
            Tab.Invisible(MusicType.ALBUMS),
            Tab.Visible(MusicType.ARTISTS),
            Tab.Invisible(MusicType.GENRES),
        )

        val migrated = HomeSettingsImpl.migrateTabsIfNeeded(fiveTabs)
        assertEquals(6, migrated.size)
        assertEquals(Tab.Visible(MusicType.DOWNLOADS), migrated.last())

        // Calling again is idempotent
        val migratedAgain = HomeSettingsImpl.migrateTabsIfNeeded(migrated)
        assertArrayEquals(migrated, migratedAgain)
    }

    @Test
    fun `Invalid intCode returns null`() {
        assertNull(Tab.fromIntCode(0))
    }

    @Test
    fun `TabDragCallback enables long press drag`() {
        val dummyListener = object : EditClickListListener<Tab> {
            override fun onClick(item: Tab, viewHolder: RecyclerView.ViewHolder) {}
            override fun onPickUp(viewHolder: RecyclerView.ViewHolder) {}
        }
        val adapter = TabAdapter(dummyListener)
        val callback = TabDragCallback(adapter)
        assertTrue(callback.isLongPressDragEnabled)
    }

    @Test
    fun `HomeSettings reorderVisibleTabs updates visible order while preserving invisible slots`() {
        val initial = arrayOf<Tab>(
            Tab.Visible(MusicType.SONGS),
            Tab.Invisible(MusicType.ALBUMS),
            Tab.Visible(MusicType.ARTISTS),
            Tab.Invisible(MusicType.GENRES),
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Visible(MusicType.DOWNLOADS),
        )

        val newVisibleOrder = listOf(
            MusicType.DOWNLOADS,
            MusicType.SONGS,
            MusicType.PLAYLISTS,
            MusicType.ARTISTS,
        )

        val reordered = HomeSettingsImpl.reorderVisibleTabs(initial, newVisibleOrder)
        val expected = arrayOf<Tab>(
            Tab.Visible(MusicType.DOWNLOADS),
            Tab.Invisible(MusicType.ALBUMS),
            Tab.Visible(MusicType.SONGS),
            Tab.Invisible(MusicType.GENRES),
            Tab.Visible(MusicType.PLAYLISTS),
            Tab.Visible(MusicType.ARTISTS),
        )

        assertArrayEquals(expected, reordered)
        // Round-trips cleanly through Tab.toIntCode / Tab.fromIntCode
        assertArrayEquals(expected, Tab.fromIntCode(Tab.toIntCode(reordered)))
    }
}
