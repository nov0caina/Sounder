package org.nov0caina.sounder.online.model

import android.net.Uri
import java.io.InputStream
import java.net.URL
import java.util.UUID
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.covers.Cover
import org.oxycblt.musikr.covers.CoverCollection
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Format
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.tag.Date
import org.oxycblt.musikr.tag.Disc
import org.oxycblt.musikr.tag.Name
import org.oxycblt.musikr.tag.ReleaseType
import org.oxycblt.musikr.tag.ReplayGainAdjustment
import org.oxycblt.musikr.tag.interpret.Naming

class OnlineCover(
    private val artworkUrl: String,
    override val id: String
) : Cover {
    override suspend fun open(): InputStream? {
        return try {
            URL(artworkUrl).openStream()
        } catch (e: Exception) {
            null
        }
    }

    override fun equals(other: Any?): Boolean = other is OnlineCover && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

class OnlineArtist(
    nameStr: String
) : Artist {
    private val artistUuid = UUID.nameUUIDFromBytes(("artist_$nameStr").toByteArray())
    override val uid: Music.UID = Music.UID.fromString("uar$artistUuid")!!
    override val name: Name.Known = Naming.simple().name(nameStr, null)
    override val songs: Collection<Song> get() = emptyList()
    override val explicitAlbums: Collection<Album> get() = emptyList()
    override val implicitAlbums: Collection<Album> get() = emptyList()
    override val durationMs: Long? get() = null
    override val covers: CoverCollection = CoverCollection.from(emptyList())
    override val genres: List<Genre> get() = emptyList()

    override fun equals(other: Any?): Boolean = other is OnlineArtist && other.uid == uid
    override fun hashCode(): Int = uid.hashCode()
}

class OnlineAlbum(
    title: String,
    artist: Artist,
    songCover: Cover? = null
) : Album {
    private val albumUuid = UUID.nameUUIDFromBytes(("album_$title").toByteArray())
    override val uid: Music.UID = Music.UID.fromString("uaa$albumUuid")!!
    override val name: Name.Known = Naming.simple().name(title, null)
    override val songs: Collection<Song> get() = emptyList()
    override val dates: Date.Range? get() = null
    override val releaseType: ReleaseType = ReleaseType.Album(null)
    override val covers: CoverCollection = CoverCollection.from(listOfNotNull(songCover))
    override val durationMs: Long = 0L
    override val addedMs: Long = System.currentTimeMillis()
    override val artists: List<Artist> = listOf(artist)

    override fun equals(other: Any?): Boolean = other is OnlineAlbum && other.uid == uid
    override fun hashCode(): Int = uid.hashCode()
}

/**
 * Representation of an online track implementing Auxio's native [Song] interface
 * so that it can seamlessly be played, queued, and displayed in the player UI.
 */
class OnlineSong(
    val onlineTrack: OnlineTrack,
    override val uri: Uri
) : Song {
    private val songUuid = UUID.nameUUIDFromBytes(onlineTrack.id.toByteArray())
    override val uid: Music.UID = Music.UID.fromString("uas$songUuid")!!

    override val name: Name.Known = Naming.simple().name(onlineTrack.title, null)
    override val track: Int? = null
    override val disc: Disc? = null
    override val date: Date? = null

    override val path: Path = Path(
        Volume.ThirdParty(uri),
        Components.parseUnix("online/${onlineTrack.id}")
    )

    override val format: Format = Format.Opus
    override val size: Long = 0L
    override var durationMs: Long = onlineTrack.durationMs
    override val bitrateKbps: Int = 160
    override val sampleRateHz: Int = 48000
    override val replayGainAdjustment: ReplayGainAdjustment = ReplayGainAdjustment(null, null)
    override val modifiedMs: Long = System.currentTimeMillis()
    override val addedMs: Long = System.currentTimeMillis()

    private val artistInstance = OnlineArtist(onlineTrack.artist)
    override val cover: Cover? = onlineTrack.artworkUrl?.let { url ->
        OnlineCover(url, "online_cover_${onlineTrack.id}")
    }

    private val sanitizedAlbumTitle: String = run {
        val raw = onlineTrack.album?.trim()
        val separators = setOf("&", ",", "+", "feat.", "feat", "ft.", "ft", "with", "vs.")
        if (raw.isNullOrBlank() || raw.lowercase() in separators) {
            onlineTrack.title
        } else {
            raw
        }
    }

    override val album: Album = OnlineAlbum(
        title = sanitizedAlbumTitle,
        artist = artistInstance,
        songCover = cover
    )

    override val artists: List<Artist> = listOf(artistInstance)
    override val genres: List<Genre> = emptyList()

    override fun equals(other: Any?): Boolean = other is OnlineSong && other.uid == uid
    override fun hashCode(): Int = uid.hashCode()
}
