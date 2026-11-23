package org.nov0caina.sounder.online.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.load
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.nov0caina.sounder.InnerDirections
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentDiscoverBinding
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.PlaylistDecision
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.download.DownloadStatus
import org.nov0caina.sounder.online.model.HomeMoodItem
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.ui.ViewBindingFragment
import org.nov0caina.sounder.util.NetworkUtil
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.getAttrColorCompat
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast

@AndroidEntryPoint
class DiscoverFragment : ViewBindingFragment<FragmentDiscoverBinding>() {

    private enum class ResultFilter { ALL, ARTISTS, SONGS }

    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var onlineSettings: OnlineSettings

    private val discoverModel: DiscoverViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()

    // All tab adapters
    private var allTrackAdapter: OnlineTrackAdapter? = null
    private var relatedArtistsAdapter: RecentArtistsAdapter? = null

    // Artists tab ConcatAdapter parts
    private var artistsHeroAdapter: DiscoverHeroAdapter? = null
    private var artistsHeaderAdapter: DiscoverSectionHeaderAdapter? = null
    private var artistAdapter: OnlineArtistAdapter? = null
    private var artistsToggleFooterAdapter: DiscoverToggleFooterAdapter? = null
    private var artistsConcatAdapter: ConcatAdapter? = null

    // Songs tab ConcatAdapter parts
    private var topPicksHeaderAdapter: DiscoverSectionHeaderAdapter? = null
    private var topPickAdapter: OnlineTopPickAdapter? = null
    private var moreSongsHeaderAdapter: DiscoverSectionHeaderAdapter? = null
    private var compactTrackAdapter: OnlineTrackAdapter? = null
    private var songsToggleFooterAdapter: DiscoverToggleFooterAdapter? = null
    private var songsConcatAdapter: ConcatAdapter? = null

    // Home adapters
    private var recentAdapter: RecentArtistsAdapter? = null
    private var moodAdapter: HomeMoodAdapter? = null
    private var genreAdapter: GenreAdapter? = null
    private var nationalTrendingAdapter: OnlineTrackAdapter? = null
    private var discoverTrendingTouchHelper: ItemTouchHelper? = null

    private var currentFilter = ResultFilter.ALL
    private var isAllTracksExpanded = false
    private var isArtistsExpanded = false
    private var isSongsExpanded = false
    private var isSuppressingTextWatcher = false
    private var hasRevealedResultsForActiveQuery = false
    private var lastGenreVisibility: Pair<Boolean, Boolean>? = null
    private var lastDownloadStatuses: Map<String, DownloadStatus>? = null

    private val artistGridInitialCap = 12
    private val collapsedTracksCapAll = 10
    private val songsTopPicksCap = 6
    private val songsMoreInitialCap = 14

    private var allTouchHelper: ItemTouchHelper? = null
    private var songsTabTouchHelper: ItemTouchHelper? = null
    private var skeletonAnimator: ObjectAnimator? = null
    private var searchBackPressedCallback: OnBackPressedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater): FragmentDiscoverBinding =
        FragmentDiscoverBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentDiscoverBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        searchBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitResultsToHome(requireBinding())
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackPressedCallback!!)

        binding.discoverCategoryBackBtn.setOnClickListener {
            exitResultsToHome(binding)
        }

        binding.discoverNormalToolbar.setNavigationOnClickListener {
            if (discoverModel.activeQueryState.value !is DiscoverQueryState.Idle) {
                exitResultsToHome(binding)
            } else {
                binding.discoverSearchInput.requestFocus()
            }
        }

        binding.discoverNormalToolbar.inflateMenu(R.menu.toolbar_discover)

        // Setup Deduplicated Activity Mood Chips in Discover Home (leaves search bar clear)
        val quickMoods = DiscoverViewModel.ACTIVITY_MOODS
        moodAdapter = HomeMoodAdapter(
            moods = quickMoods,
            selectedMoodId = "all",
            onMoodSelected = { moodItem ->
                if (moodItem.id == "all") {
                    exitResultsToHome(binding)
                } else {
                    val title = getString(moodItem.nameRes)
                    clearSearchInputSilently(binding)
                    startSearchLoading()
                    discoverModel.selectCategory(title = title, apiQuery = moodItem.query)
                }
            }
        )
        binding.discoverMoodsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = moodAdapter
        }

        // Setup Filter Chips Bar
        setupFilterChips(binding)

        binding.resultsSongsPlayAllBtn.setOnClickListener {
            val tracks = discoverModel.categorizedResults.value.tracks
            if (tracks.isNotEmpty()) {
                discoverModel.playAllTracks(tracks, playbackModel, shuffle = false)
            }
        }
        binding.resultsSongsShuffleBtn.setOnClickListener {
            val tracks = discoverModel.categorizedResults.value.tracks
            if (tracks.isNotEmpty()) {
                discoverModel.playAllTracks(tracks, playbackModel, shuffle = true)
            }
        }

        // Setup Recent Artists horizontal adapter
        recentAdapter = RecentArtistsAdapter { artist -> onArtistSelected(artist) }
        binding.recentArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
        }

        // Setup Related Artists horizontal adapter for All tab
        relatedArtistsAdapter = RecentArtistsAdapter { artist -> onArtistSelected(artist) }
        binding.resultsRelatedArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedArtistsAdapter
        }

        // Setup Genre Adapter (leaves search bar clear)
        genreAdapter = GenreAdapter(genres = loadActiveGenres()) { genre ->
            clearSearchInputSilently(binding)
            startSearchLoading()
            val title = if (genre.nameRes != 0) getString(genre.nameRes) else genre.name
            discoverModel.selectCategory(title = title, apiQuery = genre.query)
        }
        binding.genresRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = genreAdapter
        }

        val handleDownloadTrack: (OnlineTrack) -> Unit = { track ->
            org.nov0caina.sounder.online.download.DownloadActionHelper.handleTrackDownloadClick(
                context = requireContext(),
                lifecycleOwner = viewLifecycleOwner,
                downloadManager = discoverModel.downloadManager,
                track = track,
                onStateChanged = {
                    allTrackAdapter?.refreshTrackStates()
                    compactTrackAdapter?.refreshTrackStates()
                    topPickAdapter?.refreshTrackStates()
                    nationalTrendingAdapter?.refreshTrackStates()
                },
                onDeleted = { musicModel.refresh() }
            )
        }

        // --- National Trending Charts Section in Discover Home ---
        nationalTrendingAdapter = OnlineTrackAdapter(
            onPlayClick = { track -> discoverModel.playTrendingTrack(track, playbackModel) },
            onDownloadClick = handleDownloadTrack,
            isDownloaded = { track -> discoverModel.isDownloaded(track) },
            isDownloading = { track -> discoverModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> discoverModel.loadingTrackId.value == trackId },
            showRanking = true,
            onTrackLongClick = { track -> openSongMenu(track) },
            onTrackMenuClick = { track -> openSongMenu(track) }
        )
        binding.discoverTrendingRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = nationalTrendingAdapter
            isNestedScrollingEnabled = false
        }
        discoverTrendingTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { true },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val track = nationalTrendingAdapter?.currentList?.getOrNull(pos)
                        if (track != null) {
                            discoverModel.enqueueTrack(track, playbackModel) {
                                context?.showToast(R.string.lng_queue_added)
                            }
                        }
                    }
                }
            )
        ).also { it.attachToRecyclerView(binding.discoverTrendingRecycler) }
        binding.discoverTrendingToggleBtn.setOnClickListener {
            discoverModel.toggleTrendingExpanded()
        }
        binding.discoverTrendingToggleContainer.setOnClickListener {
            discoverModel.toggleTrendingExpanded()
        }

        // --- TAB 1 (ALL): Ranked Tracks List ---
        allTrackAdapter = OnlineTrackAdapter(
            onPlayClick = { track -> discoverModel.playTrack(track, playbackModel) },
            onDownloadClick = handleDownloadTrack,
            isDownloaded = { track -> discoverModel.isDownloaded(track) },
            isDownloading = { track -> discoverModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> discoverModel.loadingTrackId.value == trackId },
            showRanking = true,
            onTrackLongClick = { track -> openSongMenu(track) },
            onTrackMenuClick = { track -> openSongMenu(track) }
        )
        binding.discoverRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = allTrackAdapter
            itemAnimator = null
            setItemViewCacheSize(24)
        }

        allTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { true },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val track = allTrackAdapter?.currentList?.getOrNull(pos)
                        if (track != null) {
                            discoverModel.enqueueTrack(track, playbackModel) {
                                context?.showToast(R.string.lng_queue_added)
                            }
                        }
                    }
                }
            )
        ).also { it.attachToRecyclerView(binding.discoverRecycler) }

        binding.resultsTracksToggleBtn.setOnClickListener {
            isAllTracksExpanded = !isAllTracksExpanded
            updateAllTracksSlice(binding, discoverModel.categorizedResults.value.tracks)
        }

        // --- TAB 2 (ARTISTS): True Recycling GridLayoutManager(3) + ConcatAdapter ---
        artistsHeroAdapter = DiscoverHeroAdapter(
            onArtistClick = { artist -> onArtistSelected(artist) },
            onPlayClick = { topArtist ->
                val tracks = discoverModel.categorizedResults.value.tracks
                val matchingTrack = tracks.firstOrNull { it.artist.contains(topArtist.name, ignoreCase = true) }
                    ?: tracks.firstOrNull()
                if (matchingTrack != null) {
                    discoverModel.playTrack(matchingTrack, playbackModel)
                } else {
                    onArtistSelected(topArtist)
                }
            }
        )
        artistsHeaderAdapter = DiscoverSectionHeaderAdapter(R.string.header_artists)
        artistAdapter = OnlineArtistAdapter { artist -> onArtistSelected(artist) }.apply {
            useGridLayout = true
        }
        artistsToggleFooterAdapter = DiscoverToggleFooterAdapter {
            isArtistsExpanded = !isArtistsExpanded
            updateArtistsTabSlice(discoverModel.categorizedResults.value)
        }
        artistsConcatAdapter = ConcatAdapter(
            artistsHeroAdapter!!,
            artistsHeaderAdapter!!,
            artistAdapter!!,
            artistsToggleFooterAdapter!!,
        )
        binding.discoverArtistsTabRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 3).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val heroCount = artistsHeroAdapter?.itemCount ?: 0
                        val headerCount = artistsHeaderAdapter?.itemCount ?: 0
                        val gridCount = artistAdapter?.itemCount ?: 0
                        val gridStart = heroCount + headerCount
                        return if (position in gridStart until (gridStart + gridCount)) 1 else 3
                    }
                }
            }
            adapter = artistsConcatAdapter
            itemAnimator = null
            setItemViewCacheSize(36)
        }

        // --- TAB 3 (SONGS): True Recycling Hybrid GridLayoutManager(2) + ConcatAdapter ---
        topPicksHeaderAdapter = DiscoverSectionHeaderAdapter(R.string.header_top_picks)
        topPickAdapter = OnlineTopPickAdapter(
            onPlayClick = { track -> discoverModel.playTrack(track, playbackModel) },
            onDownloadClick = handleDownloadTrack,
            onMenuClick = { track -> openSongMenu(track) },
            isDownloaded = { track -> discoverModel.isDownloaded(track) },
            isDownloading = { track -> discoverModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> discoverModel.loadingTrackId.value == trackId },
        )
        moreSongsHeaderAdapter = DiscoverSectionHeaderAdapter(R.string.header_more_songs)
        compactTrackAdapter = OnlineTrackAdapter(
            onPlayClick = { track -> discoverModel.playTrack(track, playbackModel) },
            onDownloadClick = handleDownloadTrack,
            isDownloaded = { track -> discoverModel.isDownloaded(track) },
            isDownloading = { track -> discoverModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> discoverModel.loadingTrackId.value == trackId },
            showRanking = false,
            onTrackLongClick = { track -> openSongMenu(track) },
            onTrackMenuClick = { track -> openSongMenu(track) }
        ).apply {
            compactMode = true
        }
        songsToggleFooterAdapter = DiscoverToggleFooterAdapter {
            isSongsExpanded = !isSongsExpanded
            updateSongsTabMoreSlice(discoverModel.categorizedResults.value.tracks)
        }
        songsConcatAdapter = ConcatAdapter(
            topPicksHeaderAdapter!!,
            topPickAdapter!!,
            moreSongsHeaderAdapter!!,
            compactTrackAdapter!!,
            songsToggleFooterAdapter!!,
        )
        binding.discoverSongsTabRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val headerCount = topPicksHeaderAdapter?.itemCount ?: 0
                        val topPickCount = topPickAdapter?.itemCount ?: 0
                        return if (position in headerCount until (headerCount + topPickCount)) 1 else 2
                    }
                }
            }
            adapter = songsConcatAdapter
            itemAnimator = null
            setItemViewCacheSize(36)
        }

        songsTabTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { viewHolder -> viewHolder is OnlineTrackAdapter.ViewHolder },
                onEnqueue = { viewHolder ->
                    val globalPos = viewHolder.bindingAdapterPosition
                    if (globalPos != RecyclerView.NO_POSITION) {
                        val offset = (topPicksHeaderAdapter?.itemCount ?: 0) +
                            (topPickAdapter?.itemCount ?: 0) +
                            (moreSongsHeaderAdapter?.itemCount ?: 0)
                        val track = compactTrackAdapter?.currentList?.getOrNull(globalPos - offset)
                        if (track != null) {
                            discoverModel.enqueueTrack(track, playbackModel) {
                                context?.showToast(R.string.lng_queue_added)
                            }
                        }
                    }
                }
            )
        ).also { it.attachToRecyclerView(binding.discoverSongsTabRecycler) }

        // Pull-to-Refresh wiring
        val ctx = requireContext()
        val refreshPrimary = ctx.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
        val refreshBg = ctx.getAttrColorCompat(com.google.android.material.R.attr.colorSurfaceContainerHigh).defaultColor

        binding.discoverHomeSwipeRefresh.apply {
            setColorSchemeColors(refreshPrimary)
            setProgressBackgroundColorSchemeColor(refreshBg)
            setOnRefreshListener {
                recentAdapter?.notifyDataSetChanged()
                discoverModel.loadNationalTrends(forceRefresh = true)
                isRefreshing = false
            }
        }

        binding.discoverResultsSwipeRefresh.apply {
            setColorSchemeColors(refreshPrimary)
            setProgressBackgroundColorSchemeColor(refreshBg)
            setOnChildScrollUpCallback { _, _ ->
                when (currentFilter) {
                    ResultFilter.ALL -> binding.discoverResultsScroll.canScrollVertically(-1)
                    ResultFilter.ARTISTS -> binding.discoverArtistsTabRecycler.canScrollVertically(-1)
                    ResultFilter.SONGS -> binding.discoverSongsTabRecycler.canScrollVertically(-1)
                }
            }
            setOnRefreshListener {
                if (discoverModel.activeQueryState.value !is DiscoverQueryState.Idle) {
                    discoverModel.refreshActiveQuery()
                } else {
                    isRefreshing = false
                }
            }
        }

        // Search input handling
        binding.discoverSearchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.discoverSearchInput.windowToken, 0)
                binding.discoverSearchInput.clearFocus()
                val query = binding.discoverSearchInput.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    startSearchLoading()
                    discoverModel.search(query)
                }
                true
            } else {
                false
            }
        }

        binding.discoverSearchInput.addTextChangedListener { text ->
            if (isSuppressingTextWatcher) return@addTextChangedListener
            val hasText = !text.isNullOrBlank()
            if (!hasText && discoverModel.activeQueryState.value is DiscoverQueryState.Search) {
                isAllTracksExpanded = false
                isArtistsExpanded = false
                isSongsExpanded = false
                hasRevealedResultsForActiveQuery = false
                clearResultAdapters()
                binding.resultsFilterChipsBar.isVisible = false
                binding.resultsSongsActionBar.isVisible = false
                discoverModel.clearSearch()
                updateSearchDisplayState()
            }
        }

        OfflineStateHelper.setup(
            binding = binding.discoverOfflineLayout,
            onRetry = {
                if (discoverModel.activeQueryState.value !is DiscoverQueryState.Idle) {
                    startSearchLoading()
                    discoverModel.retryActiveQuery()
                }
            }
        )

        collect(discoverModel.activeQueryState) { queryState ->
            val b = this.binding ?: return@collect
            searchBackPressedCallback?.isEnabled = queryState !is DiscoverQueryState.Idle
            if (queryState is DiscoverQueryState.Category) {
                b.discoverCategoryHeader.isVisible = true
                b.discoverCategoryTitle.text = queryState.title
                val matchingQuickMood = quickMoods.find {
                    it.id != "all" && it.query.equals(queryState.apiQuery, ignoreCase = true)
                }
                moodAdapter?.setSelectedMood(matchingQuickMood?.id ?: "all")
            } else {
                b.discoverCategoryHeader.isVisible = false
                moodAdapter?.setSelectedMood("all")
            }
        }

        // Collect Recent Artists
        collect(discoverModel.recentArtists) { recents ->
            val b = this.binding ?: return@collect
            recentAdapter?.submitList(recents)
            b.recentArtistsSection.isVisible = recents.isNotEmpty()
        }

        // Collect Categorized Search Results
        collect(discoverModel.categorizedResults) { result ->
            val b = this.binding ?: return@collect
            val hasActiveQuery = discoverModel.activeQueryState.value !is DiscoverQueryState.Idle
            if (!hasActiveQuery) {
                b.discoverResultsSwipeRefresh.isRefreshing = false
                updateSearchDisplayState()
                return@collect
            }

            if (result.artists.isEmpty() && result.tracks.isEmpty()) {
                if (discoverModel.isSearching.value) {
                    if (!b.discoverResultsSwipeRefresh.isRefreshing) {
                        b.discoverHomeSwipeRefresh.isVisible = false
                        b.discoverResultsSwipeRefresh.isVisible = false
                        b.resultsFilterChipsBar.isVisible = false
                        b.resultsSongsActionBar.isVisible = false
                        hideEmptyAndOfflineState(b)
                        b.discoverLoadingLayout.isVisible = true
                        startSkeletonPulse()
                    }
                } else {
                    b.discoverResultsSwipeRefresh.isRefreshing = false
                    clearResultAdapters()
                    b.resultsFilterChipsBar.isVisible = false
                    b.resultsSongsActionBar.isVisible = false
                    stopSkeletonPulse()
                    b.discoverHomeSwipeRefresh.isVisible = false
                    b.discoverLoadingLayout.isVisible = false
                    b.discoverResultsSwipeRefresh.isVisible = false
                    showEmptyOrOfflineState(b)
                }
                return@collect
            }

            // Reattach All tab adapters if detached cleanly
            if (b.discoverRecycler.adapter == null) {
                b.discoverRecycler.adapter = allTrackAdapter
            }
            if (b.resultsRelatedArtistsRecycler.adapter == null) {
                b.resultsRelatedArtistsRecycler.adapter = relatedArtistsAdapter
            }

            // Pre-populate ALL 3 tabs immediately in the same pass for 0ms chip switching
            populateAllTabs(b, result)
            applyTabVisibility(b, result)
            prefetchUpcomingArtwork(result)

            val wasRefreshing = b.discoverResultsSwipeRefresh.isRefreshing
            val shouldResetScroll = !wasRefreshing && !hasRevealedResultsForActiveQuery
            hasRevealedResultsForActiveQuery = true
            b.discoverHomeSwipeRefresh.isVisible = false
            hideEmptyAndOfflineState(b)
            b.resultsFilterChipsBar.isVisible = true
            b.discoverResultsSwipeRefresh.isVisible = true

            if (shouldResetScroll) {
                b.discoverResultsScroll.scrollTo(0, 0)
                b.discoverArtistsTabRecycler.scrollToPosition(0)
                b.discoverSongsTabRecycler.scrollToPosition(0)
            }

            b.discoverResultsTabsContainer.post {
                val currentBinding = this.binding ?: return@post
                val queryActive = discoverModel.activeQueryState.value !is DiscoverQueryState.Idle
                if (queryActive) {
                    stopSkeletonPulse()
                    currentBinding.discoverLoadingLayout.isVisible = false
                    hideEmptyAndOfflineState(currentBinding)
                    currentBinding.discoverHomeSwipeRefresh.isVisible = false
                    currentBinding.resultsFilterChipsBar.isVisible = true
                    currentBinding.discoverResultsSwipeRefresh.isVisible = true
                    currentBinding.discoverResultsSwipeRefresh.isRefreshing = discoverModel.isSearching.value
                    searchBackPressedCallback?.isEnabled = true
                    val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(currentBinding.discoverSearchInput.windowToken, 0)
                    currentBinding.discoverSearchInput.clearFocus()
                }
            }
        }

        // Collect Searching Progress State
        collect(discoverModel.isSearching) { isSearching ->
            val b = this.binding ?: return@collect
            if (!isSearching) {
                b.discoverResultsSwipeRefresh.isRefreshing = false
            }
            val hasActiveQuery = discoverModel.activeQueryState.value !is DiscoverQueryState.Idle
            if (hasActiveQuery) {
                if (isSearching) {
                    if (!b.discoverResultsSwipeRefresh.isRefreshing) {
                        b.discoverHomeSwipeRefresh.isVisible = false
                        b.discoverResultsSwipeRefresh.isVisible = false
                        b.resultsFilterChipsBar.isVisible = false
                        b.resultsSongsActionBar.isVisible = false
                        hideEmptyAndOfflineState(b)
                        b.discoverLoadingLayout.isVisible = true
                        startSkeletonPulse()
                    }
                } else {
                    val result = discoverModel.categorizedResults.value
                    if (result.artists.isEmpty() && result.tracks.isEmpty()) {
                        stopSkeletonPulse()
                        b.discoverLoadingLayout.isVisible = false
                        b.discoverResultsSwipeRefresh.isVisible = false
                        b.resultsFilterChipsBar.isVisible = false
                        b.resultsSongsActionBar.isVisible = false
                        showEmptyOrOfflineState(b)
                    }
                }
            }
        }

        // Collect National Trending Charts for Discover Home
        collect(discoverModel.nationalTrendingTracks) { tracks ->
            updateDiscoverTrendingCharts(tracks, discoverModel.isTrendingExpanded.value)
        }
        collect(discoverModel.isTrendingExpanded) { isExpanded ->
            updateDiscoverTrendingCharts(discoverModel.nationalTrendingTracks.value, isExpanded)
        }

        collect(discoverModel.loadingTrackId) {
            allTrackAdapter?.refreshTrackStates()
            compactTrackAdapter?.refreshTrackStates()
            topPickAdapter?.refreshTrackStates()
            nationalTrendingAdapter?.refreshTrackStates()
        }

        collect(discoverModel.downloads) { items ->
            val statusMap = items.associate { it.trackId to it.status }
            if (statusMap != lastDownloadStatuses) {
                lastDownloadStatuses = statusMap
                allTrackAdapter?.refreshTrackStates()
                compactTrackAdapter?.refreshTrackStates()
                topPickAdapter?.refreshTrackStates()
                nationalTrendingAdapter?.refreshTrackStates()
            }
        }

        collect(musicModel.indexingState) {
            allTrackAdapter?.refreshTrackStates()
            compactTrackAdapter?.refreshTrackStates()
            topPickAdapter?.refreshTrackStates()
            nationalTrendingAdapter?.refreshTrackStates()
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

    private fun clearSearchInputSilently(b: FragmentDiscoverBinding) {
        isSuppressingTextWatcher = true
        b.discoverSearchInput.text?.clear()
        isSuppressingTextWatcher = false
        b.discoverSearchInput.clearFocus()
    }

    private fun clearResultAdapters() {
        relatedArtistsAdapter?.submitList(emptyList())
        allTrackAdapter?.submitList(emptyList())
        artistsHeroAdapter?.artist = null
        artistsHeaderAdapter?.visible = false
        artistAdapter?.submitList(emptyList())
        artistsToggleFooterAdapter?.textRes = null
        topPicksHeaderAdapter?.visible = false
        topPickAdapter?.submitList(emptyList())
        moreSongsHeaderAdapter?.visible = false
        compactTrackAdapter?.submitList(emptyList())
        songsToggleFooterAdapter?.textRes = null
    }

    private fun exitResultsToHome(b: FragmentDiscoverBinding) {
        val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(b.discoverSearchInput.windowToken, 0)
        isAllTracksExpanded = false
        isArtistsExpanded = false
        isSongsExpanded = false
        hasRevealedResultsForActiveQuery = false
        currentFilter = ResultFilter.ALL
        updateFilterButtons(b)
        moodAdapter?.setSelectedMood("all")
        clearResultAdapters()
        b.topResultCard.root.isVisible = false
        b.resultsRelatedArtistsSection.isVisible = false
        b.resultsTracksSection.isVisible = false
        b.resultsFilterChipsBar.isVisible = false
        b.resultsSongsActionBar.isVisible = false
        b.discoverCategoryHeader.isVisible = false
        clearSearchInputSilently(b)
        discoverModel.clearSearch()
        stopSkeletonPulse()
        b.discoverResultsSwipeRefresh.isRefreshing = false
        b.discoverHomeSwipeRefresh.isRefreshing = false
        b.discoverLoadingLayout.isVisible = false
        b.discoverResultsSwipeRefresh.isVisible = false
        hideEmptyAndOfflineState(b)
        b.discoverHomeSwipeRefresh.isVisible = true
        searchBackPressedCallback?.isEnabled = false
    }

    private fun startSearchLoading() {
        val b = binding ?: return
        isAllTracksExpanded = false
        isArtistsExpanded = false
        isSongsExpanded = false
        hasRevealedResultsForActiveQuery = false
        currentFilter = ResultFilter.ALL
        updateFilterButtons(b)
        searchBackPressedCallback?.isEnabled = true

        clearResultAdapters()
        b.topResultCard.root.isVisible = false
        b.resultsRelatedArtistsSection.isVisible = false
        b.resultsTracksSection.isVisible = false
        b.resultsFilterChipsBar.isVisible = false
        b.resultsSongsActionBar.isVisible = false

        b.discoverHomeSwipeRefresh.isVisible = false
        b.discoverResultsSwipeRefresh.isVisible = false
        b.discoverResultsSwipeRefresh.isRefreshing = false
        hideEmptyAndOfflineState(b)
        b.discoverLoadingLayout.isVisible = true
        b.discoverResultsScroll.scrollTo(0, 0)
        startSkeletonPulse()
    }

    private fun setupFilterChips(binding: FragmentDiscoverBinding) {
        binding.filterChipAll.setOnClickListener { setFilter(ResultFilter.ALL) }
        binding.filterChipArtists.setOnClickListener { setFilter(ResultFilter.ARTISTS) }
        binding.filterChipSongs.setOnClickListener { setFilter(ResultFilter.SONGS) }
        updateFilterButtons(binding)
    }

    private fun setFilter(filter: ResultFilter) {
        if (currentFilter == filter) return
        currentFilter = filter
        val b = binding ?: return
        updateFilterButtons(b)
        applyTabVisibility(b, discoverModel.categorizedResults.value)
    }

    private fun updateFilterButtons(binding: FragmentDiscoverBinding) {
        val ctx = binding.root.context
        val activeBg = ctx.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary)
        val activeText = ctx.getAttrColorCompat(com.google.android.material.R.attr.colorOnPrimary)
        val inactiveBg = ctx.getAttrColorCompat(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val inactiveText = ctx.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurfaceVariant)

        binding.filterChipAll.backgroundTintList = if (currentFilter == ResultFilter.ALL) activeBg else inactiveBg
        binding.filterChipAll.setTextColor(if (currentFilter == ResultFilter.ALL) activeText else inactiveText)

        binding.filterChipArtists.backgroundTintList = if (currentFilter == ResultFilter.ARTISTS) activeBg else inactiveBg
        binding.filterChipArtists.setTextColor(if (currentFilter == ResultFilter.ARTISTS) activeText else inactiveText)

        binding.filterChipSongs.backgroundTintList = if (currentFilter == ResultFilter.SONGS) activeBg else inactiveBg
        binding.filterChipSongs.setTextColor(if (currentFilter == ResultFilter.SONGS) activeText else inactiveText)
    }

    private fun populateAllTabs(binding: FragmentDiscoverBinding, result: OnlineSearchResult) {
        val validArtists = result.artists.filter { !it.avatarUrl.isNullOrBlank() }

        // 1. Tab ALL
        updateAllTabTopResult(binding, result, validArtists.firstOrNull())
        relatedArtistsAdapter?.submitList(validArtists)
        binding.resultsRelatedArtistsSection.isVisible = validArtists.isNotEmpty()
        updateAllTracksSlice(binding, result.tracks)

        // 2. Tab ARTISTS (Hero + 3-col recycling grid)
        artistsHeroAdapter?.artist = validArtists.firstOrNull()
        updateArtistsTabSlice(result)

        // 3. Tab SONGS (Action header + 2-col Top Picks + Compact list)
        binding.resultsSongsCountHeader.setText(R.string.header_songs)
        val topPicks = result.tracks.take(songsTopPicksCap)
        topPicksHeaderAdapter?.visible = topPicks.isNotEmpty()
        topPickAdapter?.submitList(topPicks)
        updateSongsTabMoreSlice(result.tracks)
    }

    private fun applyTabVisibility(binding: FragmentDiscoverBinding, result: OnlineSearchResult) {
        val hasTracks = result.tracks.isNotEmpty()
        binding.discoverResultsScroll.isVisible = currentFilter == ResultFilter.ALL
        binding.discoverArtistsTabRecycler.isVisible = currentFilter == ResultFilter.ARTISTS
        binding.discoverSongsTabRecycler.isVisible = currentFilter == ResultFilter.SONGS
        binding.resultsSongsActionBar.isVisible = currentFilter == ResultFilter.SONGS && hasTracks
    }

    private fun updateAllTabTopResult(
        binding: FragmentDiscoverBinding,
        result: OnlineSearchResult,
        topArtist: OnlineArtistSummary?,
    ) {
        val cardBinding = binding.topResultCard
        if (topArtist != null) {
            cardBinding.root.isVisible = true
            cardBinding.topResultTitle.text = topArtist.name
            cardBinding.topResultSubtitle.text = topArtist.subtitle ?: getString(R.string.header_artists)
            cardBinding.topResultAvatar.load(topArtist.avatarUrl) {
                crossfade(true)
            }
            if (!topArtist.avatarUrl.isNullOrBlank()) {
                cardBinding.topResultAmbientGlow.bindData(topArtist.avatarUrl, topArtist.avatarUrl)
            } else {
                cardBinding.topResultAmbientGlow.clearGlow()
            }
            cardBinding.root.setOnClickListener { onArtistSelected(topArtist) }
            cardBinding.topResultAvatar.setOnClickListener { onArtistSelected(topArtist) }
            cardBinding.topResultTitle.setOnClickListener { onArtistSelected(topArtist) }
            cardBinding.topResultExploreBtn.setOnClickListener { onArtistSelected(topArtist) }
            cardBinding.topResultPlayBtn.setOnClickListener {
                val matchingTrack = result.tracks.firstOrNull { it.artist.contains(topArtist.name, ignoreCase = true) }
                    ?: result.tracks.firstOrNull()
                if (matchingTrack != null) {
                    discoverModel.playTrack(matchingTrack, playbackModel)
                } else {
                    onArtistSelected(topArtist)
                }
            }
        } else {
            cardBinding.topResultAmbientGlow.clearGlow()
            cardBinding.root.isVisible = false
        }
    }

    private fun updateAllTracksSlice(binding: FragmentDiscoverBinding, tracks: List<OnlineTrack>) {
        if (tracks.isEmpty()) {
            binding.resultsTracksSection.isVisible = false
            binding.resultsTracksToggleBtn.isVisible = false
            allTrackAdapter?.submitList(emptyList())
            return
        }
        binding.resultsTracksSection.isVisible = true
        val hasOverflow = tracks.size > collapsedTracksCapAll
        binding.resultsTracksToggleBtn.isVisible = hasOverflow
        val listToSubmit = if (isAllTracksExpanded) tracks else tracks.take(collapsedTracksCapAll)
        val buttonTextRes = if (isAllTracksExpanded) R.string.btn_show_less_tracks else R.string.btn_show_more_tracks
        binding.resultsTracksToggleBtn.setText(buttonTextRes)
        allTrackAdapter?.submitList(listToSubmit)
    }

    private fun currentArtistsForGrid(result: OnlineSearchResult): List<OnlineArtistSummary> {
        val validArtists = result.artists.filter { !it.avatarUrl.isNullOrBlank() }
        return if (validArtists.size > 1) validArtists.drop(1) else emptyList()
    }

    private fun updateArtistsTabSlice(result: OnlineSearchResult) {
        val gridArtists = currentArtistsForGrid(result)
        if (gridArtists.isEmpty()) {
            artistsHeaderAdapter?.visible = false
            artistsToggleFooterAdapter?.textRes = null
            artistAdapter?.submitList(emptyList())
            return
        }
        artistsHeaderAdapter?.visible = true
        val hasOverflow = gridArtists.size > artistGridInitialCap
        val listToSubmit = if (isArtistsExpanded) gridArtists else gridArtists.take(artistGridInitialCap)
        artistsToggleFooterAdapter?.textRes = if (!hasOverflow) {
            null
        } else if (isArtistsExpanded) {
            R.string.btn_show_less_artists
        } else {
            R.string.btn_show_more_artists
        }
        artistAdapter?.submitList(listToSubmit)
    }

    private fun updateSongsTabMoreSlice(allTracks: List<OnlineTrack>) {
        val moreTracks = allTracks.drop(songsTopPicksCap)
        if (moreTracks.isEmpty()) {
            moreSongsHeaderAdapter?.visible = false
            songsToggleFooterAdapter?.textRes = null
            compactTrackAdapter?.submitList(emptyList())
            return
        }
        moreSongsHeaderAdapter?.visible = true
        val hasOverflow = moreTracks.size > songsMoreInitialCap
        val listToSubmit = if (isSongsExpanded) moreTracks else moreTracks.take(songsMoreInitialCap)
        songsToggleFooterAdapter?.textRes = if (!hasOverflow) {
            null
        } else if (isSongsExpanded) {
            R.string.btn_show_less_tracks
        } else {
            R.string.btn_show_more_tracks
        }
        compactTrackAdapter?.submitList(listToSubmit)
    }

    private fun prefetchUpcomingArtwork(result: OnlineSearchResult) {
        val ctx = context ?: return
        val urls = buildList {
            result.artists.drop(1).drop(artistGridInitialCap).take(18).mapNotNullTo(this) { it.avatarUrl }
            result.tracks.drop(songsTopPicksCap + songsMoreInitialCap).take(16).mapNotNullTo(this) { it.artworkUrl }
        }
        for (url in urls) {
            if (url.isNotBlank()) {
                imageLoader.enqueue(ImageRequest.Builder(ctx).data(url).build())
            }
        }
    }

    private fun onArtistSelected(artist: OnlineArtistSummary) {
        discoverModel.recordRecentArtist(artist)
        findNavController().navigateSafe(
            DiscoverFragmentDirections.showOnlineArtist(artist.id, artist.name)
        )
    }

    private fun updateSearchDisplayState() {
        val b = binding ?: return
        val hasActiveQuery = discoverModel.activeQueryState.value !is DiscoverQueryState.Idle
        val result = discoverModel.categorizedResults.value
        val isEmpty = result.artists.isEmpty() && result.tracks.isEmpty()
        val isSearching = discoverModel.isSearching.value

        searchBackPressedCallback?.isEnabled = hasActiveQuery

        if (hasActiveQuery) {
            b.discoverHomeSwipeRefresh.isVisible = false
            if (isSearching) {
                if (!b.discoverResultsSwipeRefresh.isRefreshing) {
                    b.discoverLoadingLayout.isVisible = true
                    b.discoverResultsSwipeRefresh.isVisible = false
                    b.resultsFilterChipsBar.isVisible = false
                    b.resultsSongsActionBar.isVisible = false
                    hideEmptyAndOfflineState(b)
                    startSkeletonPulse()
                }
            } else if (!isEmpty) {
                if (!b.discoverLoadingLayout.isVisible) {
                    stopSkeletonPulse()
                    b.resultsFilterChipsBar.isVisible = true
                    applyTabVisibility(b, result)
                    b.discoverResultsSwipeRefresh.isVisible = true
                    hideEmptyAndOfflineState(b)
                }
            } else {
                stopSkeletonPulse()
                b.discoverLoadingLayout.isVisible = false
                b.discoverResultsSwipeRefresh.isVisible = false
                b.resultsFilterChipsBar.isVisible = false
                b.resultsSongsActionBar.isVisible = false
                showEmptyOrOfflineState(b)
            }
        } else {
            stopSkeletonPulse()
            b.discoverLoadingLayout.isVisible = false
            b.discoverResultsSwipeRefresh.isVisible = false
            b.discoverResultsSwipeRefresh.isRefreshing = false
            b.resultsFilterChipsBar.isVisible = false
            b.resultsSongsActionBar.isVisible = false
            b.discoverCategoryHeader.isVisible = false
            hideEmptyAndOfflineState(b)
            b.discoverHomeSwipeRefresh.isVisible = true
        }
        b.discoverProgress.isVisible = false
    }

    private fun showEmptyOrOfflineState(b: FragmentDiscoverBinding) {
        val isConnected = NetworkUtil.isConnected(requireContext())
        if (!isConnected) {
            b.discoverEmptyState.isVisible = false
            b.discoverOfflineLayout.root.isVisible = true
            OfflineStateHelper.applyProposal(
                binding = b.discoverOfflineLayout,
                proposal = OfflineStateHelper.getNextProposal(requireContext())
            )
        } else {
            b.discoverOfflineLayout.root.isVisible = false
            b.discoverEmptyState.isVisible = true
        }
    }

    private fun hideEmptyAndOfflineState(b: FragmentDiscoverBinding) {
        b.discoverEmptyState.isVisible = false
        b.discoverOfflineLayout.root.isVisible = false
    }

    private fun startSkeletonPulse() {
        val b = binding ?: return
        val target = b.discoverLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.discoverLoadingLayout
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

    private fun updateDiscoverTrendingCharts(allTracks: List<OnlineTrack>, isExpanded: Boolean) {
        val b = binding ?: return
        b.discoverTrendingSection.isVisible = allTracks.isNotEmpty()
        val tracksToShow = if (isExpanded) allTracks.take(20) else allTracks.take(5)
        b.discoverTrendingToggleContainer.isVisible = allTracks.size > 5
        if (isExpanded) {
            b.discoverTrendingToggleBtn.setText(R.string.home_show_top_5)
        } else {
            b.discoverTrendingToggleBtn.setText(R.string.home_show_top_20)
        }

        val targetRotation = if (isExpanded) 180f else 0f
        b.discoverTrendingToggleChevron.animate()
            .rotation(targetRotation)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        nationalTrendingAdapter?.submitList(tracksToShow)
    }

    private fun stopSkeletonPulse() {
        skeletonAnimator?.cancel()
        val b = binding ?: return
        val target = b.discoverLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.discoverLoadingLayout
        target.alpha = 1.0f
    }

    private fun loadActiveGenres(): List<GenreItem> {
        val showChristian = onlineSettings.showChristianGenre
        val showKpop = onlineSettings.showKpopGenre
        lastGenreVisibility = showChristian to showKpop
        return GenreAdapter.filterGenres(showChristian = showChristian, showKpop = showKpop)
    }

    override fun onResume() {
        super.onResume()
        if (binding != null) {
            val currentVisibility = onlineSettings.showChristianGenre to onlineSettings.showKpopGenre
            if (currentVisibility != lastGenreVisibility) {
                lastGenreVisibility = currentVisibility
                genreAdapter?.updateGenres(
                    GenreAdapter.filterGenres(
                        showChristian = currentVisibility.first,
                        showKpop = currentVisibility.second
                    )
                )
            }
        }
    }

    override fun onDestroyBinding(binding: FragmentDiscoverBinding) {
        super.onDestroyBinding(binding)
        lastGenreVisibility = null
        lastDownloadStatuses = null
        searchBackPressedCallback?.remove()
        searchBackPressedCallback = null
        allTouchHelper?.attachToRecyclerView(null)
        allTouchHelper = null
        songsTabTouchHelper?.attachToRecyclerView(null)
        songsTabTouchHelper = null
        discoverTrendingTouchHelper?.attachToRecyclerView(null)
        discoverTrendingTouchHelper = null
        binding.discoverArtistsTabRecycler.adapter = null
        binding.discoverSongsTabRecycler.adapter = null
        binding.discoverRecycler.adapter = null
        binding.resultsRelatedArtistsRecycler.adapter = null
        binding.recentArtistsRecycler.adapter = null
        binding.discoverMoodsRecycler.adapter = null
        binding.genresRecycler.adapter = null
        binding.discoverTrendingRecycler.adapter = null
        allTrackAdapter = null
        nationalTrendingAdapter = null
        relatedArtistsAdapter = null
        artistsHeroAdapter = null
        artistsHeaderAdapter = null
        artistAdapter = null
        artistsToggleFooterAdapter = null
        artistsConcatAdapter = null
        topPicksHeaderAdapter = null
        topPickAdapter = null
        moreSongsHeaderAdapter = null
        compactTrackAdapter = null
        songsToggleFooterAdapter = null
        songsConcatAdapter = null
        recentAdapter = null
        moodAdapter = null
        genreAdapter = null
        stopSkeletonPulse()
        skeletonAnimator = null
    }
}
