package org.nov0caina.sounder.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.TransactionTooLargeException
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.R
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.model.OnlineSong
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Prepares audio tracks for external sharing via [FileProvider], validating real container headers,
 * assigning accurate MIME types, and converting legacy WebM/Opus files into universally playable
 * M4A (AAC-LC) so recipients on messaging apps like WhatsApp hear full audio without silence.
 */
object AudioShareHelper {

    enum class AudioContainer {
        MP4,
        OGG,
        MP3,
        FLAC,
        WAV,
        WEBM,
        UNKNOWN,
    }

    data class PreparedShare(
        val uris: ArrayList<Uri>,
        val mimeType: String,
        val fallbackText: String? = null,
    )

    private const val SHARED_CACHE_DIR = "shared_audio"
    private const val MAX_SHARED_CACHE_FILES = 12

    fun detectContainer(file: File): AudioContainer {
        if (!file.exists() || file.length() < 4) return AudioContainer.UNKNOWN
        return try {
            val header = ByteArray(12)
            val read = file.inputStream().use { it.read(header) }
            detectContainerFromHeader(header, read)
        } catch (_: Exception) {
            AudioContainer.UNKNOWN
        }
    }

    internal fun detectContainerFromHeader(header: ByteArray, length: Int = header.size): AudioContainer {
        if (length >= 4 &&
            header[0] == 0x1A.toByte() &&
            header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() &&
            header[3] == 0xA3.toByte()
        ) {
            return AudioContainer.WEBM
        }
        if (length >= 8 &&
            header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        ) {
            return AudioContainer.MP4
        }
        if (length >= 4 &&
            header[0] == 'O'.code.toByte() &&
            header[1] == 'g'.code.toByte() &&
            header[2] == 'g'.code.toByte() &&
            header[3] == 'S'.code.toByte()
        ) {
            return AudioContainer.OGG
        }
        if (length >= 4 &&
            header[0] == 'f'.code.toByte() &&
            header[1] == 'L'.code.toByte() &&
            header[2] == 'a'.code.toByte() &&
            header[3] == 'C'.code.toByte()
        ) {
            return AudioContainer.FLAC
        }
        if (length >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'A'.code.toByte() &&
            header[10] == 'V'.code.toByte() &&
            header[11] == 'E'.code.toByte()
        ) {
            return AudioContainer.WAV
        }
        if ((length >= 3 &&
                header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte()) ||
            (length >= 2 &&
                header[0] == 0xFF.toByte() &&
                (header[1].toInt() and 0xE0) == 0xE0)
        ) {
            return AudioContainer.MP3
        }
        return AudioContainer.UNKNOWN
    }

    fun mimeTypeForContainer(container: AudioContainer, fallbackExtension: String?): String =
        when (container) {
            AudioContainer.MP4 -> "audio/mp4"
            AudioContainer.OGG -> "audio/ogg"
            AudioContainer.MP3 -> "audio/mpeg"
            AudioContainer.FLAC -> "audio/flac"
            AudioContainer.WAV -> "audio/wav"
            AudioContainer.WEBM -> "audio/webm"
            AudioContainer.UNKNOWN -> when (fallbackExtension?.lowercase()) {
                "m4a", "mp4", "aac" -> "audio/mp4"
                "ogg", "opus", "oga" -> "audio/ogg"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "webm" -> "audio/webm"
                else -> "audio/*"
            }
        }

    fun shareSongs(context: Context, songs: Collection<Song>) {
        if (songs.isEmpty()) return
        L.d("Preparing share for %d songs", songs.size)
        CoroutineScope(Dispatchers.Main).launch {
            val prepared = withContext(Dispatchers.IO) {
                prepareSharePayload(context, songs)
            }
            launchShareIntent(context, songs.size, prepared)
        }
    }

    private fun prepareSharePayload(context: Context, songs: Collection<Song>): PreparedShare {
        val uris = ArrayList<Uri>()
        val mimeTypes = mutableSetOf<String>()
        val shareCacheDir = File(context.cacheDir, SHARED_CACHE_DIR).apply { mkdirs() }
        pruneOldCacheFiles(shareCacheDir)

        val authority = "${context.packageName}.fileprovider"

        for (song in songs) {
            val resolvedFile = resolveOrCopyPhysicalFile(context, song, shareCacheDir)
            if (resolvedFile == null || !resolvedFile.exists() || resolvedFile.length() <= 0L) {
                continue
            }

            val shareableFileAndMime = preparePlayableShareFile(resolvedFile, shareCacheDir)
            val contentUri = try {
                FileProvider.getUriForFile(context, authority, shareableFileAndMime.first)
            } catch (e: Exception) {
                L.w(e, "FileProvider failed for %s; copying to shared cache", shareableFileAndMime.first.absolutePath)
                val cachedCopy = File(shareCacheDir, shareableFileAndMime.first.name)
                if (cachedCopy.absolutePath != shareableFileAndMime.first.absolutePath) {
                    shareableFileAndMime.first.copyTo(cachedCopy, overwrite = true)
                }
                FileProvider.getUriForFile(context, authority, cachedCopy)
            }
            uris.add(contentUri)
            mimeTypes.add(shareableFileAndMime.second)
        }

        if (uris.isEmpty()) {
            val onlineLinks = songs.mapNotNull { song ->
                val online = song as? OnlineSong ?: return@mapNotNull null
                val url = online.uri.toString().takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: return@mapNotNull null
                "${online.onlineTrack.title} - ${online.onlineTrack.artist}\n$url"
            }
            if (onlineLinks.isNotEmpty()) {
                return PreparedShare(
                    uris = arrayListOf(),
                    mimeType = "text/plain",
                    fallbackText = onlineLinks.joinToString("\n\n"),
                )
            }
        }

        val resolvedMime = mimeTypes.singleOrNull() ?: "audio/*"
        return PreparedShare(uris = uris, mimeType = resolvedMime)
    }

    private fun launchShareIntent(context: Context, count: Int, prepared: PreparedShare) {
        if (prepared.uris.isEmpty() && prepared.fallbackText.isNullOrBlank()) {
            context.showToast(R.string.err_share_failed)
            return
        }

        try {
            val baseIntent = if (!prepared.fallbackText.isNullOrBlank() && prepared.uris.isEmpty()) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, prepared.fallbackText)
                }
            } else if (prepared.uris.size == 1) {
                val uri = prepared.uris[0]
                Intent(Intent.ACTION_SEND).apply {
                    type = prepared.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = prepared.mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, prepared.uris)
                    val clip = ClipData.newRawUri("", prepared.uris[0])
                    for (i in 1 until prepared.uris.size) {
                        clip.addItem(ClipData.Item(prepared.uris[i]))
                    }
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            val chooser = Intent.createChooser(baseIntent, null).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(chooser)
        } catch (_: TransactionTooLargeException) {
            L.e("Failed to share %d songs: Too large", count)
            context.showToast(R.string.err_share_too_large)
        } catch (e: Exception) {
            L.e(e, "Failed to share %d songs", count)
            context.showToast(R.string.err_share_failed)
        }
    }

    private fun resolveOrCopyPhysicalFile(context: Context, song: Song, cacheDir: File): File? {
        if (song.uri.scheme.equals("file", ignoreCase = true)) {
            val path = song.uri.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists() && f.length() > 0L) return f
            }
        }

        val resolved = MediaDeletionUtil.resolveSongFile(context, song)
        if (resolved != null && resolved.exists() && resolved.length() > 0L) {
            return resolved
        }

        if (song is OnlineSong) {
            val downloaded = findSounderDownloadForTrack(context, song.onlineTrack.title, song.onlineTrack.artist)
            if (downloaded != null && downloaded.exists() && downloaded.length() > 0L) {
                return downloaded
            }
            return null
        }

        // Handle SAF / MediaStore content URIs cleanly via shared cache copy
        return try {
            val title = sanitizeFileName(song.name.resolve(context)).ifBlank { "track_${song.uid}" }
            val ext = song.format.mimeType.substringAfterLast('/', "").let {
                when (it.lowercase()) {
                    "mp4", "m4a" -> "m4a"
                    "mpeg", "mp3" -> "mp3"
                    "ogg" -> "ogg"
                    "opus" -> "opus"
                    "flac" -> "flac"
                    "wav", "x-wav" -> "wav"
                    else -> "m4a"
                }
            }
            val dest = File(cacheDir, "${title}_${song.uid.hashCode()}.$ext")
            context.contentResolver.openInputStream(song.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.takeIf { it.exists() && it.length() > 0L }
        } catch (e: Exception) {
            L.w(e, "Could not open stream for song URI: %s", song.uri)
            null
        }
    }

    private fun findSounderDownloadForTrack(context: Context, title: String, artist: String): File? {
        val roots = buildList {
            add(StorageVolumeHelper.getInternalSounderDir())
            StorageVolumeHelper.getSdCardSounderDir(context)?.let(::add)
        }
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile && MediaDeletionUtil.AUDIO_EXTENSIONS.contains(it.extension.lowercase()) }
                .forEach { file ->
                    val folderArtist = file.parentFile?.parentFile?.name.orEmpty()
                    if (SongMatcher.matches(file.nameWithoutExtension, folderArtist, title, artist)) {
                        return file
                    }
                }
        }
        return null
    }

    private fun preparePlayableShareFile(source: File, cacheDir: File): Pair<File, String> {
        val container = detectContainer(source)
        if (container == AudioContainer.WEBM) {
            val targetM4a = File(cacheDir, "${sanitizeFileName(source.nameWithoutExtension)}_${source.length()}.m4a")
            if (targetM4a.exists() && targetM4a.length() > 0L) {
                return targetM4a to "audio/mp4"
            }
            if (transcodeToM4a(source, targetM4a)) {
                return targetM4a to "audio/mp4"
            }
            // Fallback to explicit .webm container copy if MediaCodec cannot transcode on this device
            val fallbackWebm = File(cacheDir, "${sanitizeFileName(source.nameWithoutExtension)}.webm")
            source.copyTo(fallbackWebm, overwrite = true)
            return fallbackWebm to "audio/webm"
        }

        // WhatsApp often fails to classify `.opus` with OggS headers unless extension is `.ogg`
        if (container == AudioContainer.OGG && source.extension.equals("opus", ignoreCase = true)) {
            val targetOgg = File(cacheDir, "${sanitizeFileName(source.nameWithoutExtension)}.ogg")
            if (!targetOgg.exists() || targetOgg.length() != source.length()) {
                source.copyTo(targetOgg, overwrite = true)
            }
            return targetOgg to "audio/ogg"
        }

        return source to mimeTypeForContainer(container, source.extension)
    }

    private fun transcodeToM4a(inputFile: File, outputFile: File): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) return false

            extractor.selectTrack(audioTrackIndex)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var extractorEos = false
            var decoderEos = false
            var encoderEos = false
            var muxerStarted = false
            var muxerTrackIdx = -1
            val bufferInfo = MediaCodec.BufferInfo()

            var pendingPcm: ByteArray? = null
            var pendingPcmOffset = 0
            var pendingPcmPts = 0L

            while (!encoderEos) {
                // 1. Feed extractor samples into decoder
                if (!extractorEos) {
                    val decInIdx = decoder.dequeueInputBuffer(5000)
                    if (decInIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(decInIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(decInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorEos = true
                        } else {
                            decoder.queueInputBuffer(decInIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. Pull decoded PCM if we don't have leftover PCM buffered
                if (pendingPcm == null && !decoderEos) {
                    val decOutIdx = decoder.dequeueOutputBuffer(bufferInfo, 5000)
                    if (decOutIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(decOutIdx)
                        if (bufferInfo.size > 0 && outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)
                            pendingPcm = chunk
                            pendingPcmOffset = 0
                            pendingPcmPts = bufferInfo.presentationTimeUs
                        }
                        decoder.releaseOutputBuffer(decOutIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderEos = true
                        }
                    }
                }

                // 3. Feed PCM into encoder
                if (pendingPcm != null) {
                    val encInIdx = encoder.dequeueInputBuffer(5000)
                    if (encInIdx >= 0) {
                        val encInBuf = encoder.getInputBuffer(encInIdx)!!
                        encInBuf.clear()
                        val remaining = pendingPcm.size - pendingPcmOffset
                        val copyLen = minOf(remaining, encInBuf.capacity())
                        encInBuf.put(pendingPcm, pendingPcmOffset, copyLen)
                        val isLast = decoderEos && (pendingPcmOffset + copyLen >= pendingPcm.size)
                        val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(encInIdx, 0, copyLen, pendingPcmPts, flags)
                        pendingPcmOffset += copyLen
                        if (pendingPcmOffset >= pendingPcm.size) {
                            pendingPcm = null
                        }
                    }
                } else if (decoderEos) {
                    val encInIdx = encoder.dequeueInputBuffer(5000)
                    if (encInIdx >= 0) {
                        encoder.queueInputBuffer(encInIdx, 0, 0, pendingPcmPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                // 4. Drain encoded AAC into muxer
                var draining = true
                while (draining) {
                    val encOutIdx = encoder.dequeueOutputBuffer(bufferInfo, 2000)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrackIdx = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            val encodedBuf = encoder.getOutputBuffer(encOutIdx)
                            if (encodedBuf != null &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                bufferInfo.size > 0 &&
                                muxerStarted
                            ) {
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxerTrackIdx, encodedBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderEos = true
                                draining = false
                            }
                        }
                    }
                }
            }

            muxer.stop()
            return outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            L.w(e, "WebM-to-M4A transcoding failed for %s", inputFile.absolutePath)
            if (outputFile.exists()) outputFile.delete()
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun pruneOldCacheFiles(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (files.size > MAX_SHARED_CACHE_FILES) {
            for (oldFile in files.drop(MAX_SHARED_CACHE_FILES)) {
                oldFile.delete()
            }
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
