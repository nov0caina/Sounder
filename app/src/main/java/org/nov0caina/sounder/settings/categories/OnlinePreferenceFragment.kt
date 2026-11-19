package org.nov0caina.sounder.settings.categories

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.nov0caina.sounder.R
import org.nov0caina.sounder.online.cache.StreamingCacheManager
import org.nov0caina.sounder.settings.BasePreferenceFragment
import org.nov0caina.sounder.settings.ui.WrappedDialogPreference
import org.nov0caina.sounder.util.StorageVolumeHelper
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast
import timber.log.Timber as L

/**
 * Online streaming and downloads settings interface.
 */
@AndroidEntryPoint
class OnlinePreferenceFragment : BasePreferenceFragment(R.xml.preferences_online) {

    @Inject lateinit var streamingCacheManager: StreamingCacheManager

    private var clearCachePreference: Preference? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectImmediately(streamingCacheManager.cacheSizeBytes) { bytes ->
            updateCacheUsageSummary(bytes)
        }
    }

    override fun onResume() {
        super.onResume()
        streamingCacheManager.refreshCacheMetrics()
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_migrate_library)) {
            L.d("Navigating to library migration dialog")
            findNavController()
                .navigateSafe(OnlinePreferenceFragmentDirections.libraryMigrationSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        when (preference.key) {
            getString(R.string.set_key_download_storage) -> {
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
            getString(R.string.set_key_clear_streaming_cache) -> {
                clearCachePreference = preference
                updateCacheUsageSummary(streamingCacheManager.cacheSizeBytes.value)
                preference.setOnPreferenceClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        streamingCacheManager.clearCache()
                        updateCacheUsageSummary(0L)
                        context?.showToast(R.string.msg_streaming_cache_cleared)
                    }
                    true
                }
            }
        }
    }

    private fun updateCacheUsageSummary(bytes: Long) {
        val pref = clearCachePreference ?: return
        val usedMb = (bytes / (1024L * 1024L)).toInt()
        pref.summary = getString(R.string.fmt_streaming_cache_usage, usedMb)
    }
}
