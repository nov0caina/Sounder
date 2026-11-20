/*
 * Copyright (c) 2025 Auxio Project
 * MusicFolderScanner.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.musikr.fs.mediastore

import android.annotation.SuppressLint
import android.content.Context
import android.provider.MediaStore as AOSPMediaStore
import androidx.core.database.getStringOrNull
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.path.MediaStorePathInterpreter
import org.oxycblt.musikr.fs.path.VolumeManager
import org.oxycblt.musikr.fs.saf.contentResolverSafe
import org.oxycblt.musikr.fs.saf.useQuery

data class DiscoveredMusicFolder(
    val path: Path,
    val location: Location.Unopened,
    val displayName: String,
    val songCount: Int,
    val isSounderManaged: Boolean = false,
)

internal data class ScannedAudioRow(
    val filePath: Path,
    val isMusic: Boolean = true,
    val isRingtone: Boolean = false,
    val isNotification: Boolean = false,
    val isAlarm: Boolean = false,
    val durationMs: Long = MediaStoreAudioFilter.DEFAULT_MIN_DURATION_MS,
)

interface MusicFolderScanner {
    suspend fun scan(
        excludeNonMusic: Boolean = true,
        excludeVoiceNotes: Boolean = true,
        excludeShortAudio: Boolean = true,
        minDurationMs: Long = MediaStoreAudioFilter.DEFAULT_MIN_DURATION_MS,
    ): List<DiscoveredMusicFolder>

    companion object {
        fun from(context: Context): MusicFolderScanner =
            MusicFolderScannerImpl(context.applicationContext, VolumeManager.from(context))
    }
}

private class MusicFolderScannerImpl(
    private val context: Context,
    private val volumeManager: VolumeManager,
) : MusicFolderScanner {
    private val pathInterpreterFactory = MediaStorePathInterpreter.Factory.from(volumeManager)

    @SuppressLint("InlinedApi")
    override suspend fun scan(
        excludeNonMusic: Boolean,
        excludeVoiceNotes: Boolean,
        excludeShortAudio: Boolean,
        minDurationMs: Long,
    ): List<DiscoveredMusicFolder> =
        withContext(Dispatchers.IO) {
            val baseProjection =
                arrayOf(
                    AOSPMediaStore.Audio.AudioColumns._ID,
                    AOSPMediaStore.Audio.AudioColumns.DATA,
                    AOSPMediaStore.Audio.AudioColumns.DURATION,
                    AOSPMediaStore.Audio.AudioColumns.IS_MUSIC,
                    AOSPMediaStore.Audio.AudioColumns.IS_RINGTONE,
                    AOSPMediaStore.Audio.AudioColumns.IS_NOTIFICATION,
                    AOSPMediaStore.Audio.AudioColumns.IS_ALARM,
                )
            val projection =
                (baseProjection.toList() + pathInterpreterFactory.projection.toList())
                    .distinct()
                    .toTypedArray()

            val rows = mutableListOf<ScannedAudioRow>()
            try {
                context.contentResolverSafe.useQuery(
                    AOSPMediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "NOT ${AOSPMediaStore.Audio.Media.SIZE}=0",
                    null,
                ) { cursor ->
                    val pathInterpreter = pathInterpreterFactory.wrap(cursor)
                    val dataIdx = cursor.getColumnIndex(AOSPMediaStore.Audio.AudioColumns.DATA)
                    val durationIdx =
                        cursor.getColumnIndex(AOSPMediaStore.Audio.AudioColumns.DURATION)
                    val isMusicIdx =
                        cursor.getColumnIndex(AOSPMediaStore.Audio.AudioColumns.IS_MUSIC)
                    val isRingtoneIdx =
                        cursor.getColumnIndex(AOSPMediaStore.Audio.AudioColumns.IS_RINGTONE)
                    val isNotifIdx =
                        cursor.getColumnIndex(AOSPMediaStore.Audio.AudioColumns.IS_NOTIFICATION)
                    val isAlarmIdx =
                        cursor.getColumnIndex(AOSPMediaStore.Audio.AudioColumns.IS_ALARM)

                    while (cursor.moveToNext()) {
                        val filePath = pathInterpreter.extract() ?: continue
                        val rawDataPath =
                            if (dataIdx != -1) cursor.getStringOrNull(dataIdx) else null
                        if (rawDataPath != null && !File(rawDataPath).exists()) {
                            continue
                        } else if (rawDataPath == null) {
                            val volComp = filePath.volume.components?.toString()
                            if (volComp != null && !File("/$volComp", filePath.components.toString()).exists()) {
                                continue
                            }
                        }

                        val isMusic = isMusicIdx == -1 || cursor.getInt(isMusicIdx) != 0
                        val isRingtone = isRingtoneIdx != -1 && cursor.getInt(isRingtoneIdx) != 0
                        val isNotification = isNotifIdx != -1 && cursor.getInt(isNotifIdx) != 0
                        val isAlarm = isAlarmIdx != -1 && cursor.getInt(isAlarmIdx) != 0
                        val durationMs =
                            if (durationIdx != -1) cursor.getLong(durationIdx) else Long.MAX_VALUE

                        rows.add(
                            ScannedAudioRow(
                                filePath = filePath,
                                isMusic = isMusic,
                                isRingtone = isRingtone,
                                isNotification = isNotification,
                                isAlarm = isAlarm,
                                durationMs = durationMs,
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {
                return@withContext emptyList()
            }

            aggregateDiscoveredFolders(
                rows = rows,
                excludeNonMusic = excludeNonMusic,
                excludeVoiceNotes = excludeVoiceNotes,
                excludeShortAudio = excludeShortAudio,
                minDurationMs = minDurationMs,
                volumeNameResolver = { volume -> volume.resolveName(context) },
            )
        }
}

internal fun aggregateDiscoveredFolders(
    rows: List<ScannedAudioRow>,
    excludeNonMusic: Boolean,
    excludeVoiceNotes: Boolean,
    excludeShortAudio: Boolean,
    minDurationMs: Long = MediaStoreAudioFilter.DEFAULT_MIN_DURATION_MS,
    volumeNameResolver: (Volume) -> String,
    locationFactory: (Path) -> Location.Unopened? = Location.Unopened::fromPath,
): List<DiscoveredMusicFolder> {
    data class BucketAccumulator(
        val path: Path,
        val isSounderManaged: Boolean,
        var count: Int = 0,
    )

    val buckets = linkedMapOf<Path, BucketAccumulator>()

    for (row in rows) {
        val dirPath = row.filePath.directory
        val isSounder = MediaStoreAudioFilter.isSounderPath(dirPath.components)
        if (!isSounder) {
            val included =
                MediaStoreAudioFilter.shouldIncludeAudio(
                    directoryComponents = dirPath.components,
                    isMusic = row.isMusic,
                    isRingtone = row.isRingtone,
                    isNotification = row.isNotification,
                    isAlarm = row.isAlarm,
                    durationMs = row.durationMs,
                    excludeNonMusic = excludeNonMusic,
                    excludeVoiceNotes = excludeVoiceNotes,
                    excludeShortAudio = excludeShortAudio,
                    minDurationMs = minDurationMs,
                )
            if (!included) continue
        }

        val bucketPath =
            when {
                isSounder -> Path(row.filePath.volume, Components.parseUnix("Music/Sounder"))
                dirPath.components.components.isEmpty() ->
                    Path(row.filePath.volume, Components.root())
                else ->
                    Path(
                        row.filePath.volume,
                        Components.parseUnix(dirPath.components.components.first()),
                    )
            }

        val acc =
            buckets.getOrPut(bucketPath) {
                BucketAccumulator(path = bucketPath, isSounderManaged = isSounder)
            }
        acc.count += 1
    }

    return buckets.values
        .mapNotNull { acc ->
            val location = locationFactory(acc.path) ?: return@mapNotNull null
            val displayName =
                if (acc.isSounderManaged) {
                    "Sounder"
                } else {
                    acc.path.name ?: volumeNameResolver(acc.path.volume)
                }
            DiscoveredMusicFolder(
                path = acc.path,
                location = location,
                displayName = displayName,
                songCount = acc.count,
                isSounderManaged = acc.isSounderManaged,
            )
        }
        .sortedWith(
            compareByDescending<DiscoveredMusicFolder> { it.isSounderManaged }
                .thenByDescending { it.songCount }
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
        )
}
