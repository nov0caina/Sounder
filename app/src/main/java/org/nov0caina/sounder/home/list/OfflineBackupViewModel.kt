package org.nov0caina.sounder.home.list

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.nov0caina.sounder.online.cache.OfflineBackupFilter
import org.nov0caina.sounder.online.cache.OfflineBackupFilterMode
import org.nov0caina.sounder.online.cache.OfflineBackupFilterState
import org.nov0caina.sounder.online.cache.StreamingCacheManager
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.state.ShuffleMode

@HiltViewModel
class OfflineBackupViewModel @Inject constructor(
    private val streamingCacheManager: StreamingCacheManager,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val cachedTracks: StateFlow<List<OnlineTrack>> =
        streamingCacheManager.observePlayableCachedTracks()
            .map { entities -> entities.map { it.toOnlineTrack() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _filterState = MutableStateFlow(OfflineBackupFilterState())
    val filterState: StateFlow<OfflineBackupFilterState> = _filterState.asStateFlow()

    val filteredTracks: StateFlow<List<OnlineTrack>> =
        combine(cachedTracks, filterState) { tracks, filter ->
            OfflineBackupFilter.filterTracks(tracks, filter)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cacheSizeBytes: StateFlow<Long> = streamingCacheManager.cacheSizeBytes
    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

    fun refresh() {
        streamingCacheManager.refreshCacheMetrics()
    }

    fun selectFilterMode(mode: OfflineBackupFilterMode) {
        _filterState.value = OfflineBackupFilterState(mode = mode, selectedValue = null)
    }

    fun selectFilterValue(value: String?) {
        val current = _filterState.value
        val nextValue = if (current.selectedValue.equals(value, ignoreCase = true)) null else value
        _filterState.value = current.copy(selectedValue = nextValue)
    }

    fun play(
        startTrack: OnlineTrack? = null,
        shuffle: Boolean = false,
        playbackModel: PlaybackViewModel,
    ) {
        val tracks = filteredTracks.value.ifEmpty { cachedTracks.value }
        if (tracks.isEmpty()) return

        val queue = tracks.map { track ->
            val localFile = downloadManager.getDownloadedFile(track)
            if (localFile != null && localFile.exists()) {
                OnlineSong(track, Uri.fromFile(localFile))
            } else {
                OnlineSong(track, Uri.parse("sounder://stream/${track.id}"))
            }
        }
        val target = if (startTrack != null) {
            queue.firstOrNull { (it as? OnlineSong)?.onlineTrack?.id == startTrack.id } ?: queue.first()
        } else if (shuffle) {
            queue.random()
        } else {
            queue.first()
        }
        playbackModel.play(target, queue, if (shuffle) ShuffleMode.ON else ShuffleMode.OFF)
    }

    fun downloadTrack(track: OnlineTrack) {
        downloadManager.enqueue(track)
    }

    fun isDownloaded(track: OnlineTrack): Boolean = downloadManager.isDownloaded(track)

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean = downloadManager.isEnqueuedOrDownloading(track)
}
