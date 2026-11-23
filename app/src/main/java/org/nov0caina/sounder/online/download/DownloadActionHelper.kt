package org.nov0caina.sounder.online.download

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.R
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.util.StoragePermissionHelper
import org.nov0caina.sounder.util.showToast

/**
 * Shared interactive controller for single-track download/delete/queued buttons
 * across Now Playing, Home feed, Discover, Artist profile, Album detail, Offline Backup, and Menus.
 */
object DownloadActionHelper {

    fun handleTrackDownloadClick(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        downloadManager: DownloadManager,
        track: OnlineTrack,
        onStateChanged: () -> Unit = {},
        onDeleted: () -> Unit = {},
        onRequestPermission: (() -> Unit)? = null
    ) {
        if (downloadManager.isEnqueuedOrDownloading(track)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_download_waiting_title)
                .setMessage(R.string.dialog_download_waiting_message)
                .setPositiveButton(R.string.btn_download_now) { _, _ ->
                    downloadManager.enqueue(track, allowMeteredNetwork = true)
                    context.showToast(context.getString(R.string.download_queued, track.title))
                    onStateChanged()
                }
                .setNeutralButton(R.string.lbl_delete_download) { _, _ ->
                    downloadManager.cancel(track.id)
                    downloadManager.removeDownload(track.id)
                    onStateChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (downloadManager.isDownloaded(track)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lbl_delete_download)
                .setMessage(R.string.msg_confirm_delete_song)
                .setPositiveButton(R.string.btn_delete) { _, _ ->
                    lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val dummySong = OnlineSong(track, Uri.parse("sounder://stream/${track.id}"))
                        val success = downloadManager.deleteDownloadedSong(dummySong)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                context.showToast(R.string.msg_download_deleted)
                                onDeleted()
                                onStateChanged()
                            } else {
                                context.showToast(R.string.err_delete_failed)
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (!StoragePermissionHelper.hasPermission(context)) {
            onRequestPermission?.invoke()
            context.showToast(R.string.msg_storage_permission_required)
            return
        }

        if (downloadManager.requiresCellularConfirmation(track)) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_download_cellular_title)
                .setMessage(R.string.dialog_download_cellular_message)
                .setPositiveButton(R.string.btn_download_now) { _, _ ->
                    downloadManager.enqueue(track, allowMeteredNetwork = true)
                    context.showToast(context.getString(R.string.download_queued, track.title))
                    onStateChanged()
                }
                .setNeutralButton(R.string.btn_wait_for_wifi) { _, _ ->
                    downloadManager.enqueue(track, allowMeteredNetwork = false)
                    context.showToast(context.getString(R.string.msg_download_waiting_wifi, track.title))
                    onStateChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            downloadManager.enqueue(track, allowMeteredNetwork = false)
            context.showToast(context.getString(R.string.download_queued, track.title))
            onStateChanged()
        }
    }
}
