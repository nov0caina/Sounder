package org.nov0caina.sounder.online.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.nov0caina.sounder.online.model.OnlineArtistSummary

@Entity(
    tableName = "favorite_artists",
    indices = [
        Index(value = ["added_at"])
    ]
)
data class FavoriteArtistEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String?,

    @ColumnInfo(name = "subtitle")
    val subtitle: String?,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
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
        fun fromSummary(summary: OnlineArtistSummary, timestamp: Long = System.currentTimeMillis()): FavoriteArtistEntity {
            return FavoriteArtistEntity(
                id = summary.id,
                name = summary.name,
                avatarUrl = summary.avatarUrl,
                subtitle = summary.subtitle,
                addedAt = timestamp
            )
        }
    }
}
