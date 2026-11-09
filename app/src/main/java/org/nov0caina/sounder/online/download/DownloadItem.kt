package org.nov0caina.sounder.online.download

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    TAGGING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long,
    val progress: Int = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val localFilePath: String? = null
)

