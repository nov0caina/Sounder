package org.nov0caina.sounder.home.list

import android.content.Context
import android.net.Uri
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadStatus
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

class RecentDownloadsViewModelTest {

    private val fakeVolume = object : Volume.Internal {
        override val mediaStoreName: String = "external_primary"
        override val components: Components = Components.root()
        override fun resolveName(context: Context): String = "Internal"
    }

    private fun createFakeSong(
        title: String,
        unixPath: String,
        addedMs: Long,
    ): Song {
        return object : Song {
            private val uuid = UUID.nameUUIDFromBytes((title + unixPath).toByteArray())
            override val uid: Music.UID = Music.UID.fromString("uas$uuid")!!
            override val name: Name.Known = Naming.simple().name(title, null)
            override val track: Int? = null
            override val disc: Disc? = null
            override val date: Date? = null
            override val uri: Uri
                get() = throw UnsupportedOperationException()
            override val path: Path = Path(
                fakeVolume,
                Components.parseUnix(unixPath),
            )
            override val format: Format = Format.AAC
            override val size: Long = 1000L
            override val durationMs: Long = 180_000L
            override val bitrateKbps: Int = 256
            override val sampleRateHz: Int = 44100
            override val replayGainAdjustment: ReplayGainAdjustment =
                ReplayGainAdjustment(null, null)
            override val modifiedMs: Long = addedMs
            override val addedMs: Long = addedMs
            override val cover: Cover? = null
            override val album: Album
                get() = throw UnsupportedOperationException()
            override val artists: List<Artist> = emptyList()
            override val genres: List<Genre> = emptyList()
        }
    }

    @Test
    fun `filterActiveDownloads keeps active statuses and orders downloading before queued`() {
        val items = listOf(
            DownloadItem(
                trackId = "1",
                title = "Completed Track",
                artist = "Artist A",
                album = "Album A",
                artworkUrl = null,
                durationMs = 1000L,
                progress = 100,
                status = DownloadStatus.COMPLETED,
            ),
            DownloadItem(
                trackId = "2",
                title = "Queued Track",
                artist = "Artist B",
                album = "Album B",
                artworkUrl = null,
                durationMs = 1000L,
                progress = 0,
                status = DownloadStatus.QUEUED,
            ),
            DownloadItem(
                trackId = "3",
                title = "Downloading Track",
                artist = "Artist C",
                album = "Album C",
                artworkUrl = null,
                durationMs = 1000L,
                progress = 55,
                status = DownloadStatus.DOWNLOADING,
            ),
            DownloadItem(
                trackId = "4",
                title = "Cancelled Track",
                artist = "Artist D",
                album = "Album D",
                artworkUrl = null,
                durationMs = 1000L,
                progress = 10,
                status = DownloadStatus.CANCELLED,
            ),
        )

        val active = RecentDownloadsViewModel.filterActiveDownloads(items)
        assertEquals(2, active.size)
        assertEquals("3", active[0].trackId)
        assertEquals("2", active[1].trackId)
    }

    @Test
    fun `filterAndSortDownloadedSongs filters Sounder tracks and sorts newest to oldest`() {
        val oldDownload = createFakeSong("Old Song", "Music/Sounder/Artist/Album/Old.m4a", 1000L)
        val newDownload = createFakeSong("New Song", "Music/Sounder/Artist/Album/New.m4a", 5000L)
        val midDownload = createFakeSong("Mid Song", "Music/Sounder/Artist/Album/Mid.m4a", 3000L)
        val nonSounderLocal = createFakeSong("Local Song", "Music/OtherFolder/Local.mp3", 9000L)

        val sorted = RecentDownloadsViewModel.filterAndSortDownloadedSongs(
            listOf(oldDownload, newDownload, midDownload, nonSounderLocal)
        )

        assertEquals(listOf(newDownload, midDownload, oldDownload), sorted)
    }

    @Test
    fun `combineEntries places active downloads at top followed by completed songs`() {
        val activeItem = DownloadItem(
            trackId = "active_1",
            title = "Active Song",
            artist = "Artist",
            album = null,
            artworkUrl = null,
            durationMs = 120_000L,
            progress = 42,
            status = DownloadStatus.DOWNLOADING,
        )
        val completedSong = createFakeSong("Completed Song", "Music/Sounder/Artist/Album/Done.m4a", 5000L)

        val combined = RecentDownloadsViewModel.combineEntries(
            listOf(activeItem),
            listOf(completedSong),
        )

        assertEquals(2, combined.size)
        assertTrue(combined[0] is RecentDownloadEntry.Active)
        assertTrue(combined[1] is RecentDownloadEntry.Completed)
    }
}
