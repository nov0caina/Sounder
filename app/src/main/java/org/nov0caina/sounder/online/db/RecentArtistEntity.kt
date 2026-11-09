package org.nov0caina.sounder.online.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.nov0caina.sounder.online.model.OnlineArtistSummary

@Entity(tableName = "recent_artists")
data class RecentArtistEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String?,

    @ColumnInfo(name = "subtitle")
    val subtitle: String?,

    @ColumnInfo(name = "last_viewed_at")
    val lastViewedAt: Long = System.currentTimeMillis()
) {
    fun toSummary(): OnlineArtistSummary {
        return OnlineArtistSummary(
            id = id,
            name = name,
            avatarUrl = avatarUrl,
            subtitle = subtitle
        )
    }

    companion object {
        fun fromSummary(summary: OnlineArtistSummary, timestamp: Long = System.currentTimeMillis()): RecentArtistEntity {
            return RecentArtistEntity(
                id = summary.id,
                name = summary.name,
                avatarUrl = summary.avatarUrl,
                subtitle = summary.subtitle,
                lastViewedAt = timestamp
            )
        }
    }
}

