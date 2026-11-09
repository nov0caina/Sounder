package org.nov0caina.sounder.online.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.repository.OnlineMusicRepository
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.PlaySong
import org.oxycblt.musikr.Song
import timber.log.Timber as L

@HiltViewModel
class OnlineAlbumViewModel @Inject constructor(
    private val repository: OnlineMusicRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _albumDetail = MutableStateFlow<OnlineAlbumDetail?>(null)
    val albumDetail: StateFlow<OnlineAlbumDetail?> = _albumDetail.asStateFlow()

    private val _moreAlbums = MutableStateFlow<List<OnlineAlbumSummary>>(emptyList())
    val moreAlbums: StateFlow<List<OnlineAlbumSummary>> = _moreAlbums.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

    fun loadAlbum(browseId: String, albumTitle: String? = null, artistName: String? = null) {
        if (browseId.isNotBlank() && _albumDetail.value?.id == browseId) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                var resolvedDetail: OnlineAlbumDetail? = null

                // 1. If browseId is provided, try browsing it directly
                if (browseId.isNotBlank()) {
                    try {
                        resolvedDetail = repository.browseAlbum(browseId)
                    } catch (e: Exception) {
                        L.w(e, "Could not load album directly with browseId: %s", browseId)
                    }
                }

                val separators = setOf("&", ",", "+", "feat.", "feat", "ft.", "ft", "with", "vs.")
                val cleanAlbumTitle = albumTitle?.trim()?.takeIf {
                    it.isNotBlank() && it !in separators && it.lowercase() !in separators
                }
                val cleanArtistName = artistName?.trim()?.takeIf { it.isNotBlank() }

                // 2. If no detail yet, try resolving browseId via artist's catalog
                if (resolvedDetail == null && !cleanAlbumTitle.isNullOrBlank()) {
                    var foundBrowseId = ""
                    if (!cleanArtistName.isNullOrBlank()) {
                        try {
                            val artistSearch = repository.queryCategorized(cleanArtistName)
                            val artistId = artistSearch.artists.firstOrNull()?.id
                            if (!artistId.isNullOrBlank()) {
                                val artistDetail = repository.browseArtist(artistId)
                                val matchingAlbum = (artistDetail.albums + artistDetail.singles).find {
                                    it.title.contains(cleanAlbumTitle, ignoreCase = true) ||
                                        cleanAlbumTitle.contains(it.title, ignoreCase = true)
                                }
                                foundBrowseId = matchingAlbum?.id ?: ""
                            }
                        } catch (e: Exception) {
                            L.w(e, "Could not resolve album browseId via artist catalog")
                        }
                    }

                    if (foundBrowseId.isNotBlank()) {
                        try {
                            resolvedDetail = repository.browseAlbum(foundBrowseId)
                        } catch (e: Exception) {
                            L.w(e, "Could not load resolved album: %s", foundBrowseId)
                        }
                    }
                }

                // 3. If still not resolved, search for the album or track
                if (resolvedDetail == null && !cleanAlbumTitle.isNullOrBlank()) {
                    val query = if (!cleanArtistName.isNullOrBlank()) "$cleanAlbumTitle $cleanArtistName" else cleanAlbumTitle
                    try {
                        val searchResult = repository.queryCategorized(query)
                        val matchingTrack = searchResult.tracks.firstOrNull {
                            it.title.contains(cleanAlbumTitle, ignoreCase = true) ||
                                cleanAlbumTitle.contains(it.title, ignoreCase = true) ||
                                (it.album != null && (it.album.contains(cleanAlbumTitle, ignoreCase = true) || cleanAlbumTitle.contains(it.album, ignoreCase = true)))
                        } ?: searchResult.tracks.firstOrNull()

                        if (matchingTrack != null) {
                            // If matching track has an albumId, try loading that album
                            if (!matchingTrack.albumId.isNullOrBlank()) {
                                try {
                                    resolvedDetail = repository.browseAlbum(matchingTrack.albumId)
                                } catch (e: Exception) {
                                    L.w(e, "Could not load track albumId %s", matchingTrack.albumId)
                                }
                            }

                            // If still null, synthesize single/track album detail so user NEVER sees empty 0-song screen
                            if (resolvedDetail == null) {
                                val synthesizedTitle = matchingTrack.album?.takeIf {
                                    it.isNotBlank() && it !in separators && it.lowercase() !in separators
                                } ?: matchingTrack.title
                                resolvedDetail = OnlineAlbumDetail(
                                    id = matchingTrack.albumId ?: matchingTrack.id,
                                    title = synthesizedTitle,
                                    artist = matchingTrack.artist.ifBlank { cleanArtistName ?: "Unknown Artist" },
                                    year = matchingTrack.year?.toString(),
                                    coverUrl = matchingTrack.artworkUrl,
                                    tracks = listOf(matchingTrack)
                                )
                            }
                        }
                    } catch (e: Exception) {
                        L.w(e, "Could not search fallback for album %s", cleanAlbumTitle)
                    }
                }

                if (resolvedDetail != null) {
                    _albumDetail.value = resolvedDetail
                    fetchMoreAlbums(resolvedDetail)
                }
            } catch (e: Exception) {
                L.e(e, "Failed to load album detail for %s", browseId)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun fetchMoreAlbums(detail: OnlineAlbumDetail) {
        val artist = detail.artist.trim()
        if (artist.isBlank() || artist.equals("Unknown Artist", ignoreCase = true)) return
        viewModelScope.launch {
            try {
                val searchResult = repository.queryCategorized(artist)
                val artistId = searchResult.artists.firstOrNull()?.id
                if (!artistId.isNullOrBlank()) {
                    val artistDetail = repository.browseArtist(artistId)
                    val filtered = (artistDetail.albums + artistDetail.singles).filter {
                        it.id != detail.id && !it.title.equals(detail.title, ignoreCase = true)
                    }
                    _moreAlbums.value = filtered
                }
            } catch (e: Exception) {
                L.w(e, "Could not load more albums for %s", artist)
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

                val allTracks = _albumDetail.value?.tracks ?: listOf(track)
                val queue = allTracks.map { t ->
                    if (t.id == track.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) queue else listOf(targetSong) + queue
                playbackModel.play(targetSong, finalQueue)

                // Silent background prefetching for the other album tracks so ExoPlayer plays them instantly
                launch(Dispatchers.IO) {
                    allTracks.filter { it.id != track.id }.forEach { t ->
                        try {
                            if (downloadManager.getDownloadedFile(t)?.exists() != true && downloadManager.findSongInLibrary(t) == null) {
                                repository.resolveStreamInfo(t.id, title = t.title, artist = t.artist)
                            }
                        } catch (e: Exception) {
                            L.w(e, "Prefetch failed for album track %s", t.title)
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
     * Plays the entire album starting with Track 1 immediately, enqueuing all tracks.
     */
    fun playAlbum(playbackModel: PlaybackViewModel) {
        val tracks = _albumDetail.value?.tracks ?: return
        if (tracks.isEmpty()) return
        playTrack(tracks.first(), playbackModel)
    }

    fun downloadAlbum() {
        val detail = _albumDetail.value ?: return
        downloadManager.enqueueAlbum(detail)
    }

    fun downloadTrack(track: OnlineTrack) {
        downloadManager.enqueue(track)
    }

    fun isDownloaded(track: OnlineTrack): Boolean = downloadManager.isDownloaded(track)

    fun isDownloaded(trackId: String): Boolean = downloadManager.isDownloaded(trackId)

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean = downloadManager.isEnqueuedOrDownloading(track)

    fun isEnqueuedOrDownloading(trackId: String): Boolean = downloadManager.isEnqueuedOrDownloading(trackId)

    fun getAlbumDownloadStatus(): AlbumDownloadStatus {
        val detail = _albumDetail.value ?: return AlbumDownloadStatus.AVAILABLE
        val tracks = detail.tracks
        if (tracks.isEmpty()) return AlbumDownloadStatus.AVAILABLE

        val allDownloaded = tracks.all { isDownloaded(it) }
        if (allDownloaded) return AlbumDownloadStatus.COMPLETED

        val anyDownloading = tracks.any { isEnqueuedOrDownloading(it) }
        if (anyDownloading) return AlbumDownloadStatus.DOWNLOADING

        return AlbumDownloadStatus.AVAILABLE
    }

    fun getUndownloadedTracksCount(): Int {
        val detail = _albumDetail.value ?: return 0
        return detail.tracks.count { !isDownloaded(it) }
    }
}

enum class AlbumDownloadStatus {
    AVAILABLE,
    DOWNLOADING,
    COMPLETED
}
