/*
 * Copyright (c) 2021 Auxio Project
 * RootPreferenceFragment.kt is part of Auxio.
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
 
package org.nov0caina.sounder.settings

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.nov0caina.sounder.R
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.settings.ui.WrappedDialogPreference
import org.nov0caina.sounder.util.StoragePermissionHelper
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast
import timber.log.Timber as L

/**
 * The [PreferenceFragmentCompat] that displays the root settings list.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class RootPreferenceFragment : BasePreferenceFragment(R.xml.preferences_root) {
    private val musicModel: MusicViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialFadeThrough()
        returnTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>(getString(R.string.set_key_direct_deletion))?.let { pref ->
            updateDirectDeletionSummary(pref)
        }
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_music_dirs) -> {
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicLocationsSettings())
            }
            getString(R.string.set_key_migrate_library) -> {
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.libraryMigrationSettings())
            }
        }
    }

    override fun onSetupPreference(preference: Preference) {
        if (preference.key == getString(R.string.set_key_download_storage)) {
            val listPref = preference as? androidx.preference.ListPreference ?: return
            val hasSd = org.nov0caina.sounder.util.StorageVolumeHelper.isSdCardAvailable(requireContext())
            if (!hasSd) {
                listPref.entries = arrayOf(
                    getString(R.string.set_download_storage_internal),
                    getString(R.string.set_download_storage_sd_unavailable)
                )
                listPref.entryValues = arrayOf("INTERNAL", "INTERNAL")
            }
        } else if (preference.key == getString(R.string.set_key_direct_deletion)) {
            updateDirectDeletionSummary(preference)
        }
    }

    private fun updateDirectDeletionSummary(preference: Preference) {
        val context = context ?: return
        if (StoragePermissionHelper.hasAllFilesAccess(context)) {
            preference.summary = getString(R.string.set_direct_deletion_enabled_desc)
        } else {
            preference.summary = getString(R.string.set_direct_deletion_desc)
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Hook generic preferences to their specified preferences
        // TODO: These seem like good things to put into a side navigation view, if I choose to
        //  do one.
        when (preference.key) {
            getString(R.string.set_key_ui) -> {
                L.d("Navigating to UI preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.uiPreferences())
            }
            getString(R.string.set_key_personalize) -> {
                L.d("Navigating to personalization preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.personalizePreferences())
            }
            getString(R.string.set_key_music) -> {
                L.d("Navigating to music preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicPreferences())
            }
            getString(R.string.set_key_audio) -> {
                L.d("Navigating to audio preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.audioPeferences())
            }
            getString(R.string.set_key_direct_deletion) -> {
                val context = requireContext()
                if (!StoragePermissionHelper.hasAllFilesAccess(context)) {
                    try {
                        startActivity(StoragePermissionHelper.createManageAllFilesAccessIntent(context))
                    } catch (t: Throwable) {
                        context.showToast(R.string.err_no_app)
                    }
                } else {
                    context.showToast(R.string.set_direct_deletion_enabled_desc)
                }
            }
            getString(R.string.set_key_reindex) -> musicModel.refresh()
            getString(R.string.set_key_rescan) -> musicModel.rescan()
            else -> return super.onPreferenceTreeClick(preference)
        }

        return true
    }
}
