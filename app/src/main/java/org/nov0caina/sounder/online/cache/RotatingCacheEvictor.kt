package org.nov0caina.sounder.online.cache

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Dynamic, variety-aware LRU [CacheEvictor] for Sounder's rotating streaming cache.
 *
 * Features:
 * - Reads the user's configured maximum size dynamically (100 MB - 700 MB) so changes in Settings
 *   apply immediately without restarting ExoPlayer.
 * - Applies a soft per-artist cap when evicting so heavy listening to a single artist/album does
 *   not monopolize the entire cache and starve playback variety.
 * - Notifies a listener whenever a cache key loses all its spans so companion metadata can stay in sync.
 */
@OptIn(UnstableApi::class)
class RotatingCacheEvictor(
    private val maxBytesProvider: () -> Long,
    private val maxTracksPerArtistSoftCap: Int = DEFAULT_MAX_TRACKS_PER_ARTIST,
) : CacheEvictor {

    private val lock = Any()
    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compareSpans)
    private var currentSize: Long = 0L

    @Volatile
    var artistForKeyProvider: ((String) -> String?)? = null

    @Volatile
    var onSpanEvictedListener: ((cache: Cache, key: String) -> Unit)? = null

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        // No-op; spans added during initialization are tracked via onSpanAdded.
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            if (leastRecentlyUsed.add(span)) {
                currentSize += span.length
            }
        }
        evictCache(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            if (leastRecentlyUsed.remove(span)) {
                currentSize = (currentSize - span.length).coerceAtLeast(0L)
            }
        }
        onSpanEvictedListener?.invoke(cache, span.key)
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        synchronized(lock) {
            if (leastRecentlyUsed.remove(oldSpan)) {
                currentSize = (currentSize - oldSpan.length).coerceAtLeast(0L)
            }
            if (leastRecentlyUsed.add(newSpan)) {
                currentSize += newSpan.length
            }
        }
        evictCache(cache, 0L)
    }

    /**
     * Immediately trims the cache down to the current configured [maxBytesProvider] limit.
     * Safe to invoke from background threads when the user reduces cache capacity in Settings.
     */
    fun evictToLimit(cache: Cache) {
        evictCache(cache, 0L)
    }

    fun getTrackedSizeBytes(): Long = synchronized(lock) { currentSize }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        val maxBytes = maxBytesProvider().coerceAtLeast(MIN_CACHE_BYTES)
        val skipped = HashSet<CacheSpan>()

        while (true) {
            val candidate = synchronized(lock) {
                if (currentSize + requiredSpace <= maxBytes || leastRecentlyUsed.isEmpty()) {
                    null
                } else {
                    pickEvictionCandidate(skipped)
                }
            } ?: break

            val sizeBefore = synchronized(lock) { currentSize }
            cache.removeSpan(candidate)
            val sizeAfter = synchronized(lock) { currentSize }
            if (sizeAfter >= sizeBefore) {
                // Span could not be removed (e.g. currently locked); avoid spinning.
                skipped.add(candidate)
            }
        }
    }

    /**
     * Picks the best span to evict while holding [lock]:
     * 1. If any artist has more than [maxTracksPerArtistSoftCap] distinct cached keys,
     *    pick the least-recently-used eligible span from an over-represented artist to preserve variety.
     * 2. Otherwise fall back to the globally least-recently-used eligible span.
     */
    private fun pickEvictionCandidate(skipped: Set<CacheSpan>): CacheSpan? {
        val artistResolver = artistForKeyProvider
        if (artistResolver != null && maxTracksPerArtistSoftCap > 0) {
            val artistKeys = HashMap<String, MutableSet<String>>()
            for (span in leastRecentlyUsed) {
                val artist = artistResolver(span.key)?.trim()?.lowercase()
                if (!artist.isNullOrBlank()) {
                    artistKeys.getOrPut(artist) { HashSet() }.add(span.key)
                }
            }
            val overRepresentedArtists = artistKeys
                .filterValues { it.size > maxTracksPerArtistSoftCap }
                .keys

            if (overRepresentedArtists.isNotEmpty()) {
                val excessSpan = leastRecentlyUsed.firstOrNull { span ->
                    if (span in skipped) return@firstOrNull false
                    val artist = artistResolver(span.key)?.trim()?.lowercase()
                    artist != null && artist in overRepresentedArtists
                }
                if (excessSpan != null) return excessSpan
            }
        }
        return leastRecentlyUsed.firstOrNull { it !in skipped }
    }

    companion object {
        const val DEFAULT_MAX_TRACKS_PER_ARTIST = 5
        private const val MIN_CACHE_BYTES = 0L

        internal fun compareSpans(lhs: CacheSpan, rhs: CacheSpan): Int {
            val delta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
            return if (delta == 0L) {
                lhs.compareTo(rhs)
            } else if (delta < 0L) {
                -1
            } else {
                1
            }
        }
    }
}
