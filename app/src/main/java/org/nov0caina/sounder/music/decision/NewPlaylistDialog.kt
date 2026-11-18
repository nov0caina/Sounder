/*
 * Copyright (c) 2023 Auxio Project
 * NewPlaylistDialog.kt is part of Auxio.
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

import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
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
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.PlaylistDecision
import org.nov0caina.sounder.ui.ViewBindingMaterialDialogFragment
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.unlikelyToBeNull
import timber.log.Timber as L

/**
 * A dialog allowing the name and custom cover of a new playlist to be chosen before committing it
 * to the database.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class NewPlaylistDialog : ViewBindingMaterialDialogFragment<DialogPlaylistNameBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val pickerModel: PlaylistPickerViewModel by viewModels()
    // Information about what playlist to name for is initially within the navigation arguments
    // as UIDs, as that is the only safe way to parcel playlist information.
    private val args: NewPlaylistDialogArgs by navArgs()
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
                val pendingPlaylist = unlikelyToBeNull(pickerModel.currentPendingNewPlaylist.value)
                val name =
                    when (val chosenName = pickerModel.chosenName.value) {
                        is ChosenName.Valid -> chosenName.value
                        is ChosenName.Empty -> pendingPlaylist.preferredName
                        else -> throw IllegalStateException()
                    }
                musicModel.createPlaylist(
                    name,
                    pendingPlaylist.songs,
                    pendingPlaylist.reason,
                    pickerModel.chosenCoverUri.value,
                )
                findNavController().apply {
                    navigateUp()
                    // Do an additional navigation away from the playlist addition dialog, if
                    // needed. Only pop when AddToPlaylistDialog is actually on the backstack so we
                    // never pop home_fragment (Tu Biblioteca) back to home_feed_fragment (Inicio).
                    if (currentDestination?.id == R.id.add_to_playlist_dialog) {
                        navigateUp()
                    }
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogPlaylistNameBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogPlaylistNameBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.configurePlaylistDialogHeader(
            titleRes =
                when (args.reason) {
                    PlaylistDecision.New.Reason.NEW,
                    PlaylistDecision.New.Reason.ADD -> R.string.lbl_new_playlist
                    PlaylistDecision.New.Reason.IMPORT -> R.string.lbl_import_playlist
                },
            onPickCover = { getCoverLauncher?.launch("image/*") },
            onRemoveCover = { pickerModel.selectCoverUri(null) },
        )
        binding.playlistName.addTextChangedListener { pickerModel.updateChosenName(it?.toString()) }

        // --- VIEWMODEL SETUP ---
        musicModel.playlistDecision.consume()
        pickerModel.setPendingPlaylist(requireContext(), args.songUids, args.template, args.reason)

        collectImmediately(
            pickerModel.currentPendingNewPlaylist,
            pickerModel.chosenCoverUri,
            ::updatePendingPlaylist,
        )
        collectImmediately(pickerModel.chosenName, ::updateChosenName)
    }

    override fun onDestroyBinding(binding: DialogPlaylistNameBinding) {
        super.onDestroyBinding(binding)
        getCoverLauncher = null
    }

    private fun updatePendingPlaylist(
        pendingNewPlaylist: PendingNewPlaylist?,
        chosenCoverUri: Uri?,
    ) {
        if (pendingNewPlaylist == null) {
            L.d("No playlist to create, leaving")
            findNavController().navigateUp()
            return
        }
        val binding = requireBinding()
        if (pendingNewPlaylist.template != null) {
            if (!initializedField) {
                initializedField = true
                if (args.template != null) {
                    binding.playlistName.text = EDITABLE_FACTORY.newEditable(args.template)
                }
            }
        } else {
            binding.playlistName.hint = pendingNewPlaylist.preferredName
        }

        if (chosenCoverUri != null) {
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
        } else {
            binding.playlistCustomCoverPreview.isVisible = false
            binding.playlistDefaultCover.isVisible = true
            binding.playlistRemoveCoverBtn.isVisible = false
            binding.playlistDefaultCover.bind(
                pendingNewPlaylist.songs,
                getString(R.string.lbl_new_playlist),
                R.drawable.ic_playlist_24,
            )
            val firstSong = pendingNewPlaylist.songs.firstOrNull()
            if (firstSong != null) {
                binding.playlistCoverGlow.bind(firstSong, imageLoader)
            } else {
                binding.playlistCoverGlow.clearGlow()
            }
        }
    }

    private fun updateChosenName(chosenName: ChosenName) {
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            chosenName is ChosenName.Valid || chosenName is ChosenName.Empty
    }

    private companion object {
        val EDITABLE_FACTORY: Editable.Factory = Editable.Factory.getInstance()
    }
}

/**
 * Configures the shared glassmorphic header, top-corner outline clipping, title, and cover picker
 * controls across playlist creation and editing dialogs.
 */
internal fun DialogPlaylistNameBinding.configurePlaylistDialogHeader(
    @androidx.annotation.StringRes titleRes: Int,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
) {
    playlistDialogRoot.apply {
        clipToOutline = true
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radiusPx = (28f * view.resources.displayMetrics.density).toInt()
                    outline.setRoundRect(0, 0, view.width, view.height + radiusPx, radiusPx.toFloat())
                }
            }
    }
    playlistDialogTitle.apply {
        setText(titleRes)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    playlistCoverGlow.showFallbackAura = true
    playlistCoverCard.setOnClickListener { onPickCover() }
    playlistChooseCoverBtn.setOnClickListener { onPickCover() }
    playlistRemoveCoverBtn.setOnClickListener { onRemoveCover() }
}
