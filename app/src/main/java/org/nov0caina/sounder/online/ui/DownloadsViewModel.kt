package org.nov0caina.sounder.online.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.model.OnlineTrack

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadManager: DownloadManager
) : ViewModel() {

    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

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
            streamUrl = null
        )
        downloadManager.enqueue(track)
    }
}

