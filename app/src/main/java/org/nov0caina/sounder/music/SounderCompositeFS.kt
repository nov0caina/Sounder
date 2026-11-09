package org.nov0caina.sounder.music

import android.net.Uri
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.util.tryAsyncWith

/**
 * Composite [FS] that wraps a primary [FS] (e.g. SAF) and ensures that downloaded tracks
 * under /Music/Sounder/ from [sounderFs] (e.g. MediaStore) are always included in the index.
 */
class SounderCompositeFS(
    private val primaryFs: FS,
    private val sounderFs: FS,
) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        tryAsyncWith(files, Dispatchers.IO) { channel ->
            val seenUris = mutableSetOf<Uri>()
            val primaryChannel = Channel<File>(Channel.UNLIMITED)
            val primaryTask = primaryFs.explore(primaryChannel)
            for (file in primaryChannel) {
                seenUris.add(file.uri)
                channel.send(file)
            }
            primaryTask.await().getOrThrow()

            val sounderChannel = Channel<File>(Channel.UNLIMITED)
            val sounderTask = sounderFs.explore(sounderChannel)
            for (file in sounderChannel) {
                val pathString = file.path.components.toString()
                if (pathString.contains("Sounder", ignoreCase = true) && seenUris.add(file.uri)) {
                    channel.send(file)
                }
            }
            sounderTask.await().getOrThrow()
        }
    }

    override fun track(): Flow<FSUpdate> = merge(primaryFs.track(), sounderFs.track())
}
