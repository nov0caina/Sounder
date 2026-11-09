/*
 * Copyright (c) 2026 Sounder Project
 * UpdateDialogHelper.kt is part of Sounder.
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

package org.nov0caina.sounder.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.nov0caina.sounder.BuildConfig
import org.nov0caina.sounder.R
import org.nov0caina.sounder.util.openInBrowser
import org.nov0caina.sounder.util.showToast

/**
 * Helper object providing standardized Material 3 dialogs and notifications for update events.
 */
object UpdateDialogHelper {

    /**
     * Displays a Material 3 dialog informing the user that a new release is available on GitHub.
     *
     * @param activity Hosting activity for the dialog context.
     * @param updateInfo Details regarding the available update.
     * @param onIgnore Callback executed when the user chooses to ignore this version.
     */
    fun showUpdateAvailableDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        onIgnore: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val cleanBody = if (updateInfo.body.length > 500) {
            updateInfo.body.take(500).trimEnd() + "…"
        } else {
            updateInfo.body
        }

        val message = activity.getString(
            R.string.lbl_update_available_msg,
            updateInfo.tagName,
            BuildConfig.VERSION_NAME,
            cleanBody
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.lbl_update_available)
            .setMessage(message)
            .setPositiveButton(R.string.lbl_update_view) { _, _ ->
                activity.openInBrowser(updateInfo.htmlUrl)
            }
            .setNegativeButton(R.string.lbl_update_later, null)
            .setNeutralButton(R.string.lbl_update_ignore) { _, _ ->
                onIgnore()
            }
            .show()
    }

    /**
     * Displays an informational dialog for F-Droid builds explaining that updates are managed
     * directly by the F-Droid client and provides a shortcut to open the F-Droid store page.
     */
    fun showFDroidInfoDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.lbl_check_updates)
            .setMessage(R.string.lbl_update_managed_by_fdroid)
            .setPositiveButton(R.string.lbl_open_fdroid) { _, _ ->
                try {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=${activity.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (_: Exception) {
                    activity.openInBrowser("https://f-droid.org/packages/${activity.packageName}")
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Displays a toast indicating the application is on the latest version.
     */
    fun showUpToDateToast(context: Context) {
        context.showToast(context.getString(R.string.lbl_update_latest, BuildConfig.VERSION_NAME))
    }

    /**
     * Displays a toast indicating the update check failed due to network or connection issues.
     */
    fun showErrorToast(context: Context) {
        context.showToast(R.string.err_update_check_failed)
    }

    /**
     * Displays a toast indicating an update check is in progress.
     */
    fun showCheckingToast(context: Context) {
        context.showToast(R.string.lbl_checking_updates)
    }
}
