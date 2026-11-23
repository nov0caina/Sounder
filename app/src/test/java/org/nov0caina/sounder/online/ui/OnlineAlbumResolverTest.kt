package org.nov0caina.sounder.online.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.HomeFeedData
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.RecentArtist
import org.nov0caina.sounder.online.model.StreamInfo
import org.nov0caina.sounder.online.repository.OnlineMusicRepository

class OnlineAlbumResolverTest {

    private class StubOnlineMusicRepository(
        private val queries: Map<String, OnlineSearchResult> = emptyMap(),
        private val artists: Map<String, OnlineArtistDetail> = emptyMap(),
        private val albums: Map<String, OnlineAlbumDetail> = emptyMap(),
    ) : OnlineMusicRepository {
        override val searchResults: StateFlow<List<OnlineTrack>> = MutableStateFlow(emptyList())
        override val categorizedResults: StateFlow<OnlineSearchResult> =
            MutableStateFlow(OnlineSearchResult())
        override val recentArtists: Flow<List<RecentArtist>> = emptyFlow()
        override val isSearching: StateFlow<Boolean> = MutableStateFlow(false)

        override suspend fun search(query: String): List<OnlineTrack> = emptyList()
        override suspend fun searchCategorized(query: String): OnlineSearchResult = OnlineSearchResult()
        override suspend fun queryCategorized(query: String): OnlineSearchResult =
            queries[query] ?: OnlineSearchResult()
        override suspend fun recordRecentArtist(artist: RecentArtist) {}
        override suspend fun browseArtist(browseId: String): OnlineArtistDetail =
            artists[browseId] ?: error("Unknown artist $browseId")
        override suspend fun browseAlbum(browseId: String): OnlineAlbumDetail =
            albums[browseId] ?: error("Unknown album $browseId")
        override suspend fun getHomeFeed(forceRefresh: Boolean): HomeFeedData = HomeFeedData()
        override suspend fun getHomeFeed(category: String?, forceRefresh: Boolean): HomeFeedData =
            HomeFeedData()
        override suspend fun resolveStreamInfo(
            trackId: String,
            quality: AudioQuality,
            title: String?,
            artist: String?
        ): StreamInfo? = null
        override suspend fun resolveStreamInfo(trackId: String, title: String?, artist: String?): StreamInfo? =
            null
        override suspend fun resolveStreamUrl(
            trackId: String,
            quality: AudioQuality,
            title: String?,
            artist: String?
        ): String? = null
        override suspend fun resolveStreamUrl(trackId: String, title: String?, artist: String?): String? =
            null
        override fun getCachedTrack(trackId: String): OnlineTrack? = null
        override fun cacheTrack(track: OnlineTrack) {}
    }

    @Test
    fun resolveOnlineAlbumDetail_blankBrowseId_prioritizesExactArtistAndStudioAlbum() = runTest {
        val repo = StubOnlineMusicRepository(
            queries = mapOf(
                "Black Sabbath" to OnlineSearchResult(
                    artists = listOf(
                        OnlineArtistSummary("UC_tribute", "Black Sabbath Tribute", null),
                        OnlineArtistSummary("UC_official", "Black Sabbath", null),
                    )
                )
            ),
            artists = mapOf(
                "UC_official" to OnlineArtistDetail(
                    id = "UC_official",
                    name = "Black Sabbath",
                    avatarUrl = null,
                    description = null,
                    albums = listOf(
                        OnlineAlbumSummary("alb_deluxe", "Paranoid (Deluxe Edition)", "2020", null),
                        OnlineAlbumSummary("alb_exact", "Paranoid", "1970", null),
                    ),
                    singles = listOf(
                        OnlineAlbumSummary("sgl_exact", "Paranoid", "1970", null),
                    )
                )
            ),
            albums = mapOf(
                "alb_exact" to OnlineAlbumDetail("alb_exact", "Paranoid", "Black Sabbath", "1970", null)
            )
        )

        val detail = resolveOnlineAlbumDetail(repo, browseId = "", albumTitle = "Paranoid", artistName = "Black Sabbath")

        assertNotNull(detail)
        assertEquals("alb_exact", detail?.id)
    }

    @Test
    fun findBestAlbumMatch_prefersExactSingleBeforeSubstringAlbum() {
        val result = findBestAlbumMatch(
            albums = listOf(OnlineAlbumSummary("alb_sub", "Heaven And Hell Live", "2007", null)),
            singles = listOf(OnlineAlbumSummary("sgl_exact", "Heaven And Hell", "1980", null)),
            cleanAlbumTitle = "Heaven And Hell"
        )

        assertEquals("sgl_exact", result?.id)
    }

    @Test
    fun resolveOnlineAlbumDetail_searchFallback_prioritizesTrackWithMatchingAlbumAndAlbumId() = runTest {
        val videoTrack = OnlineTrack(
            id = "vid1",
            title = "Master of Puppets (Live)",
            artist = "Metallica",
            album = null,
            albumId = null,
            durationMs = 515_000L,
            artworkUrl = null
        )
        val studioTrack = OnlineTrack(
            id = "trk1",
            title = "Battery",
            artist = "Metallica",
            album = "Master of Puppets",
            albumId = "MPREb_master",
            durationMs = 312_000L,
            artworkUrl = null
        )
        val expectedAlbum = OnlineAlbumDetail(
            id = "MPREb_master",
            title = "Master of Puppets",
            artist = "Metallica",
            year = "1986",
            coverUrl = null,
            tracks = listOf(studioTrack)
        )
        val repo = StubOnlineMusicRepository(
            queries = mapOf(
                "Master of Puppets Metallica" to OnlineSearchResult(tracks = listOf(videoTrack, studioTrack))
            ),
            albums = mapOf("MPREb_master" to expectedAlbum)
        )

        val detail = resolveOnlineAlbumDetail(
            repo,
            browseId = "",
            albumTitle = "Master of Puppets",
            artistName = "Metallica"
        )

        assertEquals("MPREb_master", detail?.id)
    }
}
