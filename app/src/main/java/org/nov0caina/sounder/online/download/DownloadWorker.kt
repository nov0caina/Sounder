package org.nov0caina.sounder.online.download

import android.app.Notification
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.nov0caina.sounder.R
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.innertube.InnerTubeClient
import org.nov0caina.sounder.online.innertube.InnerTubeParser
import org.nov0caina.sounder.online.lyrics.LrcLibClient
import org.nov0caina.sounder.online.lyrics.LyricsSanitizer
import org.oxycblt.musikr.metadata.TagLibJNI
import timber.log.Timber as L

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadWorkerEntryPoint {
    fun innerTubeClient(): InnerTubeClient
    fun lrcLibClient(): LrcLibClient
    fun onlineSettings(): OnlineSettings
    fun musicRepository(): MusicRepository
}

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        DownloadWorkerEntryPoint::class.java
    )

    private val notificationManager = NotificationManagerCompat.from(context)

    companion object {
        const val CHANNEL_ID = "sounder_downloads"
        const val KEY_TRACK_ID = "track_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_ARTWORK_URL = "artwork_url"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_PROGRESS = "progress"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_ERROR = "error"
        const val KEY_TRACK_NUMBER = "track_number"
        const val KEY_YEAR = "year"

        private data class ITunesMeta(
            val genre: String?,
            val album: String?,
            val artworkUrl: String?,
            val trackNumber: Int? = null,
            val year: Int? = null
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown Track"
        val artist = inputData.getString(KEY_ARTIST) ?: "Unknown Artist"
        val album = inputData.getString(KEY_ALBUM)
        val artworkUrl = inputData.getString(KEY_ARTWORK_URL)
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val inputTrackNumber = inputData.getInt(KEY_TRACK_NUMBER, 0)
        val inputYear = inputData.getInt(KEY_YEAR, 0)

        val notificationId = trackId.hashCode()
        ensureNotificationChannel()

        try {
            try {
                setForeground(createForegroundInfo(notificationId, title, artist, 0))
            } catch (e: Exception) {
                L.w(e, "Failed to set foreground info for download")
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            // 1. Resolve Audio Stream URL
            val quality = entryPoint.onlineSettings().audioQuality
            val streamUrl = entryPoint.innerTubeClient().resolveStreamUrl(trackId, quality, title, artist)
                ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Failed to resolve audio stream URL")
                )

            // 2. Resolve Metadata via iTunes Search API (genre, album, high-res artwork) if needed
            val resolvedTitle = LyricsSanitizer.cleanTitle(title, artist)
            val needsITunes = (album.isNullOrBlank() || album.equals("Single", ignoreCase = true) || artworkUrl.isNullOrBlank() || inputTrackNumber <= 0 || inputYear <= 0)
            val itunesClient = if (needsITunes) {
                OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()
            } else null

            val itunesMeta = if (needsITunes && itunesClient != null) {
                withTimeoutOrNull(2500L) {
                    fetchITunesMetadata(itunesClient, artist, resolvedTitle)
                }
            } else null

            val resolvedGenre = itunesMeta?.genre
            val resolvedAlbum = album?.takeIf { it.isNotBlank() && !it.equals("Single", ignoreCase = true) }
                ?: itunesMeta?.album?.takeIf { it.isNotBlank() }
                ?: album?.takeIf { it.isNotBlank() }
                ?: "Single"
            val providedArtwork = if (!artworkUrl.isNullOrBlank()) {
                InnerTubeParser.upgradeArtworkResolution(artworkUrl)
            } else {
                null
            }
            val bestArtworkUrl = providedArtwork ?: itunesMeta?.artworkUrl

            // 3. Fetch Cover Artwork Bytes Upfront (prioritize verified provided artwork, fallback to iTunes)
            var coverBytes: ByteArray? = null
            if (!bestArtworkUrl.isNullOrBlank()) {
                try {
                    val coverReq = Request.Builder()
                        .url(bestArtworkUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    okHttpClient.newCall(coverReq).execute().use { res ->
                        if (res.isSuccessful) {
                            coverBytes = res.body?.bytes()
                        }
                    }
                } catch (e: Exception) {
                    L.w(e, "Failed to download artwork from %s", bestArtworkUrl)
                }
            }
            val fallbackArtworkUrl = itunesMeta?.artworkUrl
            if (coverBytes == null && !fallbackArtworkUrl.isNullOrBlank() && fallbackArtworkUrl != bestArtworkUrl) {
                try {
                    val fallbackReq = Request.Builder()
                        .url(fallbackArtworkUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    okHttpClient.newCall(fallbackReq).execute().use { res ->
                        if (res.isSuccessful) {
                            coverBytes = res.body?.bytes()
                        }
                    }
                } catch (e: Exception) {
                    L.w(e, "Failed to download fallback artwork from %s", fallbackArtworkUrl)
                }
            }

            // 4. Prepare Destination Folder
            val targetStorage = entryPoint.onlineSettings().downloadStorage
            val musicDir = org.nov0caina.sounder.util.StorageVolumeHelper.getSounderDirForDestination(
                context,
                targetStorage
            )
            val sanitizedArtist = sanitizeFileName(artist)
            val sanitizedAlbum = sanitizeFileName(resolvedAlbum)
            val targetDir = File(File(musicDir, sanitizedArtist), sanitizedAlbum)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val trackNumber = if (inputTrackNumber > 0) inputTrackNumber else (itunesMeta?.trackNumber ?: 0)
            val year = if (inputYear > 0) inputYear else (itunesMeta?.year ?: 0)

            val sanitizedTitle = sanitizeFileName(resolvedTitle)
            val baseFileName = if (trackNumber > 0) {
                "${String.format("%02d", trackNumber)} - $sanitizedTitle"
            } else {
                "$sanitizedArtist - $sanitizedTitle"
            }
            val audioExtension = if (streamUrl.contains("itag=140") || streamUrl.contains("itag=18") || streamUrl.contains("mp4")) "m4a" else "opus"
            val audioFile = File(targetDir, "$baseFileName.$audioExtension")

            // 5. Download Audio Stream with Progress Tracking
            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "HTTP error: ${response.code}")
                    )
                }

                val body = response.body ?: return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Empty response body")
                )

                val contentLength = body.contentLength()
                val tempFile = File(context.cacheDir, "$baseFileName.$audioExtension.tmp")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastReportedProgress = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isStopped) {
                                tempFile.delete()
                                return@withContext Result.failure(
                                    workDataOf(KEY_ERROR to "Cancelled")
                                )
                            }

                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (contentLength > 0) {
                                val progress = ((totalRead * 95) / contentLength).toInt().coerceIn(0, 95)
                                if (progress != lastReportedProgress && progress % 5 == 0) {
                                    lastReportedProgress = progress
                                    setProgress(workDataOf(KEY_PROGRESS to progress))
                                    notificationManager.notify(
                                        notificationId,
                                        buildNotification(title, artist, progress, false)
                                    )
                                }
                            }
                        }
                    }
                }

                setProgress(workDataOf(KEY_PROGRESS to 95, "status" to "TAGGING"))

                // 5.1. Write metadata tags into the container via TagLib
                try {
                    TagLibJNI.writeTags(
                        path = tempFile.absolutePath,
                        title = resolvedTitle,
                        artist = artist,
                        album = resolvedAlbum,
                        genre = resolvedGenre,
                        coverData = coverBytes,
                        track = trackNumber,
                        year = year
                    )
                } catch (e: Throwable) {
                    L.w(e, "TagLib writeTags failed on %s", tempFile.absolutePath)
                }

                // Copy to destination: try direct file copy first, fallback to MediaStore if restricted
                var savedSuccessfully = false
                try {
                    if (audioFile.exists()) {
                        audioFile.delete()
                    }
                    tempFile.copyTo(audioFile, overwrite = true)
                    savedSuccessfully = true
                } catch (e: Exception) {
                    L.w(e, "Direct file copy failed, falling back to MediaStore insertion")
                }

                if (!savedSuccessfully) {
                    try {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$baseFileName.$audioExtension")
                            put(android.provider.MediaStore.Audio.Media.TITLE, resolvedTitle)
                            put(android.provider.MediaStore.Audio.Media.ARTIST, artist)
                            put(android.provider.MediaStore.Audio.Media.ALBUM, resolvedAlbum)
                            if (trackNumber > 0) {
                                put(android.provider.MediaStore.Audio.Media.TRACK, trackNumber)
                            }
                            if (year > 0) {
                                put(android.provider.MediaStore.Audio.Media.YEAR, year)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && resolvedGenre != null) {
                                put(android.provider.MediaStore.Audio.Media.GENRE, resolvedGenre)
                            }
                            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, if (audioExtension == "m4a") "audio/mp4" else "audio/ogg")
                            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/Sounder/$sanitizedArtist/$sanitizedAlbum")
                            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
                        }
                        val uri = context.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                tempFile.inputStream().use { input -> input.copyTo(out) }
                            }
                            values.clear()
                            values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                            context.contentResolver.update(uri, values, null, null)
                            savedSuccessfully = true
                        }
                    } catch (e: Exception) {
                        L.e(e, "MediaStore insertion failed for %s", baseFileName)
                    }
                }
                tempFile.delete()
            }

            // 6. Download Lyrics from LrcLib
            try {
                withTimeoutOrNull(2000L) {
                    val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else null
                    val lyricsResult = entryPoint.lrcLibClient().getLyrics(
                        trackName = resolvedTitle,
                        artistName = artist,
                        albumName = resolvedAlbum,
                        durationSeconds = durationSec
                    )

                    if (lyricsResult.hasSyncedLyrics) {
                        val lrcFile = File(targetDir, "$baseFileName.lrc")
                        val lrcContent = lyricsResult.syncedLyrics.joinToString("\n") { line ->
                            val minutes = line.timeMs / 60_000
                            val seconds = (line.timeMs % 60_000) / 1000
                            val hundredths = (line.timeMs % 1000) / 10
                            String.format("[%02d:%02d.%02d] %s", minutes, seconds, hundredths, line.text)
                        }
                        lrcFile.writeText(lrcContent)
                    } else if (lyricsResult.hasPlainLyrics) {
                        val txtFile = File(targetDir, "$baseFileName.lrc")
                        txtFile.writeText(lyricsResult.plainLyrics.orEmpty())
                    }
                }
            } catch (e: Exception) {
                L.w(e, "Could not save lyrics for %s", baseFileName)
            }

            // 7. Save Artwork Cover to disk
            val bytes = coverBytes
            if (bytes != null && bytes.isNotEmpty()) {
                try {
                    val coverFile = File(targetDir, "$baseFileName.jpg")
                    val albumCoverFile = File(targetDir, "cover.jpg")
                    coverFile.writeBytes(bytes)
                    albumCoverFile.writeBytes(bytes)
                } catch (e: Exception) {
                    L.w(e, "Could not write cover images to disk for %s", baseFileName)
                }
            }

            // 8. Trigger MediaScanner and Auxio Library Refresh
            val filesToScan = mutableListOf(audioFile.absolutePath)
            val albumCoverFile = File(targetDir, "cover.jpg")
            if (albumCoverFile.exists()) {
                filesToScan.add(albumCoverFile.absolutePath)
            }
            MediaScannerConnection.scanFile(
                context,
                filesToScan.toTypedArray(),
                null
            ) { _, _ ->
                entryPoint.musicRepository().requestIndex(true)
            }

            // Notify completion
            notificationManager.notify(
                notificationId,
                buildNotification(title, artist, 100, true)
            )

            Result.success(
                workDataOf(
                    KEY_LOCAL_PATH to audioFile.absolutePath,
                    KEY_PROGRESS to 100
                )
            )
        } catch (e: Exception) {
            L.e(e, "Error downloading track: %s", title)
            notificationManager.cancel(notificationId)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    private fun fetchITunesMetadata(
        client: OkHttpClient,
        artist: String,
        title: String
    ): ITunesMeta? {
        try {
            val cleanedTitle = LyricsSanitizer.cleanTitle(title, artist)
            val cleanedArtist = LyricsSanitizer.cleanArtist(artist)
            val query = "$cleanedArtist $cleanedTitle"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val item = results.getJSONObject(0)
                            val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                            val album = item.optString("collectionName").takeIf { it.isNotBlank() }
                            val artwork = item.optString("artworkUrl100").takeIf { it.isNotBlank() }
                                ?.replace("100x100bb", "600x600bb")
                            val itunesTrack = item.optInt("trackNumber", 0).takeIf { it > 0 }
                            val itunesDate = item.optString("releaseDate")
                            val itunesYear = Regex("\\b(19|20)\\d{2}\\b").find(itunesDate)?.value?.toIntOrNull()
                            return ITunesMeta(
                                genre = genre,
                                album = album,
                                artworkUrl = artwork,
                                trackNumber = itunesTrack,
                                year = itunesYear
                            )
                        }
                    }
                }
            }

            // Fallback: search only cleaned title if artist query had no match
            val singleEncoded = URLEncoder.encode(cleanedTitle, "UTF-8")
            val singleUrl = "https://itunes.apple.com/search?term=$singleEncoded&entity=song&limit=1"
            val singleRequest = Request.Builder()
                .url(singleUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            client.newCall(singleRequest).execute().use { res ->
                if (res.isSuccessful) {
                    val singleBody = res.body?.string()
                    if (!singleBody.isNullOrBlank()) {
                        val singleJson = JSONObject(singleBody)
                        val singleResults = singleJson.optJSONArray("results")
                        if (singleResults != null && singleResults.length() > 0) {
                            val item = singleResults.getJSONObject(0)
                            val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                            val album = item.optString("collectionName").takeIf { it.isNotBlank() }
                            val artwork = item.optString("artworkUrl100").takeIf { it.isNotBlank() }
                                ?.replace("100x100bb", "600x600bb")
                            val itunesTrack = item.optInt("trackNumber", 0).takeIf { it > 0 }
                            val itunesDate = item.optString("releaseDate")
                            val itunesYear = Regex("\\b(19|20)\\d{2}\\b").find(itunesDate)?.value?.toIntOrNull()
                            return ITunesMeta(
                                genre = genre,
                                album = album,
                                artworkUrl = artwork,
                                trackNumber = itunesTrack,
                                year = itunesYear
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed to fetch iTunes metadata for %s - %s", artist, title)
        }
        return null
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(context.getString(R.string.title_downloads))
            .setShowBadge(false)
            .setSound(null, null)
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(
        notificationId: Int,
        title: String,
        artist: String,
        progress: Int
    ): ForegroundInfo {
        val notification = buildNotification(title, artist, progress, false)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(
        title: String,
        artist: String,
        progress: Int,
        isComplete: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (isComplete) context.getString(R.string.download_complete, title) else title)
            .setContentText(if (isComplete) artist else "$artist • $progress%")
            .setSmallIcon(if (isComplete) R.drawable.ic_check_24 else R.drawable.ic_download_24)
            .setOngoing(!isComplete)
            .setAutoCancel(isComplete)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        if (!isComplete) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }
}

