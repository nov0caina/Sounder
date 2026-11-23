package org.nov0caina.sounder.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.db.PlaybackRecord
import org.nov0caina.sounder.online.db.PlaybackRecordDao
import org.nov0caina.sounder.online.model.OnlineSong
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Tracks active playback sessions and records meaningful listening events locally once anti-skip
 * thresholds are reached, respecting user privacy settings.
 */
@Singleton
class PlaybackHistoryTracker internal constructor(
    private val context: Context?,
    private val playbackRecordDao: PlaybackRecordDao,
    private val onlineSettings: OnlineSettings,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        playbackRecordDao: PlaybackRecordDao,
        onlineSettings: OnlineSettings,
    ) : this(context, playbackRecordDao, onlineSettings, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    internal data class TrackSnapshot(
        val sessionKey: String,
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val genre: String?,
        val artworkUrl: String?,
        val durationMs: Long,
        val sourceType: String,
    )

    private var activeSnapshot: TrackSnapshot? = null
    private var activeIsPlaying: Boolean = false
    private var accumulatedPlayedMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var persistedRowId: Long? = null
    private var insertRequestedForSession: Boolean = false
    private var lastPersistedPlayedMs: Long = 0L

    /** Updates active track or playing state from the playback pipeline. */
    @Synchronized
    fun onSongOrStateChanged(
        song: Song?,
        isPlaying: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val snapshot = song?.toSnapshot()
        onTrackStateChanged(snapshot, isPlaying, nowMs)
    }

    /** Updates active session progress and triggers persistence once threshold is crossed. */
    @Synchronized
    fun onPositionTick(nowMs: Long = System.currentTimeMillis()) {
        val current = activeSnapshot ?: return
        if (!activeIsPlaying) {
            lastTickMs = nowMs
            return
        }
        advanceAccumulatedTime(current, nowMs)
        maybePersist(current, forceUpdate = false, nowMs = nowMs)
    }

    @Synchronized
    internal fun onTrackStateChanged(
        snapshot: TrackSnapshot?,
        isPlaying: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val previous = activeSnapshot
        if (previous != null && previous.sessionKey != snapshot?.sessionKey) {
            if (activeIsPlaying) {
                advanceAccumulatedTime(previous, nowMs)
            }
            maybePersist(previous, forceUpdate = true, nowMs = nowMs)
            resetSessionState(snapshot, isPlaying, nowMs)
            return
        }

        if (previous == null) {
            resetSessionState(snapshot, isPlaying, nowMs)
            return
        }

        if (activeIsPlaying && !isPlaying) {
            advanceAccumulatedTime(previous, nowMs)
            maybePersist(previous, forceUpdate = true, nowMs = nowMs)
        } else if (!activeIsPlaying && isPlaying) {
            lastTickMs = nowMs
        }
        activeIsPlaying = isPlaying
    }

    private fun resetSessionState(
        snapshot: TrackSnapshot?,
        isPlaying: Boolean,
        nowMs: Long,
    ) {
        activeSnapshot = snapshot
        activeIsPlaying = isPlaying
        accumulatedPlayedMs = 0L
        lastTickMs = nowMs
        persistedRowId = null
        insertRequestedForSession = false
        lastPersistedPlayedMs = 0L
    }

    private fun advanceAccumulatedTime(snapshot: TrackSnapshot, nowMs: Long) {
        if (lastTickMs <= 0L) {
            lastTickMs = nowMs
            return
        }
        val delta = (nowMs - lastTickMs).coerceAtLeast(0L)
        lastTickMs = nowMs
        val maxCapMs = if (snapshot.durationMs > 0L) snapshot.durationMs else FALLBACK_MAX_TRACK_MS
        val boundedDelta = delta.coerceAtMost(maxCapMs)
        accumulatedPlayedMs = (accumulatedPlayedMs + boundedDelta).coerceAtMost(maxCapMs)
    }

    private fun maybePersist(snapshot: TrackSnapshot, forceUpdate: Boolean, nowMs: Long) {
        if (!onlineSettings.enableLocalListeningInsights) return
        if (!isQualifyingPlayback(accumulatedPlayedMs, snapshot.durationMs)) return

        val playedSnapshot = accumulatedPlayedMs
        val existingId = persistedRowId
        if (!insertRequestedForSession) {
            insertRequestedForSession = true
            lastPersistedPlayedMs = playedSnapshot
            val (timeOfDay, dayOfWeek) = PlaybackRecord.currentContext(nowMs)
            val record = PlaybackRecord(
                trackId = snapshot.trackId,
                title = snapshot.title,
                artist = snapshot.artist,
                album = snapshot.album,
                genre = snapshot.genre,
                artworkUrl = snapshot.artworkUrl,
                durationMs = snapshot.durationMs,
                playedMs = playedSnapshot,
                timestamp = nowMs,
                timeOfDay = timeOfDay,
                dayOfWeek = dayOfWeek,
                sourceType = snapshot.sourceType,
            )
            scope.launch {
                try {
                    val insertedId = playbackRecordDao.insert(record)
                    onInsertCompleted(snapshot.sessionKey, insertedId, playedSnapshot)
                } catch (e: Exception) {
                    L.w(e, "Failed to persist playback record for %s", snapshot.title)
                }
            }
        } else if (existingId != null && (forceUpdate || playedSnapshot - lastPersistedPlayedMs >= UPDATE_INTERVAL_MS)) {
            if (playedSnapshot > lastPersistedPlayedMs) {
                lastPersistedPlayedMs = playedSnapshot
                scope.launch {
                    try {
                        playbackRecordDao.updatePlayedMs(existingId, playedSnapshot)
                    } catch (e: Exception) {
                        L.w(e, "Failed to update playback duration for row %d", existingId)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun onInsertCompleted(sessionKey: String, rowId: Long, insertedPlayedMs: Long) {
        if (activeSnapshot?.sessionKey != sessionKey) {
            val finalPlayed = maxOf(insertedPlayedMs, accumulatedPlayedMs)
            if (finalPlayed > insertedPlayedMs) {
                scope.launch {
                    try {
                        playbackRecordDao.updatePlayedMs(rowId, finalPlayed)
                    } catch (e: Exception) {
                        L.w(e, "Failed to finalize playback duration for row %d", rowId)
                    }
                }
            }
            return
        }
        persistedRowId = rowId
        if (accumulatedPlayedMs > insertedPlayedMs) {
            lastPersistedPlayedMs = accumulatedPlayedMs
            val targetPlayed = accumulatedPlayedMs
            scope.launch {
                try {
                    playbackRecordDao.updatePlayedMs(rowId, targetPlayed)
                } catch (e: Exception) {
                    L.w(e, "Failed to sync latest playback duration for row %d", rowId)
                }
            }
        }
    }

    private fun Song.toSnapshot(): TrackSnapshot {
        val ctx = checkNotNull(context) { "Context is required to resolve Song metadata" }
        val primaryArtist = artists.firstOrNull { it.name is Name.Known }?.name?.resolve(ctx) ?: ""
        val localCoverUri = cover?.id?.takeIf { it.isNotBlank() }?.let { coverId ->
            org.nov0caina.sounder.image.CoverProvider.CONTENT_URI
                .buildUpon()
                .appendPath(coverId)
                .build()
                .toString()
        }
        return if (this is OnlineSong) {
            val resolvedArtwork = onlineTrack.artworkUrl?.takeIf { it.isNotBlank() }
                ?: onlineTrack.localCoverPath?.takeIf { it.isNotBlank() }?.let { path ->
                    java.io.File(path).takeIf { it.exists() && it.length() > 0L }?.let { file ->
                        android.net.Uri.fromFile(file).toString()
                    }
                }
                ?: localCoverUri
            TrackSnapshot(
                sessionKey = uid.toString(),
                trackId = onlineTrack.id,
                title = onlineTrack.title,
                artist = onlineTrack.artist.ifBlank { primaryArtist },
                album = onlineTrack.album,
                genre = null,
                artworkUrl = resolvedArtwork,
                durationMs = durationMs,
                sourceType = PlaybackRecord.SOURCE_STREAM,
            )
        } else {
            val albumName = album.takeIf { it.name is Name.Known }?.name?.resolve(ctx)
            val genreName = genres.firstOrNull { it.name is Name.Known }?.name?.resolve(ctx)
            TrackSnapshot(
                sessionKey = uid.toString(),
                trackId = uid.toString(),
                title = name.resolve(ctx),
                artist = primaryArtist,
                album = albumName,
                genre = genreName,
                artworkUrl = localCoverUri,
                durationMs = durationMs,
                sourceType = PlaybackRecord.SOURCE_LOCAL,
            )
        }
    }

    companion object {
        const val MIN_PLAYED_THRESHOLD_MS = 30_000L
        const val SHORT_TRACK_COMPLETION_RATIO = 0.70
        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val FALLBACK_MAX_TRACK_MS = 600_000L

        /**
         * Returns true when playback lasted at least 30s, or >= 70% for tracks shorter than 30s.
         */
        fun isQualifyingPlayback(playedMs: Long, durationMs: Long): Boolean {
            if (playedMs <= 0L) return false
            if (playedMs >= MIN_PLAYED_THRESHOLD_MS) return true
            if (durationMs in 1L until MIN_PLAYED_THRESHOLD_MS) {
                return playedMs >= (durationMs * SHORT_TRACK_COMPLETION_RATIO).toLong()
            }
            return false
        }
    }
}
