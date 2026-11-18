package org.nov0caina.sounder.settings.categories

import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.nov0caina.sounder.R
import org.nov0caina.sounder.settings.BasePreferenceFragment
import org.nov0caina.sounder.settings.ui.WrappedDialogPreference
import org.nov0caina.sounder.util.StorageVolumeHelper
import org.nov0caina.sounder.util.navigateSafe
import timber.log.Timber as L

/**
 * Online streaming and downloads settings interface.
 */
class OnlinePreferenceFragment : BasePreferenceFragment(R.xml.preferences_online) {

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_migrate_library)) {
            L.d("Navigating to library migration dialog")
            findNavController()
                .navigateSafe(OnlinePreferenceFragmentDirections.libraryMigrationSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        if (preference.key == getString(R.string.set_key_download_storage)) {
            val listPref = preference as? ListPreference ?: return
            val hasSd = StorageVolumeHelper.isSdCardAvailable(requireContext())
            if (!hasSd) {
                listPref.entries = arrayOf(
                    getString(R.string.set_download_storage_internal),
                    getString(R.string.set_download_storage_sd_unavailable),
                )
                listPref.entryValues = arrayOf("INTERNAL", "INTERNAL")
            }
        }
    }
}
