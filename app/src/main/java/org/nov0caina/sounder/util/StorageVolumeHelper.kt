package org.nov0caina.sounder.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import java.io.File
import org.nov0caina.sounder.online.StorageDestination
import timber.log.Timber as L

data class StorageVolumeInfo(
    val destination: StorageDestination,
    val title: String,
    val baseDir: File,
    val freeSpaceBytes: Long,
    val totalSpaceBytes: Long,
    val isAvailable: Boolean
) {
    fun formatFreeSpace(context: Context): String =
        Formatter.formatFileSize(context, freeSpaceBytes)

    fun formatTotalSpace(context: Context): String =
        Formatter.formatFileSize(context, totalSpaceBytes)
}

object StorageVolumeHelper {

    fun getInternalSounderDir(): File {
        val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(publicMusic, "Sounder").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getSdCardSounderDir(context: Context): File? {
        try {
            // Method 1: StorageManager (API 30+)
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (sm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                for (volume in sm.storageVolumes) {
                    if (volume.isRemovable) {
                        val dir = volume.directory
                        if (dir != null && dir.canWrite()) {
                            val candidate = File(File(dir, "Music"), "Sounder")
                            if (candidate.exists() || candidate.mkdirs()) {
                                return candidate
                            }
                        }
                    }
                }
            }

            // Method 2: Secondary external files dir
            val dirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MUSIC)
            if (dirs.size > 1 && dirs[1] != null) {
                val secFile = dirs[1]!!
                val secPath = secFile.absolutePath
                val androidDataIdx = secPath.indexOf("/Android/data/")
                if (androidDataIdx != -1) {
                    val rootSd = File(secPath.substring(0, androidDataIdx))
                    val publicSdMusic = File(File(rootSd, "Music"), "Sounder")
                    if (publicSdMusic.exists() || publicSdMusic.mkdirs()) {
                        return publicSdMusic
                    }
                }
                val appSpecificMusic = File(secFile, "Sounder")
                if (appSpecificMusic.exists() || appSpecificMusic.mkdirs()) {
                    return appSpecificMusic
                }
            }
        } catch (e: Exception) {
            L.w(e, "Error locating SD card Sounder directory")
        }
        return null
    }

    fun isSdCardAvailable(context: Context): Boolean {
        return getSdCardSounderDir(context) != null
    }

    fun getSounderDirForDestination(context: Context, destination: StorageDestination): File {
        return when (destination) {
            StorageDestination.SD_CARD -> getSdCardSounderDir(context) ?: getInternalSounderDir()
            StorageDestination.INTERNAL -> getInternalSounderDir()
        }
    }

    fun getVolumeInfo(context: Context, destination: StorageDestination): StorageVolumeInfo {
        return when (destination) {
            StorageDestination.INTERNAL -> {
                val dir = getInternalSounderDir()
                StorageVolumeInfo(
                    destination = StorageDestination.INTERNAL,
                    title = "Memoria Interna",
                    baseDir = dir,
                    freeSpaceBytes = dir.freeSpace,
                    totalSpaceBytes = dir.totalSpace,
                    isAvailable = true
                )
            }
            StorageDestination.SD_CARD -> {
                val sdDir = getSdCardSounderDir(context)
                if (sdDir != null) {
                    StorageVolumeInfo(
                        destination = StorageDestination.SD_CARD,
                        title = "Tarjeta SD",
                        baseDir = sdDir,
                        freeSpaceBytes = sdDir.freeSpace,
                        totalSpaceBytes = sdDir.totalSpace,
                        isAvailable = true
                    )
                } else {
                    StorageVolumeInfo(
                        destination = StorageDestination.SD_CARD,
                        title = "Tarjeta SD (No detectada)",
                        baseDir = getInternalSounderDir(),
                        freeSpaceBytes = 0L,
                        totalSpaceBytes = 0L,
                        isAvailable = false
                    )
                }
            }
        }
    }
}

