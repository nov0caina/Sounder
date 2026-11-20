package org.nov0caina.sounder.online.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.HomeFeedData
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.RecentArtist
import org.nov0caina.sounder.online.model.StreamInfo
import org.nov0caina.sounder.online.repository.OnlineMusicRepository

class DiscoverViewModelTest {

    private class FakeOnlineMusicRepository : OnlineMusicRepository {
        val recordedCalls = mutableListOf<Triple<String, Boolean, Boolean>>()

        override val searchResults: StateFlow<List<OnlineTrack>> = MutableStateFlow(emptyList())
        override val categorizedResults: StateFlow<OnlineSearchResult> =
            MutableStateFlow(OnlineSearchResult())
        override val recentArtists: Flow<List<RecentArtist>> = emptyFlow()
        override val isSearching: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun search(query: String): List<OnlineTrack> = emptyList()
        override suspend fun searchCategorized(query: String): OnlineSearchResult =
            searchCategorized(query, isCategory = false, forceRefresh = false)

        override suspend fun searchCategorized(query: String, isCategory: Boolean): OnlineSearchResult =
            searchCategorized(query, isCategory = isCategory, forceRefresh = false)

        override suspend fun searchCategorized(
            query: String,
            isCategory: Boolean,
            forceRefresh: Boolean
        ): OnlineSearchResult {
            recordedCalls.add(Triple(query, isCategory, forceRefresh))
            return OnlineSearchResult()
        }

        override suspend fun queryCategorized(query: String): OnlineSearchResult = OnlineSearchResult()
        override suspend fun recordRecentArtist(artist: RecentArtist) {}
        override suspend fun browseArtist(browseId: String): OnlineArtistDetail =
            OnlineArtistDetail(browseId, "", null, null)

        override suspend fun browseAlbum(browseId: String): OnlineAlbumDetail =
            OnlineAlbumDetail(browseId, "", "", null, null)

        override suspend fun getHomeFeed(forceRefresh: Boolean): HomeFeedData = HomeFeedData()
        override suspend fun getHomeFeed(category: String?, forceRefresh: Boolean): HomeFeedData =
            HomeFeedData()

        override suspend fun resolveStreamInfo(
            trackId: String,
            quality: AudioQuality,
            title: String?,
            artist: String?
        ): StreamInfo? = null

        override suspend fun resolveStreamInfo(
            trackId: String,
            title: String?,
            artist: String?
        ): StreamInfo? = null

        override suspend fun resolveStreamUrl(
            trackId: String,
            quality: AudioQuality,
            title: String?,
            artist: String?
        ): String? = null

        override suspend fun resolveStreamUrl(
            trackId: String,
            title: String?,
            artist: String?
        ): String? = null

        override fun getCachedTrack(trackId: String): OnlineTrack? = null
        override fun cacheTrack(track: OnlineTrack) {}
    }

    @Test
    fun categorySelectionSearchOverrideRetryAndClear_transitionCleanly() = runTest {
        val repo = FakeOnlineMusicRepository()
        val coordinator = DiscoverQueryCoordinator(repo)

        assertEquals(DiscoverQueryState.Idle, coordinator.activeQueryState.value)

        // 1. Category / mood selection stores display title & apiQuery without search text mutation
        coordinator.selectCategory(this, title = "Metal", apiQuery = "Metal artists")
        advanceUntilIdle()
        assertEquals(
            DiscoverQueryState.Category(title = "Metal", apiQuery = "Metal artists"),
            coordinator.activeQueryState.value
        )
        assertEquals(listOf(Triple("Metal artists", true, false)), repo.recordedCalls)

        // 2. Refresh re-runs active category query with forceRefresh = true (pull-to-refresh)
        coordinator.retryActiveQuery(this, forceRefresh = true)
        advanceUntilIdle()
        assertEquals(
            listOf(
                Triple("Metal artists", true, false),
                Triple("Metal artists", true, true)
            ),
            repo.recordedCalls
        )

        // 3. Submitting a manual text search exits Category state to Search state
        coordinator.search(this, "  Metallica  ")
        advanceUntilIdle()
        assertEquals(DiscoverQueryState.Search("Metallica"), coordinator.activeQueryState.value)
        assertEquals(Triple("Metallica", false, false), repo.recordedCalls.last())

        // 4. Clear / Back returns to Idle and clears repository results
        coordinator.clearSearch(this)
        advanceUntilIdle()
        assertEquals(DiscoverQueryState.Idle, coordinator.activeQueryState.value)
        assertEquals(Triple("", false, false), repo.recordedCalls.last())
    }
}
