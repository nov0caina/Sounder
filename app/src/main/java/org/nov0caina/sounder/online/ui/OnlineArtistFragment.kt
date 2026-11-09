package org.nov0caina.sounder.online.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.nov0caina.sounder.InnerDirections
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentOnlineArtistBinding
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.PlaylistDecision
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.oxycblt.musikr.Song
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.getAttrColorCompat
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast

@AndroidEntryPoint
class OnlineArtistFragment : ViewBindingFragment<FragmentOnlineArtistBinding>() {

    private val artistModel: OnlineArtistViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val args: OnlineArtistFragmentArgs by navArgs()

    private var topSongsAdapter: OnlineTrackAdapter? = null
    private var artistTouchHelper: ItemTouchHelper? = null
    private var albumsAdapter: OnlineAlbumCardAdapter? = null
    private var singlesAdapter: OnlineAlbumCardAdapter? = null
    private var relatedAdapter: RecentArtistsAdapter? = null
    private var skeletonAnimator: ObjectAnimator? = null
    private var isTopSongsExpanded: Boolean = false
    private val collapsedSongsCap: Int = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentOnlineArtistBinding =
        FragmentOnlineArtistBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentOnlineArtistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.artistToolbar.title = args.artistName
        binding.artistToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.artistHeroBlurImage.setRenderEffect(
                RenderEffect.createBlurEffect(
                    25f,
                    25f,
                    Shader.TileMode.CLAMP
                )
            )
        }

        binding.artistHeaderName.text = args.artistName

        topSongsAdapter = OnlineTrackAdapter(
            onPlayClick = { track ->
                if (isCurrentTrack(track)) {
                    playbackModel.togglePlaying()
                } else {
                    artistModel.playTrack(track, playbackModel)
                }
            },
            onDownloadClick = { track ->
                if (!org.nov0caina.sounder.util.StoragePermissionHelper.hasPermission(requireContext())) {
                    Snackbar.make(binding.root, R.string.msg_storage_permission_required, Snackbar.LENGTH_LONG).show()
                    return@OnlineTrackAdapter
                }
                artistModel.downloadTrack(track)
                Snackbar.make(binding.root, getString(R.string.download_queued, track.title), Snackbar.LENGTH_SHORT).show()
                topSongsAdapter?.notifyDataSetChanged()
            },
            isDownloaded = { track -> artistModel.isDownloaded(track) },
            isDownloading = { track -> artistModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> artistModel.loadingTrackId.value == trackId },
            isCurrentlyPlaying = { track -> isTrackPlaying(track) },
            showRanking = true,
            onTrackLongClick = { track ->
                openSongMenu(track)
            }
        )
        binding.artistTopSongsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = topSongsAdapter
        }

        artistTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { true },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val track = topSongsAdapter?.currentList?.getOrNull(pos)
                        if (track != null) {
                            artistModel.enqueueTrack(track, playbackModel) {
                                context?.showToast(R.string.lng_queue_added)
                            }
                        }
                    }
                }
            )
        ).also { it.attachToRecyclerView(binding.artistTopSongsRecycler) }

        albumsAdapter = OnlineAlbumCardAdapter { album ->
            findNavController().navigateSafe(
                OnlineArtistFragmentDirections.showOnlineAlbum(album.id, album.title, args.artistName)
            )
        }
        binding.artistAlbumsRecycler.adapter = albumsAdapter

        singlesAdapter = OnlineAlbumCardAdapter { single ->
            findNavController().navigateSafe(
                OnlineArtistFragmentDirections.showOnlineAlbum(single.id, single.title, args.artistName)
            )
        }
        binding.artistSinglesRecycler.adapter = singlesAdapter

        relatedAdapter = RecentArtistsAdapter { related ->
            findNavController().navigateSafe(
                OnlineArtistFragmentDirections.showOnlineArtist(related.id, related.name)
            )
        }
        binding.artistRelatedRecycler.adapter = relatedAdapter

        binding.artistTopSongsToggleBtn.setOnClickListener {
            isTopSongsExpanded = !isTopSongsExpanded
            updateTopSongsList(artistModel.artistDetail.value?.topSongs.orEmpty())
        }

        binding.artistPlayButton.setOnClickListener {
            if (isArtistCurrentSong()) {
                playbackModel.togglePlaying()
            } else {
                artistModel.playTopSongs(playbackModel)
            }
        }

        binding.artistShuffleButton.setOnClickListener {
            val songs = artistModel.artistDetail.value?.topSongs.orEmpty()
            if (songs.isNotEmpty()) {
                val randomTrack = songs.random()
                artistModel.playTrack(randomTrack, playbackModel)
                context?.showToast(R.string.lbl_shuffle)
            }
        }

        var isFavorite = false
        binding.artistFavoriteButton.setOnClickListener {
            isFavorite = !isFavorite
            binding.artistFavoriteButton.setIconResource(
                if (isFavorite) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
            )
            binding.artistFavoriteButton.iconTint = context?.getAttrColorCompat(
                if (isFavorite) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
            context?.showToast(if (isFavorite) R.string.lng_added_favorite else R.string.lng_removed_favorite)
        }

        var isDescriptionExpanded = false
        val toggleDescription = {
            isDescriptionExpanded = !isDescriptionExpanded
            binding.artistDescription.maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4
            binding.artistDescription.ellipsize = if (isDescriptionExpanded) null else android.text.TextUtils.TruncateAt.END
        }
        binding.artistDescription.setOnClickListener { toggleDescription() }
        binding.artistDescriptionCard.setOnClickListener { toggleDescription() }

        OfflineStateHelper.setup(
            binding = binding.artistOfflineLayout,
            onRetry = {
                artistModel.loadArtist(args.artistId, args.artistName)
            }
        )

        artistModel.loadArtist(args.artistId, args.artistName)

        collect(playbackModel.song) {
            topSongsAdapter?.notifyDataSetChanged()
            updateArtistPlayButton()
        }

        collect(playbackModel.isPlaying) {
            topSongsAdapter?.notifyDataSetChanged()
            updateArtistPlayButton()
        }

        collect(artistModel.artistDetail) { detail ->
            if (detail != null) {
                stopSkeletonPulse()
                binding.artistLoadingLayout.isVisible = false
                binding.artistOfflineLayout.root.isVisible = false
                binding.artistScroll.isVisible = true

                isDescriptionExpanded = false
                binding.artistDescription.maxLines = 4
                binding.artistDescription.ellipsize = android.text.TextUtils.TruncateAt.END
                binding.artistToolbar.title = detail.name
                binding.artistHeaderName.text = detail.name

                if (!detail.avatarUrl.isNullOrBlank()) {
                    binding.artistHeroImage.load(detail.avatarUrl) {
                        crossfade(true)
                    }
                    binding.artistHeroBlurImage.load(detail.avatarUrl) {
                        crossfade(true)
                    }
                }

                if (!detail.subscribers.isNullOrBlank()) {
                    binding.artistHeaderAudience.isVisible = true
                    binding.artistHeaderAudience.text = detail.subscribers
                } else {
                    binding.artistHeaderAudience.isVisible = false
                }

                val hasDesc = !detail.description.isNullOrBlank()
                binding.artistDescriptionCard.isVisible = hasDesc
                binding.artistDescription.isVisible = hasDesc
                binding.artistDescription.text = detail.description?.let {
                    it.replace(Regex("""(?is)[\s\n]*(From Wikipedia|De Wikipedia|Fuente:\s*Wikipedia).*$"""), "")
                        .replace(Regex("""\(?\s*https?://\S+\s*\)?"""), "")
                        .trim()
                }

                updateTopSongsList(detail.topSongs)

                albumsAdapter?.submitList(detail.albums)
                binding.artistAlbumsSection.isVisible = detail.albums.isNotEmpty()

                singlesAdapter?.submitList(detail.singles)
                binding.artistSinglesSection.isVisible = detail.singles.isNotEmpty()

                relatedAdapter?.submitList(detail.relatedArtists)
                binding.artistRelatedSection.isVisible = detail.relatedArtists.isNotEmpty()

                updateArtistPlayButton()
            }
        }

        collect(artistModel.isLoading) { isLoading ->
            val hasData = artistModel.artistDetail.value != null
            if (isLoading && !hasData) {
                binding.artistLoadingLayout.isVisible = true
                binding.artistScroll.isVisible = false
                binding.artistOfflineLayout.root.isVisible = false
                startSkeletonPulse()
            } else if (!isLoading && !hasData) {
                stopSkeletonPulse()
                binding.artistLoadingLayout.isVisible = false
                binding.artistScroll.isVisible = false
                binding.artistOfflineLayout.root.isVisible = true
                OfflineStateHelper.applyProposal(
                    binding = binding.artistOfflineLayout,
                    proposal = OfflineStateHelper.getNextProposal(requireContext())
                )
            } else {
                stopSkeletonPulse()
                binding.artistLoadingLayout.isVisible = false
                binding.artistOfflineLayout.root.isVisible = false
                binding.artistScroll.isVisible = true
            }
            binding.artistProgress.isVisible = isLoading && hasData
        }

        collect(artistModel.loadingTrackId) {
            topSongsAdapter?.notifyDataSetChanged()
        }

        collect(artistModel.downloads) {
            topSongsAdapter?.notifyDataSetChanged()
        }

        collect(musicModel.indexingState) {
            topSongsAdapter?.notifyDataSetChanged()
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

    private fun isArtistCurrentSong(): Boolean {
        val currentSong = playbackModel.song.value ?: return false
        val detail = artistModel.artistDetail.value
        if (detail != null && detail.topSongs.any { isTrackMatching(it, currentSong) }) {
            return true
        }
        val artistName = detail?.name ?: args.artistName
        if (artistName.isNotBlank()) {
            val ctx = context ?: return false
            val songArtist = currentSong.artists.resolveNames(ctx)
            return songArtist.contains(artistName, ignoreCase = true) || artistName.contains(songArtist, ignoreCase = true)
        }
        return false
    }

    private fun isArtistPlaying(): Boolean {
        return playbackModel.isPlaying.value && isArtistCurrentSong()
    }

    private fun updateArtistPlayButton() {
        val b = binding ?: return
        val playing = isArtistPlaying()
        if (playing) {
            b.artistPlayButton.setText(R.string.btn_pause)
            b.artistPlayButton.setIconResource(R.drawable.ic_pause_24)
        } else {
            b.artistPlayButton.setText(R.string.btn_play)
            b.artistPlayButton.setIconResource(R.drawable.ic_play_24)
        }
    }

    private fun startSkeletonPulse() {
        val b = binding ?: return
        val target = b.artistLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.artistLoadingLayout
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
        val target = b.artistLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.artistLoadingLayout
        target.alpha = 1.0f
    }

    private fun updateTopSongsList(songs: List<OnlineTrack>) {
        val binding = binding ?: return
        if (songs.isEmpty()) {
            binding.artistTopSongsSection.isVisible = false
            binding.artistTopSongsToggleBtn.isVisible = false
            topSongsAdapter?.submitList(emptyList())
            return
        }
        binding.artistTopSongsSection.isVisible = true
        val hasOverflow = songs.size > collapsedSongsCap
        binding.artistTopSongsToggleBtn.isVisible = hasOverflow
        val listToSubmit = if (isTopSongsExpanded) songs else songs.take(collapsedSongsCap)
        val buttonTextRes = if (isTopSongsExpanded) R.string.btn_show_less_tracks else R.string.btn_show_more_tracks
        binding.artistTopSongsToggleBtn.setText(buttonTextRes)
        topSongsAdapter?.submitList(listToSubmit)
    }

    override fun onDestroyBinding(binding: FragmentOnlineArtistBinding) {
        super.onDestroyBinding(binding)
        isTopSongsExpanded = false
        artistTouchHelper?.attachToRecyclerView(null)
        artistTouchHelper = null
        topSongsAdapter = null
        albumsAdapter = null
        singlesAdapter = null
        relatedAdapter = null
        stopSkeletonPulse()
        skeletonAnimator = null
    }
}

