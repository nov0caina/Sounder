/*
 * Copyright (c) 2025 Auxio Project
 * MediaStoreAudioFilter.kt is part of Auxio.
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
import android.provider.MediaStore as AOSPMediaStore
import java.util.Locale
import org.oxycblt.musikr.fs.Components

/**
 * Shared MediaStore audio filtering rules that exclude system tones, messaging voice notes,
 * call/voice recordings, and short non-music clips while keeping Sounder downloads protected.
 */
object MediaStoreAudioFilter {
    const val DEFAULT_MIN_DURATION_MS = 30_000L

    private val RECORDER_SEGMENTS =
        setOf(
            "recordings",
            "callrecordings",
            "call recordings",
            "call_recordings",
            "voice recorder",
            "voicerecorder",
            "voice notes",
            "voicenotes",
            "voice recordings",
            "voice_recordings",
            "sound_recorder",
            "soundrecorder",
            "callrec",
        )

    private val SYSTEM_TONE_SEGMENTS = setOf("ringtones", "notifications", "alarms")

    @SuppressLint("InlinedApi")
    fun buildSqlSelection(
        excludeNonMusic: Boolean,
        excludeShortAudio: Boolean,
        minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    ): String {
        val parts = mutableListOf("NOT ${AOSPMediaStore.Audio.Media.SIZE}=0")
        if (excludeNonMusic) {
            parts.add("${AOSPMediaStore.Audio.AudioColumns.IS_MUSIC}=1")
            parts.add(
                "(${AOSPMediaStore.Audio.AudioColumns.IS_RINGTONE}=0 OR ${AOSPMediaStore.Audio.AudioColumns.IS_RINGTONE} IS NULL)"
            )
            parts.add(
                "(${AOSPMediaStore.Audio.AudioColumns.IS_NOTIFICATION}=0 OR ${AOSPMediaStore.Audio.AudioColumns.IS_NOTIFICATION} IS NULL)"
            )
            parts.add(
                "(${AOSPMediaStore.Audio.AudioColumns.IS_ALARM}=0 OR ${AOSPMediaStore.Audio.AudioColumns.IS_ALARM} IS NULL)"
            )
        }
        if (excludeShortAudio && minDurationMs > 0) {
            parts.add("${AOSPMediaStore.Audio.AudioColumns.DURATION}>=$minDurationMs")
        }
        return parts.joinToString(" AND ")
    }

    fun isSounderPath(components: Components): Boolean {
        val list = components.components
        return list.size >= 2 &&
            list[0].equals("Music", ignoreCase = true) &&
            list[1].equals("Sounder", ignoreCase = true)
    }

    fun isVoiceNoteOrRecorderPath(directoryPath: String): Boolean {
        val normalized = directoryPath.replace('\\', '/').trim('/').lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        if (normalized == "music/sounder" || normalized.startsWith("music/sounder/")) {
            return false
        }
        if (normalized == "android/media" || normalized.startsWith("android/media/")) {
            return true
        }
        if (normalized.contains("miui/sound_recorder")) {
            return true
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        return segments.any { segment ->
            segment.contains("whatsapp") ||
                segment.contains("telegram") ||
                segment in RECORDER_SEGMENTS
        }
    }

    fun isSystemTonePath(directoryPath: String): Boolean {
        val normalized = directoryPath.replace('\\', '/').trim('/').lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        return segments.any { it in SYSTEM_TONE_SEGMENTS }
    }

    fun shouldIncludeAudio(
        directoryComponents: Components,
        isMusic: Boolean,
        isRingtone: Boolean,
        isNotification: Boolean,
        isAlarm: Boolean,
        durationMs: Long,
        excludeNonMusic: Boolean,
        excludeVoiceNotes: Boolean,
        excludeShortAudio: Boolean,
        minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    ): Boolean {
        if (excludeNonMusic) {
            if (!isMusic || isRingtone || isNotification || isAlarm) {
                return false
            }
            if (isSystemTonePath(directoryComponents.unixString)) {
                return false
            }
        }
        if (
            excludeVoiceNotes &&
                !isSounderPath(directoryComponents) &&
                isVoiceNoteOrRecorderPath(directoryComponents.unixString)
        ) {
            return false
        }
        if (excludeShortAudio && minDurationMs > 0 && durationMs < minDurationMs) {
            return false
        }
        return true
    }
}
