package org.nov0caina.sounder.online.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.db.RecentArtistEntity
import org.nov0caina.sounder.online.db.RecentArtistsDao
import org.nov0caina.sounder.online.innertube.InnerTubeClient
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.RecentArtist
import org.nov0caina.sounder.online.model.StreamInfo
import timber.log.Timber as L

/**
 * Repository interface for discovering online music and resolving audio stream endpoints.
 */
interface OnlineMusicRepository {
    val searchResults: StateFlow<List<OnlineTrack>>
    val categorizedResults: StateFlow<OnlineSearchResult>
    val recentArtists: Flow<List<RecentArtist>>
    val isSearching: StateFlow<Boolean>

    suspend fun search(query: String): List<OnlineTrack>
    suspend fun searchCategorized(query: String): OnlineSearchResult
    suspend fun queryCategorized(query: String): OnlineSearchResult
    suspend fun recordRecentArtist(artist: RecentArtist)
    suspend fun browseArtist(browseId: String): OnlineArtistDetail
    suspend fun browseAlbum(browseId: String): OnlineAlbumDetail
    suspend fun getHomeFeed(forceRefresh: Boolean = false): org.nov0caina.sounder.online.model.HomeFeedData
    suspend fun getHomeFeed(category: String?, forceRefresh: Boolean = false): org.nov0caina.sounder.online.model.HomeFeedData
    suspend fun resolveStreamInfo(trackId: String, quality: AudioQuality, title: String? = null, artist: String? = null): StreamInfo?
    suspend fun resolveStreamInfo(trackId: String, title: String? = null, artist: String? = null): StreamInfo?
    suspend fun resolveStreamUrl(trackId: String, quality: AudioQuality, title: String? = null, artist: String? = null): String?
    suspend fun resolveStreamUrl(trackId: String, title: String? = null, artist: String? = null): String?
    fun getCachedTrack(trackId: String): OnlineTrack?
    fun cacheTrack(track: OnlineTrack)
}

@Singleton
class OnlineMusicRepositoryImpl @Inject constructor(
    private val client: InnerTubeClient,
    private val recentArtistsDao: RecentArtistsDao? = null,
    private val settings: OnlineSettings? = null
) : OnlineMusicRepository {

    private val _searchResults = MutableStateFlow<List<OnlineTrack>>(emptyList())
    override val searchResults: StateFlow<List<OnlineTrack>> = _searchResults.asStateFlow()

    private val _categorizedResults = MutableStateFlow(OnlineSearchResult())
    override val categorizedResults: StateFlow<OnlineSearchResult> = _categorizedResults.asStateFlow()

    override val recentArtists: Flow<List<RecentArtist>> =
        recentArtistsDao?.getRecentArtistsFlow()?.map { entities ->
            entities.map { it.toSummary() }
        } ?: emptyFlow()

    private val _isSearching = MutableStateFlow(false)
    override val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val trackCache = ConcurrentHashMap<String, OnlineTrack>()

    override suspend fun search(query: String): List<OnlineTrack> {
        return searchCategorized(query).tracks
    }

    override suspend fun queryCategorized(query: String): OnlineSearchResult {
        if (query.isBlank()) return OnlineSearchResult()
        return try {
            val result = client.searchCategorized(query)
            result.tracks.forEach { track ->
                trackCache[track.id] = track
            }
            result
        } catch (e: Exception) {
            L.w(e, "Failed to perform online query for: %s", query)
            OnlineSearchResult()
        }
    }

    override suspend fun searchCategorized(query: String): OnlineSearchResult {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _categorizedResults.value = OnlineSearchResult()
            return OnlineSearchResult()
        }

        _isSearching.value = true
        _searchResults.value = emptyList()
        _categorizedResults.value = OnlineSearchResult()
        return try {
            val result = queryCategorized(query)
            _searchResults.value = result.tracks
            _categorizedResults.value = result
            result
        } catch (e: Exception) {
            L.w(e, "Failed to perform online search for query: %s", query)
            OnlineSearchResult()
        } finally {
            _isSearching.value = false
        }
    }

    override suspend fun recordRecentArtist(artist: RecentArtist) {
        try {
            recentArtistsDao?.recordArtistVisit(RecentArtistEntity.fromSummary(artist))
        } catch (e: Exception) {
            L.w(e, "Failed to record recent artist: %s", artist.name)
        }
    }

    override suspend fun browseArtist(browseId: String): OnlineArtistDetail {
        val detail = client.browseArtist(browseId)
        if (detail.id.isNotBlank() && detail.name.isNotBlank()) {
            recordRecentArtist(
                RecentArtist(
                    id = detail.id,
                    name = detail.name,
                    avatarUrl = detail.avatarUrl,
                    subtitle = detail.description?.take(60)
                )
            )
        }
        detail.topSongs.forEach { trackCache[it.id] = it }
        return detail
    }

    override suspend fun browseAlbum(browseId: String): OnlineAlbumDetail {
        val detail = client.browseAlbum(browseId)
        detail.tracks.forEach { trackCache[it.id] = it }
        return detail
    }

    private var cachedHomeFeed: org.nov0caina.sounder.online.model.HomeFeedData? = null
    private val cachedCategoryFeeds = ConcurrentHashMap<String, org.nov0caina.sounder.online.model.HomeFeedData>()

    override suspend fun getHomeFeed(forceRefresh: Boolean): org.nov0caina.sounder.online.model.HomeFeedData =
        getHomeFeed(category = null, forceRefresh = forceRefresh)

    override suspend fun getHomeFeed(category: String?, forceRefresh: Boolean): org.nov0caina.sounder.online.model.HomeFeedData {
        val normCat = category?.trim()?.lowercase()
        if (normCat.isNullOrBlank() || normCat == "all" || normCat == "todo") {
            if (forceRefresh) {
                cachedHomeFeed = null
            } else {
                cachedHomeFeed?.let { return it }
            }
            val feed = client.browseHomeFeed()
            feed.trendingTracks.forEach { trackCache[it.id] = it }
            feed.quickPicks.forEach { trackCache[it.id] = it }
            if (feed.trendingTracks.isNotEmpty() || feed.trendingAlbums.isNotEmpty() || feed.popularArtists.isNotEmpty()) {
                cachedHomeFeed = feed
            }
            return feed
        }

        if (!forceRefresh) {
            cachedCategoryFeeds[normCat]?.let { return it }
        }

        val feed = client.browseHomeFeed(category = normCat)
        feed.trendingTracks.forEach { trackCache[it.id] = it }
        feed.quickPicks.forEach { trackCache[it.id] = it }
        if (feed.trendingTracks.isNotEmpty()) {
            cachedCategoryFeeds[normCat] = feed
        }
        return feed
    }

    override suspend fun resolveStreamInfo(
        trackId: String,
        quality: AudioQuality,
        title: String?,
        artist: String?
    ): StreamInfo? {
        val info = client.resolveStreamInfo(trackId, quality, title, artist) ?: return null
        val cached = trackCache[trackId]
        if (cached != null) {
            val updatedDuration = if (cached.durationMs > 0) cached.durationMs else info.durationMs
            trackCache[trackId] = cached.copy(streamUrl = info.url, durationMs = updatedDuration)
        }
        return info
    }

    override suspend fun resolveStreamInfo(trackId: String, title: String?, artist: String?): StreamInfo? {
        val quality = settings?.audioQuality ?: AudioQuality.HIGH_QUALITY
        return resolveStreamInfo(trackId, quality, title, artist)
    }

    override suspend fun resolveStreamUrl(
        trackId: String,
        quality: AudioQuality,
        title: String?,
        artist: String?
    ): String? {
        val cached = trackCache[trackId]
        if (!cached?.streamUrl.isNullOrBlank()) {
            return cached.streamUrl
        }

        return resolveStreamInfo(trackId, quality, title, artist)?.url
    }

    override suspend fun resolveStreamUrl(trackId: String, title: String?, artist: String?): String? {
        val quality = settings?.audioQuality ?: AudioQuality.HIGH_QUALITY
        return resolveStreamUrl(trackId, quality, title, artist)
    }

    override fun getCachedTrack(trackId: String): OnlineTrack? = trackCache[trackId]

    override fun cacheTrack(track: OnlineTrack) {
        trackCache[track.id] = track
    }
}
