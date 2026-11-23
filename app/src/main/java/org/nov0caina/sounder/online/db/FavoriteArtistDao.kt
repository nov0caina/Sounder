package org.nov0caina.sounder.online.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteArtistDao {

    @Query("SELECT * FROM favorite_artists ORDER BY added_at DESC")
    fun observeFavoriteArtists(): Flow<List<FavoriteArtistEntity>>

    @Query("SELECT * FROM favorite_artists ORDER BY added_at DESC")
    suspend fun getAll(): List<FavoriteArtistEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_artists WHERE id = :id)")
    fun observeIsFavorite(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_artists WHERE id = :id)")
    suspend fun isFavorite(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(artist: FavoriteArtistEntity)

    @Query("DELETE FROM favorite_artists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM favorite_artists")
    suspend fun clearAll()
}
