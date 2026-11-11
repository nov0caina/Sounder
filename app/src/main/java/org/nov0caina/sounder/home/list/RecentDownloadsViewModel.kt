package org.nov0caina.sounder.home.list

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
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.download.DownloadStatus
import org.nov0caina.sounder.online.model.OnlineTrack
import org.oxycblt.musikr.Song

/**
 * Sealed representation of an item displayed in the "Descargas Recientes" library tab.
 * Active/queued downloads appear at the top, followed by completed downloaded songs
 * ordered from newest to oldest.
 */
sealed interface RecentDownloadEntry {
    data class Active(val item: DownloadItem) : RecentDownloadEntry
    data class Completed(val song: Song) : RecentDownloadEntry
}

@HiltViewModel
class RecentDownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager,
    private val musicRepository: MusicRepository,
) : ViewModel(), MusicRepository.UpdateListener {

    private val _completedSongs = MutableStateFlow<List<Song>>(emptyList())
    val completedSongs: StateFlow<List<Song>> = _completedSongs.asStateFlow()

    val activeDownloads: StateFlow<List<DownloadItem>> =
        downloadManager.downloads
            .map { filterActiveDownloads(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, filterActiveDownloads(downloadManager.downloads.value))

    val entries: StateFlow<List<RecentDownloadEntry>> =
        combine(activeDownloads, _completedSongs) { active, completed ->
            combineEntries(active, completed)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            combineEntries(activeDownloads.value, _completedSongs.value),
        )

    init {
        musicRepository.addUpdateListener(this)
        refreshCompletedSongs()
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary) {
            refreshCompletedSongs()
        }
    }

    fun refreshCompletedSongs() {
        val songs = musicRepository.library?.songs.orEmpty()
        _completedSongs.value = filterAndSortDownloadedSongs(songs)
    }

    fun cancelDownload(trackId: String) {
        downloadManager.cancel(trackId)
    }

    fun retryDownload(item: DownloadItem) {
        val track = OnlineTrack(
            id = item.trackId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            durationMs = item.durationMs,
            artworkUrl = item.artworkUrl,
            streamUrl = null,
        )
        downloadManager.enqueue(track)
    }

    override fun onCleared() {
        super.onCleared()
        musicRepository.removeUpdateListener(this)
    }

    companion object {
        fun filterActiveDownloads(items: List<DownloadItem>): List<DownloadItem> {
            return items
                .filter { item ->
                    item.status == DownloadStatus.DOWNLOADING ||
                        item.status == DownloadStatus.QUEUED ||
                        item.status == DownloadStatus.TAGGING ||
                        item.status == DownloadStatus.FAILED
                }
                .sortedBy { item ->
                    when (item.status) {
                        DownloadStatus.DOWNLOADING -> 0
                        DownloadStatus.TAGGING -> 1
                        DownloadStatus.QUEUED -> 2
                        DownloadStatus.FAILED -> 3
                        else -> 4
                    }
                }
        }

        fun filterAndSortDownloadedSongs(songs: Collection<Song>): List<Song> {
            return songs
                .filter { song ->
                    song.path.components.toString().contains("Sounder", ignoreCase = true)
                }
                .sortedByDescending { it.addedMs }
        }

        fun combineEntries(
            active: List<DownloadItem>,
            completed: List<Song>,
        ): List<RecentDownloadEntry> {
            return buildList(active.size + completed.size) {
                active.forEach { add(RecentDownloadEntry.Active(it)) }
                completed.forEach { add(RecentDownloadEntry.Completed(it)) }
            }
        }
    }
}
