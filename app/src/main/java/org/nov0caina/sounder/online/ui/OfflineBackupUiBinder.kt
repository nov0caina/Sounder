package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemHomeMoodChipBinding
import org.nov0caina.sounder.databinding.LayoutOfflineBackupBinding
import org.nov0caina.sounder.online.cache.OfflineBackupFilter
import org.nov0caina.sounder.online.cache.OfflineBackupFilterMode
import org.nov0caina.sounder.online.cache.OfflineBackupFilterState
import org.nov0caina.sounder.online.model.OnlineTrack

/**
 * Shared UI binding helper for the "Respaldo Musical" (Offline Backup) filter chips and header,
 * reused across both HomeFeedFragment (offline takeover) and OfflineBackupListFragment (Tu Biblioteca).
 */
object OfflineBackupUiBinder {

    fun bindFilterChips(
        binding: LayoutOfflineBackupBinding,
        onModeSelected: (OfflineBackupFilterMode) -> Unit,
    ) {
        binding.chipBackupAll.setOnClickListener {
            onModeSelected(OfflineBackupFilterMode.ALL)
        }
        binding.chipBackupArtists.setOnClickListener {
            onModeSelected(OfflineBackupFilterMode.ARTISTS)
        }
        binding.chipBackupAlbums.setOnClickListener {
            onModeSelected(OfflineBackupFilterMode.ALBUMS)
        }
    }

    fun renderFilterState(
        binding: LayoutOfflineBackupBinding,
        allTracks: List<OnlineTrack>,
        state: OfflineBackupFilterState,
        onValueSelected: (String?) -> Unit,
    ) {
        val targetChipId = when (state.mode) {
            OfflineBackupFilterMode.ALL -> R.id.chip_backup_all
            OfflineBackupFilterMode.ARTISTS -> R.id.chip_backup_artists
            OfflineBackupFilterMode.ALBUMS -> R.id.chip_backup_albums
        }
        if (binding.offlineBackupFilterChips.checkedChipId != targetChipId) {
            binding.offlineBackupFilterChips.check(targetChipId)
        }

        val values = OfflineBackupFilter.availableValues(allTracks, state.mode)
        if (state.mode == OfflineBackupFilterMode.ALL || values.isEmpty()) {
            binding.offlineBackupSubChipsScroll.isVisible = false
            binding.offlineBackupSubChips.removeAllViews()
            return
        }

        binding.offlineBackupSubChipsScroll.isVisible = true
        val chipGroup = binding.offlineBackupSubChips
        val inflater = LayoutInflater.from(chipGroup.context)
        chipGroup.removeAllViews()

        for (value in values) {
            val chipBinding = ItemHomeMoodChipBinding.inflate(inflater, chipGroup, false)
            val chip: Chip = chipBinding.moodChip
            chip.id = android.view.View.generateViewId()
            chip.text = value
            chip.isCheckable = true
            chip.isChecked = state.selectedValue.equals(value, ignoreCase = true)
            chip.setOnClickListener {
                onValueSelected(value)
            }
            chipGroup.addView(chip)
        }
    }
}
