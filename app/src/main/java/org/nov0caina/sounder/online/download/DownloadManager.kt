package org.nov0caina.sounder.online.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.util.SongMatcher
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * Coordinates audio downloads, tracking overall progress and managing WorkManager jobs.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val onlineSettings: OnlineSettings,
    private val musicRepositoryProvider: Provider<MusicRepository>
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("sounder_downloads_store", Context.MODE_PRIVATE)

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val itemsMap = ConcurrentHashMap<String, DownloadItem>()

    init {
        loadPersistedDownloads()
    }

    private fun loadPersistedDownloads() {
        try {
            val all = prefs.all
            for ((trackId, pathObj) in all) {
                val path = pathObj as? String ?: continue
                val file = java.io.File(path)
                if (file.exists()) {
                    val item = DownloadItem(
                        trackId = trackId,
                        title = file.nameWithoutExtension,
                        artist = file.parentFile?.parentFile?.name ?: "",
                        album = file.parentFile?.name ?: "",
                        artworkUrl = null,
                        durationMs = 0L,
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        localFilePath = path
                    )
                    itemsMap[trackId] = item
                } else {
                    prefs.edit().remove(trackId).apply()
                }
            }
            updateList()
        } catch (e: Exception) {
            L.w(e, "Error loading persisted downloads")
        }
    }

    fun enqueue(track: OnlineTrack) {
        if (isDownloaded(track)) {
            L.d("Track %s (%s) is already downloaded, skipping enqueue", track.title, track.id)
            return
        }

        if (isEnqueuedOrDownloading(track)) {
            L.d("Track %s (%s) is already enqueued or downloading, skipping", track.title, track.id)
            return
        }

        val item = DownloadItem(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            status = DownloadStatus.QUEUED
        )
        itemsMap[track.id] = item
        updateList()

        val constraints = Constraints.Builder().apply {
            if (onlineSettings.wifiOnlyDownloads) {
                setRequiredNetworkType(NetworkType.UNMETERED)
            } else {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()

        val inputData = workDataOf(
            DownloadWorker.KEY_TRACK_ID to track.id,
            DownloadWorker.KEY_TITLE to track.title,
            DownloadWorker.KEY_ARTIST to track.artist,
            DownloadWorker.KEY_ALBUM to track.album,
            DownloadWorker.KEY_ARTWORK_URL to track.artworkUrl,
            DownloadWorker.KEY_DURATION_MS to track.durationMs,
            DownloadWorker.KEY_TRACK_NUMBER to (track.trackNumber ?: 0),
            DownloadWorker.KEY_YEAR to (track.year ?: 0)
        )

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("download_${track.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${track.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        observeWork(track.id, workRequest.id)
    }

    /**
     * Enqueues all tracks from an album sequentially with a cooldown delay between tasks
     * to protect against IP rate-limiting and CDN throttling (HTTP 429), skipping tracks that are
     * already downloaded or queued.
     */
    fun enqueueAlbum(album: OnlineAlbumDetail) {
        scope.launch(Dispatchers.IO) {
            for (track in album.tracks) {
                if (isDownloaded(track) || isEnqueuedOrDownloading(track)) {
                    continue
                }
                enqueue(track)
                kotlinx.coroutines.delay(300)
            }
        }
    }

    private fun observeWork(trackId: String, workId: java.util.UUID) {
        scope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo == null) return@collect

                val current = itemsMap[trackId] ?: return@collect
                val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, current.progress)

                val updated = when (workInfo.state) {
                    WorkInfo.State.ENQUEUED -> current.copy(status = DownloadStatus.QUEUED)
                    WorkInfo.State.RUNNING -> current.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = progress.coerceAtMost(99)
                    )
                    WorkInfo.State.SUCCEEDED -> {
                        val path = workInfo.outputData.getString(DownloadWorker.KEY_LOCAL_PATH)
                        if (!path.isNullOrBlank()) {
                            prefs.edit().putString(trackId, path).apply()
                        }
                        current.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            localFilePath = path
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        val error = workInfo.outputData.getString(DownloadWorker.KEY_ERROR)
                        current.copy(status = DownloadStatus.FAILED, error = error)
                    }
                    WorkInfo.State.CANCELLED -> current.copy(status = DownloadStatus.CANCELLED)
                    WorkInfo.State.BLOCKED -> current
                }

                itemsMap[trackId] = updated
                updateList()
            }
        }
    }

    fun cancel(trackId: String) {
        workManager.cancelUniqueWork("download_$trackId")
        itemsMap[trackId]?.let {
            itemsMap[trackId] = it.copy(status = DownloadStatus.CANCELLED)
            updateList()
        }
    }

    fun removeDownload(trackId: String) {
        prefs.edit().remove(trackId).apply()
        itemsMap.remove(trackId)
        updateList()
    }

    fun getDownloadedFilePath(trackId: String): String? {
        val item = itemsMap[trackId]
        if (item?.status == DownloadStatus.COMPLETED && !item.localFilePath.isNullOrBlank()) {
            return item.localFilePath
        }
        val persisted = prefs.getString(trackId, null)
        if (!persisted.isNullOrBlank() && java.io.File(persisted).exists()) {
            return persisted
        }
        return null
    }

    fun isDownloaded(trackId: String): Boolean {
        val item = itemsMap[trackId]
        if (item?.status == DownloadStatus.COMPLETED) {
            if (!item.localFilePath.isNullOrBlank()) {
                if (java.io.File(item.localFilePath).exists()) {
                    return true
                } else {
                    itemsMap.remove(trackId)
                    prefs.edit().remove(trackId).apply()
                    updateList()
                    return false
                }
            }
            return true
        }

        val persistedPath = prefs.getString(trackId, null)
        if (!persistedPath.isNullOrBlank()) {
            val file = java.io.File(persistedPath)
            if (file.exists()) {
                itemsMap[trackId] = DownloadItem(
                    trackId = trackId,
                    title = file.nameWithoutExtension,
                    artist = file.parentFile?.parentFile?.name ?: "",
                    album = file.parentFile?.name ?: "",
                    artworkUrl = null,
                    durationMs = 0L,
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    localFilePath = persistedPath
                )
                return true
            } else {
                prefs.edit().remove(trackId).apply()
            }
        }
        return false
    }

    fun isEnqueuedOrDownloading(trackId: String): Boolean {
        val s = itemsMap[trackId]?.status
        return s == DownloadStatus.QUEUED || s == DownloadStatus.DOWNLOADING
    }

    fun findSongInLibrary(track: OnlineTrack): Song? {
        val lib = musicRepositoryProvider.get().library ?: return null
        return lib.songs.firstOrNull { song ->
            val songTitle = song.name.resolve(context)
            val songArtist = song.artists.resolveNames(context)
            SongMatcher.matches(songTitle, songArtist, track.title, track.artist)
        }
    }

    fun getDownloadedFile(track: OnlineTrack): java.io.File? {
        // 1. By exact track ID
        val pathById = getDownloadedFilePath(track.id)
        if (!pathById.isNullOrBlank()) {
            val file = java.io.File(pathById)
            if (file.exists()) return file
        }

        // 2. By metadata match in itemsMap
        val normTitle = SongMatcher.normalizeTitle(track.title)
        val normArtist = SongMatcher.normalizeArtist(track.artist)
        for (item in itemsMap.values) {
            if (item.status == DownloadStatus.COMPLETED && !item.localFilePath.isNullOrBlank()) {
                if (SongMatcher.matches(item.title, item.artist, normTitle, normArtist)) {
                    val file = java.io.File(item.localFilePath)
                    if (file.exists()) {
                        prefs.edit().putString(track.id, item.localFilePath).apply()
                        return file
                    }
                }
            }
        }

        // 3. From library
        val libSong = findSongInLibrary(track)
        if (libSong != null) {
            val file = java.io.File(libSong.path.toString())
            if (file.exists()) {
                prefs.edit().putString(track.id, file.absolutePath).apply()
                return file
            }
        }

        return null
    }

    fun isDownloaded(track: OnlineTrack): Boolean {
        if (isDownloaded(track.id)) return true
        if (getDownloadedFile(track) != null) return true
        return findSongInLibrary(track) != null
    }

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean {
        if (isEnqueuedOrDownloading(track.id)) return true
        val normTitle = SongMatcher.normalizeTitle(track.title)
        val normArtist = SongMatcher.normalizeArtist(track.artist)
        return itemsMap.values.any { item ->
            (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING) &&
                SongMatcher.matches(item.title, item.artist, normTitle, normArtist)
        }
    }

    private fun updateList() {
        val list = itemsMap.values.toList().sortedByDescending { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
        _downloads.value = list
        _activeCount.value = list.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    }
}

