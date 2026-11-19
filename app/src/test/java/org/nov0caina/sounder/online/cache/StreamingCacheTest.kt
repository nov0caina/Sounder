package org.nov0caina.sounder.online.cache

import android.net.TestUri
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.File
import java.io.IOException
import java.util.NavigableSet
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nov0caina.sounder.online.lyrics.LrcParser
import org.nov0caina.sounder.online.model.OnlineCover

@OptIn(UnstableApi::class)
class StreamingCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `RotatingCacheEvictor evicts LRU spans and respects dynamic limit reduction`() {
        var limitBytes = 200L
        val evictedKeys = mutableListOf<String>()
        val evictor = RotatingCacheEvictor(maxBytesProvider = { limitBytes })
        evictor.onSpanEvictedListener = { _, key -> evictedKeys.add(key) }

        val cache = InMemoryTestCache(tempFolder.newFolder("cache_lru"), evictor)
        cache.putBytes("stream_a", ByteArray(80) { 1 }, timestamp = 100L)
        cache.putBytes("stream_b", ByteArray(80) { 2 }, timestamp = 200L)
        assertEquals(160L, cache.cacheSpace)

        // Adding 80 bytes exceeds 200 -> evicts oldest ("stream_a")
        cache.putBytes("stream_c", ByteArray(80) { 3 }, timestamp = 300L)
        assertFalse(cache.isCached("stream_a", 0, 80))
        assertTrue(cache.isCached("stream_b", 0, 80))
        assertTrue(cache.isCached("stream_c", 0, 80))
        assertTrue("stream_a" in evictedKeys)

        // Lowering limit dynamically to 100 bytes and calling evictToLimit evicts "stream_b"
        limitBytes = 100L
        evictor.evictToLimit(cache)
        assertFalse(cache.isCached("stream_b", 0, 80))
        assertTrue(cache.isCached("stream_c", 0, 80))
        assertEquals(80L, cache.cacheSpace)
    }

    @Test
    fun `RotatingCacheEvictor preserves artist variety using soft per-artist cap`() {
        val artists = mapOf(
            "stream_other" to "Artist Other",
            "stream_a1" to "Artist Mono",
            "stream_a2" to "Artist Mono",
            "stream_a3" to "Artist Mono",
            "stream_a4" to "Artist Mono",
        )
        val evictor = RotatingCacheEvictor(
            maxBytesProvider = { 400L },
            maxTracksPerArtistSoftCap = 2,
        )
        evictor.artistForKeyProvider = { key -> artists[key] }

        val cache = InMemoryTestCache(tempFolder.newFolder("cache_variety"), evictor)
        // Oldest overall is Artist Other, followed by 3 tracks of Artist Mono (exceeds cap 2)
        cache.putBytes("stream_other", ByteArray(100) { 1 }, timestamp = 10L)
        cache.putBytes("stream_a1", ByteArray(100) { 2 }, timestamp = 20L)
        cache.putBytes("stream_a2", ByteArray(100) { 3 }, timestamp = 30L)
        cache.putBytes("stream_a3", ByteArray(100) { 4 }, timestamp = 40L)

        // Adding 4th track of Artist Mono forces eviction; over-represented Artist Mono's oldest (a1)
        // should be evicted instead of Artist Other to preserve variety
        cache.putBytes("stream_a4", ByteArray(100) { 5 }, timestamp = 50L)

        assertTrue("Artist Other should remain cached for variety", cache.isCached("stream_other", 0, 100))
        assertFalse("Oldest excess track of Artist Mono should be evicted", cache.isCached("stream_a1", 0, 100))
        assertTrue(cache.isCached("stream_a4", 0, 100))
    }

    @Test
    fun `RoutingCacheDataSource serves cached stream offline with zero upstream network calls`() {
        val evictor = RotatingCacheEvictor(maxBytesProvider = { 10L * 1024L * 1024L })
        val cache = InMemoryTestCache(tempFolder.newFolder("cache_routing"), evictor)

        val payload = "OPUS_AUDIO_BYTES_1234567890_SOUNDER_STREAM".toByteArray()
        val upstreamCalls = AtomicInteger(0)
        var networkAvailable = true

        val upstreamFactory = DataSource.Factory {
            FakeByteDataSource { spec ->
                upstreamCalls.incrementAndGet()
                if (!networkAvailable) {
                    throw IOException("Network unavailable")
                }
                payload
            }
        }
        val directFactory = DataSource.Factory {
            FakeByteDataSource { payload }
        }
        val cacheDataSourceFactory = DataSource.Factory {
            TestCacheBackedDataSource(cache, upstreamFactory.createDataSource())
        }

        val routingFactory = RoutingCacheDataSourceFactory(
            directDataSourceFactory = directFactory,
            cacheDataSourceFactory = cacheDataSourceFactory,
        )

        // 1. Online read populates cache under stable key "stream_vid123"
        val firstRead = readAll(
            routingFactory.createDataSource(),
            DataSpec.Builder()
                .setUri(stubUri("sounder://stream/vid123"))
                .build()
        )
        assertArrayEquals(payload, firstRead)
        assertEquals(1, upstreamCalls.get())
        assertTrue(cache.isCached("stream_vid123", 0, payload.size.toLong()))

        // 2. Simulate complete network loss & different URI; second read must succeed with ZERO upstream calls
        networkAvailable = false
        val secondRead = readAll(
            routingFactory.createDataSource(),
            DataSpec.Builder()
                .setUri(stubUri("https://music.youtube.com/watch?v=vid123"))
                .build()
        )
        assertArrayEquals(payload, secondRead)
        assertEquals("Upstream must not be called on cache hit", 1, upstreamCalls.get())
    }

    @Test
    fun `RoutingCacheDataSource bypasses cache for permanently downloaded tracks`() {
        val evictor = RotatingCacheEvictor(maxBytesProvider = { 10L * 1024L * 1024L })
        val cache = InMemoryTestCache(tempFolder.newFolder("cache_bypass"), evictor)

        val localFile = tempFolder.newFile("downloaded_song.opus").apply {
            writeBytes("LOCAL_DOWNLOADED_AUDIO".toByteArray())
        }
        val directCalls = AtomicInteger(0)
        val upstreamCalls = AtomicInteger(0)

        val routingFactory = RoutingCacheDataSourceFactory(
            directDataSourceFactory = DataSource.Factory {
                FakeByteDataSource {
                    directCalls.incrementAndGet()
                    localFile.readBytes()
                }
            },
            cacheDataSourceFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(DataSource.Factory {
                    FakeByteDataSource {
                        upstreamCalls.incrementAndGet()
                        "REMOTE".toByteArray()
                    }
                })
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache)),
            downloadedFileProvider = { videoId ->
                if (videoId == "dl_track") localFile else null
            },
            fileUriProvider = { file -> stubUri("file://${file.absolutePath}") },
        )

        val read = readAll(
            routingFactory.createDataSource(),
            DataSpec.Builder()
                .setUri(stubUri("sounder://stream/dl_track"))
                .setKey(StreamingCacheManager.cacheKeyFor("dl_track"))
                .build()
        )
        assertArrayEquals("LOCAL_DOWNLOADED_AUDIO".toByteArray(), read)
        assertEquals(1, directCalls.get())
        assertEquals(0, upstreamCalls.get())
        assertEquals(0L, cache.cacheSpace)
    }

    @Test
    fun `OnlineCover and LrcParser serve cached artwork and lyrics with zero network`() = runBlocking {
        val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val coverFile = tempFolder.newFile("cover_vid1.jpg").apply { writeBytes(coverBytes) }

        val cover = OnlineCover(
            artworkUrl = null,
            id = "online_cover_vid1",
            trackId = "vid1",
            localCoverPath = coverFile.absolutePath,
        )
        val opened = cover.open()
        assertNotNull("Cached cover must open locally without network URL", opened)
        assertArrayEquals(coverBytes, opened!!.readBytes())

        val lrcFile = tempFolder.newFile("lyrics_vid1.lrc").apply {
            writeText("[00:12.50] First cached line\n[00:25.00] Second cached line")
        }
        val parsed = LrcParser.parse(lrcFile.readText())
        assertEquals(2, parsed.size)
        assertEquals(12_500L, parsed[0].timeMs)
        assertEquals("First cached line", parsed[0].text)
        assertEquals(25_000L, parsed[1].timeMs)
        assertEquals("Second cached line", parsed[1].text)
    }

    @Test
    fun `detectAudioExtension identifies mp4 vs opus from magic bytes`() {
        val mp4File = tempFolder.newFile("sample.tmp").apply {
            writeBytes(byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'M'.code.toByte(), '4'.code.toByte(), 'A'.code.toByte(), ' '.code.toByte()))
        }
        val webmFile = tempFolder.newFile("sample2.tmp").apply {
            writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))
        }
        assertEquals("m4a", StreamingCacheManager.detectAudioExtension(mp4File))
        assertEquals("opus", StreamingCacheManager.detectAudioExtension(webmFile))
    }

    private fun stubUri(raw: String): Uri = TestUri.parse(raw)

    private fun readAll(dataSource: DataSource, spec: DataSpec): ByteArray {
        return try {
            dataSource.open(spec)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = dataSource.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            dataSource.close()
        }
    }

    private class TestCacheBackedDataSource(
        private val cache: InMemoryTestCache,
        private val upstream: DataSource,
    ) : BaseDataSource(false) {
        private var data: ByteArray = ByteArray(0)
        private var pos: Int = 0
        private var remaining: Int = 0
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            val key = dataSpec.key ?: dataSpec.uri.toString()
            val spans = cache.getCachedSpans(key)
            if (spans.isNotEmpty() && spans.first().file != null) {
                data = spans.first().file!!.readBytes()
            } else {
                upstream.open(dataSpec)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1024)
                while (true) {
                    val n = upstream.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT || n <= 0) break
                    out.write(buf, 0, n)
                }
                upstream.close()
                data = out.toByteArray()
                cache.putBytes(key, data, System.currentTimeMillis())
            }
            pos = 0
            remaining = data.size
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length, remaining)
            System.arraycopy(data, pos, buffer, offset, toRead)
            pos += toRead
            remaining -= toRead
            return toRead
        }

        override fun getUri(): Uri? = uri
        override fun close() {
            uri = null
        }
    }

    private class FakeByteDataSource(
        private val provider: (DataSpec) -> ByteArray,
    ) : BaseDataSource(false) {
        private var data: ByteArray = ByteArray(0)
        private var pos: Int = 0
        private var remaining: Int = 0
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            data = provider(dataSpec)
            pos = dataSpec.position.toInt().coerceAtMost(data.size)
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length.toInt(), data.size - pos)
            } else {
                data.size - pos
            }
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            val toRead = minOf(length, remaining)
            System.arraycopy(data, pos, buffer, offset, toRead)
            pos += toRead
            remaining -= toRead
            return toRead
        }

        override fun getUri(): Uri? = uri
        override fun close() {
            uri = null
        }
    }

    /**
     * Pure-JVM Media3 [Cache] implementation backed by temporary files so real [CacheDataSource],
     * [CacheDataSink], and [RotatingCacheEvictor] execute deterministically in unit tests.
     */
    internal class InMemoryTestCache(
        private val dir: File,
        private val evictor: RotatingCacheEvictor,
    ) : Cache {
        private val spansByKey = HashMap<String, TreeSet<CacheSpan>>()
        private val metadataByKey = HashMap<String, MutableMap<String, Any>>()
        private var totalSpace = 0L
        private var nextId = 0

        fun putBytes(key: String, bytes: ByteArray, timestamp: Long) {
            val file = File(dir, "span_${nextId++}.v3.exo").apply { writeBytes(bytes) }
            val span = CacheSpan(key, 0L, bytes.size.toLong(), timestamp, file)
            spansByKey.getOrPut(key) { TreeSet() }.add(span)
            totalSpace += span.length
            applyContentMetadataMutations(
                key,
                ContentMetadataMutations().apply {
                    ContentMetadataMutations.setContentLength(this, bytes.size.toLong())
                }
            )
            evictor.onSpanAdded(this, span)
        }

        override fun getUid(): Long = 1L
        override fun release() {}
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> = getCachedSpans(key)
        override fun removeListener(key: String, listener: Cache.Listener) {}

        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
            TreeSet(spansByKey[key].orEmpty())

        override fun getKeys(): MutableSet<String> = HashSet(spansByKey.keys)
        override fun getCacheSpace(): Long = totalSpace

        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
            startReadWriteNonBlocking(key, position, length)!!

        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? {
            val existing = spansByKey[key]?.firstOrNull {
                position >= it.position && position < it.position + it.length
            }
            if (existing != null) return existing
            val holeLength = if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else length
            return CacheSpan(key, position, holeLength, C.TIME_UNSET, null)
        }

        override fun startFile(key: String, position: Long, length: Long): File {
            evictor.onStartFile(this, key, position, length)
            return File(dir, "write_${nextId++}_${key}_${position}.v3.exo")
        }

        override fun commitFile(file: File, length: Long) {
            val parts = file.name.removeSuffix(".v3.exo").split("_")
            val pos = parts.last().toLongOrNull() ?: 0L
            val key = parts.subList(2, parts.size - 1).joinToString("_")
            val span = CacheSpan(key, pos, length, System.currentTimeMillis(), file)
            spansByKey.getOrPut(key) { TreeSet() }.add(span)
            totalSpace += length
            evictor.onSpanAdded(this, span)
        }

        override fun releaseHoleSpan(holeSpan: CacheSpan) {}

        override fun removeResource(key: String) {
            val spans = spansByKey[key]?.toList().orEmpty()
            for (span in spans) {
                removeSpan(span)
            }
        }

        override fun removeSpan(span: CacheSpan) {
            val set = spansByKey[span.key] ?: return
            if (set.remove(span)) {
                totalSpace = (totalSpace - span.length).coerceAtLeast(0L)
                span.file?.delete()
                if (set.isEmpty()) {
                    spansByKey.remove(span.key)
                    metadataByKey.remove(span.key)
                }
                evictor.onSpanRemoved(this, span)
            }
        }

        override fun isCached(key: String, position: Long, length: Long): Boolean {
            return getCachedBytes(key, position, length) >= length
        }

        override fun getCachedLength(key: String, position: Long, length: Long): Long {
            val span = spansByKey[key]?.firstOrNull {
                position >= it.position && position < it.position + it.length
            } ?: return -length
            return minOf(length, (span.position + span.length) - position)
        }

        override fun getCachedBytes(key: String, position: Long, length: Long): Long {
            val spans = spansByKey[key] ?: return 0L
            val end = position + length
            var total = 0L
            for (span in spans) {
                val overlapStart = maxOf(position, span.position)
                val overlapEnd = minOf(end, span.position + span.length)
                if (overlapEnd > overlapStart) {
                    total += overlapEnd - overlapStart
                }
            }
            return total
        }

        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
            val map = metadataByKey.getOrPut(key) { HashMap() }
            for (removed in mutations.removedValues) {
                map.remove(removed)
            }
            for ((k, v) in mutations.editedValues) {
                map[k] = v
            }
        }

        override fun getContentMetadata(key: String): ContentMetadata {
            val map = metadataByKey[key] ?: emptyMap()
            return object : ContentMetadata {
                override fun get(name: String, defaultValue: String?): String? =
                    map[name] as? String ?: defaultValue

                override fun get(name: String, defaultValue: ByteArray?): ByteArray? =
                    map[name] as? ByteArray ?: defaultValue

                override fun get(name: String, defaultValue: Long): Long =
                    (map[name] as? Number)?.toLong() ?: defaultValue

                override fun contains(name: String): Boolean = map.containsKey(name)
            }
        }
    }
}
