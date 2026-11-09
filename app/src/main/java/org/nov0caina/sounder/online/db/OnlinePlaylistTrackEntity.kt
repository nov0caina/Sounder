package org.nov0caina.sounder.online.db

import androidx.room.Entity

@Entity(
    tableName = "online_playlist_tracks",
    primaryKeys = ["playlistName", "songUid"]
)
data class OnlinePlaylistTrackEntity(
    val playlistName: String,
    val songUid: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?
)
