package org.nov0caina.sounder.online.download

import android.content.Context
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.cache.StreamingCacheManager
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.util.MediaDeletionUtil
import org.nov0caina.sounder.util.NetworkUtil
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
    private val musicRepositoryProvider: Provider<MusicRepository>,
    private val streamingCacheManager: StreamingCacheManager,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("sounder_downloads_store", Context.MODE_PRIVATE)

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private val itemsMap = ConcurrentHashMap<String, DownloadItem>()
    private val pendingTracks = ConcurrentHashMap<String, OnlineTrack>()
    private val completedMatcherPaths = ConcurrentHashMap<String, String>()
    private val lastProgressEmitMs = ConcurrentHashMap<String, Long>()
    private val observedWorkIds = ConcurrentHashMap<String, java.util.UUID>()

    @Volatile
    private var cachedLibraryPair: Pair<Any?, Map<String, List<Song>>>? = null

    @Volatile
    private var indexDebounceJob: Job? = null

    init {
        loadPersistedDownloadsSync()
        scope.launch(Dispatchers.IO) {
            pruneMissingPersistedFiles()
            observeConnectivityForResumption()
        }
    }

    private fun loadPersistedDownloadsSync() {
        try {
            val all = prefs.all
            for ((key, rawObj) in all) {
                if (key.startsWith(PENDING_TRACK_PREFIX)) {
                    val trackId = key.removePrefix(PENDING_TRACK_PREFIX)
                    val jsonStr = rawObj as? String ?: continue
                    val track = deserializeTrackJson(jsonStr)
                    if (track != null) {
                        pendingTracks[trackId] = track
                    }
                    continue
                }

                val path = rawObj as? String ?: continue
                val file = java.io.File(path)
                val artistName = file.parentFile?.parentFile?.name ?: ""
                val albumName = file.parentFile?.name ?: ""
                val titleName = file.nameWithoutExtension
                val item = DownloadItem(
                    trackId = key,
                    title = titleName,
                    artist = artistName,
                    album = albumName,
                    artworkUrl = null,
                    durationMs = 0L,
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    localFilePath = path
                )
                itemsMap[key] = item
                indexCompletedMatchKey(titleName, artistName, path)
            }

            for ((trackId, track) in pendingTracks) {
                if (itemsMap[trackId]?.status == DownloadStatus.COMPLETED) {
                    prefs.edit().remove("$PENDING_TRACK_PREFIX$trackId").apply()
                    pendingTracks.remove(trackId)
                    continue
                }
                itemsMap.putIfAbsent(
                    trackId,
                    DownloadItem(
                        trackId = track.id,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        artworkUrl = track.artworkUrl,
                        durationMs = track.durationMs,
                        status = DownloadStatus.QUEUED
                    )
                )
            }
            updateList()
        } catch (e: Exception) {
            L.w(e, "Error loading persisted downloads")
        }
    }

    private fun pruneMissingPersistedFiles() {
        var changed = false
        for ((trackId, item) in itemsMap.entries.toList()) {
            val path = item.localFilePath ?: continue
            if (item.status == DownloadStatus.COMPLETED && !java.io.File(path).exists()) {
                itemsMap.remove(trackId)
                completedMatcherPaths.remove(matcherKey(item.title, item.artist))
                prefs.edit().remove(trackId).apply()
                changed = true
            }
        }
        if (changed) {
            updateList()
        }
    }

    private suspend fun observeConnectivityForResumption() {
        NetworkUtil.observeConnectivity(context).collect { isOnline ->
            if (isOnline) {
                resumePendingOrFailedDownloads()
            }
        }
    }

    private fun isNetworkReadyForDownloads(): Boolean {
        if (!NetworkUtil.isConnected(context)) return false
        if (onlineSettings.wifiOnlyDownloads) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null && cm.isActiveNetworkMetered) return false
        }
        return true
    }

    private fun resumePendingOrFailedDownloads() {
        if (!isNetworkReadyForDownloads()) return
        val candidates = pendingTracks.values.toList()
        for (track in candidates) {
            val status = itemsMap[track.id]?.status
            if (status == DownloadStatus.COMPLETED || status == DownloadStatus.CANCELLED) {
                pendingTracks.remove(track.id)
                prefs.edit().remove("$PENDING_TRACK_PREFIX${track.id}").apply()
                continue
            }
            if (status != DownloadStatus.QUEUED && status != DownloadStatus.FAILED) continue

            try {
                val infos = workManager.getWorkInfosForUniqueWork("download_${track.id}").get()
                val alreadyRunning = infos.any { it.state == WorkInfo.State.RUNNING }
                if (!alreadyRunning) {
                    L.d("Resuming offline queued/failed download after reconnect: %s (%s)", track.title, track.id)
                    enqueueWorkRequest(track, ExistingWorkPolicy.REPLACE)
                }
            } catch (e: Exception) {
                L.w(e, "Failed checking WorkManager state for %s, re-enqueuing", track.id)
                enqueueWorkRequest(track, ExistingWorkPolicy.REPLACE)
            }
        }
    }

    /**
     * Returns true if [track] is not fully cached locally, "Download over Wi-Fi only" is enabled,
     * and the current network connection is metered (e.g., cellular data) or lacks unmetered Wi-Fi.
     */
    fun requiresCellularConfirmation(track: OnlineTrack): Boolean {
        if (!onlineSettings.wifiOnlyDownloads) return false
        if (streamingCacheManager.isTrackFullyCached(track.id)) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val isOnline = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!isOnline) return false
        return cm.isActiveNetworkMetered || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun enqueue(track: OnlineTrack, allowMeteredNetwork: Boolean = false) {
        if (isDownloaded(track)) {
            L.d("Track %s (%s) is already downloaded, skipping enqueue", track.title, track.id)
            return
        }

        if (!allowMeteredNetwork && isEnqueuedOrDownloading(track)) {
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
        pendingTracks[track.id] = track
        prefs.edit().putString("$PENDING_TRACK_PREFIX${track.id}", serializeTrackJson(track)).apply()
        updateList()

        enqueueWorkRequest(track, ExistingWorkPolicy.REPLACE, allowMeteredNetwork)
    }

    private fun enqueueWorkRequest(
        track: OnlineTrack,
        policy: ExistingWorkPolicy,
        allowMeteredNetwork: Boolean = false
    ) {
        val isFullyCached = streamingCacheManager.isTrackFullyCached(track.id)
        val constraints = Constraints.Builder().apply {
            if (isFullyCached) {
                setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            } else if (onlineSettings.wifiOnlyDownloads && !allowMeteredNetwork) {
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(inputData)
            .addTag("download_${track.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${track.id}",
            policy,
            workRequest
        )

        observeWork(track.id, workRequest.id)
    }

    /**
     * Enqueues all tracks from an album sequentially with a cooldown delay between tasks
     * to protect against IP rate-limiting and CDN throttling (HTTP 429), skipping tracks that are
     * already downloaded or queued.
     */
    fun enqueueAlbum(album: OnlineAlbumDetail, allowMeteredNetwork: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            for (track in album.tracks) {
                if (isDownloaded(track) || (!allowMeteredNetwork && isEnqueuedOrDownloading(track))) {
                    continue
                }
                enqueue(track, allowMeteredNetwork)
                delay(300)
            }
        }
    }

    /**
     * Coalesces concurrent post-download media library index requests (such as during full album
     * downloads) into a single library re-index once the batch settles.
     */
    fun requestDebouncedLibraryIndex() {
        indexDebounceJob?.cancel()
        indexDebounceJob = scope.launch(Dispatchers.Default) {
            delay(INDEX_DEBOUNCE_MS)
            L.d("Executing consolidated post-download library re-index")
            musicRepositoryProvider.get().requestIndex(true)
        }
    }

    private fun observeWork(trackId: String, workId: java.util.UUID) {
        val previous = observedWorkIds.put(trackId, workId)
        if (previous == workId) return

        scope.launch {
            workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo == null) return@collect
                if (observedWorkIds[trackId] != workId) return@collect

                val current = itemsMap[trackId] ?: return@collect
                val progress = workInfo.progress.getInt(DownloadWorker.KEY_PROGRESS, current.progress)

                val updated = when (workInfo.state) {
                    WorkInfo.State.ENQUEUED -> {
                        if (current.status == DownloadStatus.QUEUED) return@collect
                        current.copy(status = DownloadStatus.QUEUED)
                    }
                    WorkInfo.State.RUNNING -> {
                        val clamped = progress.coerceAtMost(99)
                        val now = System.currentTimeMillis()
                        val lastEmit = lastProgressEmitMs[trackId] ?: 0L
                        if (current.status == DownloadStatus.DOWNLOADING &&
                            (clamped == current.progress || (clamped < 95 && now - lastEmit < PROGRESS_THROTTLE_MS))
                        ) {
                            return@collect
                        }
                        lastProgressEmitMs[trackId] = now
                        current.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = clamped
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val path = workInfo.outputData.getString(DownloadWorker.KEY_LOCAL_PATH)
                        pendingTracks.remove(trackId)
                        lastProgressEmitMs.remove(trackId)
                        prefs.edit()
                            .remove("$PENDING_TRACK_PREFIX$trackId")
                            .apply {
                                if (!path.isNullOrBlank()) {
                                    putString(trackId, path)
                                    indexCompletedMatchKey(current.title, current.artist, path)
                                }
                            }
                            .apply()
                        current.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 100,
                            localFilePath = path
                        )
                    }
                    WorkInfo.State.FAILED -> {
                        lastProgressEmitMs.remove(trackId)
                        val error = workInfo.outputData.getString(DownloadWorker.KEY_ERROR)
                        current.copy(status = DownloadStatus.FAILED, error = error)
                    }
                    WorkInfo.State.CANCELLED -> {
                        pendingTracks.remove(trackId)
                        lastProgressEmitMs.remove(trackId)
                        prefs.edit().remove("$PENDING_TRACK_PREFIX$trackId").apply()
                        current.copy(status = DownloadStatus.CANCELLED)
                    }
                    WorkInfo.State.BLOCKED -> return@collect
                }

                itemsMap[trackId] = updated
                updateList()
            }
        }
    }

    fun cancel(trackId: String) {
        workManager.cancelUniqueWork("download_$trackId")
        pendingTracks.remove(trackId)
        lastProgressEmitMs.remove(trackId)
        prefs.edit().remove("$PENDING_TRACK_PREFIX$trackId").apply()
        itemsMap[trackId]?.let {
            itemsMap[trackId] = it.copy(status = DownloadStatus.CANCELLED)
            updateList()
        }
    }

    fun removeDownload(trackId: String) {
        workManager.cancelUniqueWork("download_$trackId")
        observedWorkIds.remove(trackId)
        val removed = itemsMap.remove(trackId)
        pendingTracks.remove(trackId)
        lastProgressEmitMs.remove(trackId)
        prefs.edit()
            .remove(trackId)
            .remove("$PENDING_TRACK_PREFIX$trackId")
            .apply()
        if (removed != null) {
            completedMatcherPaths.remove(matcherKey(removed.title, removed.artist))
        }
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
            return true
        }
        val persistedPath = prefs.getString(trackId, null)
        if (!persistedPath.isNullOrBlank()) {
            val file = java.io.File(persistedPath)
            val restored = DownloadItem(
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
            itemsMap[trackId] = restored
            indexCompletedMatchKey(restored.title, restored.artist, persistedPath)
            return true
        }
        return false
    }

    fun isEnqueuedOrDownloading(trackId: String): Boolean {
        val s = itemsMap[trackId]?.status
        return s == DownloadStatus.QUEUED || s == DownloadStatus.DOWNLOADING
    }

    private fun getLibrarySongsByNormalizedTitle(): Map<String, List<Song>> {
        val lib = musicRepositoryProvider.get().library ?: return emptyMap()
        val cached = cachedLibraryPair
        if (cached != null && cached.first === lib) {
            return cached.second
        }
        val grouped = HashMap<String, MutableList<Song>>(lib.songs.size)
        for (song in lib.songs) {
            val normTitle = SongMatcher.normalizeTitle(song.name.resolve(context))
            grouped.getOrPut(normTitle) { mutableListOf() }.add(song)
        }
        cachedLibraryPair = lib to grouped
        return grouped
    }

    fun findSongInLibrary(track: OnlineTrack): Song? {
        val grouped = getLibrarySongsByNormalizedTitle()
        val normTitle = SongMatcher.normalizeTitle(track.title)
        val candidates = grouped[normTitle] ?: return null
        return candidates.firstOrNull { song ->
            val songTitle = song.name.resolve(context)
            val songArtist = song.artists.resolveNames(context)
            if (SongMatcher.matches(songTitle, songArtist, track.title, track.artist)) {
                MediaDeletionUtil.resolveSongFile(context, song) != null
            } else {
                false
            }
        }
    }

    fun getDownloadedFile(track: OnlineTrack): java.io.File? {
        // 1. By exact track ID
        val pathById = getDownloadedFilePath(track.id)
        if (!pathById.isNullOrBlank()) {
            val file = java.io.File(pathById)
            if (file.exists()) return file
        }

        // 2. O(1) lookup by normalized title + artist in completed matcher cache
        val normTitle = SongMatcher.normalizeTitle(track.title)
        val normArtist = SongMatcher.normalizeArtist(track.artist)
        val matchedPath = completedMatcherPaths["$normTitle|$normArtist"]
        if (!matchedPath.isNullOrBlank()) {
            val file = java.io.File(matchedPath)
            if (file.exists()) {
                prefs.edit().putString(track.id, matchedPath).apply()
                return file
            } else {
                completedMatcherPaths.remove("$normTitle|$normArtist")
            }
        }

        // 3. Fallback scan across itemsMap
        for (item in itemsMap.values) {
            if (item.status == DownloadStatus.COMPLETED && !item.localFilePath.isNullOrBlank()) {
                if (SongMatcher.matches(item.title, item.artist, normTitle, normArtist)) {
                    val file = java.io.File(item.localFilePath)
                    if (file.exists()) {
                        prefs.edit().putString(track.id, item.localFilePath).apply()
                        completedMatcherPaths["$normTitle|$normArtist"] = item.localFilePath
                        return file
                    }
                }
            }
        }

        // 4. O(1) bucketed lookup in local library
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

    /**
     * Fast O(1)-amortized check suitable for RecyclerView binding on the main thread without
     * synchronous disk I/O or full-library regex scans.
     */
    fun isDownloaded(track: OnlineTrack): Boolean {
        val item = itemsMap[track.id]
        if (item?.status == DownloadStatus.COMPLETED) {
            val path = item.localFilePath
            if (path.isNullOrBlank() || java.io.File(path).exists()) {
                return true
            } else {
                itemsMap.remove(track.id)
                prefs.edit().remove(track.id).apply()
            }
        }
        val matchKey = matcherKey(track.title, track.artist)
        val matchedPath = completedMatcherPaths[matchKey]
        if (matchedPath != null) {
            if (java.io.File(matchedPath).exists()) {
                return true
            } else {
                completedMatcherPaths.remove(matchKey)
            }
        }
        return findSongInLibrary(track) != null
    }

    /**
     * Physically deletes the downloaded audio file and its companion files (.lrc, artwork)
     * from disk, clears tracking in [DownloadManager] and [SharedPreferences], updates MediaStore,
     * and triggers a debounced library re-index.
     */
    fun deleteDownloadedSong(song: Song): Boolean {
        var deleted = false
        val track = (song as? OnlineSong)?.onlineTrack

        // 1. If matching library song exists, delete via MediaDeletionUtil
        val libSong = if (song !is OnlineSong) song else (track?.let { findSongInLibrary(it) })
        if (libSong != null) {
            deleted = MediaDeletionUtil.deleteSongFile(context, libSong)
        }

        // 2. Also check physical downloaded file from DownloadManager
        val file = if (track != null) {
            getDownloadedFile(track) ?: getDownloadedFilePath(track.id)?.let { java.io.File(it) }
        } else {
            MediaDeletionUtil.resolveSongFile(context, song)
        }

        if (file != null && file.exists()) {
            val parent = file.parentFile
            if (parent != null) {
                val songTitle = track?.title ?: try { song.name.resolve(context) } catch (_: Exception) { null }
                MediaDeletionUtil.deleteAuxFilesForSong(parent, file, songTitle)
            }
            val fileDeleted = file.delete() || !file.exists()
            if (fileDeleted) {
                deleted = true
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                if (parent != null) {
                    MediaDeletionUtil.cleanAlbumAndArtistDirs(parent)
                }
            }
        }

        // 3. Clear tracking and invalidate caches
        if (track != null) {
            workManager.cancelUniqueWork("download_${track.id}")
            observedWorkIds.remove(track.id)
            removeDownload(track.id)
            completedMatcherPaths.remove(matcherKey(track.title, track.artist))
            val normTitle = SongMatcher.normalizeTitle(track.title)
            val normArtist = SongMatcher.normalizeArtist(track.artist)
            for ((id, item) in itemsMap.entries.toList()) {
                if (SongMatcher.matches(item.title, item.artist, normTitle, normArtist)) {
                    removeDownload(id)
                }
            }
        }
        cachedLibraryPair = null
        pruneMissingPersistedFiles()
        requestDebouncedLibraryIndex()

        return deleted
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

    private fun matcherKey(title: String, artist: String): String =
        "${SongMatcher.normalizeTitle(title)}|${SongMatcher.normalizeArtist(artist)}"

    private fun indexCompletedMatchKey(title: String, artist: String, path: String) {
        completedMatcherPaths[matcherKey(title, artist)] = path
    }

    private fun updateList() {
        val list = itemsMap.values.toList().sortedByDescending {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }
        _downloads.value = list
        _activeCount.value = list.count {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
        }
    }

    private fun serializeTrackJson(track: OnlineTrack): String =
        JSONObject().apply {
            put("id", track.id)
            put("title", track.title)
            put("artist", track.artist)
            if (track.album != null) put("album", track.album)
            put("durationMs", track.durationMs)
            if (track.artworkUrl != null) put("artworkUrl", track.artworkUrl)
            if (track.trackNumber != null) put("trackNumber", track.trackNumber)
            if (track.year != null) put("year", track.year)
            if (track.albumId != null) put("albumId", track.albumId)
            if (track.artistId != null) put("artistId", track.artistId)
        }.toString()

    private fun deserializeTrackJson(raw: String): OnlineTrack? =
        try {
            val json = JSONObject(raw)
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            OnlineTrack(
                id = id,
                title = json.optString("title", "Unknown Track"),
                artist = json.optString("artist", "Unknown Artist"),
                album = json.optString("album").takeIf { it.isNotBlank() },
                durationMs = json.optLong("durationMs", 0L),
                artworkUrl = json.optString("artworkUrl").takeIf { it.isNotBlank() },
                trackNumber = json.optInt("trackNumber", 0).takeIf { it > 0 },
                year = json.optInt("year", 0).takeIf { it > 0 },
                albumId = json.optString("albumId").takeIf { it.isNotBlank() },
                artistId = json.optString("artistId").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val PENDING_TRACK_PREFIX = "pending_track_"
        private const val PROGRESS_THROTTLE_MS = 300L
        private const val INDEX_DEBOUNCE_MS = 1500L
    }
}
