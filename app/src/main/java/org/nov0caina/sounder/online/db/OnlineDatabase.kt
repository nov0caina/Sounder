package org.nov0caina.sounder.online.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RecentArtistEntity::class,
        OnlinePlaylistTrackEntity::class,
        CachedTrackEntity::class,
        PlaybackRecord::class,
        FavoriteArtistEntity::class,
    ],
    version = 5,
    exportSchema = false
)
abstract class OnlineDatabase : RoomDatabase() {
    abstract fun recentArtistsDao(): RecentArtistsDao
    abstract fun onlinePlaylistTrackDao(): OnlinePlaylistTrackDao
    abstract fun cachedTrackDao(): CachedTrackDao
    abstract fun playbackRecordDao(): PlaybackRecordDao
    abstract fun favoriteArtistDao(): FavoriteArtistDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `track_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT,
                        `genre` TEXT,
                        `artwork_url` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `played_ms` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `time_of_day` INTEGER NOT NULL,
                        `day_of_week` INTEGER NOT NULL,
                        `source_type` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_records_artist` ON `playback_records` (`artist`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_records_timestamp` ON `playback_records` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_records_time_of_day_day_of_week` ON `playback_records` (`time_of_day`, `day_of_week`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_records_track_id` ON `playback_records` (`track_id`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_artists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `avatar_url` TEXT,
                        `subtitle` TEXT,
                        `added_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorite_artists_added_at` ON `favorite_artists` (`added_at`)")
            }
        }
    }
}

