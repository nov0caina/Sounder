package org.nov0caina.sounder.online.download

import java.io.File
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
import org.nov0caina.sounder.online.cache.StreamingCacheManager

class CacheDownloadPromoterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `fully cached track promotes directly from disk with zero HTTP requests`() = runBlocking {
        val tempDir = tempFolder.newFolder("dl_cache_hit")
        val cachedPayload = byteArrayOf(
            0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(),
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        )
        val networkCalls = AtomicInteger(0)

        val result = CacheDownloadPromoter.acquireAudio(
            trackId = "cached_vid",
            tempDir = tempDir,
            baseFileName = "01 - Cached Song",
            isFullyCached = { it == "cached_vid" },
            copyFromCache = { _, dest ->
                dest.writeBytes(cachedPayload)
                true
            },
            detectExtension = { file -> StreamingCacheManager.detectAudioExtension(file) },
            networkDownload = {
                networkCalls.incrementAndGet()
                throw AssertionError("Network must not be called for fully cached track")
            },
        )

        assertNotNull(result)
        assertTrue(result!!.promotedFromCache)
        assertEquals("opus", result.audioExtension)
        assertEquals(0, networkCalls.get())
        assertTrue(result.tempFile.exists())
        assertArrayEquals(cachedPayload, result.tempFile.readBytes())
    }

    @Test
    fun `uncached or partially cached track falls back to HTTPS network download`() = runBlocking {
        val tempDir = tempFolder.newFolder("dl_cache_miss")
        val remotePayload = "REMOTE_HTTPS_STREAM_BYTES".toByteArray()
        val networkCalls = AtomicInteger(0)

        val result = CacheDownloadPromoter.acquireAudio(
            trackId = "uncached_vid",
            tempDir = tempDir,
            baseFileName = "02 - Remote Song",
            isFullyCached = { false },
            copyFromCache = { _, _ -> false },
            detectExtension = { "opus" },
            networkDownload = {
                networkCalls.incrementAndGet()
                val file = File(tempDir, "02 - Remote Song.m4a.tmp").apply {
                    writeBytes(remotePayload)
                }
                ResolvedDownloadAudio(
                    tempFile = file,
                    audioExtension = "m4a",
                    promotedFromCache = false,
                )
            },
        )

        assertNotNull(result)
        assertFalse(result!!.promotedFromCache)
        assertEquals("m4a", result.audioExtension)
        assertEquals(1, networkCalls.get())
        assertArrayEquals(remotePayload, result.tempFile.readBytes())
    }
}
