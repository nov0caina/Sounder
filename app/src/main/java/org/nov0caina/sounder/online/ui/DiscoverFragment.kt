package org.nov0caina.sounder.online.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import android.net.Uri
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.nov0caina.sounder.InnerDirections
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentDiscoverBinding
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.PlaylistDecision
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

    private val discoverModel: DiscoverViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()

    private var trackAdapter: OnlineTrackAdapter? = null
    private var artistAdapter: OnlineArtistAdapter? = null
    private var recentAdapter: RecentArtistsAdapter? = null
    private var relatedArtistsAdapter: RecentArtistsAdapter? = null
    private var moodAdapter: HomeMoodAdapter? = null
    private var genreAdapter: GenreAdapter? = null

    private var currentFilter = ResultFilter.ALL
    private var isArtistsExpanded = false
    private var isTracksExpanded = false
    private val collapsedArtistsCap = 3
    private val collapsedTracksCap = 10
    private var discoverTouchHelper: ItemTouchHelper? = null
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
                val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(binding.discoverSearchInput.windowToken, 0)
                isArtistsExpanded = false
                isTracksExpanded = false
                currentFilter = ResultFilter.ALL
                updateFilterButtons(binding)
                artistAdapter?.submitList(emptyList())
                trackAdapter?.submitList(emptyList())
                relatedArtistsAdapter?.submitList(emptyList())
                binding.topResultCard.isVisible = false
                binding.resultsRelatedArtistsSection.isVisible = false
                binding.resultsRelatedArtistsRecycler.adapter = null
                binding.resultsArtistsRecycler.adapter = null
                binding.discoverRecycler.adapter = null
                binding.resultsArtistsSection.isVisible = false
                binding.resultsTracksSection.isVisible = false
                binding.discoverSearchInput.text?.clear()
                binding.discoverSearchInput.clearFocus()
                discoverModel.clearSearch()
                stopSkeletonPulse()
                binding.discoverLoadingLayout.isVisible = false
                binding.discoverResultsScroll.isVisible = false
                hideEmptyAndOfflineState(binding)
                binding.discoverHomeScroll.isVisible = true
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackPressedCallback!!)

        binding.discoverNormalToolbar.setNavigationOnClickListener {
            if (binding.discoverSearchInput.text?.isNotEmpty() == true) {
                searchBackPressedCallback?.handleOnBackPressed()
            } else {
                binding.discoverSearchInput.requestFocus()
            }
        }

        binding.discoverNormalToolbar.inflateMenu(R.menu.toolbar_discover)
        binding.discoverNormalToolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_downloads) {
                findNavController().navigateSafe(DiscoverFragmentDirections.downloads())
                true
            } else {
                false
            }
        }

        // Setup Mood Chips in Discover Home
        val quickMoods = listOf(
            HomeMoodItem("all", R.string.home_mood_all, "all"),
            HomeMoodItem("workout", R.string.home_mood_workout, "workout"),
            HomeMoodItem("focus", R.string.home_mood_focus, "focus"),
            HomeMoodItem("party", R.string.home_mood_party, "party"),
            HomeMoodItem("chill", R.string.home_mood_chill, "chill")
        )
        moodAdapter = HomeMoodAdapter(
            moods = quickMoods,
            selectedMoodId = "",
            onMoodSelected = { moodItem ->
                val query = if (moodItem.id == "all") "" else getString(moodItem.nameRes)
                if (query.isNotEmpty()) {
                    binding.discoverSearchInput.setText(query)
                    binding.discoverSearchInput.clearFocus()
                    startSearchLoading()
                    discoverModel.search(query)
                }
            }
        )
        binding.discoverMoodsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = moodAdapter
        }

        // Setup Filter Chips Bar
        setupFilterChips(binding)

        // Setup Recent Artists horizontal adapter
        recentAdapter = RecentArtistsAdapter { artist ->
            onArtistSelected(artist)
        }
        binding.recentArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
        }

        // Setup Related Artists horizontal adapter for search results
        relatedArtistsAdapter = RecentArtistsAdapter { artist ->
            onArtistSelected(artist)
        }
        binding.resultsRelatedArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedArtistsAdapter
        }

        // Setup Genre Adapter
        genreAdapter = GenreAdapter { genre ->
            binding.discoverSearchInput.setText(genre.name)
            binding.discoverSearchInput.clearFocus()
            startSearchLoading()
            discoverModel.selectGenre(genre)
        }
        binding.genresRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = genreAdapter
        }

        // Setup Matching Artists adapter for search results
        artistAdapter = OnlineArtistAdapter { artist ->
            onArtistSelected(artist)
        }
        binding.resultsArtistsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = artistAdapter
            itemAnimator = null
        }

        binding.resultsArtistsToggleBtn.setOnClickListener {
            isArtistsExpanded = !isArtistsExpanded
            updateArtistsList(discoverModel.categorizedResults.value.artists)
        }

        // Setup Matching Tracks adapter for search results with Billboard rank numbers
        trackAdapter = OnlineTrackAdapter(
            onPlayClick = { track ->
                discoverModel.playTrack(track, playbackModel)
            },
            onDownloadClick = { track ->
                if (!org.nov0caina.sounder.util.StoragePermissionHelper.hasPermission(requireContext())) {
                    Snackbar.make(binding.root, R.string.msg_storage_permission_required, Snackbar.LENGTH_LONG).show()
                    return@OnlineTrackAdapter
                }
                discoverModel.downloadTrack(track)
                Snackbar.make(
                    binding.root,
                    getString(R.string.download_queued, track.title),
                    Snackbar.LENGTH_SHORT
                ).show()
                trackAdapter?.notifyDataSetChanged()
            },
            isDownloaded = { track -> discoverModel.isDownloaded(track) },
            isDownloading = { track -> discoverModel.isEnqueuedOrDownloading(track) },
            isCurrentlyLoading = { trackId -> discoverModel.loadingTrackId.value == trackId },
            showRanking = true,
            onTrackLongClick = { track ->
                openSongMenu(track)
            }
        )
        binding.discoverRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trackAdapter
            itemAnimator = null
        }

        discoverTouchHelper = ItemTouchHelper(
            SwipeToQueueHelper(
                canSwipe = { true },
                onEnqueue = { viewHolder ->
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        val track = trackAdapter?.currentList?.getOrNull(pos)
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
            isTracksExpanded = !isTracksExpanded
            updateTracksList(discoverModel.categorizedResults.value.tracks)
        }

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
            val hasQuery = !text.isNullOrBlank()
            searchBackPressedCallback?.isEnabled = hasQuery
            if (!hasQuery) {
                isArtistsExpanded = false
                isTracksExpanded = false
                artistAdapter?.submitList(emptyList())
                trackAdapter?.submitList(emptyList())
                binding.resultsArtistsRecycler.adapter = null
                binding.discoverRecycler.adapter = null
                binding.resultsArtistsSection.isVisible = false
                binding.resultsTracksSection.isVisible = false
                discoverModel.clearSearch()
                updateSearchDisplayState()
            }
        }

        OfflineStateHelper.setup(
            binding = binding.discoverOfflineLayout,
            onRetry = {
                val query = binding.discoverSearchInput.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    startSearchLoading()
                    discoverModel.search(query)
                }
            }
        )

        // Collect Recent Artists
        collect(discoverModel.recentArtists) { recents ->
            recentAdapter?.submitList(recents)
            binding.recentArtistsSection.isVisible = recents.isNotEmpty()
        }

        // Collect Categorized Search Results
        collect(discoverModel.categorizedResults) { result ->
            val b = binding ?: return@collect
            val hasQuery = !b.discoverSearchInput.text.isNullOrBlank()
            if (!hasQuery) {
                updateSearchDisplayState()
                return@collect
            }

            if (result.artists.isEmpty() && result.tracks.isEmpty()) {
                if (discoverModel.isSearching.value) {
                    b.discoverHomeScroll.isVisible = false
                    b.discoverResultsScroll.isVisible = false
                    hideEmptyAndOfflineState(b)
                    b.discoverLoadingLayout.isVisible = true
                    startSkeletonPulse()
                } else {
                    b.resultsArtistsRecycler.adapter = null
                    b.discoverRecycler.adapter = null
                    artistAdapter?.submitList(emptyList())
                    trackAdapter?.submitList(emptyList())
                    b.resultsArtistsSection.isVisible = false
                    b.resultsTracksSection.isVisible = false
                    stopSkeletonPulse()
                    b.discoverHomeScroll.isVisible = false
                    b.discoverLoadingLayout.isVisible = false
                    b.discoverResultsScroll.isVisible = false
                    showEmptyOrOfflineState(b)
                }
                return@collect
            }

            // Reattach adapters to clean recyclers
            if (b.resultsArtistsRecycler.adapter == null) {
                b.resultsArtistsRecycler.adapter = artistAdapter
            }
            if (b.discoverRecycler.adapter == null) {
                b.discoverRecycler.adapter = trackAdapter
            }
            if (b.resultsRelatedArtistsRecycler.adapter == null) {
                b.resultsRelatedArtistsRecycler.adapter = relatedArtistsAdapter
            }

            // Populate Related Artists shelf
            val validArtists = result.artists.filter { !it.avatarUrl.isNullOrBlank() }
            relatedArtistsAdapter?.submitList(validArtists)

            // Update content in adapters and sections based on current category filter
            applyCurrentFilter(b, result)

            // Make results scroll visible underneath the opaque skeleton
            b.discoverHomeScroll.isVisible = false
            hideEmptyAndOfflineState(b)
            b.discoverResultsScroll.isVisible = true
            b.discoverResultsScroll.scrollTo(0, 0)

            // Wait until layout pass is completely done before revealing
            b.discoverResultsScroll.post {
                val currentBinding = binding ?: return@post
                val queryPresent = !currentBinding.discoverSearchInput.text.isNullOrBlank()
                if (queryPresent) {
                    stopSkeletonPulse()
                    currentBinding.discoverLoadingLayout.isVisible = false
                    hideEmptyAndOfflineState(currentBinding)
                    currentBinding.discoverHomeScroll.isVisible = false
                    currentBinding.discoverResultsScroll.isVisible = true
                    searchBackPressedCallback?.isEnabled = true
                    val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(currentBinding.discoverSearchInput.windowToken, 0)
                    currentBinding.discoverSearchInput.clearFocus()
                }
            }
        }

        // Collect Searching Progress State
        collect(discoverModel.isSearching) { isSearching ->
            val b = binding ?: return@collect
            val hasQuery = !b.discoverSearchInput.text.isNullOrBlank()
            if (hasQuery) {
                if (isSearching) {
                    b.discoverHomeScroll.isVisible = false
                    b.discoverResultsScroll.isVisible = false
                    hideEmptyAndOfflineState(b)
                    b.discoverLoadingLayout.isVisible = true
                    startSkeletonPulse()
                } else {
                    val result = discoverModel.categorizedResults.value
                    if (result.artists.isEmpty() && result.tracks.isEmpty()) {
                        stopSkeletonPulse()
                        b.discoverLoadingLayout.isVisible = false
                        b.discoverResultsScroll.isVisible = false
                        showEmptyOrOfflineState(b)
                    }
                }
            }
        }

        collect(discoverModel.loadingTrackId) {
            trackAdapter?.notifyDataSetChanged()
        }

        collect(discoverModel.downloads) {
            trackAdapter?.notifyDataSetChanged()
        }

        collect(musicModel.indexingState) {
            trackAdapter?.notifyDataSetChanged()
        }

        collect(discoverModel.activeDownloadsCount) { count ->
            val menuItem = binding.discoverNormalToolbar.menu.findItem(R.id.action_downloads)
            if (menuItem != null) {
                menuItem.title = if (count > 0) {
                    "${getString(R.string.title_downloads)} ($count)"
                } else {
                    getString(R.string.title_downloads)
                }
            }
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

    private fun startSearchLoading() {
        val b = binding ?: return
        isArtistsExpanded = false
        isTracksExpanded = false
        currentFilter = ResultFilter.ALL
        updateFilterButtons(b)
        searchBackPressedCallback?.isEnabled = true

        artistAdapter?.submitList(emptyList())
        trackAdapter?.submitList(emptyList())
        relatedArtistsAdapter?.submitList(emptyList())
        b.topResultCard.isVisible = false
        b.resultsRelatedArtistsSection.isVisible = false
        b.resultsRelatedArtistsRecycler.adapter = null
        b.resultsArtistsRecycler.adapter = null
        b.discoverRecycler.adapter = null
        b.resultsArtistsSection.isVisible = false
        b.resultsTracksSection.isVisible = false

        b.discoverHomeScroll.isVisible = false
        b.discoverResultsScroll.isVisible = false
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
        applyCurrentFilter(b, discoverModel.categorizedResults.value)
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

    private fun applyCurrentFilter(binding: FragmentDiscoverBinding, result: OnlineSearchResult) {
        val validArtists = result.artists.filter { !it.avatarUrl.isNullOrBlank() }
        val hasArtists = validArtists.isNotEmpty()
        val hasTracks = result.tracks.isNotEmpty()

        when (currentFilter) {
            ResultFilter.ALL -> {
                updateTopResultCard(binding, result)
                binding.resultsRelatedArtistsSection.isVisible = hasArtists
                binding.resultsArtistsSection.isVisible = false
                binding.resultsTracksSection.isVisible = hasTracks
                updateTracksList(result.tracks)
            }
            ResultFilter.ARTISTS -> {
                binding.topResultCard.isVisible = false
                binding.resultsRelatedArtistsSection.isVisible = false
                binding.resultsArtistsSection.isVisible = hasArtists
                binding.resultsTracksSection.isVisible = false
                isArtistsExpanded = true
                updateArtistsList(validArtists)
            }
            ResultFilter.SONGS -> {
                binding.topResultCard.isVisible = false
                binding.resultsRelatedArtistsSection.isVisible = false
                binding.resultsArtistsSection.isVisible = false
                binding.resultsTracksSection.isVisible = hasTracks
                isTracksExpanded = true
                updateTracksList(result.tracks)
            }
        }
    }

    private fun updateTopResultCard(binding: FragmentDiscoverBinding, result: OnlineSearchResult) {
        val topArtist = result.artists.firstOrNull { !it.avatarUrl.isNullOrBlank() }
        if (topArtist != null && currentFilter == ResultFilter.ALL) {
            binding.topResultCard.isVisible = true
            binding.topResultTitle.text = topArtist.name
            binding.topResultSubtitle.text = topArtist.subtitle ?: getString(R.string.header_artists)
            binding.topResultAvatar.load(topArtist.avatarUrl) {
                crossfade(true)
            }
            binding.topResultCard.setOnClickListener {
                onArtistSelected(topArtist)
            }
            binding.topResultAvatar.setOnClickListener {
                onArtistSelected(topArtist)
            }
            binding.topResultTitle.setOnClickListener {
                onArtistSelected(topArtist)
            }
            binding.topResultPlayBtn.setOnClickListener {
                val matchingTrack = result.tracks.firstOrNull { it.artist.contains(topArtist.name, ignoreCase = true) }
                    ?: result.tracks.firstOrNull()
                if (matchingTrack != null) {
                    discoverModel.playTrack(matchingTrack, playbackModel)
                } else {
                    onArtistSelected(topArtist)
                }
            }
            binding.topResultExploreBtn.setOnClickListener {
                onArtistSelected(topArtist)
            }
        } else {
            binding.topResultCard.isVisible = false
        }
    }

    private fun updateArtistsList(allArtists: List<OnlineArtistSummary>) {
        val binding = binding ?: return
        val validArtists = allArtists.filter { !it.avatarUrl.isNullOrBlank() }
        if (validArtists.isEmpty()) {
            binding.resultsArtistsSection.isVisible = false
            binding.resultsArtistsToggleBtn.isVisible = false
            artistAdapter?.submitList(emptyList())
            return
        }
        binding.resultsArtistsSection.isVisible = true
        val hasOverflow = validArtists.size > collapsedArtistsCap
        binding.resultsArtistsToggleBtn.isVisible = hasOverflow
        val listToSubmit = if (isArtistsExpanded) validArtists else validArtists.take(collapsedArtistsCap)
        val buttonTextRes = if (isArtistsExpanded) R.string.btn_show_less_artists else R.string.btn_show_more_artists
        binding.resultsArtistsToggleBtn.setText(buttonTextRes)
        artistAdapter?.submitList(listToSubmit)
    }

    private fun updateTracksList(tracks: List<OnlineTrack>) {
        val binding = binding ?: return
        if (tracks.isEmpty()) {
            binding.resultsTracksSection.isVisible = false
            binding.resultsTracksToggleBtn.isVisible = false
            trackAdapter?.submitList(emptyList())
            return
        }
        binding.resultsTracksSection.isVisible = true
        val hasOverflow = tracks.size > collapsedTracksCap
        binding.resultsTracksToggleBtn.isVisible = hasOverflow
        val listToSubmit = if (isTracksExpanded) tracks else tracks.take(collapsedTracksCap)
        val buttonTextRes = if (isTracksExpanded) R.string.btn_show_less_tracks else R.string.btn_show_more_tracks
        binding.resultsTracksToggleBtn.setText(buttonTextRes)
        trackAdapter?.submitList(listToSubmit)
    }

    private fun onArtistSelected(artist: OnlineArtistSummary) {
        discoverModel.recordRecentArtist(artist)
        findNavController().navigateSafe(
            DiscoverFragmentDirections.showOnlineArtist(artist.id, artist.name)
        )
    }

    private fun updateSearchDisplayState() {
        val b = binding ?: return
        val hasQuery = !b.discoverSearchInput.text.isNullOrBlank()
        val result = discoverModel.categorizedResults.value
        val isEmpty = result.artists.isEmpty() && result.tracks.isEmpty()
        val isSearching = discoverModel.isSearching.value

        searchBackPressedCallback?.isEnabled = hasQuery

        if (hasQuery) {
            b.discoverHomeScroll.isVisible = false
            if (isSearching) {
                b.discoverLoadingLayout.isVisible = true
                b.discoverResultsScroll.isVisible = false
                hideEmptyAndOfflineState(b)
                startSkeletonPulse()
            } else if (!isEmpty) {
                // If skeleton is currently active and waiting for differ to commit, don't dismiss early
                if (!b.discoverLoadingLayout.isVisible) {
                    stopSkeletonPulse()
                    b.discoverResultsScroll.isVisible = true
                    hideEmptyAndOfflineState(b)
                }
            } else {
                stopSkeletonPulse()
                b.discoverLoadingLayout.isVisible = false
                b.discoverResultsScroll.isVisible = false
                showEmptyOrOfflineState(b)
            }
        } else {
            stopSkeletonPulse()
            b.discoverLoadingLayout.isVisible = false
            b.discoverResultsScroll.isVisible = false
            hideEmptyAndOfflineState(b)
            b.discoverHomeScroll.isVisible = true
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

    private fun stopSkeletonPulse() {
        skeletonAnimator?.cancel()
        val b = binding ?: return
        val target = b.discoverLoadingLayout.findViewById<View>(R.id.skeleton_root) ?: b.discoverLoadingLayout
        target.alpha = 1.0f
    }

    override fun onDestroyBinding(binding: FragmentDiscoverBinding) {
        super.onDestroyBinding(binding)
        searchBackPressedCallback?.remove()
        searchBackPressedCallback = null
        discoverTouchHelper?.attachToRecyclerView(null)
        discoverTouchHelper = null
        binding.resultsArtistsRecycler.adapter = null
        binding.discoverRecycler.adapter = null
        binding.resultsRelatedArtistsRecycler.adapter = null
        binding.recentArtistsRecycler.adapter = null
        binding.discoverMoodsRecycler.adapter = null
        binding.genresRecycler.adapter = null
        trackAdapter = null
        artistAdapter = null
        recentAdapter = null
        relatedArtistsAdapter = null
        moodAdapter = null
        genreAdapter = null
        stopSkeletonPulse()
        skeletonAnimator = null
    }
}
