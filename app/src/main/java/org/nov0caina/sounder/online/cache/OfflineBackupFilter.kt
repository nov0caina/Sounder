package org.nov0caina.sounder.online.cache

import org.nov0caina.sounder.online.model.OnlineTrack

enum class OfflineBackupFilterMode {
    ALL,
    ARTISTS,
    ALBUMS,
}

data class OfflineBackupFilterState(
    val mode: OfflineBackupFilterMode = OfflineBackupFilterMode.ALL,
    val selectedValue: String? = null,
)

/**
 * Pure filtering and grouping logic for the "Respaldo Musical" (Offline Backup) quick filter chips.
 */
object OfflineBackupFilter {

    fun availableValues(
        tracks: List<OnlineTrack>,
        mode: OfflineBackupFilterMode,
    ): List<String> = when (mode) {
        OfflineBackupFilterMode.ALL -> emptyList()
        OfflineBackupFilterMode.ARTISTS -> tracks
            .map { it.artist.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        OfflineBackupFilterMode.ALBUMS -> tracks
            .mapNotNull { it.album?.trim()?.takeIf { album -> album.isNotEmpty() } }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }

    fun filterTracks(
        tracks: List<OnlineTrack>,
        state: OfflineBackupFilterState,
    ): List<OnlineTrack> = when (state.mode) {
        OfflineBackupFilterMode.ALL -> tracks
        OfflineBackupFilterMode.ARTISTS -> {
            val target = state.selectedValue?.trim()
            if (!target.isNullOrEmpty()) {
                tracks.filter { it.artist.trim().equals(target, ignoreCase = true) }
            } else {
                tracks.sortedBy { it.artist.trim().lowercase() }
            }
        }
        OfflineBackupFilterMode.ALBUMS -> {
            val target = state.selectedValue?.trim()
            if (!target.isNullOrEmpty()) {
                tracks.filter { it.album?.trim().equals(target, ignoreCase = true) }
            } else {
                tracks.sortedBy { (it.album?.trim()?.takeIf { a -> a.isNotEmpty() } ?: "\uFFFF").lowercase() }
            }
        }
    }
}
