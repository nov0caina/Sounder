package org.nov0caina.sounder.util

import android.content.Context
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.StorageDestination
import timber.log.Timber as L

data class MigrationPlan(
    val sourceDir: File,
    val destDir: File,
    val totalFiles: Int,
    val totalBytes: Long,
    val destFreeBytes: Long,
    val hasEnoughSpace: Boolean
)

data class MigrationProgress(
    val currentFileName: String,
    val filesCopied: Int,
    val totalFiles: Int,
    val percent: Int
)

object LibraryMigrationManager {

    fun planMigration(
        context: Context,
        from: StorageDestination,
        to: StorageDestination
    ): MigrationPlan {
        val sourceDir = StorageVolumeHelper.getSounderDirForDestination(context, from)
        val destDir = StorageVolumeHelper.getSounderDirForDestination(context, to)

        val files = getAllFiles(sourceDir)
        val totalBytes = files.sumOf { it.length() }
        val destFree = destDir.freeSpace
        // Require at least 50 MB margin
        val hasEnoughSpace = destFree > (totalBytes + 50L * 1024L * 1024L)

        return MigrationPlan(
            sourceDir = sourceDir,
            destDir = destDir,
            totalFiles = files.size,
            totalBytes = totalBytes,
            destFreeBytes = destFree,
            hasEnoughSpace = hasEnoughSpace
        )
    }

    suspend fun executeMigration(
        context: Context,
        plan: MigrationPlan,
        targetDestination: StorageDestination,
        onlineSettings: OnlineSettings,
        musicRepository: MusicRepository,
        onProgress: (MigrationProgress) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        val allFiles = getAllFiles(plan.sourceDir)
        if (allFiles.isEmpty()) {
            onlineSettings.setDownloadStorage(targetDestination)
            return@withContext Result.success(0)
        }

        val total = allFiles.size
        var copied = 0
        val buffer = ByteArray(64 * 1024)
        val migratedPaths = mutableListOf<String>()

        try {
            for (sourceFile in allFiles) {
                val relPath = sourceFile.relativeTo(plan.sourceDir).path
                val targetFile = File(plan.destDir, relPath)

                targetFile.parentFile?.mkdirs()

                // Copy stream
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }

                // Verify integrity
                if (targetFile.exists() && targetFile.length() == sourceFile.length()) {
                    sourceFile.delete()
                    migratedPaths.add(targetFile.absolutePath)
                    copied++
                } else {
                    targetFile.delete()
                    return@withContext Result.failure(
                        IllegalStateException("Verification failed for file: $relPath")
                    )
                }

                val percent = ((copied.toFloat() / total.toFloat()) * 100).toInt()
                withContext(Dispatchers.Main) {
                    onProgress(
                        MigrationProgress(
                            currentFileName = sourceFile.name,
                            filesCopied = copied,
                            totalFiles = total,
                            percent = percent
                        )
                    )
                }
            }

            // Clean up empty directories in source
            cleanEmptyDirs(plan.sourceDir)

            // Update default download destination
            onlineSettings.setDownloadStorage(targetDestination)

            // Notify MediaScanner for new files
            if (migratedPaths.isNotEmpty()) {
                MediaScannerConnection.scanFile(
                    context,
                    migratedPaths.toTypedArray(),
                    null,
                    null
                )
            }

            // Refresh library indexing
            musicRepository.requestIndex(true)

            Result.success(copied)
        } catch (e: Exception) {
            L.w(e, "Error executing library migration")
            Result.failure(e)
        }
    }

    private fun getAllFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        val files = mutableListOf<File>()
        dir.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.startsWith(".")) {
                files.add(file)
            }
        }
        return files
    }

    private fun cleanEmptyDirs(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                cleanEmptyDirs(child)
                if (child.list().isNullOrEmpty()) {
                    child.delete()
                }
            }
        }
    }
}

