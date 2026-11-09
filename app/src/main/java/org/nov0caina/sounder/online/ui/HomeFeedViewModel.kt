package org.nov0caina.sounder.online.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.HomeFeedData
import org.nov0caina.sounder.online.model.HomeMoodItem
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.QuickAccessItem
import org.nov0caina.sounder.online.repository.OnlineMusicRepository
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.util.NetworkUtil
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@HiltViewModel
class HomeFeedViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OnlineMusicRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _feedData = MutableStateFlow(HomeFeedData())
    val feedData: StateFlow<HomeFeedData> = _feedData.asStateFlow()

    private val _quickAccessItems = MutableStateFlow<List<QuickAccessItem>>(emptyList())
    val quickAccessItems: StateFlow<List<QuickAccessItem>> = _quickAccessItems.asStateFlow()

    val availableMoods: List<HomeMoodItem> = listOf(
        HomeMoodItem("all", org.nov0caina.sounder.R.string.home_mood_all, "all"),
        HomeMoodItem("workout", org.nov0caina.sounder.R.string.home_mood_workout, "workout"),
        HomeMoodItem("focus", org.nov0caina.sounder.R.string.home_mood_focus, "focus"),
        HomeMoodItem("party", org.nov0caina.sounder.R.string.home_mood_party, "party"),
        HomeMoodItem("chill", org.nov0caina.sounder.R.string.home_mood_chill, "chill"),
        HomeMoodItem("latin", org.nov0caina.sounder.R.string.home_mood_latin, "latin"),
        HomeMoodItem("rock", org.nov0caina.sounder.R.string.home_mood_rock, "rock")
    )

    private val _selectedMood = MutableStateFlow("all")
    val selectedMood: StateFlow<String> = _selectedMood.asStateFlow()

    private val _isTopChartsExpanded = MutableStateFlow(false)
    val isTopChartsExpanded: StateFlow<Boolean> = _isTopChartsExpanded.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val activeDownloadsCount: StateFlow<Int> = downloadManager.activeCount
    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    init {
        loadFeed()
    }

    fun selectMood(moodId: String) {
        if (_selectedMood.value == moodId) return
        _selectedMood.value = moodId
        val moodItem = availableMoods.find { it.id == moodId }
        val categoryQuery = if (moodId == "all") null else moodItem?.query ?: moodId
        loadFeed(force = false, category = categoryQuery)
    }

    private var loadFeedJob: Job? = null
    private var currentLoadedCategory: String? = null

    fun toggleTopChartsExpanded() {
        _isTopChartsExpanded.value = !_isTopChartsExpanded.value
    }

    fun loadFeed(force: Boolean = false, category: String? = null) {
        val hasExistingData = _feedData.value.trendingTracks.isNotEmpty() ||
            _feedData.value.trendingAlbums.isNotEmpty() ||
            _feedData.value.popularArtists.isNotEmpty()

        if (!force && currentLoadedCategory == category && hasExistingData) {
            _isLoading.value = false
            _isOffline.value = false
            return
        }

        // Fast offline check: if device has no connection and no cached data, avoid 30s timeouts
        if (!NetworkUtil.isConnected(context)) {
            if (!hasExistingData) {
                _isOffline.value = true
                _isLoading.value = false
                _error.value = null
                return
            }
        }

        loadFeedJob?.cancel()
        loadFeedJob = viewModelScope.launch {
            _isLoading.value = true
            _isOffline.value = false
            _error.value = null
            try {
                val feed = repository.getHomeFeed(category = category, forceRefresh = force)
                currentLoadedCategory = category
                _feedData.value = feed
                rebuildQuickAccess(feed)
                if (feed.trendingTracks.isEmpty() && feed.trendingAlbums.isEmpty() && feed.popularArtists.isEmpty()) {
                    if (!NetworkUtil.isConnected(context)) {
                        _isOffline.value = true
                    } else {
                        _error.value = "No se pudieron cargar las tendencias"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w(e, "Error loading home feed")
                if (!NetworkUtil.isConnected(context)) {
                    _isOffline.value = true
                } else {
                    _error.value = e.message ?: "Error al cargar tendencias"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun rebuildQuickAccess(feed: HomeFeedData) {
        val list = mutableListOf<QuickAccessItem>()

        // 1. Favorites Card
        list.add(
            QuickAccessItem(
                id = "quick_favorites",
                title = context.getString(org.nov0caina.sounder.R.string.home_quick_favorites),
                subtitle = context.getString(org.nov0caina.sounder.R.string.lbl_favorites_playlist),
                iconRes = org.nov0caina.sounder.R.drawable.ic_favorite_24,
                isFavorites = true
            )
        )

        // 2. Popular Artist or Top Item
        if (feed.popularArtists.isNotEmpty()) {
            val artist = feed.popularArtists[0]
            list.add(
                QuickAccessItem(
                    id = "artist_${artist.id}",
                    title = artist.name,
                    subtitle = artist.subtitle ?: context.getString(org.nov0caina.sounder.R.string.lbl_artist),
                    imageUrl = artist.avatarUrl,
                    artist = artist
                )
            )
        }

        // 3..6. Up to 4 Quick Picks / trending tracks
        val candidates = if (feed.quickPicks.isNotEmpty()) feed.quickPicks else feed.trendingTracks
        for (track in candidates) {
            if (list.size >= 6) break
            list.add(
                QuickAccessItem(
                    id = "track_${track.id}",
                    title = track.title,
                    subtitle = track.artist,
                    imageUrl = track.artworkUrl,
                    track = track
                )
            )
        }

        _quickAccessItems.value = list
    }

    private fun toSong(track: OnlineTrack, streamUrl: String? = null): Song {
        val localFile = downloadManager.getDownloadedFile(track)
        if (localFile != null && localFile.exists()) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
        }
        val localSong = downloadManager.findSongInLibrary(track)
        if (localSong != null) {
            return localSong
        }
        val targetUri = when {
            !streamUrl.isNullOrBlank() -> Uri.parse(streamUrl)
            !track.streamUrl.isNullOrBlank() -> Uri.parse(track.streamUrl)
            else -> Uri.parse("https://music.youtube.com/watch?v=${track.id}")
        }
        val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
        return OnlineSong(track.copy(durationMs = resolvedDuration), targetUri)
    }

    private suspend fun resolveToPlayableSong(track: OnlineTrack): Song {
        val localFile = downloadManager.getDownloadedFile(track)
        if (localFile != null && localFile.exists()) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
        }
        val localSong = downloadManager.findSongInLibrary(track)
        if (localSong != null) {
            return localSong
        }
        val streamInfo = repository.resolveStreamInfo(track.id, title = track.title, artist = track.artist)
        if (streamInfo != null && streamInfo.url.isNotBlank()) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else streamInfo.durationMs
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse(streamInfo.url))
        }
        return toSong(track)
    }

    private suspend fun ensureAtLeast20Tracks(
        baseTracks: List<OnlineTrack>,
        fallbackCategory: String? = null
    ): List<OnlineTrack> {
        if (baseTracks.size >= 20) return baseTracks
        val result = baseTracks.toMutableList()
        val seenIds = result.map { it.id }.toMutableSet()

        // 1. First complement with quickPicks and trendingTracks from feedData
        val feed = _feedData.value
        for (t in feed.quickPicks + feed.trendingTracks) {
            if (seenIds.add(t.id)) {
                result.add(t)
                if (result.size >= 25) return result
            }
        }

        // 2. If still < 20, fetch from search
        if (result.size < 20) {
            try {
                val query = when {
                    !fallbackCategory.isNullOrBlank() -> "$fallbackCategory top hits"
                    else -> "Top 50 musica mundial"
                }
                val searchResult = repository.queryCategorized(query)
                for (t in searchResult.tracks) {
                    if (seenIds.add(t.id)) {
                        result.add(t)
                        if (result.size >= 25) break
                    }
                }
            } catch (e: Exception) {
                L.w(e, "Error fetching fallback tracks to reach 20")
            }
        }

        return result
    }

    fun playTrackWithQueue(
        track: OnlineTrack,
        sectionTracks: List<OnlineTrack>,
        playbackModel: PlaybackViewModel
    ) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            try {
                val targetSong = resolveToPlayableSong(track)
                val enrichedTracks = ensureAtLeast20Tracks(sectionTracks, currentLoadedCategory)

                val queue = enrichedTracks.map { t ->
                    if (t.id == track.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) {
                    queue
                } else {
                    listOf(targetSong) + queue
                }

                playbackModel.play(targetSong, finalQueue)

                // Background prefetch for remaining tracks so ExoPlayer transitions seamlessly
                launch(Dispatchers.IO) {
                    enrichedTracks.filter { it.id != track.id }.take(20).forEach { t ->
                        try {
                            if (downloadManager.getDownloadedFile(t)?.exists() != true &&
                                downloadManager.findSongInLibrary(t) == null
                            ) {
                                repository.resolveStreamInfo(t.id, title = t.title, artist = t.artist)
                            }
                        } catch (e: Exception) {
                            L.w(e, "Prefetch failed for track %s", t.title)
                        }
                    }
                }
            } catch (e: Exception) {
                L.e(e, "Error playing track %s with queue", track.title)
            } finally {
                _loadingTrackId.value = null
            }
        }
    }

    fun playTrendingTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val tracks = _feedData.value.trendingTracks
        playTrackWithQueue(track, tracks.ifEmpty { listOf(track) }, playbackModel)
    }

    fun playQuickPickTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val tracks = _feedData.value.quickPicks
        playTrackWithQueue(track, tracks.ifEmpty { listOf(track) }, playbackModel)
    }

    fun playQuickAccessTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val recommendations = _feedData.value.quickPicks.ifEmpty { _feedData.value.trendingTracks }
        playTrackWithQueue(track, recommendations.ifEmpty { listOf(track) }, playbackModel)
    }

    fun playTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val feed = _feedData.value
        val sectionTracks = when {
            feed.quickPicks.any { it.id == track.id } -> feed.quickPicks
            feed.trendingTracks.any { it.id == track.id } -> feed.trendingTracks
            else -> feed.quickPicks + feed.trendingTracks
        }
        playTrackWithQueue(track, sectionTracks.ifEmpty { listOf(track) }, playbackModel)
    }

    fun playArtist(artist: OnlineArtistSummary, playbackModel: PlaybackViewModel) {
        viewModelScope.launch {
            try {
                val resolvedId = if (artist.id.isBlank() && artist.name.isNotBlank()) {
                    val results = repository.queryCategorized(artist.name)
                    results.artists.firstOrNull()?.id ?: ""
                } else {
                    artist.id
                }
                if (resolvedId.isNotBlank()) {
                    val detail = repository.browseArtist(resolvedId)
                    var artistTracks = detail.topSongs
                    if (artistTracks.size < 20) {
                        try {
                            val searchResult = repository.queryCategorized("${detail.name} songs")
                            val combined = artistTracks.toMutableList()
                            for (t in searchResult.tracks) {
                                if (combined.none { it.id == t.id }) {
                                    combined.add(t)
                                    if (combined.size >= 25) break
                                }
                            }
                            artistTracks = combined
                        } catch (e: Exception) {
                            L.w(e, "Error fetching artist tracks")
                        }
                    }
                    val firstTrack = artistTracks.firstOrNull() ?: return@launch
                    playTrackWithQueue(firstTrack, artistTracks, playbackModel)
                }
            } catch (e: Exception) {
                L.e(e, "Error playing artist %s", artist.name)
            }
        }
    }

    fun enqueueTrack(
        track: OnlineTrack,
        playbackModel: PlaybackViewModel,
        onEnqueued: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                val localFile = downloadManager.getDownloadedFile(track)
                if (localFile != null && localFile.exists()) {
                    val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
                    val onlineSong = OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
                    playbackModel.addToQueue(onlineSong)
                    onEnqueued?.invoke()
                    return@launch
                }
                val localSong = downloadManager.findSongInLibrary(track)
                if (localSong != null) {
                    playbackModel.addToQueue(localSong)
                    onEnqueued?.invoke()
                    return@launch
                }
                val streamInfo = repository.resolveStreamInfo(track.id, title = track.title, artist = track.artist)
                if (streamInfo != null && streamInfo.url.isNotBlank()) {
                    val resolvedDuration = if (track.durationMs > 0) track.durationMs else streamInfo.durationMs
                    val onlineSong = OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse(streamInfo.url))
                    playbackModel.addToQueue(onlineSong)
                    onEnqueued?.invoke()
                } else {
                    L.w("Failed to resolve stream for track %s to enqueue", track.id)
                }
            } catch (e: Exception) {
                L.e(e, "Error resolving online track %s for queue", track.title)
            }
        }
    }

    fun downloadTrack(track: OnlineTrack) {
        downloadManager.enqueue(track)
    }

    fun isDownloaded(track: OnlineTrack): Boolean = downloadManager.isDownloaded(track)

    fun isDownloaded(trackId: String): Boolean = downloadManager.isDownloaded(trackId)

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean = downloadManager.isEnqueuedOrDownloading(track)

    fun isEnqueuedOrDownloading(trackId: String): Boolean = downloadManager.isEnqueuedOrDownloading(trackId)

    fun recordRecentArtist(artist: OnlineArtistSummary) {
        viewModelScope.launch {
            repository.recordRecentArtist(artist)
        }
    }
}
