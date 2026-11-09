package org.nov0caina.sounder.online.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OnlinePlaylistTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: OnlinePlaylistTrackEntity)

    @Query("SELECT * FROM online_playlist_tracks WHERE playlistName = :playlistName")
    suspend fun getByPlaylistName(playlistName: String): List<OnlinePlaylistTrackEntity>

    @Query("SELECT * FROM online_playlist_tracks")
    suspend fun getAll(): List<OnlinePlaylistTrackEntity>

    @Query("DELETE FROM online_playlist_tracks WHERE playlistName = :playlistName AND songUid = :songUid")
    suspend fun delete(playlistName: String, songUid: String)

    @Query("DELETE FROM online_playlist_tracks WHERE playlistName = :playlistName")
    suspend fun deleteForPlaylist(playlistName: String)
}
