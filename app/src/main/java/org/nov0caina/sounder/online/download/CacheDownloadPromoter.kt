package org.nov0caina.sounder.online.download

import java.io.File

/**
 * Result of resolving the audio source for a permanent download.
 */
data class ResolvedDownloadAudio(
    val tempFile: File,
    val audioExtension: String,
    val promotedFromCache: Boolean,
)

/**
 * Coordinates cache-to-download promotion so fully cached streaming tracks are copied
 * locally without making remote audio stream network requests.
 */
object CacheDownloadPromoter {

    /**
     * Attempts to copy a fully cached audio stream into [tempDir] without hitting the network.
     * Falls back to [networkDownload] if the track is missing or only partially cached.
     */
    suspend fun acquireAudio(
        trackId: String,
        tempDir: File,
        baseFileName: String,
        isFullyCached: (String) -> Boolean,
        copyFromCache: (String, File) -> Boolean,
        detectExtension: (File) -> String,
        networkDownload: suspend () -> ResolvedDownloadAudio?,
    ): ResolvedDownloadAudio? {
        if (isFullyCached(trackId)) {
            val rawTemp = File(tempDir, "$baseFileName.cache.tmp")
            if (copyFromCache(trackId, rawTemp) && rawTemp.exists() && rawTemp.length() > 0L) {
                val ext = detectExtension(rawTemp)
                val typedTemp = File(tempDir, "$baseFileName.$ext.tmp")
                if (typedTemp.exists()) typedTemp.delete()
                val finalTemp = if (rawTemp.renameTo(typedTemp)) typedTemp else rawTemp
                return ResolvedDownloadAudio(
                    tempFile = finalTemp,
                    audioExtension = ext,
                    promotedFromCache = true,
                )
            }
            rawTemp.delete()
        }
        return networkDownload()
    }
}
