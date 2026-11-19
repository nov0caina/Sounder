package org.nov0caina.sounder.online.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RecentArtistEntity::class, OnlinePlaylistTrackEntity::class, CachedTrackEntity::class],
    version = 3,
    exportSchema = false
)
abstract class OnlineDatabase : RoomDatabase() {
    abstract fun recentArtistsDao(): RecentArtistsDao
    abstract fun onlinePlaylistTrackDao(): OnlinePlaylistTrackDao
    abstract fun cachedTrackDao(): CachedTrackDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_tracks` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT,
                        `durationMs` INTEGER NOT NULL,
                        `artworkUrl` TEXT,
                        `localCoverPath` TEXT,
                        `localLyricsPath` TEXT,
                        `artistId` TEXT,
                        `albumId` TEXT,
                        `trackNumber` INTEGER,
                        `year` INTEGER,
                        `audioExtension` TEXT NOT NULL DEFAULT 'opus',
                        `cachedAtMs` INTEGER NOT NULL,
                        `lastPlayedMs` INTEGER NOT NULL,
                        `playCount` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}

