/*
 * Copyright (c) 2025 Auxio Project
 * MusicFolderScannerTest.kt is part of Auxio.
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

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MusicFolderScannerTest {
    private data class FakeInternalVolume(
        override val mediaStoreName: String = "external_primary",
        override val components: Components = Components.parseUnix("storage/emulated/0"),
    ) : Volume.Internal {
        override fun resolveName(context: Context) = "Internal Storage"
    }

    private data class FakeExternalVolume(
        override val id: String = "1234-5678",
        override val mediaStoreName: String = "1234-5678",
        override val components: Components = Components.parseUnix("storage/1234-5678"),
    ) : Volume.External {
        override fun resolveName(context: Context) = "SD Card"
    }

    private val internal = FakeInternalVolume()
    private val sdCard = FakeExternalVolume()

    @Test
    fun aggregateDiscoveredFolders_groupsValidMusicAndOmitsNoiseByDefault() {
        val rows =
            listOf(
                // Valid internal Music tracks (nested under albums)
                ScannedAudioRow(Path(internal, Components.parseUnix("Music/Rock/track1.mp3"))),
                ScannedAudioRow(Path(internal, Components.parseUnix("Music/Jazz/track2.flac"))),
                // Sounder managed tracks (including a short intro < 30s)
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Music/Sounder/Artist/Album/intro.m4a")),
                    durationMs = 15_000L,
                ),
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Music/Sounder/Artist/Album/song.m4a")),
                    durationMs = 200_000L,
                ),
                // SD card music
                ScannedAudioRow(Path(sdCard, Components.parseUnix("Albums/Pop/hit.mp3"))),
                // Voice notes (should be omitted by default)
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("WhatsApp/Media/WhatsApp Audio/PTT-1.opus")),
                    durationMs = 45_000L,
                ),
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Telegram/Telegram Audio/voice.ogg")),
                    durationMs = 60_000L,
                ),
                // Ringtones & notifications (should be omitted by default)
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Ringtones/classic.ogg")),
                    isMusic = false,
                    isRingtone = true,
                    durationMs = 40_000L,
                ),
                // Short clip in Download (should be omitted by default)
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Download/sfx.mp3")),
                    durationMs = 5_000L,
                ),
                // Valid song in Download
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Download/podcast_song.mp3")),
                    durationMs = 210_000L,
                ),
            )

        val result =
            aggregateDiscoveredFolders(
                rows = rows,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = true,
                volumeNameResolver = {
                    when (it) {
                        is Volume.Internal -> "Internal Storage"
                        is Volume.External -> "SD Card"
                        else -> "Other"
                    }
                },
            )

        assertEquals(4, result.size)

        // Sounder should be first and marked as Sounder-managed with both tracks
        val sounder = result[0]
        assertTrue(sounder.isSounderManaged)
        assertEquals("Sounder", sounder.displayName)
        assertEquals(2, sounder.songCount)
        assertEquals("Music/Sounder", sounder.path.components.unixString)

        // Music folder has 2 tracks (Sounder excluded from Music count)
        val music = result.first { it.path.components.unixString == "Music" }
        assertFalse(music.isSounderManaged)
        assertEquals(2, music.songCount)

        // Download folder has 1 valid song (5s clip omitted)
        val download = result.first { it.path.components.unixString == "Download" }
        assertEquals(1, download.songCount)

        // SD Card Albums folder has 1 track
        val sdAlbums = result.first { it.path.volume == sdCard }
        assertEquals("Albums", sdAlbums.displayName)
        assertEquals(1, sdAlbums.songCount)

        // WhatsApp, Telegram, and Ringtones must not appear
        assertFalse(result.any { it.displayName.contains("WhatsApp") })
        assertFalse(result.any { it.displayName.contains("Telegram") })
        assertFalse(result.any { it.displayName.contains("Ringtones") })
    }

    @Test
    fun aggregateDiscoveredFolders_respectsDisabledFilters() {
        val rows =
            listOf(
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("WhatsApp/Media/WhatsApp Audio/PTT-1.opus")),
                    durationMs = 45_000L,
                ),
                ScannedAudioRow(
                    Path(internal, Components.parseUnix("Download/short_jingle.mp3")),
                    durationMs = 8_000L,
                ),
            )

        val result =
            aggregateDiscoveredFolders(
                rows = rows,
                excludeNonMusic = true,
                excludeVoiceNotes = false,
                excludeShortAudio = false,
                volumeNameResolver = { "Internal Storage" },
            )

        assertEquals(2, result.size)
        assertTrue(result.any { it.path.components.unixString == "WhatsApp" && it.songCount == 1 })
        assertTrue(result.any { it.path.components.unixString == "Download" && it.songCount == 1 })
    }
}
