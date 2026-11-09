package org.nov0caina.sounder.settings.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.DialogLibraryMigrationBinding
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.StorageDestination
import org.nov0caina.sounder.ui.ViewBindingMaterialDialogFragment
import org.nov0caina.sounder.util.LibraryMigrationManager
import org.nov0caina.sounder.util.MigrationPlan
import org.nov0caina.sounder.util.StorageVolumeHelper
import org.nov0caina.sounder.util.showToast
import timber.log.Timber as L

@AndroidEntryPoint
class LibraryMigrationDialog : ViewBindingMaterialDialogFragment<DialogLibraryMigrationBinding>() {

    @Inject lateinit var onlineSettings: OnlineSettings
    @Inject lateinit var musicRepository: MusicRepository

    private var activePlan: MigrationPlan? = null
    private var targetDestination: StorageDestination = StorageDestination.INTERNAL

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogLibraryMigrationBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.dialog_migrate_title)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.dialog_migrate_btn, null)
    }

    override fun onBindingCreated(binding: DialogLibraryMigrationBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        val context = requireContext()
        val currentStorage = onlineSettings.downloadStorage
        targetDestination = if (currentStorage == StorageDestination.INTERNAL) {
            StorageDestination.SD_CARD
        } else {
            StorageDestination.INTERNAL
        }

        if (targetDestination == StorageDestination.SD_CARD && !StorageVolumeHelper.isSdCardAvailable(context)) {
            binding.migrateDescription.text = getString(R.string.set_download_storage_sd_unavailable)
            return
        }

        val plan = LibraryMigrationManager.planMigration(context, currentStorage, targetDestination)
        activePlan = plan

        val fromTitle = if (currentStorage == StorageDestination.INTERNAL) {
            getString(R.string.set_download_storage_internal)
        } else {
            getString(R.string.set_download_storage_sd)
        }
        val toTitle = if (targetDestination == StorageDestination.INTERNAL) {
            getString(R.string.set_download_storage_internal)
        } else {
            getString(R.string.set_download_storage_sd)
        }

        binding.migrateDescription.text = getString(
            R.string.dialog_migrate_msg,
            plan.totalFiles,
            Formatter.formatFileSize(context, plan.totalBytes),
            fromTitle,
            toTitle,
            Formatter.formatFileSize(context, plan.destFreeBytes)
        )
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? AlertDialog ?: return
        val positiveButton = d.getButton(AlertDialog.BUTTON_POSITIVE)
        val negativeButton = d.getButton(AlertDialog.BUTTON_NEGATIVE)

        val plan = activePlan
        if (plan == null || !plan.hasEnoughSpace || (targetDestination == StorageDestination.SD_CARD && !StorageVolumeHelper.isSdCardAvailable(requireContext()))) {
            positiveButton.isEnabled = false
            if (plan != null && !plan.hasEnoughSpace) {
                requireBinding().migrateDescription.append("\n\n" + getString(R.string.dialog_migrate_insufficient_space))
            }
            return
        }

        positiveButton.setOnClickListener {
            positiveButton.isEnabled = false
            negativeButton.isEnabled = false
            isCancelable = false

            val binding = requireBinding()
            binding.migrateProgressBar.isVisible = true
            binding.migrateStatus.isVisible = true
            binding.migrateProgressBar.isIndeterminate = false
            binding.migrateProgressBar.progress = 0

            lifecycleScope.launch {
                val result = LibraryMigrationManager.executeMigration(
                    context = requireContext(),
                    plan = plan,
                    targetDestination = targetDestination,
                    onlineSettings = onlineSettings,
                    musicRepository = musicRepository
                ) { progress ->
                    binding.migrateProgressBar.progress = progress.percent
                    binding.migrateStatus.text = "${progress.currentFileName} (${progress.filesCopied}/${progress.totalFiles})"
                }

                if (result.isSuccess) {
                    requireContext().showToast(R.string.dialog_migrate_success)
                } else {
                    requireContext().showToast(R.string.dialog_migrate_error)
                }
                dismissAllowingStateLoss()
            }
        }
    }
}

