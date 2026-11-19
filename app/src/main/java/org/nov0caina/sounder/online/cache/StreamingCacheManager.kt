package org.nov0caina.sounder.online.cache

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.db.CachedTrackDao
import org.nov0caina.sounder.online.db.CachedTrackEntity
import org.nov0caina.sounder.online.innertube.InnerTubeParser
import org.nov0caina.sounder.online.lyrics.LrcLibClient
import org.nov0caina.sounder.online.lyrics.LrcParser
import org.nov0caina.sounder.online.lyrics.LyricsSanitizer
import org.nov0caina.sounder.online.model.LyricsResult
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import timber.log.Timber as L

/**
 * Coordinates Sounder's rotating streaming disk cache (Media3 [Cache] + [RotatingCacheEvictor])
 * together with integral companion metadata (artwork, synchronized lyrics, and navigation IDs).
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamingCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: Cache,
    private val evictor: RotatingCacheEvictor,
    private val cachedTrackDao: CachedTrackDao,
    private val lrcLibClient: LrcLibClient,
    private val onlineSettings: OnlineSettings,
) : OnlineSettings.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coversDir = File(context.cacheDir, COVERS_DIR_NAME).apply { mkdirs() }
    private val lyricsDir = File(context.cacheDir, LYRICS_DIR_NAME).apply { mkdirs() }
    private val artistByKey = ConcurrentHashMap<String, String>()
    private val cacheRevision = MutableStateFlow(0L)

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    init {
        staticCoversDir = coversDir
        evictor.artistForKeyProvider = { key -> artistByKey[key] }
        evictor.onSpanEvictedListener = { cacheRef, key ->
            handleSpanEvicted(cacheRef, key)
        }
        onlineSettings.registerListener(this)
        scope.launch {
            preloadArtistIndex()
            refreshCacheMetrics()
        }
    }

    override fun onStreamingCacheSizeChanged() {
        scope.launch {
            evictor.evictToLimit(cache)
            reconcileWithDiskCache()
            refreshCacheMetrics()
        }
    }

    /**
     * Observes cached tracks that currently have playable audio data resident in the disk cache.
     */
    fun observePlayableCachedTracks(): Flow<List<CachedTrackEntity>> =
        combine(cachedTrackDao.observeAll(), cacheRevision) { entities, _ ->
            entities.filter { isTrackFullyCached(it.videoId) }
        }

    /**
     * Returns all cached tracks currently playable offline from disk cache.
     */
    suspend fun getPlayableCachedTracks(): List<CachedTrackEntity> = withContext(Dispatchers.IO) {
        cachedTrackDao.getAll().filter { isTrackFullyCached(it.videoId) }
    }

    /**
     * Returns true when the complete audio stream for [videoId] is stored in [cache].
     */
    fun isTrackFullyCached(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val key = cacheKeyFor(videoId)
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (contentLength <= 0L) return false
        return cache.isCached(key, 0L, contentLength)
    }

    /**
     * Returns true when at least some audio chunks for [videoId] exist in [cache].
     */
    fun hasAnyCachedBytes(videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val key = cacheKeyFor(videoId)
        return cache.getCachedSpans(key).isNotEmpty()
    }

    /**
     * Returns the number of cached audio bytes for [videoId].
     */
    fun getCachedAudioBytes(videoId: String): Long {
        if (videoId.isBlank()) return 0L
        val key = cacheKeyFor(videoId)
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (contentLength > 0L) {
            return cache.getCachedBytes(key, 0L, contentLength)
        }
        return cache.getCachedSpans(key).sumOf { it.length }
    }

    /**
     * Returns total disk space consumed by audio cache + cached covers + cached lyrics.
     */
    fun computeTotalCacheSizeBytes(): Long {
        val audioBytes = try {
            cache.cacheSpace
        } catch (e: Exception) {
            L.w(e, "Failed reading SimpleCache space")
            0L
        }
        val coversBytes = coversDir.listFiles()?.sumOf { it.length() } ?: 0L
        val lyricsBytes = lyricsDir.listFiles()?.sumOf { it.length() } ?: 0L
        return (audioBytes + coversBytes + lyricsBytes).coerceAtLeast(0L)
    }

    fun refreshCacheMetrics() {
        _cacheSizeBytes.value = computeTotalCacheSizeBytes()
        cacheRevision.value = System.nanoTime()
    }

    /**
     * Records an online track being streamed/played so its metadata, navigation browse IDs,
     * cover artwork, and synchronized lyrics are cached alongside the audio stream.
     */
    fun recordTrackPlayback(track: OnlineTrack, audioExtension: String? = null) {
        if (track.id.isBlank()) return
        val key = cacheKeyFor(track.id)
        if (track.artist.isNotBlank()) {
            artistByKey[key] = track.artist
        }
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = cachedTrackDao.getById(track.id)
                val coverFile = coverFileFor(track.id)
                val lyricsFile = lyricsFileFor(track.id)

                val entity = CachedTrackEntity(
                    videoId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album?.takeIf { it.isNotBlank() } ?: existing?.album,
                    durationMs = if (track.durationMs > 0L) track.durationMs else (existing?.durationMs ?: 0L),
                    artworkUrl = track.artworkUrl?.takeIf { it.isNotBlank() } ?: existing?.artworkUrl,
                    localCoverPath = coverFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
                        ?: existing?.localCoverPath,
                    localLyricsPath = lyricsFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
                        ?: existing?.localLyricsPath,
                    artistId = track.artistId?.takeIf { it.isNotBlank() } ?: existing?.artistId,
                    albumId = track.albumId?.takeIf { it.isNotBlank() } ?: existing?.albumId,
                    trackNumber = track.trackNumber ?: existing?.trackNumber,
                    year = track.year ?: existing?.year,
                    audioExtension = audioExtension ?: existing?.audioExtension ?: "opus",
                    cachedAtMs = existing?.cachedAtMs ?: now,
                    lastPlayedMs = now,
                    playCount = (existing?.playCount ?: 0) + 1
                )
                cachedTrackDao.upsert(entity)
                refreshCacheMetrics()

                // Cache companion artwork and lyrics asynchronously in the background
                launch { cacheCoverIfNeeded(entity) }
                launch { cacheLyricsIfNeeded(entity) }
            } catch (e: Exception) {
                L.w(e, "Failed recording cached track playback for %s", track.id)
            }
        }
    }

    /**
     * Updates the audio container extension detected for a resolved stream URL.
     */
    fun recordStreamExtension(videoId: String, streamUrl: String) {
        if (videoId.isBlank()) return
        val ext = if (streamUrl.contains("itag=140") || streamUrl.contains("itag=18") || streamUrl.contains("mp4")) {
            "m4a"
        } else {
            "opus"
        }
        scope.launch {
            try {
                cachedTrackDao.updateAudioExtension(videoId, ext)
            } catch (e: Exception) {
                L.w(e, "Failed updating audio extension for %s", videoId)
            }
        }
    }

    suspend fun getCachedTrackEntity(videoId: String): CachedTrackEntity? = withContext(Dispatchers.IO) {
        cachedTrackDao.getById(videoId)
    }

    fun getCachedCoverFile(videoId: String): File? {
        if (videoId.isBlank()) return null
        val file = coverFileFor(videoId)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    fun getCachedCoverBytes(videoId: String): ByteArray? =
        getCachedCoverFile(videoId)?.let { file ->
            try {
                file.readBytes()
            } catch (e: Exception) {
                L.w(e, "Failed reading cached cover for %s", videoId)
                null
            }
        }

    fun getCachedLyricsRaw(videoId: String): String? {
        if (videoId.isBlank()) return null
        val file = lyricsFileFor(videoId)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.readText().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            L.w(e, "Failed reading cached lyrics for %s", videoId)
            null
        }
    }

    fun getCachedLyrics(videoId: String): LyricsResult? {
        val raw = getCachedLyricsRaw(videoId) ?: return null
        val synced = LrcParser.parse(raw)
        return if (synced.isNotEmpty()) {
            LyricsResult(syncedLyrics = synced)
        } else {
            LyricsResult(plainLyrics = raw)
        }
    }

    /**
     * Builds an [OnlineSong] backed by cached metadata and a `sounder://stream/<videoId>` URI
     * so playback resolves straight from [SimpleCache] without any network calls.
     */
    fun toCachedOnlineSong(entity: CachedTrackEntity): OnlineSong {
        val track = entity.toOnlineTrack()
        return OnlineSong(track, Uri.parse("sounder://stream/${entity.videoId}"))
    }

    /**
     * Copies a completely cached audio stream directly from [cache] into [destFile] using a
     * cache-only [CacheDataSource] (zero upstream network requests).
     *
     * @return true if the full audio file was copied from cache, false if not completely cached.
     */
    fun copyCachedAudioToFile(videoId: String, destFile: File): Boolean {
        if (!isTrackFullyCached(videoId)) return false
        val key = cacheKeyFor(videoId)
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (contentLength <= 0L) return false

        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse("sounder://stream/$videoId"))
            .setKey(key)
            .setPosition(0L)
            .setLength(contentLength)
            .build()

        return try {
            val openedLength = dataSource.open(dataSpec)
            val expectedLength = if (openedLength != C.LENGTH_UNSET.toLong() && openedLength > 0L) {
                openedLength
            } else {
                contentLength
            }
            var totalCopied = 0L
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                    output.write(buffer, 0, read)
                    totalCopied += read
                }
            }
            val success = totalCopied >= expectedLength && totalCopied > 0L
            if (!success) {
                destFile.delete()
            }
            success
        } catch (e: Exception) {
            L.w(e, "Failed copying cached audio for %s to %s", videoId, destFile.absolutePath)
            destFile.delete()
            false
        } finally {
            try {
                dataSource.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Removes a single track from the rotating streaming cache (e.g. after promoting it to
     * a permanent download in `/Music/Sounder/`), freeing cache space for fresh variety.
     */
    suspend fun removeCachedTrack(videoId: String) = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext
        val key = cacheKeyFor(videoId)
        try {
            cache.removeResource(key)
        } catch (e: Exception) {
            L.w(e, "Failed removing cache resource for %s", key)
        }
        artistByKey.remove(key)
        coverFileFor(videoId).delete()
        lyricsFileFor(videoId).delete()
        cachedTrackDao.deleteById(videoId)
        refreshCacheMetrics()
    }

    /**
     * Purges all cached streaming audio, cover art, lyrics, and registry metadata without
     * touching any permanently downloaded tracks in `/Music/Sounder/`.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            val keys = cache.keys.toList()
            for (key in keys) {
                try {
                    cache.removeResource(key)
                } catch (e: Exception) {
                    L.w(e, "Failed removing cached resource %s", key)
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed iterating streaming cache keys during clear")
        }
        artistByKey.clear()
        coversDir.listFiles()?.forEach { it.delete() }
        lyricsDir.listFiles()?.forEach { it.delete() }
        cachedTrackDao.deleteAll()
        refreshCacheMetrics()
    }

    private suspend fun preloadArtistIndex() {
        try {
            val all = cachedTrackDao.getAll()
            for (entity in all) {
                if (entity.artist.isNotBlank()) {
                    artistByKey[cacheKeyFor(entity.videoId)] = entity.artist
                }
            }
            reconcileWithDiskCache()
        } catch (e: Exception) {
            L.w(e, "Failed preloading cached track artist index")
        }
    }

    private suspend fun reconcileWithDiskCache() {
        try {
            val all = cachedTrackDao.getAll()
            for (entity in all) {
                if (!hasAnyCachedBytes(entity.videoId)) {
                    // Clean up stale metadata if OS cleared cacheDir or evictor purged audio
                    val key = cacheKeyFor(entity.videoId)
                    artistByKey.remove(key)
                    coverFileFor(entity.videoId).delete()
                    lyricsFileFor(entity.videoId).delete()
                    cachedTrackDao.deleteById(entity.videoId)
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed reconciling cached tracks with disk cache")
        }
    }

    private fun handleSpanEvicted(cacheRef: Cache, key: String) {
        val videoId = videoIdFromCacheKey(key) ?: return
        val remainingSpans = try {
            cacheRef.getCachedSpans(key)
        } catch (_: Exception) {
            emptySet()
        }
        if (remainingSpans.isEmpty()) {
            artistByKey.remove(key)
            scope.launch {
                try {
                    coverFileFor(videoId).delete()
                    lyricsFileFor(videoId).delete()
                    cachedTrackDao.deleteById(videoId)
                    refreshCacheMetrics()
                } catch (e: Exception) {
                    L.w(e, "Failed cleaning metadata for evicted key %s", key)
                }
            }
        } else {
            scope.launch { refreshCacheMetrics() }
        }
    }

    private suspend fun cacheCoverIfNeeded(entity: CachedTrackEntity) {
        val coverFile = coverFileFor(entity.videoId)
        if (coverFile.exists() && coverFile.length() > 0L) {
            if (entity.localCoverPath != coverFile.absolutePath) {
                cachedTrackDao.updateLocalCoverPath(entity.videoId, coverFile.absolutePath)
            }
            return
        }
        val rawUrl = entity.artworkUrl ?: return
        val upgradedUrl = InnerTubeParser.upgradeArtworkResolution(rawUrl)
        try {
            val req = Request.Builder()
                .url(upgradedUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val bytes = res.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        coverFile.writeBytes(bytes)
                        cachedTrackDao.updateLocalCoverPath(entity.videoId, coverFile.absolutePath)
                        refreshCacheMetrics()
                    }
                }
            }
        } catch (e: Exception) {
            L.d(e, "Skipped caching cover for %s", entity.videoId)
        }
    }

    private suspend fun cacheLyricsIfNeeded(entity: CachedTrackEntity) {
        val lyricsFile = lyricsFileFor(entity.videoId)
        if (lyricsFile.exists() && lyricsFile.length() > 0L) {
            if (entity.localLyricsPath != lyricsFile.absolutePath) {
                cachedTrackDao.updateLocalLyricsPath(entity.videoId, lyricsFile.absolutePath)
            }
            return
        }
        try {
            val cleanTitle = LyricsSanitizer.cleanTitle(entity.title, entity.artist)
            val cleanArtist = LyricsSanitizer.cleanArtist(entity.artist)
            val durationSec = if (entity.durationMs > 0L) (entity.durationMs / 1000L).toInt() else null
            val result = lrcLibClient.getLyrics(
                trackName = cleanTitle,
                artistName = cleanArtist,
                albumName = entity.album,
                durationSeconds = durationSec
            )
            val content = when {
                result.hasSyncedLyrics -> result.syncedLyrics.joinToString("\n") { line ->
                    val minutes = line.timeMs / 60_000
                    val seconds = (line.timeMs % 60_000) / 1000
                    val hundredths = (line.timeMs % 1000) / 10
                    String.format("[%02d:%02d.%02d] %s", minutes, seconds, hundredths, line.text)
                }
                result.hasPlainLyrics -> result.plainLyrics.orEmpty()
                else -> ""
            }
            if (content.isNotBlank()) {
                lyricsFile.writeText(content)
                cachedTrackDao.updateLocalLyricsPath(entity.videoId, lyricsFile.absolutePath)
                refreshCacheMetrics()
            }
        } catch (e: Exception) {
            L.d(e, "Skipped caching lyrics for %s", entity.videoId)
        }
    }

    private fun coverFileFor(videoId: String): File =
        File(coversDir, "${sanitizeKey(videoId)}.jpg")

    private fun lyricsFileFor(videoId: String): File =
        File(lyricsDir, "${sanitizeKey(videoId)}.lrc")

    companion object {
        const val CACHE_DIR_NAME = "streaming_cache"
        const val COVERS_DIR_NAME = "streaming_cache_covers"
        const val LYRICS_DIR_NAME = "streaming_cache_lyrics"
        private const val KEY_PREFIX = "stream_"

        @Volatile
        private var staticCoversDir: File? = null

        fun cacheKeyFor(videoId: String): String = "$KEY_PREFIX$videoId"

        fun videoIdFromCacheKey(key: String?): String? {
            if (key.isNullOrBlank() || !key.startsWith(KEY_PREFIX)) return null
            return key.removePrefix(KEY_PREFIX).takeIf { it.isNotBlank() }
        }

        fun findCachedCoverFile(videoId: String): File? {
            val dir = staticCoversDir ?: return null
            if (videoId.isBlank()) return null
            val file = File(dir, "${sanitizeKey(videoId)}.jpg")
            return file.takeIf { it.exists() && it.length() > 0L }
        }

        /**
         * Detects container extension (`m4a` vs `opus`) from file header magic bytes.
         */
        fun detectAudioExtension(file: File, fallback: String = "opus"): String {
            if (!file.exists() || file.length() < 12) return fallback
            return try {
                val header = ByteArray(12)
                file.inputStream().use { it.read(header) }
                val isMp4 = header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()
                if (isMp4) "m4a" else "opus"
            } catch (_: Exception) {
                fallback
            }
        }

        private fun sanitizeKey(raw: String): String =
            raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }
}
