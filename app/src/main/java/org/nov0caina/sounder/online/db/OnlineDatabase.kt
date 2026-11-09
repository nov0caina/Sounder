package org.nov0caina.sounder.online.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RecentArtistEntity::class, OnlinePlaylistTrackEntity::class],
    version = 2,
    exportSchema = false
)
abstract class OnlineDatabase : RoomDatabase() {
    abstract fun recentArtistsDao(): RecentArtistsDao
    abstract fun onlinePlaylistTrackDao(): OnlinePlaylistTrackDao
}

