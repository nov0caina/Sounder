package org.nov0caina.sounder.online.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentArtistsDao {

    @Query("SELECT * FROM recent_artists ORDER BY last_viewed_at DESC LIMIT 20")
    fun getRecentArtistsFlow(): Flow<List<RecentArtistEntity>>

    @Query("SELECT * FROM recent_artists ORDER BY last_viewed_at DESC LIMIT 20")
    suspend fun getRecentArtists(): List<RecentArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(artist: RecentArtistEntity)

    @Query("DELETE FROM recent_artists WHERE id NOT IN (SELECT id FROM recent_artists ORDER BY last_viewed_at DESC LIMIT 20)")
    suspend fun pruneOldArtists()

    @Transaction
    suspend fun recordArtistVisit(artist: RecentArtistEntity) {
        insertOrUpdate(artist)
        pruneOldArtists()
    }

    @Query("DELETE FROM recent_artists")
    suspend fun clearAll()
}

