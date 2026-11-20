/*
 * Copyright (c) 2024 Auxio Project
 * LocationsDialog.kt is part of Auxio.
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
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.DialogMusicLocationsBinding
import org.nov0caina.sounder.music.MusicSettings
import org.nov0caina.sounder.ui.ViewBindingMaterialDialogFragment
import org.nov0caina.sounder.util.showToast
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.mediastore.DiscoveredMusicFolder
import org.oxycblt.musikr.fs.mediastore.MediaStore
import org.oxycblt.musikr.fs.mediastore.MusicFolderScanner
import timber.log.Timber as L

@AndroidEntryPoint
class LocationsDialog : ViewBindingMaterialDialogFragment<DialogMusicLocationsBinding>() {

    private val locationListener =
        object : LocationAdapter.Listener {
            override fun onToggleDiscovered(folder: DiscoveredMusicFolder, isChecked: Boolean) {
                if (isChecked) {
                    selectedDiscoveredPaths.add(folder.path)
                } else {
                    selectedDiscoveredPaths.remove(folder.path)
                }
            }

            override fun onRemoveCustom(location: Location.Opened) {
                customSafLocations.removeAll { it.uri == location.uri }
                renderFolders()
            }
        }

    private val locationAdapter = LocationAdapter(locationListener)
    private var openDocumentTreeLauncher: ActivityResultLauncher<Uri?>? = null
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    @Inject lateinit var musicSettings: MusicSettings

    private var discoveredFolders: List<DiscoveredMusicFolder> = emptyList()
    private val selectedDiscoveredPaths = mutableSetOf<Path>()
    private val customSafLocations = mutableListOf<Location.Opened>()
    private var hasStoragePermission = false
    private var isFiltersExpanded = false
    private var permissionGrantedInSession = false
    private var rescannedInSession = false
    private var hasCompletedScan = false
    private var scanJob: Job? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogMusicLocationsBinding.inflate(inflater)

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.set_locations)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.lbl_save) { _, _ -> saveChanges() }
    }

    override fun onBindingCreated(
        binding: DialogMusicLocationsBinding,
        savedInstanceState: Bundle?,
    ) {
        openDocumentTreeLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                handleAddedDocumentTree(uri)
            }

        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                L.d("Storage permission granted: $isGranted")
                hasStoragePermission = isGranted
                if (isGranted) {
                    permissionGrantedInSession = true
                }
                updatePermissionCardVisibility(binding)
                scanFolders(binding)
            }

        binding.locationsRecycler.apply {
            adapter = locationAdapter
            itemAnimator = null
        }

        loadInitialState(binding)

        binding.locationsPermsCard.setOnClickListener { requestStoragePermission() }

        binding.locationsRescanButton.setOnClickListener {
            if (!hasStoragePermission) {
                requestStoragePermission()
            } else {
                rescannedInSession = true
                scanFolders(binding)
            }
        }

        binding.locationsAddFolder.setOnClickListener { openDocumentTreePicker() }

        binding.locationsFiltersDropdown.setOnClickListener {
            isFiltersExpanded = !isFiltersExpanded
            updateFiltersVisibility(binding)
        }

        binding.locationsExcludeShortAudio.setOnClickListener {
            binding.locationsExcludeShortAudioSwitch.toggle()
        }
        binding.locationsExcludeVoiceNotes.setOnClickListener {
            binding.locationsExcludeVoiceNotesSwitch.toggle()
        }
        binding.locationsExcludeNonMusic.setOnClickListener {
            binding.locationsExcludeNonMusicSwitch.toggle()
        }

        val onFilterToggled = { _: Any, _: Boolean -> scanFolders(binding) }
        binding.locationsExcludeShortAudioSwitch.setOnCheckedChangeListener(onFilterToggled)
        binding.locationsExcludeVoiceNotesSwitch.setOnCheckedChangeListener(onFilterToggled)
        binding.locationsExcludeNonMusicSwitch.setOnCheckedChangeListener(onFilterToggled)

        updatePermissionCardVisibility(binding)
        updateFiltersVisibility(binding)
        scanFolders(binding)
    }

    override fun onDestroyBinding(binding: DialogMusicLocationsBinding) {
        super.onDestroyBinding(binding)
        scanJob?.cancel()
        scanJob = null
        openDocumentTreeLauncher = null
        storagePermissionLauncher = null
        binding.locationsRecycler.adapter = null
    }

    private fun loadInitialState(binding: DialogMusicLocationsBinding) {
        hasStoragePermission = checkStoragePermission()

        val mediaStoreQuery = musicSettings.mediaStoreQuery
        selectedDiscoveredPaths.clear()
        selectedDiscoveredPaths.addAll(mediaStoreQuery.filtered.map { it.path })

        customSafLocations.clear()
        customSafLocations.addAll(musicSettings.safQuery.source)

        binding.locationsExcludeShortAudioSwitch.isChecked = mediaStoreQuery.excludeShortAudio
        binding.locationsExcludeVoiceNotesSwitch.isChecked = mediaStoreQuery.excludeVoiceNotes
        binding.locationsExcludeNonMusicSwitch.isChecked = mediaStoreQuery.excludeNonMusic
    }

    private fun scanFolders(binding: DialogMusicLocationsBinding) {
        scanJob?.cancel()
        hasCompletedScan = false
        if (!hasStoragePermission) {
            discoveredFolders = emptyList()
            binding.locationsScanProgress.isVisible = false
            binding.locationsRescanButton.isEnabled = true
            renderFolders()
            return
        }

        val excludeNonMusic = binding.locationsExcludeNonMusicSwitch.isChecked
        val excludeVoiceNotes = binding.locationsExcludeVoiceNotesSwitch.isChecked
        val excludeShortAudio = binding.locationsExcludeShortAudioSwitch.isChecked

        binding.locationsScanProgress.isVisible = true
        binding.locationsRescanButton.isEnabled = false

        val appContext = requireContext().applicationContext
        scanJob =
            viewLifecycleOwner.lifecycleScope.launch {
                val folders =
                    MusicFolderScanner.from(appContext)
                        .scan(
                            excludeNonMusic = excludeNonMusic,
                            excludeVoiceNotes = excludeVoiceNotes,
                            excludeShortAudio = excludeShortAudio,
                        )
                if (view == null) return@launch
                discoveredFolders = folders
                hasCompletedScan = true
                binding.locationsScanProgress.isVisible = false
                binding.locationsRescanButton.isEnabled = true
                renderFolders()
            }
    }

    private fun renderFolders() {
        val discoveredPaths = discoveredFolders.map { it.path }.toSet()
        val items = buildList {
            for (folder in discoveredFolders) {
                val isSelected = folder.isSounderManaged || folder.path in selectedDiscoveredPaths
                add(FolderItem.Discovered(folder, isSelected))
            }
            for (custom in customSafLocations) {
                if (custom.path !in discoveredPaths) {
                    add(FolderItem.Custom(custom))
                }
            }
        }
        locationAdapter.submitItems(items)
    }

    private fun updatePermissionCardVisibility(binding: DialogMusicLocationsBinding) {
        binding.locationsPermsContainer.isVisible = !hasStoragePermission
    }

    private fun updateFiltersVisibility(binding: DialogMusicLocationsBinding) {
        binding.locationsFiltersContainer.isVisible = isFiltersExpanded
        binding.locationsFiltersDropdownIcon.rotation = if (isFiltersExpanded) 180f else 0f
    }

    private fun openDocumentTreePicker() {
        val launcher =
            requireNotNull(openDocumentTreeLauncher) {
                "Document tree launcher was not available"
            }
        try {
            launcher.launch(null)
        } catch (e: ActivityNotFoundException) {
            requireContext().showToast(R.string.err_no_app)
        }
    }

    private fun handleAddedDocumentTree(uri: Uri?) {
        if (uri == null) {
            L.d("No URI given (user closed the picker)")
            return
        }
        val context = requireContext()
        val opened = Location.Unopened.from(context, uri).open(context)
        if (opened == null) {
            context.showToast(R.string.err_bad_location)
            return
        }

        val matchingDiscovered = discoveredFolders.find { it.path == opened.path }
        if (matchingDiscovered != null) {
            selectedDiscoveredPaths.add(matchingDiscovered.path)
        } else if (customSafLocations.none { it.uri == opened.uri || it.path == opened.path }) {
            customSafLocations.add(opened)
        }
        renderFolders()
    }

    private fun saveChanges() {
        val binding = requireBinding()
        val excludeNonMusic = binding.locationsExcludeNonMusicSwitch.isChecked
        val excludeVoiceNotes = binding.locationsExcludeVoiceNotesSwitch.isChecked
        val excludeShortAudio = binding.locationsExcludeShortAudioSwitch.isChecked

        val enabledMediaStoreLocations =
            if (hasStoragePermission && hasCompletedScan) {
                discoveredFolders
                    .filter { !it.isSounderManaged && it.path in selectedDiscoveredPaths }
                    .map { it.location }
            } else {
                musicSettings.mediaStoreQuery.filtered
            }

        val currentMode = musicSettings.locationMode
        val currentMediaStoreQuery = musicSettings.mediaStoreQuery
        val currentSafQuery = musicSettings.safQuery

        val newMediaStoreQuery =
            MediaStore.Query(
                mode = MediaStore.FilterMode.INCLUDE,
                filtered = enabledMediaStoreLocations,
                excludeNonMusic = excludeNonMusic,
                excludeVoiceNotes = excludeVoiceNotes,
                excludeShortAudio = excludeShortAudio,
            )

        val newSafQuery =
            currentSafQuery.copy(
                source = customSafLocations.toList(),
                exclude = emptyList(),
                withHidden = false,
            )

        val configChanged =
            currentMode != LocationMode.MEDIA_STORE ||
                currentMediaStoreQuery != newMediaStoreQuery ||
                currentSafQuery != newSafQuery

        musicSettings.excludeVoiceNotes = excludeVoiceNotes
        musicSettings.excludeShortAudio = excludeShortAudio
        musicSettings.mediaStoreQuery = newMediaStoreQuery
        musicSettings.safQuery = newSafQuery
        musicSettings.locationMode = LocationMode.MEDIA_STORE

        if (!configChanged && (permissionGrantedInSession || rescannedInSession)) {
            musicSettings.forceLocationUpdate()
        }
    }

    private fun checkStoragePermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        val launcher =
            requireNotNull(storagePermissionLauncher) {
                "Storage permission launcher was not available"
            }

        try {
            L.d("Requesting storage permission: $permission")
            launcher.launch(permission)
        } catch (e: Exception) {
            L.e("Failed to request storage permission")
            L.e(e.stackTraceToString())
            requireContext().showToast(R.string.err_no_app)
        }
    }
}
