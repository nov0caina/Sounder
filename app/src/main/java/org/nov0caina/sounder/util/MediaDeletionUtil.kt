package org.nov0caina.sounder.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.online.model.OnlineSong
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song
import timber.log.Timber as L

object MediaDeletionUtil {

    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "flac", "ogg", "wav", "aac", "wma")

    private val PROTECTED_ROOT_DIR_NAMES = setOf(
        "sounder",
        "music",
        "downloads",
        "download",
        "emulated",
        "storage"
    )

    fun getCandidateSongFiles(context: Context, song: Song): List<File> {
        val list = mutableListOf<File>()
        try {
            val cursor = context.contentResolver.query(
                song.uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIdx = it.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (dataIdx != -1) {
                        val path = it.getString(dataIdx)
                        if (!path.isNullOrBlank()) {
                            list.add(File(path))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            L.w(t, "Could not resolve file via DATA column for %s", song.uri)
        }

        val volumePath = song.path.volume.components?.toString()
        val relativePath = song.path.components.toString()
        val sdCardSounderDir = StorageVolumeHelper.getSdCardSounderDir(context)

        if (volumePath != null) list.add(File("/$volumePath", relativePath))
        list.add(File(Environment.getExternalStorageDirectory(), relativePath))
        list.add(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                relativePath.removePrefix("Music/")
            )
        )
        list.add(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Sounder/${relativePath.removePrefix("Music/Sounder/").removePrefix("Music/")}"
            )
        )
        if (sdCardSounderDir != null) {
            list.add(File(sdCardSounderDir, relativePath.removePrefix("Music/Sounder/").removePrefix("Music/")))
        }

        return list.distinctBy { it.absolutePath }
    }

    fun resolveSongFile(context: Context, song: Song): File? {
        return getCandidateSongFiles(context, song).firstOrNull { it.exists() }
    }

    internal fun stripTitleForAuxMatch(nameWithoutExt: String): String {
        return nameWithoutExt
            .replace(Regex("""^\d{1,3}\s*-\s*"""), "")
            .trim()
            .lowercase()
    }

    /**
     * Removes matching `.lrc` lyrics and `.jpg` track artwork companions alongside a deleted audio
     * file, covering exact stem matches as well as stripped track-title `.lrc` variants.
     */
    internal fun deleteAuxFilesForSong(parentDir: File, songFile: File, songTitle: String? = null) {
        if (!parentDir.exists() || !parentDir.isDirectory) return
        val stem = songFile.nameWithoutExtension
        File(parentDir, "$stem.lrc").takeIf { it.exists() }?.delete()
        File(parentDir, "$stem.jpg").takeIf { it.exists() }?.delete()

        val strippedStem = stripTitleForAuxMatch(stem)
        val normalizedTitle = songTitle?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        val auxFiles = parentDir.listFiles { _, name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext == "lrc" || (ext == "jpg" && !name.equals("cover.jpg", ignoreCase = true))
        }.orEmpty()

        for (aux in auxFiles) {
            val auxStem = aux.nameWithoutExtension
            val strippedAux = stripTitleForAuxMatch(auxStem)
            val afterDash = auxStem.substringAfter(" - ", "").trim().lowercase()
            val matchesStem = auxStem.equals(stem, ignoreCase = true) ||
                (strippedStem.isNotBlank() && (strippedAux == strippedStem || afterDash == strippedStem))
            val matchesTitle = normalizedTitle != null &&
                (strippedAux == normalizedTitle || afterDash == normalizedTitle || auxStem.equals(normalizedTitle, ignoreCase = true))
            if (matchesStem || matchesTitle) {
                aux.delete()
            }
        }
    }

    private fun isSafePrunableDir(dir: File): Boolean {
        if (dir.name.lowercase() in PROTECTED_ROOT_DIR_NAMES) return false
        return try {
            val musicPublic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val extRoot = Environment.getExternalStorageDirectory()
            dir.canonicalPath != musicPublic.canonicalPath && dir.canonicalPath != extRoot.canonicalPath
        } catch (_: Exception) {
            true
        }
    }

    fun deleteSongFile(context: Context, song: Song): Boolean {
        if (song is OnlineSong) return false
        var fileDeleted = false
        var mediaStoreDeleted = false
        val file = resolveSongFile(context, song)
        val songTitle = try {
            song.name.resolve(context)
        } catch (_: Exception) {
            null
        }

        try {
            if (file != null && file.exists()) {
                val parentDir = file.parentFile
                if (parentDir != null) {
                    deleteAuxFilesForSong(parentDir, file, songTitle)
                }
                fileDeleted = file.delete() || !file.exists()
                if (fileDeleted) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                    if (parentDir != null && cleanAlbumAndArtistDirs(parentDir)) {
                        scanCleanedDirectories(context, parentDir)
                    }
                }
            }
        } catch (t: Throwable) {
            L.w(t, "Failed to delete physical file: %s", file?.absolutePath)
        }

        try {
            val rows = context.contentResolver.delete(song.uri, null, null)
            mediaStoreDeleted = rows > 0
        } catch (t: Throwable) {
            L.w(t, "Could not delete URI via ContentResolver: %s", song.uri)
        }

        val finalFileExists = file?.exists() ?: false
        return (fileDeleted || mediaStoreDeleted) && !finalFileExists
    }

    /**
     * Deletes [albumDir] (along with orphaned `.lrc` / artwork / companion files) if it contains
     * no remaining audio files, and then prunes the parent artist directory if empty.
     */
    fun cleanAlbumAndArtistDirs(albumDir: File): Boolean {
        if (!albumDir.exists() || !albumDir.isDirectory) return false
        if (!isSafePrunableDir(albumDir)) return false

        val remainingAudio = albumDir.listFiles { _, name ->
            AUDIO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
        }
        if (remainingAudio.isNullOrEmpty()) {
            // Also skip deletion if there are nested subdirectories containing audio files
            val subDirsWithAudio = albumDir.listFiles { file ->
                file.isDirectory && file.walkTopDown().any {
                    it.isFile && AUDIO_EXTENSIONS.contains(it.extension.lowercase())
                }
            }
            if (!subDirsWithAudio.isNullOrEmpty()) return false

            albumDir.listFiles()?.forEach { child ->
                if (child.isFile) child.delete() else child.deleteRecursively()
            }
            val albumRemoved = albumDir.deleteRecursively() || !albumDir.exists()

            val artistDir = albumDir.parentFile
            if (artistDir != null && artistDir.exists() && artistDir.isDirectory && isSafePrunableDir(artistDir)) {
                val artistRemaining = artistDir.listFiles()
                val hasRemainingMeaningfulContent = artistRemaining?.any { entry ->
                    if (entry.isDirectory) {
                        val nestedAudio = entry.walkTopDown().any {
                            it.isFile && AUDIO_EXTENSIONS.contains(it.extension.lowercase())
                        }
                        if (!nestedAudio) {
                            entry.deleteRecursively()
                            false
                        } else {
                            true
                        }
                    } else {
                        AUDIO_EXTENSIONS.contains(entry.extension.lowercase())
                    }
                } ?: false

                if (!hasRemainingMeaningfulContent) {
                    artistDir.listFiles()?.forEach { child ->
                        if (child.isFile) child.delete() else child.deleteRecursively()
                    }
                    artistDir.delete()
                }
            }
            return albumRemoved
        }
        return false
    }

    private fun scanCleanedDirectories(context: Context, dir: File) {
        MediaScannerConnection.scanFile(context, arrayOf(dir.absolutePath), null, null)
        dir.parentFile?.let { artistDir ->
            if (!artistDir.exists()) {
                MediaScannerConnection.scanFile(context, arrayOf(artistDir.absolutePath), null, null)
            }
        }
    }

    fun deleteAlbumFiles(context: Context, album: Album): Int {
        var count = 0
        val albumDirs = mutableSetOf<File>()

        for (song in album.songs) {
            val file = resolveSongFile(context, song)
            if (file != null && file.exists()) {
                file.parentFile?.let { albumDirs.add(it) }
            }
            if (deleteSongFile(context, song)) {
                count++
            }
        }

        for (dir in albumDirs) {
            try {
                if (cleanAlbumAndArtistDirs(dir)) {
                    scanCleanedDirectories(context, dir)
                }
            } catch (t: Throwable) {
                L.e(t, "Error cleaning up album directory: %s", dir.absolutePath)
            }
        }
        return count
    }

    fun cleanPostMediaStoreDeletion(
        context: Context,
        preResolvedFilesWithTitles: List<Pair<File, String?>>
    ) {
        val albumDirs = mutableSetOf<File>()
        for ((file, songTitle) in preResolvedFilesWithTitles) {
            val parentDir = file.parentFile
            if (parentDir != null && parentDir.exists()) {
                albumDirs.add(parentDir)
                deleteAuxFilesForSong(parentDir, file, songTitle)
            }
        }
        for (dir in albumDirs) {
            try {
                if (cleanAlbumAndArtistDirs(dir)) {
                    scanCleanedDirectories(context, dir)
                }
            } catch (t: Throwable) {
                L.e(t, "Error cleaning album/artist directory: %s", dir.absolutePath)
            }
        }
    }

    fun cleanPostMediaStoreSongDeletion(context: Context, song: Song) {
        val songTitle = try {
            song.name.resolve(context)
        } catch (_: Exception) {
            null
        }
        val candidates = getCandidateSongFiles(context, song)
        cleanPostMediaStoreDeletion(context, candidates.map { it to songTitle })
    }

    fun cleanPostMediaStoreAlbumDeletion(context: Context, album: Album) {
        val items = mutableListOf<Pair<File, String?>>()
        for (song in album.songs) {
            val songTitle = try {
                song.name.resolve(context)
            } catch (_: Exception) {
                null
            }
            for (file in getCandidateSongFiles(context, song)) {
                items.add(file to songTitle)
            }
        }
        cleanPostMediaStoreDeletion(context, items)
    }
}
