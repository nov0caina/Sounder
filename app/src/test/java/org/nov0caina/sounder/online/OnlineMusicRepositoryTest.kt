package org.nov0caina.sounder.online

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.db.RecentArtistEntity
import org.nov0caina.sounder.online.db.RecentArtistsDao
import org.nov0caina.sounder.online.innertube.InnerTubeClient
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
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

    @Test
    fun testSearchCategorizedUsesInMemoryCacheUntilForceRefresh() = runTest {
        var clientCalls = 0
        val client = object : InnerTubeClient() {
            override fun searchCategorizedProgressive(
                query: String,
                isCategory: Boolean
            ): Flow<OnlineSearchResult> = flow {
                clientCalls++
                emit(
                    OnlineSearchResult(
                        tracks = listOf(
                            OnlineTrack("t_$clientCalls", "Track $clientCalls", "Artist", "Album", 200_000L, null)
                        )
                    )
                )
            }
        }

        val repo = OnlineMusicRepositoryImpl(client = client)
        val first = repo.searchCategorized("Metal", isCategory = true)
        val cached = repo.searchCategorized("Metal", isCategory = true)
        assertEquals(1, clientCalls)
        assertEquals(first, cached)

        val refreshed = repo.searchCategorized("Metal", isCategory = true, forceRefresh = true)
        assertEquals(2, clientCalls)
        assertEquals("Track 2", refreshed.tracks.first().title)
    }

    @Test
    fun testProgressiveSearchEndsLoadingAfterPhase1AndUpdatesCacheInPhase2() = runTest {
        val phase1Delivered = CompletableDeferred<Unit>()
        val allowPhase2 = CompletableDeferred<Unit>()
        val phase1Result = OnlineSearchResult(
            artists = listOf(OnlineArtistSummary("UC_1", "Focus", "https://art/1.jpg", "172K monthly audience")),
            tracks = listOf(OnlineTrack("v1", "Track 1", "Focus", "Album", 180_000L, null))
        )
        val phase2Result = OnlineSearchResult(
            artists = phase1Result.artists + (2..55).map { i ->
                OnlineArtistSummary("UC_$i", "Artist $i", "https://art/$i.jpg", "${i}K monthly audience")
            },
            tracks = phase1Result.tracks + OnlineTrack("v2", "Track 2", "Artist 2", "Album 2", 200_000L, null)
        )

        val client = object : InnerTubeClient() {
            override fun searchCategorizedProgressive(
                query: String,
                isCategory: Boolean
            ): Flow<OnlineSearchResult> = flow {
                emit(phase1Result)
                phase1Delivered.complete(Unit)
                allowPhase2.await()
                emit(phase2Result)
            }
        }

        val repo = OnlineMusicRepositoryImpl(client = client)
        val job = launch {
            repo.searchCategorized("Focus", isCategory = true)
        }
        phase1Delivered.await()

        // After Phase 1, loading indicator must already be off while Phase 2 is pending
        assertFalse(repo.isSearching.value)
        assertEquals(1, repo.categorizedResults.value.artists.size)

        allowPhase2.complete(Unit)
        job.join()

        assertFalse(repo.isSearching.value)
        assertEquals(55, repo.categorizedResults.value.artists.size)
        assertEquals(2, repo.categorizedResults.value.tracks.size)
        val fromCache = repo.searchCategorized("Focus", isCategory = true)
        assertEquals(55, fromCache.artists.size)
    }
}
