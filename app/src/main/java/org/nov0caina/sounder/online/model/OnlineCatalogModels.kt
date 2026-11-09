package org.nov0caina.sounder.online.model

/**
 * Summary information for an artist returned in search results or carousels.
 */
data class OnlineArtistSummary(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val subtitle: String? = null
)

typealias RecentArtist = OnlineArtistSummary

/**
 * Composite search result containing categorized artists and songs.
 */
data class OnlineSearchResult(
    val artists: List<OnlineArtistSummary> = emptyList(),
    val tracks: List<OnlineTrack> = emptyList()
)

/**
 * Summary information for an album or EP returned in artist catalogs.
 */
data class OnlineAlbumSummary(
    val id: String,
    val title: String,
    val year: String? = null,
    val coverUrl: String? = null
)

/**
 * Detailed artist profile including header, biography, top songs, discography, and related artists.
 */
data class OnlineArtistDetail(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val description: String?,
    val topSongs: List<OnlineTrack> = emptyList(),
    val albums: List<OnlineAlbumSummary> = emptyList(),
    val singles: List<OnlineAlbumSummary> = emptyList(),
    val relatedArtists: List<OnlineArtistSummary> = emptyList(),
    val subscribers: String? = null
)

/**
 * Detailed album view including header, release year, artist, and complete tracklist.
 */
data class OnlineAlbumDetail(
    val id: String,
    val title: String,
    val artist: String,
    val year: String?,
    val coverUrl: String?,
    val tracks: List<OnlineTrack> = emptyList()
)

/**
 * Composite model for the Home feed screen containing trending charts and recommendations.
 */
data class HomeFeedData(
    val trendingTracks: List<OnlineTrack> = emptyList(),
    val trendingAlbums: List<OnlineAlbumSummary> = emptyList(),
    val popularArtists: List<OnlineArtistSummary> = emptyList(),
    val quickPicks: List<OnlineTrack> = emptyList()
)

/**
 * Resolved stream information including playback URL and stream duration.
 */
data class StreamInfo(
    val url: String,
    val durationMs: Long = 0L,
    val author: String? = null
)

/**
 * Model representing a compact card in the 2x3 Quick Access Grid on the Home screen.
 */
data class QuickAccessItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val iconRes: Int? = null,
    val isFavorites: Boolean = false,
    val track: OnlineTrack? = null,
    val artist: OnlineArtistSummary? = null
)

/**
 * Filter chip for mood or musical category on the Home feed.
 */
data class HomeMoodItem(
    val id: String,
    val nameRes: Int,
    val query: String
)


