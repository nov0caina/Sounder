/*
 * Copyright (c) 2023 Auxio Project
 * RenamePlaylistDialog.kt is part of Auxio.
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
 
package org.nov0caina.sounder.music.decision

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.target
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.DialogPlaylistNameBinding
import org.nov0caina.sounder.image.PlaylistCoverManager
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.resolveName
import org.nov0caina.sounder.ui.ViewBindingMaterialDialogFragment
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.unlikelyToBeNull
import timber.log.Timber as L

/**
 * A dialog allowing the name and custom cover image of an existing playlist to be edited before
 * committing changes to the database.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class RenamePlaylistDialog : ViewBindingMaterialDialogFragment<DialogPlaylistNameBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val pickerModel: PlaylistPickerViewModel by viewModels()
    // Information about what playlist to name for is initially within the navigation arguments
    // as UIDs, as that is the only safe way to parcel playlist information.
    private val args: RenamePlaylistDialogArgs by navArgs()
    private var initializedField = false
    private var getCoverLauncher: ActivityResultLauncher<String>? = null

    @Inject lateinit var imageLoader: ImageLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getCoverLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    pickerModel.selectCoverUri(uri)
                }
            }
    }

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setPositiveButton(R.string.lbl_ok) { _, _ ->
                val pendingRenamePlaylist =
                    unlikelyToBeNull(pickerModel.currentPendingRenamePlaylist.value)
                val originalName = pendingRenamePlaylist.playlist.resolveName(requireContext())
                val targetName =
                    PlaylistPickerViewModel.resolveEditedPlaylistName(
                        pickerModel.chosenName.value,
                        originalName,
                    )
                musicModel.renamePlaylist(
                    pendingRenamePlaylist.playlist,
                    targetName,
                    pendingRenamePlaylist.applySongs,
                    pendingRenamePlaylist.reason,
                    coverUri = pickerModel.chosenCoverUri.value,
                    removeCover = pickerModel.removeExistingCover.value,
                )
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogPlaylistNameBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogPlaylistNameBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.configurePlaylistDialogHeader(
            titleRes = R.string.lbl_edit_playlist,
            onPickCover = { getCoverLauncher?.launch("image/*") },
            onRemoveCover = { pickerModel.markExistingCoverRemoved() },
        )
        binding.playlistName.addTextChangedListener { pickerModel.updateChosenName(it?.toString()) }

        // --- VIEWMODEL SETUP ---
        musicModel.playlistDecision.consume()
        pickerModel.setPlaylistToRename(
            args.playlistUid,
            args.applySongUids,
            args.template,
            args.reason,
        )
        collectImmediately(
            pickerModel.currentPendingRenamePlaylist,
            pickerModel.chosenCoverUri,
            pickerModel.removeExistingCover,
            ::updatePlaylistToRename,
        )
        collectImmediately(pickerModel.chosenName) { updateConfirmButtonState() }
    }

    override fun onStart() {
        super.onStart()
        updateConfirmButtonState()
    }

    override fun onDestroyBinding(binding: DialogPlaylistNameBinding) {
        super.onDestroyBinding(binding)
        getCoverLauncher = null
    }

    private fun updatePlaylistToRename(
        pendingRenamePlaylist: PendingRenamePlaylist?,
        chosenCoverUri: Uri?,
        removeExistingCover: Boolean,
    ) {
        if (pendingRenamePlaylist == null) {
            // Nothing to rename anymore.
            findNavController().navigateUp()
            return
        }

        val binding = requireBinding()
        val resolvedName = pendingRenamePlaylist.playlist.resolveName(requireContext())

        if (!initializedField) {
            val default = pendingRenamePlaylist.template ?: resolvedName
            L.d("Name input is not initialized, setting to $default")
            binding.playlistName.setText(default)
            initializedField = true
        }

        val existingCoverFile = PlaylistCoverManager.getCoverFile(requireContext(), resolvedName)

        when {
            chosenCoverUri != null -> {
                binding.playlistCustomCoverPreview.isVisible = true
                binding.playlistDefaultCover.isVisible = false
                binding.playlistRemoveCoverBtn.isVisible = true
                val request =
                    ImageRequest.Builder(requireContext())
                        .data(chosenCoverUri)
                        .size(512, 512)
                        .target(binding.playlistCustomCoverPreview)
                        .build()
                imageLoader.enqueue(request)
                binding.playlistCoverGlow.bindData(chosenCoverUri, chosenCoverUri, imageLoader)
            }
            removeExistingCover -> {
                binding.playlistCustomCoverPreview.isVisible = false
                binding.playlistDefaultCover.isVisible = true
                binding.playlistRemoveCoverBtn.isVisible = false
                binding.playlistDefaultCover.bind(
                    pendingRenamePlaylist.playlist.songs,
                    getString(R.string.desc_playlist_image, resolvedName),
                    R.drawable.ic_playlist_24,
                    pendingRenamePlaylist.playlist.uid.toString().hashCode(),
                )
                val firstSong = pendingRenamePlaylist.playlist.songs.firstOrNull()
                if (firstSong != null) {
                    binding.playlistCoverGlow.bind(firstSong, imageLoader)
                } else {
                    binding.playlistCoverGlow.clearGlow()
                }
            }
            existingCoverFile != null -> {
                binding.playlistCustomCoverPreview.isVisible = true
                binding.playlistDefaultCover.isVisible = false
                binding.playlistRemoveCoverBtn.isVisible = true
                val cacheKey = "${existingCoverFile.absolutePath}_${existingCoverFile.lastModified()}"
                val request =
                    ImageRequest.Builder(requireContext())
                        .data(existingCoverFile)
                        .memoryCacheKey(cacheKey)
                        .size(512, 512)
                        .target(binding.playlistCustomCoverPreview)
                        .build()
                imageLoader.enqueue(request)
                binding.playlistCoverGlow.bindData(existingCoverFile, cacheKey, imageLoader)
            }
            else -> {
                binding.playlistCustomCoverPreview.isVisible = false
                binding.playlistDefaultCover.isVisible = true
                binding.playlistRemoveCoverBtn.isVisible = false
                binding.playlistDefaultCover.bind(pendingRenamePlaylist.playlist)
                val firstSong = pendingRenamePlaylist.playlist.songs.firstOrNull()
                if (firstSong != null) {
                    binding.playlistCoverGlow.bind(firstSong, imageLoader)
                } else {
                    binding.playlistCoverGlow.clearGlow()
                }
            }
        }

        updateConfirmButtonState()
    }

    private fun updateConfirmButtonState() {
        val originalName =
            pickerModel.currentPendingRenamePlaylist.value?.playlist?.resolveName(requireContext())
        val enabled =
            PlaylistPickerViewModel.canConfirmPlaylistEdit(
                chosenName = pickerModel.chosenName.value,
                originalPlaylistName = originalName,
                hasPendingCoverUri = pickerModel.chosenCoverUri.value != null,
                removeExistingCover = pickerModel.removeExistingCover.value,
            )
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
    }
}
