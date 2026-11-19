package org.nov0caina.sounder.online.cache

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File

/**
 * Routes ExoPlayer [DataSource] reads cleanly between:
 * 1. Direct local storage (`file://`, `content://`, `android.resource://`, or permanently
 *    downloaded tracks in `/Music/Sounder/`), bypassing the streaming disk cache completely.
 * 2. Media3 [CacheDataSource] keyed by stable `stream_<videoId>` keys for all online streams,
 *    serving hits directly from disk with zero upstream network calls and transparently caching
 *    upstream bytes on misses.
 */
@OptIn(UnstableApi::class)
class RoutingCacheDataSourceFactory(
    private val directDataSourceFactory: DataSource.Factory,
    private val cacheDataSourceFactory: DataSource.Factory,
    private val downloadedFileProvider: (videoId: String) -> File? = { null },
    private val fileUriProvider: (File) -> Uri = { Uri.fromFile(it) },
    private val onCacheStreamClosed: (() -> Unit)? = null,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutingCacheDataSource(
        directDataSource = directDataSourceFactory.createDataSource(),
        cacheDataSource = cacheDataSourceFactory.createDataSource(),
        downloadedFileProvider = downloadedFileProvider,
        fileUriProvider = fileUriProvider,
        onCacheStreamClosed = onCacheStreamClosed,
    )

    private class RoutingCacheDataSource(
        private val directDataSource: DataSource,
        private val cacheDataSource: DataSource,
        private val downloadedFileProvider: (videoId: String) -> File?,
        private val fileUriProvider: (File) -> Uri,
        private val onCacheStreamClosed: (() -> Unit)?,
    ) : DataSource {

        private var activeDataSource: DataSource? = null
        private var usedCacheDataSource: Boolean = false
        private val transferListeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners.add(transferListener)
            directDataSource.addTransferListener(transferListener)
            cacheDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            if (scheme == "file" || scheme == "content" || scheme == "android.resource") {
                activeDataSource = directDataSource
                usedCacheDataSource = false
                return directDataSource.open(dataSpec)
            }

            val videoId = extractVideoId(dataSpec)
            if (!videoId.isNullOrBlank()) {
                val downloadedFile = downloadedFileProvider(videoId)
                if (downloadedFile != null && downloadedFile.exists()) {
                    activeDataSource = directDataSource
                    usedCacheDataSource = false
                    val localSpec = dataSpec.buildUpon()
                        .setUri(fileUriProvider(downloadedFile))
                        .setKey(null)
                        .build()
                    return directDataSource.open(localSpec)
                }

                val stableKey = StreamingCacheManager.cacheKeyFor(videoId)
                val keyedSpec = if (dataSpec.key == stableKey) {
                    dataSpec
                } else {
                    dataSpec.buildUpon().setKey(stableKey).build()
                }
                activeDataSource = cacheDataSource
                usedCacheDataSource = true
                return cacheDataSource.open(keyedSpec)
            }

            activeDataSource = cacheDataSource
            usedCacheDataSource = true
            return cacheDataSource.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return activeDataSource?.read(buffer, offset, length) ?: -1
        }

        override fun getUri(): Uri? = activeDataSource?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            activeDataSource?.responseHeaders ?: emptyMap()

        override fun close() {
            val wasCache = usedCacheDataSource
            try {
                activeDataSource?.close()
            } finally {
                activeDataSource = null
                usedCacheDataSource = false
                if (wasCache) {
                    onCacheStreamClosed?.invoke()
                }
            }
        }
    }

    companion object {
        fun extractVideoId(dataSpec: DataSpec): String? {
            StreamingCacheManager.videoIdFromCacheKey(dataSpec.key)?.let { return it }

            val uri = dataSpec.uri
            val uriStr = uri.toString()
            if (uriStr.contains("watch?v=") || uriStr.startsWith("https://music.youtube.com/watch")) {
                val fromQuery = uri.getQueryParameter("v")
                if (!fromQuery.isNullOrBlank()) return fromQuery
                val parsed = uriStr.substringAfterLast("watch?v=").substringBefore("&")
                if (parsed.isNotBlank()) return parsed
            }
            if (uriStr.startsWith("sounder://")) {
                return uri.lastPathSegment?.takeIf { it.isNotBlank() }
            }
            return null
        }
    }
}
