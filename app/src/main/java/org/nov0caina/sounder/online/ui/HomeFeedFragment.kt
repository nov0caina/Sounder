package org.nov0caina.sounder.online.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import kotlinx.coroutines.launch
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentHomeFeedBinding
import org.nov0caina.sounder.online.model.HomeFeedData
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.QuickAccessItem
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.InnerDirections
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.music.PlaylistDecision
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.NetworkUtil
import org.nov0caina.sounder.util.StoragePermissionHelper
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast

@AndroidEntryPoint
class HomeFeedFragment : ViewBindingFragment<FragmentHomeFeedBinding>() {

    private val feedModel: HomeFeedViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()

    private var moodChipsAdapter: HomeMoodAdapter? = null
    private var quickAccessAdapter: HomeQuickAccessAdapter? = null
    private var trendingTracksAdapter: OnlineTrackAdapter? = null
    private var trendingAlbumsAdapter: OnlineAlbumCardAdapter? = null
    private var popularArtistsAdapter: RecentArtistsAdapter? = null
    private var quickPicksAdapter: QuickPicksColumnAdapter? = null
    private var currentHeroTrack: OnlineTrack? = null
    private var trendingTouchHelper: ItemTouchHelper? = null
    private var quickPicksTouchHelper: ItemTouchHelper? = null
    private var skeletonAnimator: ObjectAnimator? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Snackbar.make(requireView(), R.string.dialog_migrate_success, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentHomeFeedBinding =
        FragmentHomeFeedBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeFeedBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeFeedToolbar.inflateMenu(R.menu.toolbar_home_feed)
        binding.homeFeedToolbar.findViewById<com.google.android.material.button.MaterialButton>(R.id.toolbar_navigation_button)?.apply {
            iconTint = null
            iconSize = resources.getDimensionPixelSize(R.dimen.size_icon_medium)
            isClickable = false
            isFocusable = false
            rippleColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            background = null
        }

        // Setup Greeting text
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetingRes = when (currentHour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..19 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
        binding.homeGreetingText.setText(greetingRes)

        // Setup Mood Filter Chips
        moodChipsAdapter = HomeMoodAdapter(
            moods = feedModel.availableMoods,
            selectedMoodId = feedModel.selectedMood.value,
            onMoodSelected = { moodItem ->
                feedModel.selectMood(moodItem.id)
            }
        )
        binding.homeMoodChipsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = moodChipsAdapter
        }

        // Setup Quick Access 2x3 Grid
        quickAccessAdapter = HomeQuickAccessAdapter(
            onItemClick = { item -> handleQuickAccessClick(item) },
            onPlayClick = { item -> handleQuickAccessPlay(item) },
            isItemPlaying = { item -> isQuickAccessItemPlaying(item) },
            onItemLongClick = { item ->
                if (item.track != null) {
                    openSongMenu(item.track)
                } else if (item.artist != null) {
                    onArtistSelected(item.artist)
                } else if (item.isFavorites) {
                    openFavoritesPlaylist()
                }
            }
        )
        binding.homeQuickAccessRecycler.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            adapter = quickAccessAdapter
        }

        // Setup Trending Tracks adapter (Billboard Numbered)
        trendingTracksAdapter = OnlineTrackAdapter(
            onPlayClick = { track ->
                if (isCurrentTrack(track)) {
                    playbackModel.togglePlaying()
                } else {
                    feedModel.playTrendingTrack(track, playbackModel)
                }
            },
            onDownloadClick = { track -> handleDownloadTrack(track) },
            isDownloaded = { track -> feedModel.isDownloaded(track) },
            isDownloading = { track -> feedModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> feedModel.loadingTrackId.value == trackId },
            isCurrentlyPlaying = { track -> isTrackPlaying(track) },
            showRanking = true,
            onTrackLongClick = { track ->
                openSongMenu(track)
            }
        )
        binding.trendingTracksRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trendingTracksAdapter
        }

        trendingTouchHelper =
            ItemTouchHelper(
                SwipeToQueueHelper(
                    canSwipe = { true },
                    onEnqueue = { viewHolder ->
                        val pos = viewHolder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val track = trendingTracksAdapter?.currentList?.getOrNull(pos)
                            if (track != null) {
                                requireContext().showToast(R.string.lng_queue_added)
                                feedModel.enqueueTrack(track, playbackModel)
                            }
                        }
                    },
                )
            ).also {
                it.attachToRecyclerView(binding.trendingTracksRecycler)
            }

        // Setup Trending Albums horizontal adapter
        trendingAlbumsAdapter = OnlineAlbumCardAdapter { album ->
            onAlbumSelected(album)
        }
        binding.trendingAlbumsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = trendingAlbumsAdapter
        }

        // Setup Popular Artists horizontal adapter
        popularArtistsAdapter = RecentArtistsAdapter { artist ->
            onArtistSelected(artist)
        }
        binding.popularArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = popularArtistsAdapter
        }

        // Setup Quick Picks horizontal columns adapter
        quickPicksAdapter = QuickPicksColumnAdapter(
            onPlayClick = { track ->
                if (isCurrentTrack(track)) {
                    playbackModel.togglePlaying()
                } else {
                    feedModel.playQuickPickTrack(track, playbackModel)
                }
            },
            onDownloadClick = { track -> handleDownloadTrack(track) },
            isDownloaded = { track -> feedModel.isDownloaded(track) },
            isDownloading = { track -> feedModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> feedModel.loadingTrackId.value == trackId },
            isCurrentlyPlaying = { track -> isTrackPlaying(track) },
            onTrackLongClick = { track, _ ->
                openSongMenu(track)
                true
            }
        )
        binding.quickPicksRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = quickPicksAdapter
            if (onFlingListener == null) {
                LinearSnapHelper().attachToRecyclerView(this)
            }
        }

        // Trending tracks toggle button (Top 5 vs Top 20) with rotating chevron
        binding.trendingTracksToggleContainer.setOnClickListener {
            feedModel.toggleTopChartsExpanded()
        }
        binding.trendingTracksToggleBtn.setOnClickListener {
            feedModel.toggleTopChartsExpanded()
        }

        // Offline / Error state setup
        OfflineStateHelper.setup(
            binding = binding.homeOfflineLayout,
            onRetry = { feedModel.loadFeed(force = true) }
        )

        // Collect feed data
        collect(feedModel.feedData) { data ->
            val hasData = data.trendingTracks.isNotEmpty() ||
                data.trendingAlbums.isNotEmpty() ||
                data.popularArtists.isNotEmpty()

            if (!hasData) {
                updateViewState(data, feedModel.isLoading.value, feedModel.isOffline.value, feedModel.error.value)
                return@collect
            }

            updateHeroSpotlight(data.trendingTracks.firstOrNull())
            updateTopCharts(data.trendingTracks, feedModel.isTopChartsExpanded.value)

            val hasTrendingAlbums = data.trendingAlbums.isNotEmpty()
            binding.trendingAlbumsSection.isVisible = hasTrendingAlbums
            trendingAlbumsAdapter?.submitList(data.trendingAlbums)

            val hasPopularArtists = data.popularArtists.isNotEmpty()
            binding.popularArtistsSection.isVisible = hasPopularArtists
            popularArtistsAdapter?.submitList(data.popularArtists)

            val hasQuickPicks = data.quickPicks.isNotEmpty()
            binding.quickPicksSection.isVisible = hasQuickPicks
            quickPicksAdapter?.submitList(data.quickPicks.take(24).chunked(3))

            if (data.trendingTracks.isEmpty()) {
                revealContent()
            }
        }

        // Collect favorite song UIDs for instant heart feedback on Hero Spotlight and Shortcuts
        collect(musicModel.favoriteSongUids) { uids ->
            quickAccessAdapter?.notifyDataSetChanged()
            currentHeroTrack?.let { track ->
                val onlineSong = OnlineSong(track, Uri.parse("https://music.youtube.com/watch?v=${track.id}"))
                updateHeroFavoriteButton(onlineSong, uids)
            }
        }

        // Collect Quick Access items (2x3 grid)
        collect(feedModel.quickAccessItems) { items ->
            binding.homeQuickAccessSection.isVisible = items.isNotEmpty()
            quickAccessAdapter?.submitList(items)
        }

        // Collect selected mood
        collect(feedModel.selectedMood) { moodId ->
            moodChipsAdapter?.setSelectedMood(moodId)
        }

        // Collect top charts expanded state
        collect(feedModel.isTopChartsExpanded) { isExpanded ->
            updateTopCharts(feedModel.feedData.value.trendingTracks, isExpanded)
        }

        // Collect loading state
        collect(feedModel.isLoading) { loading ->
            binding.homeFeedProgress.isVisible = loading
            updateViewState(feedModel.feedData.value, loading, feedModel.isOffline.value, feedModel.error.value)
        }

        // Collect offline state
        collect(feedModel.isOffline) { isOffline ->
            updateViewState(feedModel.feedData.value, feedModel.isLoading.value, isOffline, feedModel.error.value)
        }

        // Collect error state
        collect(feedModel.error) { error ->
            updateViewState(feedModel.feedData.value, feedModel.isLoading.value, feedModel.isOffline.value, error)
        }

        // Notify adapters when downloads list updates
        collect(feedModel.downloads) {
            trendingTracksAdapter?.notifyDataSetChanged()
            quickPicksAdapter?.notifyDataSetChanged()
        }

        collect(musicModel.indexingState) {
            trendingTracksAdapter?.notifyDataSetChanged()
            quickPicksAdapter?.notifyDataSetChanged()
        }

        // Collect playback song & playing state to refresh UI play/pause indicators
        collect(playbackModel.song) {
            updatePlaybackState()
        }

        collect(playbackModel.isPlaying) {
            updatePlaybackState()
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

    private fun updateViewState(
        data: HomeFeedData,
        isLoading: Boolean,
        isOffline: Boolean,
        error: String?
    ) {
        val b = binding ?: return
        val hasData = data.trendingTracks.isNotEmpty() ||
            data.trendingAlbums.isNotEmpty() ||
            data.popularArtists.isNotEmpty()

        if (!hasData && (isLoading || error == null) && !isOffline) {
            // Still loading initial data on cold start: show pulsing skeleton
            b.homeLoadingLayout.isVisible = true
            b.homeOfflineLayout.root.isVisible = false
            b.homeFeedScroll.isVisible = false
            startSkeletonPulse()
        } else if ((isOffline || error != null) && !hasData) {
            // Offline or network error and no cached data: show humorous offline screen
            stopSkeletonPulse()
            b.homeLoadingLayout.isVisible = false
            b.homeOfflineLayout.root.isVisible = true
            b.homeFeedScroll.isVisible = false

            val proposal = OfflineStateHelper.getNextProposal(requireContext())
            OfflineStateHelper.applyProposal(
                binding = b.homeOfflineLayout,
                proposal = proposal,
                customError = if (isOffline) null else error
            )
        } else if (hasData) {
            // Content is available: keep feed view visible without jarring skeleton flashes
            stopSkeletonPulse()
            b.homeLoadingLayout.isVisible = false
            b.homeOfflineLayout.root.isVisible = false
            b.homeFeedScroll.isVisible = true
        }
    }

    private fun startSkeletonPulse() {
        val b = binding ?: return
        val target = b.homeLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.homeLoadingLayout
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
        val target = b.homeLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.homeLoadingLayout
        target.alpha = 1.0f
    }

    private fun handleDownloadTrack(track: OnlineTrack) {
        if (!StoragePermissionHelper.hasPermission(requireContext())) {
            storagePermissionLauncher.launch(StoragePermissionHelper.getRequiredPermission())
            Snackbar.make(requireView(), R.string.msg_storage_permission_required, Snackbar.LENGTH_LONG).show()
            return
        }
        feedModel.downloadTrack(track)
        Snackbar.make(requireView(), getString(R.string.download_queued, track.title), Snackbar.LENGTH_SHORT).show()
        trendingTracksAdapter?.notifyDataSetChanged()
        quickPicksAdapter?.notifyDataSetChanged()
    }

    private fun revealContent() {
        val b = binding ?: return
        stopSkeletonPulse()
        b.homeLoadingLayout.isVisible = false
        b.homeOfflineLayout.root.isVisible = false
        b.homeFeedScroll.isVisible = true
    }

    private fun updateTopCharts(allTracks: List<OnlineTrack>, isExpanded: Boolean) {
        val b = binding ?: return
        val hasTrendingTracks = allTracks.isNotEmpty()
        b.trendingTracksSection.isVisible = hasTrendingTracks
        val tracksToShow = if (isExpanded) allTracks.take(20) else allTracks.take(5)
        b.trendingTracksToggleContainer.isVisible = allTracks.size > 5
        if (isExpanded) {
            b.trendingTracksToggleBtn.setText(R.string.home_show_top_5)
        } else {
            b.trendingTracksToggleBtn.setText(R.string.home_show_top_20)
        }

        val targetRotation = if (isExpanded) 180f else 0f
        b.trendingTracksToggleChevron.animate()
            .rotation(targetRotation)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        trendingTracksAdapter?.submitList(tracksToShow) {
            revealContent()
        }
    }

    private fun updateHeroSpotlight(topTrack: OnlineTrack?) {
        val b = binding ?: return
        currentHeroTrack = topTrack
        if (topTrack == null) {
            b.homeHeroSpotlight.root.isVisible = false
            return
        }

        b.homeHeroSpotlight.root.isVisible = true
        b.homeHeroSpotlight.heroSpotlightTitle.text = topTrack.title
        val artistSubtitle = if (topTrack.durationMs > 0) {
            "${topTrack.artist} • ${topTrack.formattedDuration()}"
        } else {
            topTrack.artist
        }
        b.homeHeroSpotlight.heroSpotlightArtist.text = artistSubtitle

        CoilUtils.dispose(b.homeHeroSpotlight.heroSpotlightCover)
        b.homeHeroSpotlight.heroSpotlightCover.setImageDrawable(null)
        if (!topTrack.artworkUrl.isNullOrBlank()) {
            b.homeHeroSpotlight.heroSpotlightCover.load(topTrack.artworkUrl) {
                crossfade(true)
            }
        } else {
            b.homeHeroSpotlight.heroSpotlightCover.setImageResource(R.drawable.ic_song_24)
        }

        b.homeHeroSpotlight.heroSpotlightPlayBtn.setOnClickListener {
            if (isCurrentTrack(topTrack)) {
                playbackModel.togglePlaying()
            } else {
                feedModel.playTrendingTrack(topTrack, playbackModel)
            }
        }
        b.homeHeroSpotlight.heroSpotlightCard.setOnClickListener {
            if (isCurrentTrack(topTrack)) {
                playbackModel.togglePlaying()
            } else {
                feedModel.playTrendingTrack(topTrack, playbackModel)
            }
        }
        b.homeHeroSpotlight.heroSpotlightCard.setOnLongClickListener {
            openSongMenu(topTrack)
            true
        }
        updateHeroPlayButton(topTrack)

        val onlineSong = OnlineSong(topTrack, Uri.parse("https://music.youtube.com/watch?v=${topTrack.id}"))
        updateHeroFavoriteButton(onlineSong, musicModel.favoriteSongUids.value)

        b.homeHeroSpotlight.heroSpotlightLikeBtn.setOnClickListener {
            musicModel.toggleFavorite(onlineSong) { isFav ->
                requireContext().showToast(if (isFav) R.string.lng_added_favorite else R.string.lng_removed_favorite)
            }
        }
    }

    private fun updateHeroPlayButton(topTrack: OnlineTrack?) {
        val b = binding ?: return
        val track = topTrack ?: currentHeroTrack ?: return
        val playing = isTrackPlaying(track)
        if (playing) {
            b.homeHeroSpotlight.heroSpotlightPlayBtn.setText(R.string.btn_pause)
            b.homeHeroSpotlight.heroSpotlightPlayBtn.setIconResource(R.drawable.ic_pause_24)
        } else {
            b.homeHeroSpotlight.heroSpotlightPlayBtn.setText(R.string.btn_play)
            b.homeHeroSpotlight.heroSpotlightPlayBtn.setIconResource(R.drawable.ic_play_24)
        }
    }

    private fun updatePlaybackState() {
        quickAccessAdapter?.notifyDataSetChanged()
        trendingTracksAdapter?.notifyDataSetChanged()
        quickPicksAdapter?.notifyDataSetChanged()
        updateHeroPlayButton(currentHeroTrack)
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

    private fun updateHeroFavoriteButton(song: OnlineSong?, favoriteUids: Set<Music.UID>) {
        val b = binding ?: return
        val isFav = song != null && favoriteUids.contains(song.uid)
        val iconRes = if (isFav) R.drawable.ic_favorite_24 else R.drawable.ic_favorite_border_24
        b.homeHeroSpotlight.heroSpotlightLikeBtn.setIconResource(iconRes)
    }

    private fun handleQuickAccessClick(item: QuickAccessItem) {
        if (item.isFavorites) {
            openFavoritesPlaylist()
        } else if (item.track != null) {
            if (isCurrentTrack(item.track)) {
                playbackModel.togglePlaying()
            } else {
                feedModel.playQuickAccessTrack(item.track, playbackModel)
            }
        } else if (item.artist != null) {
            onArtistSelected(item.artist)
        }
    }

    private fun handleQuickAccessPlay(item: QuickAccessItem) {
        if (item.isFavorites) {
            playFavorites()
        } else if (item.track != null) {
            if (isCurrentTrack(item.track)) {
                playbackModel.togglePlaying()
            } else {
                feedModel.playQuickAccessTrack(item.track, playbackModel)
            }
        } else if (item.artist != null) {
            if (isQuickAccessItemCurrent(item)) {
                playbackModel.togglePlaying()
            } else {
                feedModel.playArtist(item.artist, playbackModel)
            }
        }
    }

    private fun openFavoritesPlaylist() {
        val favPlaylist = musicModel.getFavoritesPlaylist()
        if (favPlaylist != null) {
            findNavController().navigateSafe(HomeFeedFragmentDirections.showPlaylist(favPlaylist.uid))
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val createdFav = musicModel.getOrCreateFavoritesPlaylist()
                if (createdFav != null) {
                    findNavController().navigateSafe(HomeFeedFragmentDirections.showPlaylist(createdFav.uid))
                } else {
                    requireActivity().findViewById<NavigationBarView>(R.id.main_bottom_nav)?.selectedItemId = R.id.nav_library
                }
            }
        }
    }

    private fun playFavorites() {
        if (isFavoritesPlaying()) {
            playbackModel.togglePlaying()
            return
        }
        if (isFavoritesCurrent()) {
            playbackModel.togglePlaying()
            return
        }

        val favSongs = musicModel.getFavoritesSongs()
        if (favSongs.isEmpty()) {
            context?.showToast(R.string.lbl_empty_playlist)
            return
        }

        val favPlaylist = musicModel.getFavoritesPlaylist()
        if (favPlaylist != null) {
            playbackModel.play(favPlaylist)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val createdFav = musicModel.getOrCreateFavoritesPlaylist()
                if (createdFav != null) {
                    playbackModel.play(createdFav)
                } else {
                    context?.showToast(R.string.lbl_empty_playlist)
                }
            }
        }
    }

    private fun isFavoritesCurrent(): Boolean {
        val currentSong = playbackModel.song.value ?: return false
        val favUids = musicModel.favoriteSongUids.value
        return favUids.contains(currentSong.uid)
    }

    private fun isFavoritesPlaying(): Boolean {
        return playbackModel.isPlaying.value && isFavoritesCurrent()
    }

    private fun isQuickAccessItemCurrent(item: QuickAccessItem): Boolean {
        val currentSong = playbackModel.song.value ?: return false
        if (item.isFavorites) {
            return isFavoritesCurrent()
        }
        if (item.track != null) {
            return isTrackMatching(item.track, currentSong)
        }
        if (item.artist != null) {
            val ctx = context ?: return false
            val songArtist = currentSong.artists.resolveNames(ctx)
            return songArtist.contains(item.artist.name, ignoreCase = true) ||
                item.artist.name.contains(songArtist, ignoreCase = true)
        }
        return false
    }

    private fun isQuickAccessItemPlaying(item: QuickAccessItem): Boolean {
        if (!playbackModel.isPlaying.value) return false
        return isQuickAccessItemCurrent(item)
    }

    private fun onAlbumSelected(album: OnlineAlbumSummary) {
        val args = Bundle().apply {
            putString("albumId", album.id)
            putString("albumTitle", album.title)
            putString("artistName", album.year ?: "")
        }
        findNavController().navigate(R.id.action_global_online_album, args)
    }

    private fun onArtistSelected(artist: OnlineArtistSummary) {
        feedModel.recordRecentArtist(artist)
        val args = Bundle().apply {
            putString("artistId", artist.id)
            putString("artistName", artist.name)
        }
        findNavController().navigate(R.id.action_global_online_artist, args)
    }

    override fun onDestroyBinding(binding: FragmentHomeFeedBinding) {
        super.onDestroyBinding(binding)
        trendingTouchHelper?.attachToRecyclerView(null)
        trendingTouchHelper = null
        quickPicksTouchHelper?.attachToRecyclerView(null)
        quickPicksTouchHelper = null
        stopSkeletonPulse()
        skeletonAnimator = null
        moodChipsAdapter = null
        quickAccessAdapter = null
        trendingTracksAdapter = null
        trendingAlbumsAdapter = null
        popularArtistsAdapter = null
        quickPicksAdapter = null
        currentHeroTrack = null
    }
}
