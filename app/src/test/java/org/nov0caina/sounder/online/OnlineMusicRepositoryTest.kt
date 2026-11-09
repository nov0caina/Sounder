package org.nov0caina.sounder.online

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.db.RecentArtistEntity
import org.nov0caina.sounder.online.db.RecentArtistsDao
import org.nov0caina.sounder.online.innertube.InnerTubeClient
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.RecentArtist
import org.nov0caina.sounder.online.repository.OnlineMusicRepositoryImpl

class OnlineMusicRepositoryTest {

    private class FakeRecentArtistsDao : RecentArtistsDao {
        private val list = mutableListOf<RecentArtistEntity>()
        private val flow = MutableStateFlow<List<RecentArtistEntity>>(emptyList())

        override fun getRecentArtistsFlow(): Flow<List<RecentArtistEntity>> = flow

        override suspend fun getRecentArtists(): List<RecentArtistEntity> = list

        override suspend fun insertOrUpdate(artist: RecentArtistEntity) {
            list.removeAll { it.id == artist.id }
            list.add(0, artist)
            pruneOldArtists()
        }

        override suspend fun pruneOldArtists() {
            list.sortByDescending { it.lastViewedAt }
            while (list.size > 20) {
                list.removeAt(list.size - 1)
            }
            flow.value = list.toList()
        }

        override suspend fun recordArtistVisit(artist: RecentArtistEntity) {
            insertOrUpdate(artist)
        }

        override suspend fun clearAll() {
            list.clear()
            flow.value = emptyList()
        }
    }

    @Test
    fun testCacheAndRetrieveTrack() = runTest {
        val repo = OnlineMusicRepositoryImpl(InnerTubeClient())
        val track = OnlineTrack(
            id = "test_1",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000,
            artworkUrl = "https://example.com/art.jpg"
        )

        repo.cacheTrack(track)
        val cached = repo.getCachedTrack("test_1")

        assertNotNull(cached)
        assertEquals("Test Song", cached?.title)
        assertEquals("3:00", cached?.formattedDuration())
    }

    @Test
    fun testRecentArtistsDaoCapAndSorting() = runTest {
        val fakeDao = FakeRecentArtistsDao()
        val repo = OnlineMusicRepositoryImpl(
            client = InnerTubeClient(),
            recentArtistsDao = fakeDao
        )

        // Insert 25 artists
        for (i in 1..25) {
            repo.recordRecentArtist(
                RecentArtist(
                    id = "artist_$i",
                    name = "Artist $i",
                    avatarUrl = "https://example.com/avatar_$i.jpg",
                    subtitle = "Genre $i"
                )
            )
        }

        val recents = repo.recentArtists.first()
        // Must be capped at 20
        assertEquals(20, recents.size)
        // Most recent must be artist_25
        assertEquals("artist_25", recents.first().id)
        assertEquals("Artist 25", recents.first().name)
    }

    @Test
    fun testBrowseArtistRecordsRecentArtist() = runTest {
        val fakeDao = FakeRecentArtistsDao()
        val client = object : InnerTubeClient() {
            override suspend fun browseArtist(browseId: String): OnlineArtistDetail {
                return OnlineArtistDetail(
                    id = browseId,
                    name = "Rammstein",
                    avatarUrl = "https://example.com/rammstein.jpg",
                    description = "German Band",
                    topSongs = listOf(
                        OnlineTrack("s1", "Du Hast", "Rammstein", "Sehnsucht", 234000, null)
                    )
                )
            }
        }

        val repo = OnlineMusicRepositoryImpl(client = client, recentArtistsDao = fakeDao)
        val detail = repo.browseArtist("UC_rammstein")

        assertEquals("Rammstein", detail.name)
        val recents = repo.recentArtists.first()
        assertEquals(1, recents.size)
        assertEquals("UC_rammstein", recents[0].id)
        assertEquals("Rammstein", recents[0].name)
    }
}
