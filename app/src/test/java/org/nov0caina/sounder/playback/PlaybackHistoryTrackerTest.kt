package org.nov0caina.sounder.playback

import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.StorageDestination
import org.nov0caina.sounder.online.db.ArtistPlayCount
import org.nov0caina.sounder.online.db.PlaybackRecord
import org.nov0caina.sounder.online.db.PlaybackRecordDao
import org.nov0caina.sounder.online.model.AudioQuality

class PlaybackHistoryTrackerTest {

    private class FakePlaybackRecordDao : PlaybackRecordDao {
        private var nextId = 1L
        val records = MutableStateFlow<List<PlaybackRecord>>(emptyList())

        override suspend fun insert(record: PlaybackRecord): Long {
            val assigned = record.copy(id = nextId++)
            records.value = listOf(assigned) + records.value
            return assigned.id
        }

        override suspend fun updatePlayedMs(id: Long, playedMs: Long) {
            records.value = records.value.map { if (it.id == id) it.copy(playedMs = playedMs) else it }
        }

        override suspend fun getRecentRecords(limit: Int): List<PlaybackRecord> =
            records.value.take(limit)

        override fun observeRecentRecords(limit: Int): Flow<List<PlaybackRecord>> =
            records.map { it.take(limit) }

        override suspend fun getTopArtistsSince(sinceTimestampMs: Long, limit: Int): List<ArtistPlayCount> =
            records.value
                .filter { it.timestamp >= sinceTimestampMs && it.artist.isNotBlank() }
                .groupBy { it.artist }
                .map { (artist, list) -> ArtistPlayCount(artist, list.size) }
                .sortedByDescending { it.playCount }
                .take(limit)

        override fun observeTotalPlayedMsSince(sinceTimestampMs: Long): Flow<Long> =
            records.map { list -> list.filter { it.timestamp >= sinceTimestampMs }.sumOf { it.playedMs } }

        override suspend fun getTotalPlayedMsSince(sinceTimestampMs: Long): Long =
            records.value.filter { it.timestamp >= sinceTimestampMs }.sumOf { it.playedMs }

        override suspend fun clearAll() {
            records.value = emptyList()
        }
    }

    private class FakeOnlineSettings(var insightsEnabled: Boolean = true) : OnlineSettings {
        override val audioQuality: AudioQuality = AudioQuality.HIGH_QUALITY
        override val wifiOnlyDownloads: Boolean = false
        override val autoDownloadFavorites: Boolean = false
        override val preferStudioAudio: Boolean = true
        override val showChristianGenre: Boolean = true
        override val showKpopGenre: Boolean = true
        override val enableLocalListeningInsights: Boolean
            get() = insightsEnabled
        override val downloadStorage: StorageDestination = StorageDestination.INTERNAL
        override fun setDownloadStorage(destination: StorageDestination) {}
        override val streamingCacheSizeMb: Int = 300
        override fun setStreamingCacheSizeMb(sizeMb: Int) {}
        override fun registerListener(listener: OnlineSettings.Listener) {}
        override fun unregisterListener(listener: OnlineSettings.Listener) {}
    }

    @Test
    fun isQualifyingPlayback_enforcesAntiSkipThresholds() {
        // Quick skips under 30s on standard tracks are ignored
        assertFalse(PlaybackHistoryTracker.isQualifyingPlayback(0L, 180_000L))
        assertFalse(PlaybackHistoryTracker.isQualifyingPlayback(29_999L, 180_000L))
        assertTrue(PlaybackHistoryTracker.isQualifyingPlayback(30_000L, 180_000L))

        // Tracks shorter than 30s qualify at >= 70% completion
        assertFalse(PlaybackHistoryTracker.isQualifyingPlayback(13_999L, 20_000L))
        assertTrue(PlaybackHistoryTracker.isQualifyingPlayback(14_000L, 20_000L))
    }

    @Test
    fun timeOfDayBuckets_andMonthStart_mapCorrectly() {
        assertEquals(PlaybackRecord.TIME_MORNING, PlaybackRecord.timeOfDayForHour(8))
        assertEquals(PlaybackRecord.TIME_AFTERNOON, PlaybackRecord.timeOfDayForHour(15))
        assertEquals(PlaybackRecord.TIME_EVENING, PlaybackRecord.timeOfDayForHour(20))
        assertEquals(PlaybackRecord.TIME_NIGHT, PlaybackRecord.timeOfDayForHour(2))

        val midMonth = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 21, 14, 30, 0)
            set(Calendar.MILLISECOND, 123)
        }.timeInMillis
        val monthStart = PlaybackRecord.currentMonthStartMs(midMonth)
        val cal = Calendar.getInstance().apply { timeInMillis = monthStart }
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun tracker_recordsAfterThreshold_updatesFullDuration_andRespectsOptOut() = runTest {
        val dao = FakePlaybackRecordDao()
        val settings = FakeOnlineSettings(insightsEnabled = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tracker = PlaybackHistoryTracker(null, dao, settings, dispatcher)

        val trackA = PlaybackHistoryTracker.TrackSnapshot(
            sessionKey = "trkA",
            trackId = "vidA",
            title = "Morning Anthem",
            artist = "Artist One",
            album = "Album One",
            genre = "Rock",
            artworkUrl = null,
            durationMs = 200_000L,
            sourceType = PlaybackRecord.SOURCE_STREAM,
        )
        val trackB = trackA.copy(sessionKey = "trkB", trackId = "vidB", title = "Skipped Song")

        // Start track A at t = 1_000, advance 15s (below 30s) -> skip to track B -> must NOT record track A
        tracker.onTrackStateChanged(trackA, isPlaying = true, nowMs = 1_000L)
        tracker.onPositionTick(nowMs = 16_000L)
        tracker.onTrackStateChanged(trackB, isPlaying = true, nowMs = 16_000L)
        advanceUntilIdle()
        assertEquals(0, dao.records.value.size)

        // Advance track B past 30s -> records, then listen to 50s total before switching -> updates playedMs
        tracker.onPositionTick(nowMs = 47_000L)
        advanceUntilIdle()
        assertEquals(1, dao.records.value.size)
        assertEquals(31_000L, dao.records.value.first().playedMs)

        tracker.onTrackStateChanged(null, isPlaying = false, nowMs = 66_000L)
        advanceUntilIdle()
        assertEquals(50_000L, dao.records.value.first().playedMs)

        // When user disables local insights, new long playback is ignored
        settings.insightsEnabled = false
        tracker.onTrackStateChanged(trackA, isPlaying = true, nowMs = 100_000L)
        tracker.onPositionTick(nowMs = 145_000L)
        advanceUntilIdle()
        assertEquals(1, dao.records.value.size)

        // Clearing history deletes all stored records
        dao.clearAll()
        assertEquals(0, dao.getRecentRecords().size)
        assertEquals(0L, dao.getTotalPlayedMsSince(0L))
    }
}
