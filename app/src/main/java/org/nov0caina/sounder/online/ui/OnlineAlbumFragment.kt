package org.nov0caina.sounder.online.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.crossfade
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nov0caina.sounder.InnerDirections
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentOnlineAlbumBinding
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.PlaylistDecision
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.download.DownloadStatus
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.oxycblt.musikr.Song
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast

@AndroidEntryPoint
class OnlineAlbumFragment :
    ViewBindingFragment<FragmentOnlineAlbumBinding>(), AppBarLayout.OnOffsetChangedListener {

    @Inject lateinit var imageLoader: ImageLoader

    private val albumModel: OnlineAlbumViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val args: OnlineAlbumFragmentArgs by navArgs()

    private var tracklistAdapter: OnlineAlbumTrackAdapter? = null
    private var moreAlbumsAdapter: OnlineAlbumCardAdapter? = null
    private var albumTouchHelper: ItemTouchHelper? = null
    private var skeletonAnimator: ObjectAnimator? = null
    private var spacingSmall: Int = 0
    private var lastDownloadStatuses: Map<String, DownloadStatus>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentOnlineAlbumBinding =
        FragmentOnlineAlbumBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentOnlineAlbumBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        spacingSmall = resources.getDimensionPixelSize(R.dimen.spacing_small)
        binding.albumAppbar.addOnOffsetChangedListener(this)

        val separators = setOf("&", ",", "+", "feat.", "feat", "ft.", "ft", "with", "vs.")
        val cleanInitialTitle = if (args.albumTitle.isBlank() || args.albumTitle.trim().lowercase() in separators) "" else args.albumTitle
        binding.albumToolbar.title = cleanInitialTitle
        binding.albumToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        animateToolbarTitle(binding, 1f)

        binding.albumHeaderTitle.text = cleanInitialTitle
        binding.albumHeaderArtist.text = args.artistName
        binding.albumHeaderArtist.setOnClickListener {
            val artistName = binding.albumHeaderArtist.text?.toString() ?: args.artistName
            if (artistName.isNotBlank()) {
                findNavController().navigateSafe(
                    OnlineAlbumFragmentDirections.showOnlineArtist("", artistName)
                )
            }
        }

        tracklistAdapter = OnlineAlbumTrackAdapter(
            onPlayClick = { track ->
                if (isCurrentTrack(track)) {
                    playbackModel.togglePlaying()
                } else {
                    albumModel.playTrack(track, playbackModel)
                }
            },
            onDownloadClick = { track ->
                org.nov0caina.sounder.online.download.DownloadActionHelper.handleTrackDownloadClick(
                    context = requireContext(),
                    lifecycleOwner = viewLifecycleOwner,
                    downloadManager = albumModel.downloadManager,
                    track = track,
                    onStateChanged = {
                        updateAlbumDownloadButton()
                        tracklistAdapter?.refreshTrackStates()
                    },
                    onDeleted = { musicModel.refresh() }
                )
            },
            isDownloaded = { track -> albumModel.isDownloaded(track) },
            isDownloading = { track -> albumModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> albumModel.loadingTrackId.value == trackId },
            isCurrentlyPlaying = { track -> isTrackPlaying(track) },
            albumArtist = args.artistName,
            onTrackLongClick = { track ->
                openSongMenu(track)
            }
        )
        binding.albumTracklistRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tracklistAdapter
        }

        moreAlbumsAdapter = OnlineAlbumCardAdapter { album ->
            findNavController().navigateSafe(
                OnlineAlbumFragmentDirections.showOnlineAlbum(
                    album.id,
                    album.title,
                    albumModel.albumDetail.value?.artist ?: args.artistName
                )
            )
        }
        binding.albumMoreRecycler.adapter = moreAlbumsAdapter

        albumTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { true },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val track = tracklistAdapter?.currentList?.getOrNull(pos)
                        if (track != null) {
                            albumModel.enqueueTrack(track, playbackModel) {
                                context?.showToast(R.string.lng_queue_added)
                            }
                        }
                    }
                }
            )
        ).also { it.attachToRecyclerView(binding.albumTracklistRecycler) }

        binding.albumPlayButton.setOnClickListener {
            if (isAlbumCurrent()) {
                playbackModel.togglePlaying()
            } else {
                albumModel.playAlbum(playbackModel)
            }
        }

        binding.albumDownloadButton.setOnClickListener {
            val detail = albumModel.albumDetail.value ?: return@setOnClickListener
            val status = albumModel.getAlbumDownloadStatus()

            if (status == AlbumDownloadStatus.DOWNLOADING) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_download_waiting_title)
                    .setMessage(R.string.dialog_download_waiting_message)
                    .setPositiveButton(R.string.btn_download_now) { _, _ ->
                        albumModel.downloadAlbum(allowMeteredNetwork = true)
                        Snackbar.make(
                            binding.root,
                            getString(R.string.album_enqueued_for_download, albumModel.getUndownloadedTracksCount()),
                            Snackbar.LENGTH_SHORT
                        ).show()
                        updateAlbumDownloadButton()
                        tracklistAdapter?.refreshTrackStates()
                    }
                    .setNeutralButton(R.string.lbl_delete_download) { _, _ ->
                        for (track in detail.tracks) {
                            albumModel.downloadManager.cancel(track.id)
                            albumModel.downloadManager.removeDownload(track.id)
                        }
                        updateAlbumDownloadButton()
                        tracklistAdapter?.refreshTrackStates()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }

            if (status == AlbumDownloadStatus.COMPLETED) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.lbl_delete_download_album)
                    .setMessage(R.string.msg_confirm_delete_album)
                    .setPositiveButton(R.string.btn_delete) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            for (track in detail.tracks) {
                                albumModel.downloadManager.deleteDownloadedSong(
                                    OnlineSong(track, Uri.parse("sounder://stream/${track.id}"))
                                )
                            }
                            withContext(Dispatchers.Main) {
                                requireContext().showToast(R.string.msg_download_deleted)
                                musicModel.refresh()
                                updateAlbumDownloadButton()
                                tracklistAdapter?.refreshTrackStates()
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }

            if (!org.nov0caina.sounder.util.StoragePermissionHelper.hasPermission(requireContext())) {
                Snackbar.make(binding.root, R.string.msg_storage_permission_required, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val undownloadedCount = albumModel.getUndownloadedTracksCount()
            if (undownloadedCount <= 0) {
                updateAlbumDownloadButton()
                return@setOnClickListener
            }

            val needsWifiConfirm = detail.tracks.any {
                !albumModel.isDownloaded(it) && albumModel.downloadManager.requiresCellularConfirmation(it)
            }
            if (needsWifiConfirm) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_download_cellular_title)
                    .setMessage(R.string.dialog_download_cellular_message)
                    .setPositiveButton(R.string.btn_download_now) { _, _ ->
                        albumModel.downloadAlbum(allowMeteredNetwork = true)
                        Snackbar.make(
                            binding.root,
                            getString(R.string.album_enqueued_for_download, undownloadedCount),
                            Snackbar.LENGTH_SHORT
                        ).show()
                        updateAlbumDownloadButton()
                        tracklistAdapter?.refreshTrackStates()
                    }
                    .setNeutralButton(R.string.btn_wait_for_wifi) { _, _ ->
                        albumModel.downloadAlbum(allowMeteredNetwork = false)
                        requireContext().showToast(getString(R.string.msg_download_waiting_wifi, detail.title))
                        updateAlbumDownloadButton()
                        tracklistAdapter?.refreshTrackStates()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                albumModel.downloadAlbum(allowMeteredNetwork = false)
                Snackbar.make(
                    binding.root,
                    getString(R.string.album_enqueued_for_download, undownloadedCount),
                    Snackbar.LENGTH_SHORT
                ).show()
                updateAlbumDownloadButton()
                tracklistAdapter?.refreshTrackStates()
            }
        }

        OfflineStateHelper.setup(
            binding = binding.albumOfflineLayout,
            onRetry = {
                albumModel.loadAlbum(args.albumId, args.albumTitle, args.artistName)
            }
        )

        albumModel.loadAlbum(args.albumId, args.albumTitle, args.artistName)

        collect(playbackModel.song) {
            tracklistAdapter?.refreshTrackStates()
            updateAlbumPlayButton()
        }

        collect(playbackModel.isPlaying) {
            tracklistAdapter?.refreshTrackStates()
            updateAlbumPlayButton()
        }

        collect(albumModel.albumDetail) { detail ->
            if (detail != null) {
                stopSkeletonPulse()
                val wasHeaderHidden = !binding.albumHeaderContainer.isVisible
                binding.albumLoadingLayout.isVisible = false
                binding.albumOfflineLayout.root.isVisible = false
                binding.albumHeaderContainer.isVisible = true
                binding.albumAmbientGlow.isVisible = true
                binding.albumScroll.isVisible = true
                if (wasHeaderHidden) {
                    animateToolbarTitle(binding, 0f)
                }

                binding.albumToolbar.title = detail.title
                binding.albumHeaderTitle.text = detail.title
                binding.albumHeaderArtist.text = detail.artist

                val yearStr = detail.year ?: ""
                val countStr = resources.getQuantityString(R.plurals.fmt_song_count, detail.tracks.size, detail.tracks.size)
                val totalMs = detail.tracks.sumOf { it.durationMs }
                val totalDurationStr = if (totalMs > 0) {
                    val totalMins = totalMs / 1000 / 60
                    if (totalMins >= 60) {
                        val hours = totalMins / 60
                        val mins = totalMins % 60
                        "${hours}h ${mins}m"
                    } else {
                        "${totalMins} min"
                    }
                } else ""
                val infoParts = listOfNotNull(
                    yearStr.takeIf { it.isNotBlank() },
                    countStr,
                    totalDurationStr.takeIf { it.isNotBlank() }
                )
                binding.albumHeaderInfo.text = infoParts.joinToString(" • ")
                binding.albumMoreHeader.text = getString(R.string.header_more_by, detail.artist)

                if (!detail.coverUrl.isNullOrBlank()) {
                    binding.albumCoverImage.load(detail.coverUrl) {
                        crossfade(true)
                    }
                    binding.albumAmbientGlow.bindData(detail.coverUrl, detail.coverUrl, imageLoader)
                } else {
                    binding.albumAmbientGlow.clearGlow()
                }

                tracklistAdapter?.submitList(detail.tracks)
                updateAlbumDownloadButton()
                updateAlbumPlayButton()
            }
        }

        collect(albumModel.moreAlbums) { albums ->
            moreAlbumsAdapter?.submitList(albums)
            binding.albumMoreSection.isVisible = albums.isNotEmpty()
        }

        collect(albumModel.isLoading) { isLoading ->
            val hasData = albumModel.albumDetail.value != null
            if (isLoading && !hasData) {
                binding.albumLoadingLayout.isVisible = true
                binding.albumScroll.isVisible = false
                binding.albumHeaderContainer.isVisible = false
                binding.albumAmbientGlow.isVisible = false
                binding.albumOfflineLayout.root.isVisible = false
                animateToolbarTitle(binding, 1f)
                startSkeletonPulse()
            } else if (!isLoading && !hasData) {
                stopSkeletonPulse()
                binding.albumLoadingLayout.isVisible = false
                binding.albumScroll.isVisible = false
                binding.albumHeaderContainer.isVisible = false
                binding.albumAmbientGlow.isVisible = false
                binding.albumOfflineLayout.root.isVisible = true
                animateToolbarTitle(binding, 1f)
                OfflineStateHelper.applyProposal(
                    binding = binding.albumOfflineLayout,
                    proposal = OfflineStateHelper.getNextProposal(requireContext())
                )
            } else {
                stopSkeletonPulse()
                val wasHeaderHidden = !binding.albumHeaderContainer.isVisible
                binding.albumLoadingLayout.isVisible = false
                binding.albumOfflineLayout.root.isVisible = false
                binding.albumHeaderContainer.isVisible = true
                binding.albumAmbientGlow.isVisible = true
                binding.albumScroll.isVisible = true
                if (wasHeaderHidden) {
                    animateToolbarTitle(binding, 0f)
                }
            }
            binding.albumProgress.isVisible = isLoading && hasData
        }

        collect(albumModel.loadingTrackId) {
            tracklistAdapter?.refreshTrackStates()
        }

        collect(albumModel.downloads) { items ->
            val statusMap = items.associate { it.trackId to it.status }
            if (statusMap != lastDownloadStatuses) {
                lastDownloadStatuses = statusMap
                updateAlbumDownloadButton()
                tracklistAdapter?.refreshTrackStates()
            }
        }

        collect(musicModel.indexingState) {
            updateAlbumDownloadButton()
            tracklistAdapter?.refreshTrackStates()
        }

        collect(listModel.menu.flow) { menu ->
            if (menu is Menu.ForSong) {
                findNavController().navigateSafe(
                    InnerDirections.actionGlobalSongMenu(menu.parcel)
                )
            }
        }

        collect(musicModel.playlistDecision.flow) { decision ->
            if (decision is PlaylistDecision.Add) {
                findNavController().navigateSafe(
                    InnerDirections.actionGlobalAddToPlaylist(
                        decision.songs.map { it.uid }.toTypedArray()
                    )
                )
            }
        }
    }

    private fun openSongMenu(track: OnlineTrack) {
        val onlineSong = OnlineSong(track, Uri.parse("https://music.youtube.com/watch?v=${track.id}"))
        listModel.openMenu(R.menu.song, onlineSong, PlaySong.ByItself)
    }

    private fun updateAlbumDownloadButton() {
        val binding = binding ?: return
        val detail = albumModel.albumDetail.value ?: return
        val status = albumModel.getAlbumDownloadStatus()
        val undownloadedCount = albumModel.getUndownloadedTracksCount()

        when (status) {
            AlbumDownloadStatus.COMPLETED -> {
                binding.albumDownloadButton.setText(R.string.album_downloaded)
                binding.albumDownloadButton.setIconResource(R.drawable.ic_check_24)
                binding.albumDownloadButton.isEnabled = true
                binding.albumDownloadButton.alpha = 1.0f
            }
            AlbumDownloadStatus.DOWNLOADING -> {
                binding.albumDownloadButton.setText(R.string.album_downloading)
                binding.albumDownloadButton.setIconResource(R.drawable.ic_download_24)
                binding.albumDownloadButton.isEnabled = true
                binding.albumDownloadButton.alpha = 0.7f
            }
            AlbumDownloadStatus.AVAILABLE -> {
                if (undownloadedCount < detail.tracks.size) {
                    binding.albumDownloadButton.text = getString(R.string.album_download_remaining, undownloadedCount)
                } else {
                    binding.albumDownloadButton.setText(R.string.download_album)
                }
                binding.albumDownloadButton.setIconResource(R.drawable.ic_download_24)
                binding.albumDownloadButton.isEnabled = true
                binding.albumDownloadButton.alpha = 1.0f
            }
        }
    }

    private fun isTrackMatching(track: OnlineTrack?, song: Song?): Boolean {
        if (track == null || song == null) return false
        if (song is OnlineSong) {
            if (song.onlineTrack.id == track.id) return true
        }
        val ctx = context ?: return false
        val songTitle = song.name.resolve(ctx)
        val songArtist = song.artists.resolveNames(ctx)
        return songTitle.equals(track.title, ignoreCase = true) &&
            (songArtist.contains(track.artist, ignoreCase = true) || track.artist.contains(songArtist, ignoreCase = true))
    }

    private fun isTrackPlaying(track: OnlineTrack?): Boolean {
        if (track == null || !playbackModel.isPlaying.value) return false
        return isTrackMatching(track, playbackModel.song.value)
    }

    private fun isCurrentTrack(track: OnlineTrack?): Boolean {
        if (track == null) return false
        return isTrackMatching(track, playbackModel.song.value)
    }

    private fun isAlbumCurrent(): Boolean {
        val currentSong = playbackModel.song.value ?: return false
        val detail = albumModel.albumDetail.value ?: return false
        return detail.tracks.any { isTrackMatching(it, currentSong) }
    }

    private fun isAlbumPlaying(): Boolean {
        return playbackModel.isPlaying.value && isAlbumCurrent()
    }

    private fun updateAlbumPlayButton() {
        val b = binding ?: return
        val playing = isAlbumPlaying()
        if (playing) {
            b.albumPlayButton.setText(R.string.btn_pause)
            b.albumPlayButton.setIconResource(R.drawable.ic_pause_24)
        } else {
            b.albumPlayButton.setText(R.string.play_album)
            b.albumPlayButton.setIconResource(R.drawable.ic_play_24)
        }
    }

    private fun startSkeletonPulse() {
        val b = binding ?: return
        val target = b.albumLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.albumLoadingLayout
        if (skeletonAnimator == null) {
            skeletonAnimator = ObjectAnimator.ofFloat(target, "alpha", 0.35f, 0.85f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        if (skeletonAnimator?.isStarted != true) {
            skeletonAnimator?.start()
        }
    }

    private fun stopSkeletonPulse() {
        skeletonAnimator?.cancel()
        val b = binding ?: return
        val target = b.albumLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.albumLoadingLayout
        target.alpha = 1.0f
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        val b = binding ?: return
        val range = appBarLayout.totalScrollRange
        if (range <= 0 || !b.albumHeaderContainer.isVisible) {
            b.albumHeaderContainer.apply {
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
            }
            b.albumAmbientGlow.apply {
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
            }
            animateToolbarTitle(b, 1f)
            return
        }

        val ratio = abs(verticalOffset.toFloat()) / range.toFloat()
        val outRatio = min(ratio * 2f, 1f)
        val scale = 1f - 0.12f * outRatio
        b.albumHeaderContainer.apply {
            scaleX = scale
            scaleY = scale
            alpha = 1f - outRatio
        }
        b.albumAmbientGlow.apply {
            scaleX = scale
            scaleY = scale
            alpha = 1f - outRatio
        }

        val inRatio = ((ratio - 0.75f) / 0.25f).coerceIn(0f, 1f)
        animateToolbarTitle(b, inRatio)
    }

    private fun animateToolbarTitle(binding: FragmentOnlineAlbumBinding, ratio: Float) {
        val titleView = binding.albumToolbar.titleContainer
        titleView.alpha = ratio
        titleView.translationY = spacingSmall * (1f - ratio)
    }

    override fun onDestroyBinding(binding: FragmentOnlineAlbumBinding) {
        super.onDestroyBinding(binding)
        lastDownloadStatuses = null
        binding.albumAppbar.removeOnOffsetChangedListener(this)
        albumTouchHelper?.attachToRecyclerView(null)
        albumTouchHelper = null
        binding.albumTracklistRecycler.adapter = null
        binding.albumMoreRecycler.adapter = null
        tracklistAdapter = null
        moreAlbumsAdapter = null
        stopSkeletonPulse()
        skeletonAnimator = null
    }
}

