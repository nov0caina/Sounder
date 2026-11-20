/*
 * Copyright (c) 2025 Auxio Project
 * MediaStoreAudioFilterTest.kt is part of Auxio.
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

import android.provider.MediaStore as AOSPMediaStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.oxycblt.musikr.fs.Components
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MediaStoreAudioFilterTest {
    @Test
    fun buildSqlSelection_defaultsIncludeMusicToneAndDurationConstraints() {
        val sql =
            MediaStoreAudioFilter.buildSqlSelection(
                excludeNonMusic = true,
                excludeShortAudio = true,
            )
        assertTrue(sql.contains("NOT ${AOSPMediaStore.Audio.Media.SIZE}=0"))
        assertTrue(sql.contains("${AOSPMediaStore.Audio.AudioColumns.IS_MUSIC}=1"))
        assertTrue(sql.contains("${AOSPMediaStore.Audio.AudioColumns.IS_RINGTONE}=0"))
        assertTrue(sql.contains("${AOSPMediaStore.Audio.AudioColumns.IS_NOTIFICATION}=0"))
        assertTrue(sql.contains("${AOSPMediaStore.Audio.AudioColumns.IS_ALARM}=0"))
        assertTrue(sql.contains("${AOSPMediaStore.Audio.AudioColumns.DURATION}>=30000"))
    }

    @Test
    fun buildSqlSelection_disabledFiltersOmitOptionalConstraints() {
        val sql =
            MediaStoreAudioFilter.buildSqlSelection(
                excludeNonMusic = false,
                excludeShortAudio = false,
            )
        assertTrue(sql.contains("NOT ${AOSPMediaStore.Audio.Media.SIZE}=0"))
        assertFalse(sql.contains(AOSPMediaStore.Audio.AudioColumns.IS_MUSIC))
        assertFalse(sql.contains(AOSPMediaStore.Audio.AudioColumns.DURATION))
    }

    @Test
    fun isVoiceNoteOrRecorderPath_matchesMessagingAndRecorderFoldersOnly() {
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("WhatsApp/Media/WhatsApp Audio"))
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"))
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Telegram/Telegram Audio"))
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Recordings/Voice Notes"))
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Voice Recorder"))
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("CallRecordings"))
        assertTrue(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("MIUI/sound_recorder/call_rec"))

        assertFalse(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Music"))
        assertFalse(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Music/T-Rex"))
        assertFalse(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Music/Sounder/WhatsApp Artist"))
        assertFalse(MediaStoreAudioFilter.isVoiceNoteOrRecorderPath("Download"))
    }

    @Test
    fun shouldIncludeAudio_filtersVoiceNotesSystemTonesAndShortClips() {
        val musicDir = Components.parseUnix("Music/Rock")
        val whatsappDir = Components.parseUnix("WhatsApp/Media/WhatsApp Audio")
        val ringtonesDir = Components.parseUnix("Ringtones")

        // Valid music >= 30s
        assertTrue(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = musicDir,
                isMusic = true,
                isRingtone = false,
                isNotification = false,
                isAlarm = false,
                durationMs = 180_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = true,
            )
        )

        // Short audio < 30s filtered when enabled, allowed when disabled
        assertFalse(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = musicDir,
                isMusic = true,
                isRingtone = false,
                isNotification = false,
                isAlarm = false,
                durationMs = 12_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = true,
            )
        )
        assertTrue(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = musicDir,
                isMusic = true,
                isRingtone = false,
                isNotification = false,
                isAlarm = false,
                durationMs = 12_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = false,
            )
        )

        // Ringtones / notifications / alarms / non-music excluded by default
        assertFalse(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = musicDir,
                isMusic = true,
                isRingtone = true,
                isNotification = false,
                isAlarm = false,
                durationMs = 45_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = true,
            )
        )
        assertFalse(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = ringtonesDir,
                isMusic = true,
                isRingtone = false,
                isNotification = false,
                isAlarm = false,
                durationMs = 45_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = true,
            )
        )

        // Voice notes excluded when enabled, included if disabled
        assertFalse(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = whatsappDir,
                isMusic = true,
                isRingtone = false,
                isNotification = false,
                isAlarm = false,
                durationMs = 45_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = true,
                excludeShortAudio = true,
            )
        )
        assertTrue(
            MediaStoreAudioFilter.shouldIncludeAudio(
                directoryComponents = whatsappDir,
                isMusic = true,
                isRingtone = false,
                isNotification = false,
                isAlarm = false,
                durationMs = 45_000L,
                excludeNonMusic = true,
                excludeVoiceNotes = false,
                excludeShortAudio = true,
            )
        )
    }
}
