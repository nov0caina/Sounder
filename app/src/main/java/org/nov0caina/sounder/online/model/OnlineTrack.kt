package org.nov0caina.sounder.online.model

/**
 * Represents a music track discovered from an online source.
 */
data class OnlineTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?,
    val streamUrl: String? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val isStudioTrack: Boolean = true,
    val albumId: String? = null,
    val artistId: String? = null
) {
    /**
     * Formats duration in milliseconds into mm:ss.
     */
    fun formattedDuration(): String {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}

