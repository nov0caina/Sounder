package org.nov0caina.sounder.music.decision

import android.content.Context
import android.net.Uri
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.music.PlaylistDecision
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.covers.Cover
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Format
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.tag.Date
import org.oxycblt.musikr.tag.Disc
import org.oxycblt.musikr.tag.Name
import org.oxycblt.musikr.tag.ReplayGainAdjustment
import org.oxycblt.musikr.tag.interpret.Naming

class PlaylistPickerViewModelTest {

    private val fakeVolume = object : Volume.Internal {
        override val mediaStoreName: String = "external_primary"
        override val components: Components = Components.root()
        override fun resolveName(context: Context): String = "Internal"
    }

    private fun createFakeSong(title: String): Song {
        return object : Song {
            private val uuid = UUID.nameUUIDFromBytes(title.toByteArray())
            override val uid: Music.UID = Music.UID.fromString("uas$uuid")!!
            override val name: Name.Known = Naming.simple().name(title, null)
            override val track: Int? = null
            override val disc: Disc? = null
            override val date: Date? = null
            override val uri: Uri
                get() = throw UnsupportedOperationException()
            override val path: Path = Path(fakeVolume, Components.parseUnix("Music/$title.mp3"))
            override val format: Format = Format.AAC
            override val size: Long = 1000L
            override val durationMs: Long = 180_000L
            override val bitrateKbps: Int = 256
            override val sampleRateHz: Int = 44100
            override val replayGainAdjustment: ReplayGainAdjustment =
                ReplayGainAdjustment(null, null)
            override val modifiedMs: Long = 1000L
            override val addedMs: Long = 1000L
            override val cover: Cover? = null
            override val album: Album
                get() = throw UnsupportedOperationException()
            override val artists: List<Artist> = emptyList()
            override val genres: List<Genre> = emptyList()
        }
    }

    @Test
    fun `buildPendingNewPlaylist allows empty songs when requestedSongUidCount is 0`() {
        val pending = PlaylistPickerViewModel.buildPendingNewPlaylist(
            possibleName = "Playlist 1",
            requestedSongUidCount = 0,
            resolvedSongs = emptyList(),
            template = null,
            reason = PlaylistDecision.New.Reason.NEW,
        )

        assertNotNull(pending)
        assertEquals("Playlist 1", pending!!.preferredName)
        assertTrue(pending.songs.isEmpty())
        assertEquals(PlaylistDecision.New.Reason.NEW, pending.reason)
    }

    @Test
    fun `buildPendingNewPlaylist returns null when non-empty requestedSongUidCount resolves to empty list`() {
        val pending = PlaylistPickerViewModel.buildPendingNewPlaylist(
            possibleName = "Playlist 1",
            requestedSongUidCount = 2,
            resolvedSongs = emptyList(),
            template = null,
            reason = PlaylistDecision.New.Reason.ADD,
        )

        assertNull(pending)
    }

    @Test
    fun `buildPendingNewPlaylist returns PendingNewPlaylist when songs resolve`() {
        val song = createFakeSong("Track A")
        val pending = PlaylistPickerViewModel.buildPendingNewPlaylist(
            possibleName = "Playlist 1",
            requestedSongUidCount = 1,
            resolvedSongs = listOf(song),
            template = "Custom Name",
            reason = PlaylistDecision.New.Reason.ADD,
        )

        assertNotNull(pending)
        assertEquals("Playlist 1", pending!!.preferredName)
        assertEquals(listOf(song), pending.songs)
        assertEquals("Custom Name", pending.template)
        assertEquals(PlaylistDecision.New.Reason.ADD, pending.reason)
    }

    @Test
    fun `canConfirmPlaylistEdit allows valid new name without cover changes`() {
        assertTrue(
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = ChosenName.Valid("Chill Vibes"),
                originalPlaylistName = "Chill",
                hasPendingCoverUri = false,
                removeExistingCover = false,
            )
        )
    }

    @Test
    fun `canConfirmPlaylistEdit allows unchanged name when cover is selected or removed`() {
        assertFalse(
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = ChosenName.AlreadyExists("Chill"),
                originalPlaylistName = "Chill",
                hasPendingCoverUri = false,
                removeExistingCover = false,
            )
        )

        assertTrue(
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = ChosenName.AlreadyExists("Chill"),
                originalPlaylistName = "Chill",
                hasPendingCoverUri = true,
                removeExistingCover = false,
            )
        )

        assertTrue(
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = ChosenName.AlreadyExists("Chill"),
                originalPlaylistName = "Chill",
                hasPendingCoverUri = false,
                removeExistingCover = true,
            )
        )
    }

    @Test
    fun `canConfirmPlaylistEdit rejects collision with a different playlist even when cover changes`() {
        assertFalse(
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = ChosenName.AlreadyExists("Other Playlist"),
                originalPlaylistName = "Chill",
                hasPendingCoverUri = true,
                removeExistingCover = false,
            )
        )
        assertFalse(
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = ChosenName.Empty,
                originalPlaylistName = "Chill",
                hasPendingCoverUri = true,
                removeExistingCover = false,
            )
        )
    }

    @Test
    fun `resolveEditedPlaylistName returns target name for valid or unchanged name`() {
        assertEquals(
            "Chill Vibes",
            PlaylistPickerViewModel.resolveEditedPlaylistName(
                ChosenName.Valid("Chill Vibes"),
                "Chill",
            ),
        )
        assertEquals(
            "Chill",
            PlaylistPickerViewModel.resolveEditedPlaylistName(
                ChosenName.AlreadyExists("Chill"),
                "Chill",
            ),
        )
    }
}
