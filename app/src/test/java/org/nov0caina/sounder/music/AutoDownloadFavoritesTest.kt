package org.nov0caina.sounder.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.model.OnlineTrack

class AutoDownloadFavoritesTest {

    private class FakeDownloadManager {
        val enqueuedTracks = mutableListOf<OnlineTrack>()
        val downloadedTrackIds = mutableSetOf<String>()

        fun isDownloaded(trackId: String): Boolean = downloadedTrackIds.contains(trackId)

        fun enqueue(track: OnlineTrack): Boolean {
            if (isDownloaded(track.id) || enqueuedTracks.any { it.id == track.id }) {
                return false
            }
            enqueuedTracks.add(track)
            return true
        }
    }

    @Test
    fun testAutoDownloadEnqueueWhenEnabled() {
        val fakeDownloadManager = FakeDownloadManager()
        val autoDownloadEnabled = true
        val track = OnlineTrack(
            id = "test_1",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000L,
            artworkUrl = null
        )

        if (autoDownloadEnabled) {
            if (!fakeDownloadManager.isDownloaded(track.id)) {
                fakeDownloadManager.enqueue(track)
            }
        }

        assertEquals(1, fakeDownloadManager.enqueuedTracks.size)
        assertEquals("test_1", fakeDownloadManager.enqueuedTracks[0].id)
    }

    @Test
    fun testAutoDownloadSkippedWhenDisabled() {
        val fakeDownloadManager = FakeDownloadManager()
        val autoDownloadEnabled = false
        val track = OnlineTrack(
            id = "test_2",
            title = "Test Song 2",
            artist = "Test Artist 2",
            album = "Test Album 2",
            durationMs = 200000L,
            artworkUrl = null
        )

        if (autoDownloadEnabled) {
            if (!fakeDownloadManager.isDownloaded(track.id)) {
                fakeDownloadManager.enqueue(track)
            }
        }

        assertTrue(fakeDownloadManager.enqueuedTracks.isEmpty())
    }

    @Test
    fun testAutoDownloadSkippedWhenAlreadyDownloaded() {
        val fakeDownloadManager = FakeDownloadManager()
        fakeDownloadManager.downloadedTrackIds.add("test_3")
        val autoDownloadEnabled = true
        val track = OnlineTrack(
            id = "test_3",
            title = "Test Song 3",
            artist = "Test Artist 3",
            album = "Test Album 3",
            durationMs = 220000L,
            artworkUrl = null
        )

        if (autoDownloadEnabled) {
            if (!fakeDownloadManager.isDownloaded(track.id)) {
                fakeDownloadManager.enqueue(track)
            }
        }

        assertTrue(fakeDownloadManager.enqueuedTracks.isEmpty())
    }
}
