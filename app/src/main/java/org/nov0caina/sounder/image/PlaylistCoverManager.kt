package org.nov0caina.sounder.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber as L

/**
 * Manages user-selected custom cover images for playlists stored inside internal storage
 * (`context.filesDir/playlist_covers/`).
 */
object PlaylistCoverManager {
    private const val COVERS_DIR_NAME = "playlist_covers"
    private const val MAX_COVER_DIMENSION = 1024
    private const val JPEG_QUALITY = 90

    private val revisionCounter = AtomicLong(0L)
    private val _coverChanges = MutableStateFlow(0L)

    /** Emits an incremented revision token whenever any custom playlist cover is saved, renamed, or deleted. */
    val coverChanges: StateFlow<Long> = _coverChanges

    private val signatureCache = ConcurrentHashMap<String, Long>()

    fun normalizeKey(playlistName: String): String {
        val trimmed = playlistName.trim().lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getCoversDir(baseFilesDir: File): File {
        val dir = File(baseFilesDir, COVERS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getCoverFile(context: Context, playlistName: String): File? =
        getCoverFile(context.filesDir, playlistName)

    fun getCoverFile(baseFilesDir: File, playlistName: String): File? {
        if (playlistName.isBlank()) return null
        val key = normalizeKey(playlistName)
        val file = File(File(baseFilesDir, COVERS_DIR_NAME), "$key.jpg")
        return if (file.exists() && file.isFile && file.length() > 0L) {
            signatureCache[key] = file.lastModified()
            file
        } else {
            signatureCache.remove(key)
            null
        }
    }

    fun hasCustomCover(context: Context, playlistName: String): Boolean =
        getCoverFile(context, playlistName) != null

    fun getCoverSignature(playlistName: String): Long {
        if (playlistName.isBlank()) return 0L
        return signatureCache[normalizeKey(playlistName)] ?: 0L
    }

    fun getCoverSignature(context: Context, playlistName: String): Long =
        getCoverFile(context, playlistName)?.lastModified() ?: 0L

    fun saveCoverBytes(baseFilesDir: File, playlistName: String, bytes: ByteArray): File? {
        if (playlistName.isBlank() || bytes.isEmpty()) return null
        val key = normalizeKey(playlistName)
        val dir = getCoversDir(baseFilesDir)
        val target = File(dir, "$key.jpg")
        return try {
            target.writeBytes(bytes)
            val now = System.currentTimeMillis()
            target.setLastModified(now)
            signatureCache[key] = target.lastModified()
            notifyCoverChanged()
            target
        } catch (e: Exception) {
            L.e(e, "Failed to write custom cover bytes for $playlistName")
            null
        }
    }

    suspend fun saveCoverFromUri(context: Context, playlistName: String, uri: Uri): File? =
        withContext(Dispatchers.IO) {
            if (playlistName.isBlank()) return@withContext null
            val key = normalizeKey(playlistName)
            val dir = getCoversDir(context.filesDir)
            val target = File(dir, "$key.jpg")
            try {
                val bitmap = decodeDownscaledBitmap(context, uri)
                if (bitmap != null) {
                    FileOutputStream(target).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                } else {
                    val stream: InputStream =
                        context.contentResolver.openInputStream(uri) ?: return@withContext null
                    stream.use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                val now = System.currentTimeMillis()
                target.setLastModified(now)
                signatureCache[key] = target.lastModified()
                notifyCoverChanged()
                target
            } catch (e: Exception) {
                L.e(e, "Failed to save custom playlist cover for $playlistName from $uri")
                null
            }
        }

    fun renameCover(context: Context, oldPlaylistName: String, newPlaylistName: String): Boolean =
        renameCover(context.filesDir, oldPlaylistName, newPlaylistName)

    fun renameCover(baseFilesDir: File, oldPlaylistName: String, newPlaylistName: String): Boolean {
        if (oldPlaylistName.isBlank() || newPlaylistName.isBlank()) return false
        val oldKey = normalizeKey(oldPlaylistName)
        val newKey = normalizeKey(newPlaylistName)
        if (oldKey == newKey) return false
        val dir = File(baseFilesDir, COVERS_DIR_NAME)
        val oldFile = File(dir, "$oldKey.jpg")
        if (!oldFile.exists()) return false
        val newFile = File(dir, "$newKey.jpg")
        return try {
            if (newFile.exists()) {
                newFile.delete()
            }
            val renamed = oldFile.renameTo(newFile) || run {
                oldFile.copyTo(newFile, overwrite = true)
                oldFile.delete()
                true
            }
            if (renamed) {
                val now = System.currentTimeMillis()
                newFile.setLastModified(now)
                signatureCache.remove(oldKey)
                signatureCache[newKey] = newFile.lastModified()
                notifyCoverChanged()
            }
            renamed
        } catch (e: Exception) {
            L.e(e, "Failed to rename playlist cover from $oldPlaylistName to $newPlaylistName")
            false
        }
    }

    fun deleteCover(context: Context, playlistName: String): Boolean =
        deleteCover(context.filesDir, playlistName)

    fun deleteCover(baseFilesDir: File, playlistName: String): Boolean {
        if (playlistName.isBlank()) return false
        val key = normalizeKey(playlistName)
        val file = File(File(baseFilesDir, COVERS_DIR_NAME), "$key.jpg")
        signatureCache.remove(key)
        if (!file.exists()) return false
        val deleted = file.delete() || !file.exists()
        if (deleted) {
            notifyCoverChanged()
        }
        return deleted
    }

    fun notifyCoverChanged() {
        _coverChanges.value = revisionCounter.incrementAndGet()
    }

    private fun decodeDownscaledBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight
            if (width <= 0 || height <= 0) return null

            var sampleSize = 1
            while (width / (sampleSize * 2) >= MAX_COVER_DIMENSION &&
                height / (sampleSize * 2) >= MAX_COVER_DIMENSION
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            if (decoded.width <= MAX_COVER_DIMENSION && decoded.height <= MAX_COVER_DIMENSION) {
                decoded
            } else {
                val ratio = minOf(
                    MAX_COVER_DIMENSION.toFloat() / decoded.width,
                    MAX_COVER_DIMENSION.toFloat() / decoded.height,
                )
                val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
            }
        } catch (e: Exception) {
            L.w(e, "Could not decode downscaled bitmap from $uri")
            null
        }
    }
}
