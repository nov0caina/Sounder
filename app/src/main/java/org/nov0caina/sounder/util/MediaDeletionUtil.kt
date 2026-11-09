package org.nov0caina.sounder.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import org.nov0caina.sounder.online.model.OnlineSong
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Song
import timber.log.Timber as L

object MediaDeletionUtil {

    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "flac", "ogg", "wav", "aac", "wma")

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

        return list
    }

    fun resolveSongFile(context: Context, song: Song): File? {
        return getCandidateSongFiles(context, song).firstOrNull { it.exists() }
    }

    fun deleteSongFile(context: Context, song: Song): Boolean {
        if (song is OnlineSong) return false
        var fileDeleted = false
        var mediaStoreDeleted = false
        val file = resolveSongFile(context, song)

        try {
            if (file != null && file.exists()) {
                val parentDir = file.parentFile
                if (parentDir != null) {
                    val lrcFile = File(parentDir, "${file.nameWithoutExtension}.lrc")
                    if (lrcFile.exists()) {
                        lrcFile.delete()
                    }
                    val jpgFile = File(parentDir, "${file.nameWithoutExtension}.jpg")
                    if (jpgFile.exists()) {
                        jpgFile.delete()
                    }
                }
                fileDeleted = file.delete() || !file.exists()
                if (fileDeleted) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
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

    fun cleanAlbumAndArtistDirs(albumDir: File): Boolean {
        if (!albumDir.exists() || !albumDir.isDirectory) return false
        val remainingAudio = albumDir.listFiles { _, name ->
            AUDIO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
        }
        if (remainingAudio.isNullOrEmpty()) {
            albumDir.deleteRecursively()
            val artistDir = albumDir.parentFile
            if (artistDir != null && artistDir.exists() && artistDir.isDirectory) {
                val artistRemaining = artistDir.listFiles()
                if (artistRemaining.isNullOrEmpty()) {
                    artistDir.delete()
                }
            }
            return true
        }
        return false
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
                    MediaScannerConnection.scanFile(context, arrayOf(dir.absolutePath), null, null)
                    dir.parentFile?.let { artistDir ->
                        if (!artistDir.exists()) {
                            MediaScannerConnection.scanFile(context, arrayOf(artistDir.absolutePath), null, null)
                        }
                    }
                }
            } catch (t: Throwable) {
                L.e(t, "Error cleaning up album directory: %s", dir.absolutePath)
            }
        }
        return count
    }

    fun cleanPostMediaStoreSongDeletion(context: Context, song: Song) {
        val candidates = getCandidateSongFiles(context, song)
        for (file in candidates) {
            val parentDir = file.parentFile
            if (parentDir != null && parentDir.exists()) {
                val lrcFile = File(parentDir, "${file.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) {
                    lrcFile.delete()
                }
                val jpgFile = File(parentDir, "${file.nameWithoutExtension}.jpg")
                if (jpgFile.exists()) {
                    jpgFile.delete()
                }
                try {
                    if (cleanAlbumAndArtistDirs(parentDir)) {
                        MediaScannerConnection.scanFile(context, arrayOf(parentDir.absolutePath), null, null)
                        parentDir.parentFile?.let { artistDir ->
                            if (!artistDir.exists()) {
                                MediaScannerConnection.scanFile(context, arrayOf(artistDir.absolutePath), null, null)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    L.e(t, "Error cleaning album/artist directory for song: %s", parentDir.absolutePath)
                }
            }
        }
    }

    fun cleanPostMediaStoreAlbumDeletion(context: Context, album: Album) {
        val albumDirs = mutableSetOf<File>()
        for (song in album.songs) {
            val candidates = getCandidateSongFiles(context, song)
            for (file in candidates) {
                val parentDir = file.parentFile
                if (parentDir != null && parentDir.exists()) {
                    albumDirs.add(parentDir)
                    val lrcFile = File(parentDir, "${file.nameWithoutExtension}.lrc")
                    if (lrcFile.exists()) {
                        lrcFile.delete()
                    }
                    val jpgFile = File(parentDir, "${file.nameWithoutExtension}.jpg")
                    if (jpgFile.exists()) {
                        jpgFile.delete()
                    }
                }
            }
        }
        for (dir in albumDirs) {
            try {
                if (cleanAlbumAndArtistDirs(dir)) {
                    MediaScannerConnection.scanFile(context, arrayOf(dir.absolutePath), null, null)
                    dir.parentFile?.let { artistDir ->
                        if (!artistDir.exists()) {
                            MediaScannerConnection.scanFile(context, arrayOf(artistDir.absolutePath), null, null)
                        }
                    }
                }
            } catch (t: Throwable) {
                L.e(t, "Error cleaning up album directory: %s", dir.absolutePath)
            }
        }
    }
}

