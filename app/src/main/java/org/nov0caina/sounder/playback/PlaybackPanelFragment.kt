/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
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
 
package org.nov0caina.sounder.playback

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import coil3.ImageLoader
import com.google.android.material.R as MR
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import org.nov0caina.sounder.InnerDirections
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentPlaybackPanelBinding
import org.nov0caina.sounder.detail.DetailViewModel
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.list.menu.MenuViewModel
import org.nov0caina.sounder.music.MusicRepository
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.lyrics.LyricsBottomSheetDialogFragment
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.playback.queue.QueueViewModel
import org.nov0caina.sounder.playback.state.RepeatMode
import org.nov0caina.sounder.playback.ui.StyledSeekBar
import org.nov0caina.sounder.playback.ui.stepper.Direction
import org.nov0caina.sounder.playback.ui.stepper.StepperOverlay
import org.nov0caina.sounder.playback.ui.swiper.CarouselTransformer
import org.nov0caina.sounder.playback.ui.swiper.CoverPagerAdapter
import org.nov0caina.sounder.playback.ui.swiper.UserAwarePagerCallback
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.dampen
import org.nov0caina.sounder.util.getAttrColorCompat
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.recycler
import org.nov0caina.sounder.util.showToast
import org.nov0caina.sounder.util.smoothScrollByPageTo
import org.nov0caina.sounder.util.systemBarInsetsCompat
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    StepperOverlay.Listener {
    private val coverPagerAdapter = CoverPagerAdapter(this)
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val queueModel: QueueViewModel by viewModels()
    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var userAwarePagerCallback: UserAwarePagerCallback? = null

    @Inject lateinit var downloadManager: DownloadManager
    @Inject lateinit var musicRepository: MusicRepository
    @Inject lateinit var imageLoader: ImageLoader

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // AudioEffect expects you to use startActivityForResult with the panel intent. There is no
        // contract analogue for this intent, so the generic contract is used instead.
        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Nothing to do
            }

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        binding.playbackPager?.apply {
            // intentional LTR override since thepager is a chronological element
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            adapter = coverPagerAdapter
            userAwarePagerCallback =
                UserAwarePagerCallback(this) {
                        // Posting the queue goto command prevents the seekbar pos from desyncing
                        // from the song's duration, which creates a visual flicker in the seekbar.
                        post { queueModel.goto(it) }
                    }
                    .also { it.attach() }
            setPageTransformer(CarouselTransformer())
            recycler().apply {
                // Make it possible to collapse the bottom sheet from the ViewPager's touch area.
                isNestedScrollingEnabled = false
                // Visual effect consistency
                // TODO: Custom overscroll?
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            // Make it easier to collapse the bottom sheet
            dampen()
            offscreenPageLimit = 1
        }

        // Set up fast seek overlay
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.apply {
            @SuppressLint("RestrictedApi")
            setCornerSpringForce(
                SpringForce().apply {
                    stiffness = 700f
                    dampingRatio = 0.9f
                }
            )
            val isPlaying = playbackModel.isPlaying.value
            isChecked = isPlaying
            setIconResource(if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24)
            setOnClickListener { playbackModel.togglePlaying() }
        }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackFavorite?.setOnClickListener {
            playbackModel.song.value?.let { song ->
                musicModel.toggleFavorite(song) { isFav ->
                    requireContext().showToast(if (isFav) R.string.lng_added_favorite else R.string.lng_removed_favorite)
                }
            }
        }
        binding.playbackDownload?.setOnClickListener {
            val song = playbackModel.song.value
            if (song is OnlineSong) {
                if (!org.nov0caina.sounder.util.StoragePermissionHelper.hasPermission(requireContext())) {
                    requireContext().showToast(R.string.msg_storage_permission_required)
                    return@setOnClickListener
                }
                downloadManager.enqueue(song.onlineTrack)
                requireContext().showToast(getString(R.string.download_queued, song.onlineTrack.title))
                binding.playbackDownload?.isEnabled = false
                binding.playbackDownload?.alpha = 0.5f
            }
        }
        binding.playbackMore?.setOnClickListener {
            val currentSong = playbackModel.song.value ?: return@setOnClickListener
            MenuViewModel.pendingSong = currentSong
            val playWith = PlaySong.ByItself
            val parcel = Menu.ForSong(R.menu.playback_song, currentSong, playWith).parcel
            try {
                requireActivity().findNavController(R.id.explore_nav_host)
                    .navigateSafe(InnerDirections.actionGlobalSongMenu(parcel))
            } catch (t: Throwable) {
                L.e(t, "Failed to navigate to global song menu")
            }
        }
        binding.playbackLyrics?.setOnClickListener {
            LyricsBottomSheetDialogFragment().show(childFragmentManager, "lyrics_dialog")
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.pagerQueue, ::updatePager)
        collectImmediately(musicModel.favoriteSongUids) { uids ->
            updateFavorite(playbackModel.song.value, uids)
        }
        collectImmediately(downloadManager.downloads) {
            updateDownload(playbackModel.song.value)
        }
        collectImmediately(musicModel.indexingState) {
            updateDownload(playbackModel.song.value)
        }
    }

    // FIXME: Old code!! Maybe not necessary anymore?
    //    override fun onStart() {
    //        super.onStart()
    //        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
    //        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    //    }

    //    override fun onStop() {
    //        super.onStop()
    //        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    //    }

    //    override fun onGlobalLayout() {
    //        if (binding == null || lastCoverWidth < 0) {
    //            return
    //        }
    // Hacky workaround for cover radius not being preserved in between sizing changes
    // (i.e split screen or landscape mode)
    // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
    // size to radically change, so we wait until it stabilizes and then force an image
    // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
    // did accidentally load the wrong image (with weird corner radius intended for bigger
    // covers) we can force it to reload.
    // If this breaks, it's fine since we also started a load as we normally did w/state
    // updates, so the cover will not break.
    //        val binding = requireBinding()
    //        val coverWidth = binding.playbackCover.width
    //        if (lastCoverWidth != coverWidth) {
    //            lastCoverWidth = coverWidth
    //        } else {
    //            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
    //            lastCoverWidth = -1
    //        }
    //    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        equalizerLauncher = null
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        userAwarePagerCallback?.release()
        binding.playbackPager?.adapter = null
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_open_equalizer) {
            // Launch the system equalizer app, if possible.
            L.d("Launching equalizer")
            val equalizerIntent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    // Provide audio session ID so the equalizer can show options for this app
                    // in particular.
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                    // Signal music type so that the equalizer settings are appropriate for
                    // music playback.
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                requireNotNull(equalizerLauncher) { "Equalizer panel launcher was not available" }
                    .launch(equalizerIntent)
            } catch (e: ActivityNotFoundException) {
                requireContext().showToast(R.string.err_no_app)
            }
            return true
        }

        return false
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
        binding.playbackAmbientGlow?.bind(song, imageLoader)
        updateFavorite(song, musicModel.favoriteSongUids.value)
        updateDownload(song)
    }

    private fun updateFavorite(song: Song?, favoriteUids: Set<Music.UID>) {
        val isFav = song != null && favoriteUids.contains(song.uid)
        val iconRes = if (isFav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
        binding?.playbackFavorite?.apply {
            setIconResource(iconRes)
            iconTint = if (isFav) {
                context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
            } else {
                context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
        }
    }

    private fun isSongDownloadedOrInLibrary(song: Song?): Boolean {
        if (song == null) return false
        if (song !is OnlineSong) return true
        return downloadManager.isDownloaded(song.onlineTrack)
    }

    private fun updateDownload(song: Song?) {
        val downloadButton = binding?.playbackDownload ?: return
        if (song == null) {
            downloadButton.isEnabled = false
            return
        }

        if (isSongDownloadedOrInLibrary(song)) {
            downloadButton.setIconResource(R.drawable.ic_check_24)
            downloadButton.isEnabled = false
            downloadButton.alpha = 0.5f
            downloadButton.contentDescription = getString(R.string.btn_downloaded)
            return
        }

        if (song is OnlineSong) {
            val isDownloading = downloadManager.isEnqueuedOrDownloading(song.onlineTrack)
            if (isDownloading) {
                downloadButton.setIconResource(R.drawable.ic_download_24)
                downloadButton.isEnabled = false
                downloadButton.alpha = 0.4f
                downloadButton.contentDescription = getString(R.string.btn_downloading)
            } else {
                downloadButton.setIconResource(R.drawable.ic_download_24)
                downloadButton.isEnabled = true
                downloadButton.alpha = 1.0f
                downloadButton.contentDescription = getString(R.string.btn_download)
            }
        } else {
            downloadButton.setIconResource(R.drawable.ic_check_24)
            downloadButton.isEnabled = false
            downloadButton.alpha = 0.5f
            downloadButton.contentDescription = getString(R.string.btn_downloaded)
        }
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        val seekBar = requireBinding().playbackSeekBar ?: return
        val currentSong = playbackModel.song.value
        if (currentSong != null && currentSong.durationMs > 0) {
            val expectedDurationDs = currentSong.durationMs.msToDs()
            if (seekBar.durationDs != expectedDurationDs) {
                seekBar.durationDs = expectedDurationDs
            }
        }
        seekBar.positionDs = positionDs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        val repeatButton = requireBinding().playbackRepeat
        repeatButton.isChecked = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        val playPauseButton = requireBinding().playbackPlayPause
        playPauseButton.isChecked = isPlaying
        playPauseButton.setIconResource(
            if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
        )
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isChecked = isShuffled
    }

    private fun updatePager(queue: PagerQueue) {
        // Right now there's easily 140ms of frame skipping when going next/prev. This is primarily
        // the fault of specifically the nested bottom sheet UI setup, which is intractable to
        // optimize. If I don't do multiple remeasures/relayouts on every slightest state
        // instability
        // I will suddenly encounter insane issues where the sheet fails to measure, appears below
        // the sidebar, flies away, not changing with ui scale, etc, often only on third-party OEM
        // ROMs that randomly mangle  SDK APIs and the SystemUI chrome for no reason.
        //
        // Historically this was not an issue, as I did not animate next/prev. Now I do, and it's
        // highly noticeable. So at least for plain next/prev I have to hack around it, do not
        // execute any transition until the state has fully adjudicated and laid out the UI. It's
        // not effective for swiping but there's nothing I can do there.
        //
        // Eventually one day Claude Fable 6.7 will probably be able to figure out that you need to
        // reflect into System.FoobaCrumbo::beegieConnector(GoolaUtils.PlubBud) and call it
        // specifically with 0x189B31FA alongside disabling the AndroidX Helpo SuperCharge by
        // manually clobbering `BottomSheetM2InternalBoogieCompat::scrimbloManager` to null for it
        // to not actually randomly mangle the sheets and do it in 1 clean layout, but for now I
        // must do this to keep my sanity.
        //
        // Actual snippet here was codex, just cleaned & adapted it / cognitive ownership
        requireBinding().playbackPager.apply {
            if (!isAttachedToWindow) {
                post { updatePagerImpl(queue) }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
                // New version using post-Q frame hooks
                viewTreeObserver.registerFrameCommitCallback {
                    post { postOnAnimation { updatePagerImpl(queue) } }
                }
                postInvalidateOnAnimation()
            } else {
                // Let current layout happen, then wait for the next to conclude
                postOnAnimation { postOnAnimation { updatePagerImpl(queue) } }
            }
        }
    }

    private fun updatePagerImpl(queue: PagerQueue) {
        // Android insanity means this may be executed after view destruction
        // but only on some devices.
        val binding = binding ?: return

        val command = playbackModel.pagerCommand.consume()
        if (command == null) {
            // This probably shouldn't happen in practice, as QueueViewModel directly
            // attaches to PlaybackStateManager and will basically always initialize
            // with a command as a result.
            //
            // If it does happen we should just make sure the UI state is aligned. Don't
            // want broken UI.
            coverPagerAdapter.update(queue.queue, null)
            binding.playbackPager.setCurrentItem(queue.index, false)
            return
        }

        if (command.update != null) {
            // queue needs to be updated.
            coverPagerAdapter.update(queue.queue, command.update)
        }

        if (command.scroll != null) {
            // we need to scroll, however the smooth scroll only really looks best
            // when we are only doing next/prev due to various factors. better to
            // just not animate on outright gotos or queue updates
            val delta = binding.playbackPager.currentItem - command.scroll
            if (delta == 0) {
                // user scroll, carry on
                return
            }
            if (command.update == null && abs(delta) == 1) {
                binding.playbackPager.smoothScrollByPageTo(command.scroll)
            } else {
                binding.playbackPager.setCurrentItem(command.scroll, false)
            }
        }
    }

    private fun navigateToCurrentSong() {
        val song = playbackModel.song.value ?: return
        playbackModel.openMain()
        if (song is OnlineSong) {
            val track = song.onlineTrack
            val albumId = track.albumId.orEmpty()
            val albumTitle = track.album?.takeIf { it.isNotBlank() } ?: track.title
            val artistName = track.artist
            if (albumId.isNotEmpty()) {
                requireActivity().findNavController(R.id.explore_nav_host)
                    .navigateSafe(InnerDirections.actionGlobalOnlineAlbum(albumId, albumTitle, artistName))
            } else {
                requireActivity().findNavController(R.id.explore_nav_host)
                    .navigateSafe(InnerDirections.actionGlobalOnlineArtist(track.artistId.orEmpty(), artistName))
            }
        } else {
            detailModel.showAlbum(song)
        }
    }

    private fun navigateToCurrentArtist() {
        val song = playbackModel.song.value ?: return
        playbackModel.openMain()
        if (song is OnlineSong) {
            val track = song.onlineTrack
            val artistId = track.artistId.orEmpty()
            val artistName = song.artists.firstOrNull()?.name?.resolve(requireContext()) ?: track.artist
            requireActivity().findNavController(R.id.explore_nav_host)
                .navigateSafe(InnerDirections.actionGlobalOnlineArtist(artistId, artistName))
        } else {
            detailModel.showArtist(song)
        }
    }

    private fun navigateToCurrentAlbum() {
        val song = playbackModel.song.value ?: return
        playbackModel.openMain()
        if (song is OnlineSong) {
            val track = song.onlineTrack
            val albumId = track.albumId.orEmpty()
            val albumTitle = track.album?.takeIf { it.isNotBlank() } ?: track.title
            val artistName = track.artist
            if (albumId.isNotEmpty()) {
                requireActivity().findNavController(R.id.explore_nav_host)
                    .navigateSafe(InnerDirections.actionGlobalOnlineAlbum(albumId, albumTitle, artistName))
            } else {
                requireActivity().findNavController(R.id.explore_nav_host)
                    .navigateSafe(InnerDirections.actionGlobalOnlineArtist(track.artistId.orEmpty(), artistName))
            }
        } else {
            detailModel.showAlbum(song.album)
        }
    }

    override fun seek(direction: Direction) {
        when (direction) {
            Direction.FORWARDS -> playbackModel.stepForward()
            Direction.BACKWARDS -> playbackModel.stepBackwards()
        }
    }
}
