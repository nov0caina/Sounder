package org.nov0caina.sounder.online.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedTrackDao {
    @Query("SELECT * FROM cached_tracks ORDER BY lastPlayedMs DESC")
    fun observeAll(): Flow<List<CachedTrackEntity>>

    @Query("SELECT * FROM cached_tracks ORDER BY lastPlayedMs DESC")
    suspend fun getAll(): List<CachedTrackEntity>

    @Query("SELECT * FROM cached_tracks WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): CachedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedTrackEntity)

    @Query("UPDATE cached_tracks SET localCoverPath = :coverPath WHERE videoId = :videoId")
    suspend fun updateLocalCoverPath(videoId: String, coverPath: String)

    @Query("UPDATE cached_tracks SET localLyricsPath = :lyricsPath WHERE videoId = :videoId")
    suspend fun updateLocalLyricsPath(videoId: String, lyricsPath: String)

    @Query("UPDATE cached_tracks SET audioExtension = :extension WHERE videoId = :videoId")
    suspend fun updateAudioExtension(videoId: String, extension: String)

    @Query("DELETE FROM cached_tracks WHERE videoId = :videoId")
    suspend fun deleteById(videoId: String)

    @Query("DELETE FROM cached_tracks")
    suspend fun deleteAll()
}
