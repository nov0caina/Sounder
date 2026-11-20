/*
 * Copyright (c) 2025 Auxio Project
 * DeviceMusicScanLauncher.kt is part of Auxio.
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
 
package org.nov0caina.sounder.music.locations

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.nov0caina.sounder.R
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.showToast

/**
 * Coordinates runtime audio permission checks, device-wide music folder scanning, and user
 * feedback messages from Settings screens.
 */
class DeviceMusicScanLauncher(
    private val fragment: Fragment,
    private val musicModelProvider: () -> MusicViewModel,
) {
    private val permissionLauncher =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                musicModelProvider().scanAndIndexDeviceMusic()
            } else {
                fragment.requireContext().showToast(R.string.lng_grant_storage_discover)
            }
        }

    fun observeMessages() {
        val model = musicModelProvider()
        fragment.collect(model.deviceScanMessage.flow) { messageRes ->
            if (messageRes != null) {
                fragment.requireContext().showToast(messageRes)
                model.deviceScanMessage.consume()
            }
        }
    }

    fun launch() {
        val context = fragment.requireContext()
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        if (
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            musicModelProvider().scanAndIndexDeviceMusic()
        } else {
            permissionLauncher.launch(permission)
        }
    }
}
