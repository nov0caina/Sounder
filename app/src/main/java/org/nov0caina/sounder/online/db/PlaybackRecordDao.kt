package org.nov0caina.sounder.online.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Aggregated play count for a single artist computed from local playback records. */
data class ArtistPlayCount(
    val artist: String,
    val playCount: Int,
)

@Dao
interface PlaybackRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PlaybackRecord): Long

    @Query("UPDATE playback_records SET played_ms = :playedMs WHERE id = :id")
    suspend fun updatePlayedMs(id: Long, playedMs: Long)

    @Query("SELECT * FROM playback_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int = 500): List<PlaybackRecord>

    @Query("SELECT * FROM playback_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentRecords(limit: Int = 500): Flow<List<PlaybackRecord>>

    @Query(
        """
        SELECT artist, COUNT(*) as playCount
        FROM playback_records
        WHERE timestamp >= :sinceTimestampMs AND TRIM(artist) != ''
        GROUP BY artist
        ORDER BY playCount DESC, MAX(timestamp) DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtistsSince(sinceTimestampMs: Long, limit: Int = 5): List<ArtistPlayCount>

    @Query("SELECT COALESCE(SUM(played_ms), 0) FROM playback_records WHERE timestamp >= :sinceTimestampMs")
    fun observeTotalPlayedMsSince(sinceTimestampMs: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(played_ms), 0) FROM playback_records WHERE timestamp >= :sinceTimestampMs")
    suspend fun getTotalPlayedMsSince(sinceTimestampMs: Long): Long

    @Query("DELETE FROM playback_records")
    suspend fun clearAll()
}
