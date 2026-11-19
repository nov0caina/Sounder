package org.nov0caina.sounder.online.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.nov0caina.sounder.online.model.OnlineTrack

/**
 * Persists integral metadata for tracks cached in the rotating streaming cache,
 * enabling zero-network offline playback, artwork/lyrics lookup, and artist/album navigation.
 */
@Entity(tableName = "cached_tracks")
data class CachedTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?,
    val localCoverPath: String?,
    val localLyricsPath: String?,
    val artistId: String?,
    val albumId: String?,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val audioExtension: String = "opus",
    val cachedAtMs: Long = System.currentTimeMillis(),
    val lastPlayedMs: Long = System.currentTimeMillis(),
    val playCount: Int = 1
) {
    fun toOnlineTrack(): OnlineTrack = OnlineTrack(
        id = videoId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        trackNumber = trackNumber,
        year = year,
        albumId = albumId,
        artistId = artistId,
        localCoverPath = localCoverPath
    )
}
