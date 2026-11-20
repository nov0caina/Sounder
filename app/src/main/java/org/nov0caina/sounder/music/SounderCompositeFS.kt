/*
 * Copyright (c) 2025 Auxio Project
 * SounderCompositeFS.kt is part of Auxio.
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
 
package org.nov0caina.sounder.music

import android.content.Context
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.fs.mediastore.MediaStore
import org.oxycblt.musikr.fs.saf.SAF
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * Composite [FS] that combines MediaStore-selected folders, manually added SAF folders, and
 * ensures downloaded tracks under `/Music/Sounder/` from [sounderFs] are always included without
 * duplicate indexing.
 */
class SounderCompositeFS(
    private val primarySources: List<FS>,
    private val sounderFs: FS,
) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        tryAsyncWith(files, Dispatchers.IO) { channel ->
            val seenUris = mutableSetOf<Uri>()
            val seenPathKeys = mutableSetOf<String>()

            suspend fun forwardUnique(fs: FS) {
                val subChannel = Channel<File>(Channel.UNLIMITED)
                val task = fs.explore(subChannel)
                for (file in subChannel) {
                    val uriNew = seenUris.add(file.uri)
                    val pathKey = normalizedPathKey(file)
                    val pathNew = pathKey == null || seenPathKeys.add(pathKey)
                    if (uriNew && pathNew) {
                        channel.send(file)
                    }
                }
                task.await().getOrThrow()
            }

            for (source in primarySources) {
                forwardUnique(source)
            }
            forwardUnique(sounderFs)
        }
    }

    override fun track(): Flow<FSUpdate> =
        (primarySources + sounderFs).map { it.track() }.merge()

    private fun normalizedPathKey(file: File): String? {
        val volume = file.path.volume
        if (volume is Volume.ThirdParty) return null
        val volId =
            volume.mediaStoreName
                ?: volume.components?.unixString
                ?: return null
        return "${volId.lowercase(Locale.ROOT)}:${file.path.components.unixString.lowercase(Locale.ROOT)}"
    }

    companion object {
        fun from(context: Context, musicSettings: MusicSettings): FS {
            val mediaStoreQuery = musicSettings.mediaStoreQuery
            val safQuery = musicSettings.safQuery
            val primarySources = buildList {
                if (
                    mediaStoreQuery.mode == MediaStore.FilterMode.EXCLUDE ||
                        mediaStoreQuery.filtered.isNotEmpty()
                ) {
                    add(MediaStore.from(context, mediaStoreQuery))
                }
                if (safQuery.source.isNotEmpty()) {
                    add(SAF.from(context, safQuery))
                }
            }
            val sounderFs =
                MediaStore.from(
                    context,
                    MediaStore.Query(
                        mode = MediaStore.FilterMode.INCLUDE,
                        filtered = emptyList(),
                        excludeNonMusic = false,
                        excludeVoiceNotes = false,
                        excludeShortAudio = false,
                    ),
                )
            return SounderCompositeFS(primarySources, sounderFs)
        }
    }
}
