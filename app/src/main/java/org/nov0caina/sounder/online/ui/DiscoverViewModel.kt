package org.nov0caina.sounder.online.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.RecentArtist
import org.nov0caina.sounder.online.repository.OnlineMusicRepository
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.state.ShuffleMode
import org.oxycblt.musikr.Song
import timber.log.Timber as L

sealed interface DiscoverQueryState {
    data object Idle : DiscoverQueryState
    data class Search(val query: String) : DiscoverQueryState
    data class Category(val title: String, val apiQuery: String) : DiscoverQueryState
}

internal class DiscoverQueryCoordinator(
    private val repository: OnlineMusicRepository
) {
    private val _activeQueryState = MutableStateFlow<DiscoverQueryState>(DiscoverQueryState.Idle)
    val activeQueryState: StateFlow<DiscoverQueryState> = _activeQueryState.asStateFlow()

    private var searchJob: Job? = null

    fun search(scope: CoroutineScope, query: String, forceRefresh: Boolean = false) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearSearch(scope)
            return
        }
        _activeQueryState.value = DiscoverQueryState.Search(trimmed)
        searchJob?.cancel()
        searchJob = scope.launch {
            repository.searchCategorized(trimmed, isCategory = false, forceRefresh = forceRefresh)
        }
    }

    fun selectCategory(scope: CoroutineScope, title: String, apiQuery: String, forceRefresh: Boolean = false) {
        val trimmedQuery = apiQuery.trim()
        if (trimmedQuery.isEmpty()) {
            clearSearch(scope)
            return
        }
        _activeQueryState.value = DiscoverQueryState.Category(
            title = title.trim(),
            apiQuery = trimmedQuery
        )
        searchJob?.cancel()
        searchJob = scope.launch {
            repository.searchCategorized(trimmedQuery, isCategory = true, forceRefresh = forceRefresh)
        }
    }

    fun retryActiveQuery(scope: CoroutineScope, forceRefresh: Boolean = false) {
        when (val current = _activeQueryState.value) {
            is DiscoverQueryState.Search -> search(scope, current.query, forceRefresh = forceRefresh)
            is DiscoverQueryState.Category -> selectCategory(scope, current.title, current.apiQuery, forceRefresh = forceRefresh)
            DiscoverQueryState.Idle -> Unit
        }
    }

    fun clearSearch(scope: CoroutineScope) {
        _activeQueryState.value = DiscoverQueryState.Idle
        searchJob?.cancel()
        searchJob = scope.launch {
            repository.searchCategorized("")
        }
    }
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val repository: OnlineMusicRepository,
    val downloadManager: DownloadManager
) : ViewModel() {

    private val queryCoordinator = DiscoverQueryCoordinator(repository)

    val searchResults: StateFlow<List<OnlineTrack>> = repository.searchResults
    val categorizedResults: StateFlow<OnlineSearchResult> = repository.categorizedResults
    val recentArtists: StateFlow<List<RecentArtist>> = repository.recentArtists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val isSearching: StateFlow<Boolean> = repository.isSearching

    val activeDownloadsCount: StateFlow<Int> = downloadManager.activeCount
    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    val activeQueryState: StateFlow<DiscoverQueryState> = queryCoordinator.activeQueryState

    fun search(query: String) = queryCoordinator.search(viewModelScope, query)

    fun selectCategory(title: String, apiQuery: String) =
        queryCoordinator.selectCategory(viewModelScope, title, apiQuery)

    fun selectGenre(genre: GenreItem) {
        selectCategory(title = genre.name, apiQuery = genre.query)
    }

    fun retryActiveQuery() = queryCoordinator.retryActiveQuery(viewModelScope, forceRefresh = false)

    fun refreshActiveQuery() = queryCoordinator.retryActiveQuery(viewModelScope, forceRefresh = true)

    fun clearSearch() = queryCoordinator.clearSearch(viewModelScope)

    fun recordRecentArtist(artist: RecentArtist) {
        viewModelScope.launch {
            repository.recordRecentArtist(artist)
        }
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

    fun playTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            L.d("playTrack: Resolving stream for %s (%s)", track.title, track.id)
            try {
                val targetSong = resolveToPlayableSong(track)
                val searchTracks = categorizedResults.value.tracks
                val queueTracks = if (searchTracks.isNotEmpty()) searchTracks else listOf(track)
                val queue = queueTracks.map { t ->
                    if (t.id == track.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) {
                    queue
                } else {
                    listOf(targetSong) + queue
                }
                playbackModel.play(targetSong, finalQueue)

                launch(Dispatchers.IO) {
                    queueTracks.filter { it.id != track.id }.take(20).forEach { t ->
                        try {
                            if (downloadManager.getDownloadedFile(t)?.exists() != true &&
                                downloadManager.findSongInLibrary(t) == null
                            ) {
                                repository.resolveStreamInfo(t.id, title = t.title, artist = t.artist)
                            }
                        } catch (e: Exception) {
                            L.w(e, "Prefetch failed for search track %s", t.title)
                        }
                    }
                }
            } catch (e: Exception) {
                L.e(e, "Failed to resolve stream and play online track %s", track.title)
            } finally {
                _loadingTrackId.value = null
            }
        }
    }

    fun playAllTracks(
        tracks: List<OnlineTrack>,
        playbackModel: PlaybackViewModel,
        shuffle: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        val starter = if (shuffle) tracks.random() else tracks.first()
        viewModelScope.launch {
            _loadingTrackId.value = starter.id
            try {
                val targetSong = resolveToPlayableSong(starter)
                val queue = tracks.map { t ->
                    if (t.id == starter.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) {
                    queue
                } else {
                    listOf(targetSong) + queue
                }
                playbackModel.play(
                    targetSong,
                    finalQueue,
                    if (shuffle) ShuffleMode.ON else ShuffleMode.OFF,
                )

                launch(Dispatchers.IO) {
                    tracks.filter { it.id != starter.id }.take(20).forEach { t ->
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
                L.e(e, "Failed to start playback for track collection")
            } finally {
                _loadingTrackId.value = null
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
                    L.w("Failed to resolve stream for track %s", track.id)
                }
            } catch (e: Exception) {
                L.e(e, "Error queueing online track %s", track.title)
            }
        }
    }

    fun downloadTrack(track: OnlineTrack) {
        L.d("downloadTrack: Enqueueing %s (%s)", track.title, track.id)
        downloadManager.enqueue(track)
    }

    fun isDownloaded(track: OnlineTrack): Boolean = downloadManager.isDownloaded(track)

    fun isDownloaded(trackId: String): Boolean = downloadManager.isDownloaded(trackId)

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean = downloadManager.isEnqueuedOrDownloading(track)

    fun isEnqueuedOrDownloading(trackId: String): Boolean = downloadManager.isEnqueuedOrDownloading(trackId)
}
