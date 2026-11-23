package org.nov0caina.sounder.util

import android.net.TestUri
import android.net.Uri
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
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
import org.oxycblt.musikr.tag.ReplayGainAdjustment
import org.oxycblt.musikr.tag.interpret.Naming

class SongMatcherTest {

    private val fakeVolume = object : Volume.Internal {
        override val mediaStoreName: String = "external_primary"
        override val components: Components = Components.root()
        override fun resolveName(context: android.content.Context): String = "Internal"
    }

    private fun createFakeSong(title: String, artist: String): Song {
        val artistObj = object : Artist {
            private val artistUuid = UUID.nameUUIDFromBytes(artist.toByteArray())
            override val uid: Music.UID = Music.UID.fromString("uar$artistUuid")!!
            override val name: Name.Known = Naming.simple().name(artist, null)
            override val songs: Collection<Song> get() = emptyList()
            override val explicitAlbums: Collection<Album> get() = emptyList()
            override val implicitAlbums: Collection<Album> get() = emptyList()
            override val durationMs: Long? get() = null
            override val covers: CoverCollection = CoverCollection.from(emptyList())
            override val genres: List<Genre> get() = emptyList()
        }

        return object : Song {
            private val uuid = UUID.nameUUIDFromBytes((title + artist).toByteArray())
            override val uid: Music.UID = Music.UID.fromString("uss$uuid")!!
            override val name: Name.Known = Naming.simple().name(title, null)
            override val track: Int? = null
            override val disc: Disc? = null
            override val date: Date? = null
            override val uri: Uri get() = TestUri.parse("content://media/external/audio/media/123")
            override val path: Path = Path(fakeVolume, Components.parseUnix("Music/$title.mp3"))
            override val format: Format = Format.AAC
            override val size: Long = 1000L
            override val durationMs: Long = 180_000L
            override val bitrateKbps: Int = 320
            override val sampleRateHz: Int = 44100
            override val replayGainAdjustment: ReplayGainAdjustment = ReplayGainAdjustment(null, null)
            override val modifiedMs: Long = 1000L
            override val addedMs: Long = 1000L
            override val cover: Cover? = null
            override val album: Album get() = throw UnsupportedOperationException()
            override val artists: List<Artist> = listOf(artistObj)
            override val genres: List<Genre> = emptyList()
        }
    }

    private fun createFakeOnlineSong(id: String, title: String, artist: String): OnlineSong {
        val track = OnlineTrack(
            id = id,
            title = title,
            artist = artist,
            album = "Album",
            durationMs = 180_000L,
            artworkUrl = null
        )
        return OnlineSong(track, TestUri.parse("https://stream/$id"))
    }

    @Test
    fun testExactMatch() {
        assertTrue(SongMatcher.matches("Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testCaseAndPunctuationMatch() {
        assertTrue(SongMatcher.matches("Thunderstruck", "AC/DC", "thunderstruck", "ac-dc"))
        assertTrue(SongMatcher.matches("XO Tour Llif3", "Lil Uzi Vert", "XO TOUR Llif3", "Lil Uzi Vert"))
    }

    @Test
    fun testTrackNumberPrefixMatch() {
        assertTrue(SongMatcher.matches("01 - Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("01. Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("1 - Thunderstruck", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testVideoTagsAndRemasteredDescriptors() {
        assertTrue(SongMatcher.matches("Thunderstruck (Official Music Video)", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("Thunderstruck [Remastered]", "AC/DC", "Thunderstruck", "AC/DC"))
        assertTrue(SongMatcher.matches("Thunderstruck (Audio)", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testFeaturingArtistMatch() {
        assertTrue(SongMatcher.matches("UnFazed", "Lil Uzi Vert feat. The Weeknd", "UnFazed", "Lil Uzi Vert"))
    }

    @Test
    fun testDifferentTitlesDoNotMatch() {
        assertFalse(SongMatcher.matches("Thunderstruck", "AC/DC", "Moneytalks", "AC/DC"))
        assertFalse(SongMatcher.matches("Fire Your Guns", "AC/DC", "Thunderstruck", "AC/DC"))
    }

    @Test
    fun testDifferentArtistsDoNotMatch() {
        assertFalse(SongMatcher.matches("One", "Metallica", "One", "U2"))
    }

    @Test
    fun testDeduplicateSongsPrioritizesLocalOverOnline() {
        val onlineSong = createFakeOnlineSong("vid_cochise", "Cochise (Official Video)", "Audioslave")
        val localSong = createFakeSong("Cochise", "Audioslave")

        val combined = listOf(onlineSong, localSong)
        val deduplicated = SongMatcher.deduplicateSongs(combined)

        assertEquals(1, deduplicated.size)
        assertTrue("Local song should replace OnlineSong", deduplicated[0] !is OnlineSong)
        assertEquals(localSong.uid, deduplicated[0].uid)
    }

    @Test
    fun testDeduplicateSongsPrioritizesLocalWhenLocalIsFirst() {
        val localSong = createFakeSong("Like a Stone", "Audioslave")
        val onlineSong = createFakeOnlineSong("vid_stone", "Like a Stone", "Audioslave")

        val combined = listOf(localSong, onlineSong)
        val deduplicated = SongMatcher.deduplicateSongs(combined)

        assertEquals(1, deduplicated.size)
        assertTrue(deduplicated[0] !is OnlineSong)
        assertEquals(localSong.uid, deduplicated[0].uid)
    }

    @Test
    fun testDeduplicateSongsDeduplicatesIdenticalOnlineTracks() {
        val online1 = createFakeOnlineSong("vid_be_yourself", "Be Yourself", "Audioslave")
        val online2 = createFakeOnlineSong("vid_be_yourself", "Be Yourself [Remastered]", "Audioslave")

        val combined = listOf(online1, online2)
        val deduplicated = SongMatcher.deduplicateSongs(combined)

        assertEquals(1, deduplicated.size)
        assertEquals(online1.uid, deduplicated[0].uid)
    }

    @Test
    fun testDeduplicateSongsRetainsDistinctTracks() {
        val song1 = createFakeSong("Cochise", "Audioslave")
        val song2 = createFakeSong("Like a Stone", "Audioslave")
        val online1 = createFakeOnlineSong("vid_showme", "Show Me How to Live", "Audioslave")

        val combined = listOf(song1, song2, online1)
        val deduplicated = SongMatcher.deduplicateSongs(combined)

        assertEquals(3, deduplicated.size)
        assertEquals(listOf(song1.uid, song2.uid, online1.uid), deduplicated.map { it.uid })
    }

    @Test
    fun testBidirectionalFavoriteMatchingSemantics() {
        val localSong = createFakeSong("Show Me How to Live", "Audioslave")
        val onlineSong = createFakeOnlineSong("vid_live", "Show Me How to Live (Audio)", "Audioslave")

        val isMatch = SongMatcher.matches(
            localSong.name.raw,
            (localSong.artists.first().name as Name.Known).raw,
            onlineSong.name.raw,
            (onlineSong.artists.first().name as Name.Known).raw
        )
        assertTrue("Local and online representations should match for favorite detection", isMatch)
    }
}
