package org.nov0caina.sounder.image.coil

import android.content.Context
import android.net.Uri
import coil3.size.Size
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.model.OnlineCover
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

class GridCompositionTest {

    private val fakeVolume = object : Volume.Internal {
        override val mediaStoreName: String = "external_primary"
        override val components: Components = Components.root()
        override fun resolveName(context: Context): String = "Internal"
    }

    private fun fakeCover(id: String): Cover = object : Cover {
        override val id: String = id
        override suspend fun open(): InputStream? = null
        override fun equals(other: Any?): Boolean = other is Cover && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    private fun fakeSong(title: String, cover: Cover?): Song = object : Song {
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
        override val replayGainAdjustment: ReplayGainAdjustment = ReplayGainAdjustment(null, null)
        override val modifiedMs: Long = 1000L
        override val addedMs: Long = 1000L
        override val cover: Cover? = cover
        override val album: Album
            get() = throw UnsupportedOperationException()
        override val artists: List<Artist> = emptyList()
        override val genres: List<Genre> = emptyList()
    }

    @Test
    fun extractOrderedDistinctCovers_preservesTrackOrderAndDeduplicatesSameCoverOrArtworkUrl() {
        val songs = listOf(
            fakeSong("s1", fakeCover("album_A")),
            fakeSong("s2", fakeCover("album_A")),
            fakeSong("s3", null),
            fakeSong("s4", OnlineCover("https://cdn.example/art1.jpg", "online_cover_1")),
            fakeSong("s5", OnlineCover("https://cdn.example/art1.jpg", "online_cover_2")),
            fakeSong("s6", fakeCover("album_C")),
            fakeSong("s7", fakeCover("album_D")),
            fakeSong("s8", fakeCover("album_E")),
        )

        val extracted = extractOrderedDistinctCovers(songs)
        assertEquals(
            listOf("album_A", "online_cover_1", "album_C", "album_D"),
            extracted.map { it.id }
        )

        // Reordering songs updates cover order accordingly
        val reordered = listOf(songs[5], songs[0], songs[3], songs[6])
        val extractedReordered = extractOrderedDistinctCovers(reordered)
        assertEquals(
            listOf("album_C", "album_A", "online_cover_1", "album_D"),
            extractedReordered.map { it.id }
        )
    }

    @Test
    fun computeGridCellBounds_producesAdaptiveGeometryWithGap() {
        val size = 100f
        val gap = 4f

        val one = computeGridCellBounds(1, size, gap)
        assertEquals(1, one.size)
        assertEquals(GridCellBounds(0f, 0f, 100f, 100f), one[0])

        val two = computeGridCellBounds(2, size, gap)
        assertEquals(2, two.size)
        assertEquals(GridCellBounds(0f, 0f, 48f, 100f), two[0])
        assertEquals(GridCellBounds(52f, 0f, 100f, 100f), two[1])

        val three = computeGridCellBounds(3, size, gap)
        assertEquals(3, three.size)
        assertEquals(GridCellBounds(0f, 0f, 48f, 100f), three[0])
        assertEquals(GridCellBounds(52f, 0f, 100f, 48f), three[1])
        assertEquals(GridCellBounds(52f, 52f, 100f, 100f), three[2])

        val four = computeGridCellBounds(4, size, gap)
        assertEquals(4, four.size)
        assertEquals(GridCellBounds(0f, 0f, 48f, 48f), four[0])
        assertEquals(GridCellBounds(52f, 0f, 100f, 48f), four[1])
        assertEquals(GridCellBounds(0f, 52f, 48f, 100f), four[2])
        assertEquals(GridCellBounds(52f, 52f, 100f, 100f), four[3])
        assertTrue(four[1].left - four[0].right == gap)
        assertTrue(four[2].top - four[0].bottom == gap)
    }

    @Test
    fun keyer_distinguishesCoverOrdering() {
        val c1 = fakeCover("c1")
        val c2 = fakeCover("c2")
        val c3 = fakeCover("c3")
        val c4 = fakeCover("c4")

        val dataA = GridCoverComposition(listOf(c1, c2, c3, c4), 3f, 0)
        val dataB = GridCoverComposition(listOf(c2, c1, c3, c4), 3f, 0)
        val dataASame = GridCoverComposition(listOf(c1, c2, c3, c4), 3f, 0)

        val size = Size(256, 256)
        assertEquals(
            GridCompositionFetcher.buildCacheKey(dataA, size),
            GridCompositionFetcher.buildCacheKey(dataASame, size)
        )
        assertNotEquals(
            GridCompositionFetcher.buildCacheKey(dataA, size),
            GridCompositionFetcher.buildCacheKey(dataB, size)
        )
    }
}
