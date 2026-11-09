/*
 * Copyright (c) 2024 Auxio Project
 * TagLibJNI.kt is part of Auxio.
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
 
package org.oxycblt.musikr.metadata

import java.io.FileInputStream
import org.oxycblt.musikr.fs.File

object TagLibJNI {
    init {
        System.loadLibrary("tagJNI")
    }

    /**
     * Open a file and extract a tag.
     *
     * Note: This method is blocking and should be handled as such if calling from a coroutine.
     */
    fun open(deviceFile: File, fis: FileInputStream): MetadataResult {
        val inputStream = NativeInputStream(deviceFile, fis)
        val tag = openNative(inputStream)
        inputStream.close()
        return tag
    }

    /**
     * Writes metadata tags (title, artist, album, genre, cover artwork, track number, year) to an audio file on disk.
     */
    fun writeTags(
        path: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        coverData: ByteArray? = null,
        track: Int = 0,
        year: Int = 0
    ): Boolean {
        return writeTagsNative(path, title, artist, album, genre, coverData, track, year)
    }

    private external fun openNative(inputStream: NativeInputStream): MetadataResult

    private external fun writeTagsNative(
        path: String,
        title: String?,
        artist: String?,
        album: String?,
        genre: String?,
        coverData: ByteArray?,
        track: Int,
        year: Int
    ): Boolean
}
