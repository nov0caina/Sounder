package org.nov0caina.sounder.online.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Calendar
import org.nov0caina.sounder.online.model.OnlineTrack

/**
 * Local listening event stored exclusively on-device for privacy-preserving personalization.
 */
@Entity(
    tableName = "playback_records",
    indices = [
        Index(value = ["artist"]),
        Index(value = ["timestamp"]),
        Index(value = ["time_of_day", "day_of_week"]),
        Index(value = ["track_id"]),
    ]
)
data class PlaybackRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "album")
    val album: String? = null,

    @ColumnInfo(name = "genre")
    val genre: String? = null,

    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "played_ms")
    val playedMs: Long,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "time_of_day")
    val timeOfDay: Int,

    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int,

    @ColumnInfo(name = "source_type")
    val sourceType: String,
) {
    /** Converts a recorded stream event into a playable [OnlineTrack] for recommendation surfaces. */
    fun toOnlineTrack(): OnlineTrack =
        OnlineTrack(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
        )

    companion object {
        const val TIME_MORNING = 0
        const val TIME_AFTERNOON = 1
        const val TIME_EVENING = 2
        const val TIME_NIGHT = 3

        const val SOURCE_LOCAL = "LOCAL"
        const val SOURCE_STREAM = "STREAM"

        /** Maps a 24-hour clock hour (0..23) into a coarse time-of-day bucket. */
        fun timeOfDayForHour(hourOfDay: Int): Int =
            when (hourOfDay) {
                in 6..11 -> TIME_MORNING
                in 12..17 -> TIME_AFTERNOON
                in 18..23 -> TIME_EVENING
                else -> TIME_NIGHT
            }

        /** Returns the `(timeOfDay, dayOfWeek)` pair for [timestampMs] in the local timezone. */
        fun currentContext(timestampMs: Long = System.currentTimeMillis()): Pair<Int, Int> {
            val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val day = cal.get(Calendar.DAY_OF_WEEK)
            return timeOfDayForHour(hour) to day
        }

        /** Returns the epoch millisecond timestamp at 00:00:00.000 on the 1st of the current month. */
        fun currentMonthStartMs(timestampMs: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = timestampMs
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }
    }
}
