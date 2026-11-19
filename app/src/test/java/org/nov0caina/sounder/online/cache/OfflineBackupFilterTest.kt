package org.nov0caina.sounder.online.cache

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nov0caina.sounder.online.model.OnlineTrack

class OfflineBackupFilterTest {

    private val sampleTracks = listOf(
        OnlineTrack(id = "1", title = "One More Time", artist = "Daft Punk", album = "Discovery", durationMs = 320_000, artworkUrl = null),
        OnlineTrack(id = "2", title = "Blinding Lights", artist = "The Weeknd", album = "After Hours", durationMs = 200_000, artworkUrl = null),
        OnlineTrack(id = "3", title = "Digital Love", artist = "Daft Punk", album = "Discovery", durationMs = 300_000, artworkUrl = null),
        OnlineTrack(id = "4", title = "Instant Crush", artist = "Daft Punk", album = "Random Access Memories", durationMs = 337_000, artworkUrl = null),
    )

    @Test
    fun `ALL mode returns every cached track unchanged`() {
        val filtered = OfflineBackupFilter.filterTracks(sampleTracks, OfflineBackupFilterState(OfflineBackupFilterMode.ALL))
        assertEquals(4, filtered.size)
    }

    @Test
    fun `ARTISTS mode extracts distinct sorted artists and filters by selection`() {
        val artists = OfflineBackupFilter.availableValues(sampleTracks, OfflineBackupFilterMode.ARTISTS)
        assertEquals(listOf("Daft Punk", "The Weeknd"), artists)

        val onlyWeeknd = OfflineBackupFilter.filterTracks(
            sampleTracks,
            OfflineBackupFilterState(OfflineBackupFilterMode.ARTISTS, "The Weeknd")
        )
        assertEquals(1, onlyWeeknd.size)
        assertEquals("Blinding Lights", onlyWeeknd.first().title)
    }

    @Test
    fun `ALBUMS mode extracts distinct sorted albums and filters by selection`() {
        val albums = OfflineBackupFilter.availableValues(sampleTracks, OfflineBackupFilterMode.ALBUMS)
        assertEquals(listOf("After Hours", "Discovery", "Random Access Memories"), albums)

        val discovery = OfflineBackupFilter.filterTracks(
            sampleTracks,
            OfflineBackupFilterState(OfflineBackupFilterMode.ALBUMS, "Discovery")
        )
        assertEquals(2, discovery.size)
        assertEquals(listOf("One More Time", "Digital Love"), discovery.map { it.title })
    }
}
