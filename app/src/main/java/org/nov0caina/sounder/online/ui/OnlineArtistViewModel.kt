package org.nov0caina.sounder.online.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.R
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.repository.OnlineMusicRepository
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.util.showToast
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@HiltViewModel
class OnlineArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OnlineMusicRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _artistDetail = MutableStateFlow<OnlineArtistDetail?>(null)
    val artistDetail: StateFlow<OnlineArtistDetail?> = _artistDetail.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

    fun loadArtist(browseId: String, artistName: String? = null) {
        if (browseId.isNotBlank() && _artistDetail.value?.id == browseId) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resolvedId = if (browseId.isBlank() && !artistName.isNullOrBlank()) {
                    val results = repository.queryCategorized(artistName)
                    val id = results.artists.firstOrNull()?.id ?: ""
                    if (id.isBlank()) {
                        withContext(Dispatchers.Main) {
                            context.showToast(R.string.err_artist_not_found_online)
                        }
                    }
                    id
                } else {
                    browseId
                }
                if (resolvedId.isNotBlank()) {
                    val detail = repository.browseArtist(resolvedId)
                    _artistDetail.value = detail

                    // Background enrichment so the artist profile displays at least 20 tracks
                    if (detail.topSongs.size < 20) {
                        launch(Dispatchers.IO) {
                            try {
                                val enriched = getEnrichedSongs(detail.topSongs, detail.name)
                                if (enriched.size > detail.topSongs.size) {
                                    withContext(Dispatchers.Main) {
                                        _artistDetail.value = _artistDetail.value?.copy(topSongs = enriched)
                                    }
                                }
                            } catch (e: Exception) {
                                L.w(e, "Background enrichment of artist top songs failed")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.e(e, "Failed to load artist detail for %s", browseId)
            } finally {
                _isLoading.value = false
            }
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

    private suspend fun getEnrichedSongs(baseTracks: List<OnlineTrack>, artistName: String): List<OnlineTrack> {
        if (baseTracks.size >= 20) return baseTracks
        return withContext(Dispatchers.IO) {
            val combined = baseTracks.toMutableList()
            try {
                val searchResult = repository.queryCategorized("$artistName songs")
                for (t in searchResult.tracks) {
                    if (combined.none { it.id == t.id }) {
                        combined.add(t)
                        if (combined.size >= 25) break
                    }
                }
            } catch (e: Exception) {
                L.w(e, "Search for $artistName songs failed")
            }

            if (combined.size < 20) {
                try {
                    val directSearch = repository.queryCategorized(artistName)
                    for (t in directSearch.tracks) {
                        if (combined.none { it.id == t.id }) {
                            combined.add(t)
                            if (combined.size >= 25) break
                        }
                    }
                } catch (e: Exception) {
                    L.w(e, "Direct search for $artistName failed")
                }
            }

            if (combined.size < 20) {
                val detail = _artistDetail.value
                val albumList = (detail?.albums.orEmpty() + detail?.singles.orEmpty())
                for (alb in albumList) {
                    if (combined.size >= 20) break
                    try {
                        val albumDetail = repository.browseAlbum(alb.id)
                        for (t in albumDetail.tracks) {
                            if (combined.none { it.id == t.id }) {
                                combined.add(t)
                                if (combined.size >= 25) break
                            }
                        }
                    } catch (e: Exception) {
                        L.w(e, "Could not fetch tracks from album ${alb.id}")
                    }
                }
            }
            combined
        }
    }

    fun playTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            try {
                val localFile = downloadManager.getDownloadedFile(track)
                val targetSong: Song = if (localFile != null && localFile.exists()) {
                    val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
                    OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
                } else {
                    val localSong = downloadManager.findSongInLibrary(track)
                    if (localSong != null) {
                        localSong
                    } else {
                        val streamInfo = repository.resolveStreamInfo(track.id, title = track.title, artist = track.artist)
                        if (streamInfo != null && streamInfo.url.isNotBlank()) {
                            val resolvedDuration = if (track.durationMs > 0) track.durationMs else streamInfo.durationMs
                            OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse(streamInfo.url))
                        } else {
                            toSong(track)
                        }
                    }
                }

                val currentTracks = _artistDetail.value?.topSongs ?: listOf(track)
                val artistName = _artistDetail.value?.name ?: track.artist
                val enrichedTracks = if (currentTracks.size < 20) {
                    getEnrichedSongs(currentTracks, artistName).also { enriched ->
                        _artistDetail.value?.let { d ->
                            _artistDetail.value = d.copy(topSongs = enriched)
                        }
                    }
                } else {
                    currentTracks
                }

                val queue = enrichedTracks.map { t ->
                    if (t.id == track.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) queue else listOf(targetSong) + queue
                playbackModel.play(targetSong, finalQueue)

                // Background prefetch for remaining artist tracks
                launch(Dispatchers.IO) {
                    enrichedTracks.filter { it.id != track.id }.take(20).forEach { t ->
                        try {
                            if (downloadManager.getDownloadedFile(t)?.exists() != true && downloadManager.findSongInLibrary(t) == null) {
                                repository.resolveStreamInfo(t.id, title = t.title, artist = t.artist)
                            }
                        } catch (e: Exception) {
                            L.w(e, "Background prefetch failed for artist track %s", t.title)
                        }
                    }
                }
            } catch (e: Exception) {
                L.e(e, "Failed to play track %s", track.title)
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

    /**
     * Plays artist popular songs starting with Track 1 immediately, enqueuing at least 20 songs.
     */
    fun playTopSongs(playbackModel: PlaybackViewModel) {
        val tracks = _artistDetail.value?.topSongs ?: return
        if (tracks.isEmpty()) return
        playTrack(tracks.first(), playbackModel)
    }

    fun downloadTrack(track: OnlineTrack) {
        downloadManager.enqueue(track)
    }

    fun isDownloaded(track: OnlineTrack): Boolean = downloadManager.isDownloaded(track)

    fun isDownloaded(trackId: String): Boolean = downloadManager.isDownloaded(trackId)

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean = downloadManager.isEnqueuedOrDownloading(track)

    fun isEnqueuedOrDownloading(trackId: String): Boolean = downloadManager.isEnqueuedOrDownloading(trackId)
}
