package org.nov0caina.sounder.online.repository

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
    suspend fun searchCategorized(query: String, isCategory: Boolean): OnlineSearchResult =
        searchCategorized(query)
    suspend fun searchCategorized(query: String, isCategory: Boolean, forceRefresh: Boolean): OnlineSearchResult =
        searchCategorized(query, isCategory)
    suspend fun queryCategorized(query: String): OnlineSearchResult
    suspend fun recordRecentArtist(artist: RecentArtist)
    suspend fun browseArtist(browseId: String): OnlineArtistDetail
    suspend fun browseArtist(browseId: String, recordRecent: Boolean): OnlineArtistDetail =
        browseArtist(browseId)
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
    private val searchCache = ConcurrentHashMap<String, OnlineSearchResult>()
    private val activeSearchSeq = AtomicLong(0L)

    override suspend fun search(query: String): List<OnlineTrack> {
        return searchCategorized(query).tracks
    }

    override suspend fun queryCategorized(query: String): OnlineSearchResult =
        queryCategorized(query, isCategory = false)

    private suspend fun queryCategorized(query: String, isCategory: Boolean): OnlineSearchResult {
        if (query.isBlank()) return OnlineSearchResult()
        return try {
            val result = client.searchCategorized(query, isCategory = isCategory)
            result.tracks.forEach { track ->
                trackCache[track.id] = track
            }
            if (result.tracks.isNotEmpty() || result.artists.isNotEmpty()) {
                val deviceLang = java.util.Locale.getDefault().language.lowercase()
                val cacheKey = "${isCategory}|${deviceLang}|${query.trim().lowercase()}"
                searchCache[cacheKey] = result
            }
            result
        } catch (e: Exception) {
            L.w(e, "Failed to perform online query for: %s", query)
            OnlineSearchResult()
        }
    }

    override suspend fun searchCategorized(query: String): OnlineSearchResult =
        searchCategorized(query, isCategory = false, forceRefresh = false)

    override suspend fun searchCategorized(query: String, isCategory: Boolean): OnlineSearchResult =
        searchCategorized(query, isCategory = isCategory, forceRefresh = false)

    override suspend fun searchCategorized(
        query: String,
        isCategory: Boolean,
        forceRefresh: Boolean
    ): OnlineSearchResult {
        val requestId = activeSearchSeq.incrementAndGet()
        if (query.isBlank()) {
            _isSearching.value = false
            _searchResults.value = emptyList()
            _categorizedResults.value = OnlineSearchResult()
            return OnlineSearchResult()
        }

        val deviceLang = java.util.Locale.getDefault().language.lowercase()
        val cacheKey = "${isCategory}|${deviceLang}|${query.trim().lowercase()}"
        if (!forceRefresh) {
            searchCache[cacheKey]?.let { cached ->
                _searchResults.value = cached.tracks
                _categorizedResults.value = cached
                _isSearching.value = false
                return cached
            }
        } else {
            searchCache.remove(cacheKey)
        }

        _isSearching.value = true
        if (!forceRefresh) {
            _searchResults.value = emptyList()
            _categorizedResults.value = OnlineSearchResult()
        }
        return try {
            var latestResult = OnlineSearchResult()
            client.searchCategorizedProgressive(query, isCategory = isCategory).collect { partial ->
                latestResult = partial
                partial.tracks.forEach { track ->
                    trackCache[track.id] = track
                }
                if (partial.tracks.isNotEmpty() || partial.artists.isNotEmpty()) {
                    searchCache[cacheKey] = partial
                }
                if (activeSearchSeq.get() == requestId) {
                    _searchResults.value = partial.tracks
                    _categorizedResults.value = partial
                    if (partial.tracks.isNotEmpty() || partial.artists.isNotEmpty()) {
                        _isSearching.value = false
                    }
                }
            }
            latestResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w(e, "Failed to perform online search for query: %s", query)
            OnlineSearchResult()
        } finally {
            if (activeSearchSeq.get() == requestId) {
                _isSearching.value = false
            }
        }
    }

    override suspend fun recordRecentArtist(artist: RecentArtist) {
        try {
            recentArtistsDao?.recordArtistVisit(RecentArtistEntity.fromSummary(artist))
        } catch (e: Exception) {
            L.w(e, "Failed to record recent artist: %s", artist.name)
        }
    }

    override suspend fun browseArtist(browseId: String): OnlineArtistDetail =
        browseArtist(browseId, recordRecent = true)

    override suspend fun browseArtist(browseId: String, recordRecent: Boolean): OnlineArtistDetail {
        val detail = client.browseArtist(browseId)
        if (recordRecent && detail.id.isNotBlank() && detail.name.isNotBlank()) {
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
