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
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.crossfade
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
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
import org.nov0caina.sounder.online.download.DownloadStatus
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.oxycblt.musikr.Song
import org.nov0caina.sounder.ui.MotionPhysics
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.ui.attachSpringTouchFeedback
import org.nov0caina.sounder.ui.performSpringPop
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.getAttrColorCompat
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast

@AndroidEntryPoint
class OnlineArtistFragment :
    ViewBindingFragment<FragmentOnlineArtistBinding>(), AppBarLayout.OnOffsetChangedListener {

    @Inject lateinit var imageLoader: ImageLoader

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
    private var isAlbumsGridMode: Boolean = false
    private var isAlbumsGridExpanded: Boolean = false
    private var isSinglesGridMode: Boolean = false
    private var isSinglesGridExpanded: Boolean = false
    private val collapsedGridCap: Int = 6
    private var spacingSmall: Int = 0
    private var lastDownloadStatuses: Map<String, DownloadStatus>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentOnlineArtistBinding =
        FragmentOnlineArtistBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentOnlineArtistBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        spacingSmall = resources.getDimensionPixelSize(R.dimen.spacing_small)
        binding.artistAppbar.addOnOffsetChangedListener(this)

        binding.artistToolbar.title = args.artistName
        binding.artistToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        animateToolbarTitle(binding, 1f)

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
                org.nov0caina.sounder.online.download.DownloadActionHelper.handleTrackDownloadClick(
                    context = requireContext(),
                    lifecycleOwner = viewLifecycleOwner,
                    downloadManager = artistModel.downloadManager,
                    track = track,
                    onStateChanged = { topSongsAdapter?.refreshTrackStates() },
                    onDeleted = { musicModel.refresh() }
                )
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

        binding.artistAlbumsViewToggleBtn.setOnClickListener {
            isAlbumsGridMode = !isAlbumsGridMode
            if (!isAlbumsGridMode) isAlbumsGridExpanded = false
            updateAlbumsSection(artistModel.artistDetail.value?.albums.orEmpty())
        }

        binding.artistAlbumsExpandBtn.setOnClickListener {
            isAlbumsGridExpanded = !isAlbumsGridExpanded
            updateAlbumsSection(artistModel.artistDetail.value?.albums.orEmpty())
        }

        binding.artistSinglesViewToggleBtn.setOnClickListener {
            isSinglesGridMode = !isSinglesGridMode
            if (!isSinglesGridMode) isSinglesGridExpanded = false
            updateSinglesSection(artistModel.artistDetail.value?.singles.orEmpty())
        }

        binding.artistSinglesExpandBtn.setOnClickListener {
            isSinglesGridExpanded = !isSinglesGridExpanded
            updateSinglesSection(artistModel.artistDetail.value?.singles.orEmpty())
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

        binding.artistFavoriteButton.apply {
            attachSpringTouchFeedback(MotionPhysics.ICON_SQUISH_SCALE)
            setOnClickListener { button ->
                artistModel.toggleFavorite { isFav ->
                    if (!isAdded || this@OnlineArtistFragment.binding == null) {
                        return@toggleFavorite
                    }
                    if (MotionPhysics.shouldTriggerFavoritePop(isFav)) {
                        button.performSpringPop()
                    }
                    context?.showToast(
                        if (isFav) R.string.lng_added_favorite else R.string.lng_removed_favorite
                    )
                }
            }
        }

        var isDescriptionExpanded = false
        var isDescriptionTruncated = false
        val toggleDescription = {
            if (isDescriptionTruncated || isDescriptionExpanded) {
                isDescriptionExpanded = !isDescriptionExpanded
                binding.artistDescription.maxLines = if (isDescriptionExpanded) Int.MAX_VALUE else 4
                binding.artistDescription.ellipsize = if (isDescriptionExpanded) null else android.text.TextUtils.TruncateAt.END
                binding.artistDescriptionChevron.animate()
                    .rotation(if (isDescriptionExpanded) 180f else 0f)
                    .setDuration(200)
                    .start()
            }
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
            topSongsAdapter?.refreshTrackStates()
            updateArtistPlayButton()
        }

        collect(playbackModel.isPlaying) {
            topSongsAdapter?.refreshTrackStates()
            updateArtistPlayButton()
        }

        collect(artistModel.artistDetail) { detail ->
            if (detail != null) {
                stopSkeletonPulse()
                val wasHeaderHidden = !binding.artistHeaderContainer.isVisible
                binding.artistLoadingLayout.isVisible = false
                binding.artistOfflineLayout.root.isVisible = false
                binding.artistHeaderContainer.isVisible = true
                binding.artistAmbientGlow.isVisible = true
                binding.artistScroll.isVisible = true
                if (wasHeaderHidden) {
                    animateToolbarTitle(binding, 0f)
                }

                isDescriptionExpanded = false
                isDescriptionTruncated = false
                binding.artistDescription.maxLines = 4
                binding.artistDescription.ellipsize = android.text.TextUtils.TruncateAt.END
                binding.artistDescriptionChevron.rotation = 0f
                binding.artistToolbar.title = detail.name
                binding.artistHeaderName.text = detail.name

                if (!detail.avatarUrl.isNullOrBlank()) {
                    binding.artistAvatar.load(detail.avatarUrl) {
                        crossfade(true)
                    }
                    binding.artistAmbientGlow.bindData(detail.avatarUrl, detail.id, imageLoader)
                } else {
                    binding.artistAvatar.setImageResource(R.drawable.ic_artist_24)
                    binding.artistAmbientGlow.clearGlow()
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
                binding.artistDescriptionChevron.isVisible = false
                binding.artistDescription.text = detail.description?.let {
                    it.replace(Regex("""(?is)[\s\n]*(From Wikipedia|De Wikipedia|Fuente:\s*Wikipedia).*$"""), "")
                        .replace(Regex("""\(?\s*https?://\S+\s*\)?"""), "")
                        .trim()
                }
                if (hasDesc) {
                    binding.artistDescription.post {
                        val b = this@OnlineArtistFragment.binding ?: return@post
                        val layout = b.artistDescription.layout ?: return@post
                        val lineCount = layout.lineCount
                        isDescriptionTruncated = lineCount > 4 || (lineCount > 0 && layout.getEllipsisCount(lineCount - 1) > 0)
                        b.artistDescriptionChevron.isVisible = isDescriptionTruncated
                    }
                }

                updateTopSongsList(detail.topSongs)

                updateAlbumsSection(detail.albums)
                updateSinglesSection(detail.singles)

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
                binding.artistHeaderContainer.isVisible = false
                binding.artistAmbientGlow.isVisible = false
                binding.artistOfflineLayout.root.isVisible = false
                animateToolbarTitle(binding, 1f)
                startSkeletonPulse()
            } else if (!isLoading && !hasData) {
                stopSkeletonPulse()
                binding.artistLoadingLayout.isVisible = false
                binding.artistScroll.isVisible = false
                binding.artistHeaderContainer.isVisible = false
                binding.artistAmbientGlow.isVisible = false
                binding.artistOfflineLayout.root.isVisible = true
                animateToolbarTitle(binding, 1f)
                OfflineStateHelper.applyProposal(
                    binding = binding.artistOfflineLayout,
                    proposal = OfflineStateHelper.getNextProposal(requireContext())
                )
            } else {
                stopSkeletonPulse()
                val wasHeaderHidden = !binding.artistHeaderContainer.isVisible
                binding.artistLoadingLayout.isVisible = false
                binding.artistOfflineLayout.root.isVisible = false
                binding.artistHeaderContainer.isVisible = true
                binding.artistAmbientGlow.isVisible = true
                binding.artistScroll.isVisible = true
                if (wasHeaderHidden) {
                    animateToolbarTitle(binding, 0f)
                }
            }
            binding.artistProgress.isVisible = isLoading && hasData
        }

        collect(artistModel.isFavorite) { isFav ->
            binding.artistFavoriteButton.apply {
                setIconResource(
                    if (isFav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
                )
                iconTint = context?.getAttrColorCompat(
                    if (isFav) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            }
        }

        collect(artistModel.loadingTrackId) {
            topSongsAdapter?.refreshTrackStates()
        }

        collect(artistModel.downloads) { items ->
            val statusMap = items.associate { it.trackId to it.status }
            if (statusMap != lastDownloadStatuses) {
                lastDownloadStatuses = statusMap
                topSongsAdapter?.refreshTrackStates()
            }
        }

        collect(musicModel.indexingState) {
            topSongsAdapter?.refreshTrackStates()
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

    private fun updateAlbumsSection(albums: List<OnlineAlbumSummary>) {
        val b = binding ?: return
        updateReleasesSection(
            releases = albums,
            section = b.artistAlbumsSection,
            headerView = b.artistAlbumsHeader,
            headerTitleRes = R.string.header_albums,
            recycler = b.artistAlbumsRecycler,
            adapter = albumsAdapter,
            viewToggleBtn = b.artistAlbumsViewToggleBtn,
            expandBtn = b.artistAlbumsExpandBtn,
            isGridMode = isAlbumsGridMode,
            isExpanded = isAlbumsGridExpanded
        )
    }

    private fun updateSinglesSection(singles: List<OnlineAlbumSummary>) {
        val b = binding ?: return
        updateReleasesSection(
            releases = singles,
            section = b.artistSinglesSection,
            headerView = b.artistSinglesHeader,
            headerTitleRes = R.string.header_singles,
            recycler = b.artistSinglesRecycler,
            adapter = singlesAdapter,
            viewToggleBtn = b.artistSinglesViewToggleBtn,
            expandBtn = b.artistSinglesExpandBtn,
            isGridMode = isSinglesGridMode,
            isExpanded = isSinglesGridExpanded
        )
    }

    private fun updateReleasesSection(
        releases: List<OnlineAlbumSummary>,
        section: View,
        headerView: android.widget.TextView,
        headerTitleRes: Int,
        recycler: RecyclerView,
        adapter: OnlineAlbumCardAdapter?,
        viewToggleBtn: MaterialButton,
        expandBtn: MaterialButton,
        isGridMode: Boolean,
        isExpanded: Boolean
    ) {
        headerView.text = if (releases.isNotEmpty()) {
            "${getString(headerTitleRes)} (${releases.size})"
        } else {
            getString(headerTitleRes)
        }
        section.isVisible = releases.isNotEmpty()
        if (releases.isEmpty()) {
            viewToggleBtn.isVisible = false
            expandBtn.isVisible = false
            adapter?.submitList(emptyList())
            return
        }

        viewToggleBtn.isVisible = releases.size > 3
        if (isGridMode) {
            if (recycler.layoutManager !is GridLayoutManager) {
                recycler.layoutManager = GridLayoutManager(requireContext(), 3)
            }
            recycler.isNestedScrollingEnabled = false
            adapter?.setGridMode(true)
            viewToggleBtn.setText(R.string.btn_view_carousel)
            viewToggleBtn.setIconResource(R.drawable.ic_carousel_view_24)

            val hasOverflow = releases.size > collapsedGridCap
            expandBtn.isVisible = hasOverflow
            val displayed = if (isExpanded || !hasOverflow) releases else releases.take(collapsedGridCap)
            expandBtn.setText(if (isExpanded) R.string.btn_show_less else R.string.btn_show_more)
            adapter?.submitList(displayed)
        } else {
            val currentLm = recycler.layoutManager
            if (currentLm !is LinearLayoutManager || currentLm is GridLayoutManager) {
                recycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            }
            recycler.isNestedScrollingEnabled = true
            adapter?.setGridMode(false)
            viewToggleBtn.setText(R.string.btn_view_grid)
            viewToggleBtn.setIconResource(R.drawable.ic_grid_view_24)
            expandBtn.isVisible = false
            adapter?.submitList(releases)
        }
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        val b = binding ?: return
        if (!b.artistHeaderContainer.isVisible) {
            animateToolbarTitle(b, 1f)
            return
        }
        val range = appBarLayout.totalScrollRange
        if (range == 0) {
            b.artistHeaderContainer.apply {
                scaleX = 1f
                scaleY = 1f
                alpha = 1f
            }
            b.artistAmbientGlow.apply {
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
        b.artistHeaderContainer.apply {
            scaleX = scale
            scaleY = scale
            alpha = 1f - outRatio
        }
        b.artistAmbientGlow.apply {
            scaleX = scale
            scaleY = scale
            alpha = 1f - outRatio
        }

        val inRatio = ((ratio - 0.75f) / 0.25f).coerceIn(0f, 1f)
        animateToolbarTitle(b, inRatio)
    }

    private fun animateToolbarTitle(binding: FragmentOnlineArtistBinding, ratio: Float) {
        val titleView = binding.artistToolbar.titleContainer
        titleView.alpha = ratio
        titleView.translationY = spacingSmall * (1f - ratio)
    }

    override fun onDestroyBinding(binding: FragmentOnlineArtistBinding) {
        super.onDestroyBinding(binding)
        lastDownloadStatuses = null
        binding.artistAppbar.removeOnOffsetChangedListener(this)
        isTopSongsExpanded = false
        isAlbumsGridMode = false
        isAlbumsGridExpanded = false
        isSinglesGridMode = false
        isSinglesGridExpanded = false
        artistTouchHelper?.attachToRecyclerView(null)
        artistTouchHelper = null
        binding.artistTopSongsRecycler.adapter = null
        binding.artistAlbumsRecycler.adapter = null
        binding.artistSinglesRecycler.adapter = null
        binding.artistRelatedRecycler.adapter = null
        topSongsAdapter = null
        albumsAdapter = null
        singlesAdapter = null
        relatedAdapter = null
        stopSkeletonPulse()
        skeletonAnimator = null
    }
}

