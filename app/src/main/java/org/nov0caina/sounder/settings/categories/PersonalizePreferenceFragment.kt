/*
 * Copyright (c) 2023 Auxio Project
 * PersonalizePreferenceFragment.kt is part of Auxio.
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
 
package org.nov0caina.sounder.settings.categories

import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.R
import org.nov0caina.sounder.online.db.PlaybackRecordDao
import org.nov0caina.sounder.settings.BasePreferenceFragment
import org.nov0caina.sounder.settings.ui.WrappedDialogPreference
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast
import timber.log.Timber as L

/**
 * Personalization settings interface.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class PersonalizePreferenceFragment : BasePreferenceFragment(R.xml.preferences_personalize) {

    @Inject lateinit var playbackRecordDao: PlaybackRecordDao

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_home_tabs)) {
            L.d("Navigating to home tab dialog")
            findNavController().navigateSafe(PersonalizePreferenceFragmentDirections.tabSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        if (preference.key == getString(R.string.set_key_clear_listening_history)) {
            preference.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.set_clear_listening_history)
                    .setMessage(R.string.msg_confirm_clear_listening_history)
                    .setPositiveButton(R.string.btn_delete) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                playbackRecordDao.clearAll()
                            }
                            context?.showToast(R.string.msg_listening_history_cleared)
                        }
                    }
                    .setNegativeButton(R.string.lbl_cancel, null)
                    .show()
                true
            }
        }
    }
}
