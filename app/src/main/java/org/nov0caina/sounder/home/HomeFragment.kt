/*
 * Copyright (c) 2021 Auxio Project
 * HomeFragment.kt is part of Auxio.
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
 
package org.nov0caina.sounder.home

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.FragmentHomeBinding
import org.nov0caina.sounder.detail.DetailViewModel
import org.nov0caina.sounder.detail.Show
import org.nov0caina.sounder.home.list.AlbumListFragment
import org.nov0caina.sounder.home.list.ArtistListFragment
import org.nov0caina.sounder.home.list.GenreListFragment
import org.nov0caina.sounder.home.list.PlaylistListFragment
import org.nov0caina.sounder.home.list.RecentDownloadsListFragment
import org.nov0caina.sounder.home.list.SongListFragment
import org.nov0caina.sounder.home.tabs.Tab
import org.nov0caina.sounder.list.ListViewModel
import org.nov0caina.sounder.list.SelectionFragment
import org.nov0caina.sounder.list.menu.Menu
import org.nov0caina.sounder.music.IndexingState
import org.nov0caina.sounder.music.MusicType
import org.nov0caina.sounder.music.MusicViewModel
import org.nov0caina.sounder.music.PlaylistDecision
import org.nov0caina.sounder.music.PlaylistMessage
import org.nov0caina.sounder.playback.PlaybackDecision
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.ui.FadingToolbarOffsetListener
import org.nov0caina.sounder.util.collect
import org.nov0caina.sounder.util.collectImmediately
import org.nov0caina.sounder.util.dampen
import org.nov0caina.sounder.util.navigateSafe
import org.nov0caina.sounder.util.showToast
import org.oxycblt.musikr.IndexingProgress
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.playlist.m3u.M3U
import timber.log.Timber as L

/**
 * The starting [SelectionFragment] of Auxio. Shows the user's music library and enables navigation
 * to other views.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class HomeFragment : SelectionFragment<FragmentHomeBinding>() {
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    private var getContentLauncher: ActivityResultLauncher<String>? = null
    private var pendingImportTarget: Playlist? = null
    private var homePagerAdapter: HomePagerAdapter? = null
    private var isSettingUpPager: Boolean = false
    private var isDraggingTab: Boolean = false

    private val tabSelectedListener =
        object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!isSettingUpPager && !isDraggingTab) {
                    val binding = binding ?: return
                    if (binding.homePager.currentItem != tab.position) {
                        binding.homePager.setCurrentItem(tab.position, true)
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentHomeBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentHomeBinding) = binding.homeSelectionToolbar

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // Have to set up the permission launcher before the view is shown
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                musicModel.refresh()
            }

        getContentLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) {
                    L.w("No URI returned from file picker")
                    return@registerForActivityResult
                }

                L.d("Received playlist URI $uri")
                musicModel.importPlaylist(uri, pendingImportTarget)
            }

        // --- UI SETUP ---

        binding.homeAppbar.addOnOffsetChangedListener(
            FadingToolbarOffsetListener(binding.homeToolbar, binding.homeContent)
        )
        binding.homeNormalToolbar.apply {
            setOnMenuItemClickListener(this@HomeFragment)
            MenuCompat.setGroupDividerEnabled(menu, true)
        }

        binding.homePager.apply {
            // Update HomeViewModel whenever the user swipes through the ViewPager.
            // This would be implemented in HomeFragment itself, but OnPageChangeCallback
            // is an object for some reason.
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageScrolled(
                        position: Int,
                        positionOffset: Float,
                        positionOffsetPixels: Int,
                    ) {
                        if (!isSettingUpPager && !isDraggingTab) {
                            binding.homeTabs.setScrollPosition(position, positionOffset, true)
                        }
                    }

                    override fun onPageSelected(position: Int) {
                        if (!isSettingUpPager && !isDraggingTab) {
                            homeModel.synchronizeTabPosition(position)
                            val tab = binding.homeTabs.getTabAt(position)
                            if (tab != null && !tab.isSelected) {
                                binding.homeTabs.selectTab(tab)
                            }
                        }
                    }
                }
            )

            // ViewPager2 will nominally consume window insets, which will then break the window
            // insets applied to the indexing view before API 30. Fix this by overriding the
            // listener with a non-consuming listener.
            setOnApplyWindowInsetsListener { _, insets -> insets }

            // We know that there will only be a fixed amount of tabs, so we manually set this
            // limit to the maximum amount possible. This will prevent the tab ripple from
            // bugging out due to dynamically inflating each fragment, at the cost of slower
            // debug UI performance.
            offscreenPageLimit = Tab.MAX_SEQUENCE_IDX + 1

            dampen()
        }

        // Further initialization must be done in the function that also handles
        // re-creating the ViewPager.
        setupTabDragReordering(binding)
        setupPager(binding)

        // --- VIEWMODEL SETUP ---
        collect(homeModel.recreateTabs.flow, ::handleRecreate)
        collect(homeModel.chooseMusicLocations.flow, ::handleChooseFolders)
        collectImmediately(homeModel.currentTabType, ::updateCurrentTab)
        collect(listModel.menu.flow, ::handleMenu)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(musicModel.indexingState, ::updateIndexerState)
        collect(musicModel.playlistDecision.flow, ::handlePlaylistDecision)
        collectImmediately(musicModel.playlistMessage.flow, ::handlePlaylistMessage)
        collect(playbackModel.playbackDecision.flow, ::handlePlaybackDecision)
    }

    override fun onDestroyBinding(binding: FragmentHomeBinding) {
        super.onDestroyBinding(binding)
        binding.homeTabs.removeOnTabSelectedListener(tabSelectedListener)
        homePagerAdapter = null
        binding.homeLayout.setOnDragListener(null)
        storagePermissionLauncher = null
        binding.homeNormalToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) {
            return true
        }

        return when (item.itemId) {
            // Handle main actions (Search, Settings, About)
            R.id.action_search -> {
                L.d("Navigating to search")
                findNavController().navigateSafe(HomeFragmentDirections.search())
                true
            }
            R.id.action_settings -> {
                L.d("Navigating to preferences")
                homeModel.showSettings()
                true
            }
            R.id.action_about -> {
                L.d("Navigating to about")
                homeModel.showAbout()
                true
            }

            // Handle sort menu
            R.id.action_sort -> {
                // Junk click event when opening the menu
                val directions =
                    when (homeModel.currentTabType.value) {
                        MusicType.SONGS -> HomeFragmentDirections.sortSongs()
                        MusicType.ALBUMS -> HomeFragmentDirections.sortAlbums()
                        MusicType.ARTISTS -> HomeFragmentDirections.sortArtists()
                        MusicType.GENRES -> HomeFragmentDirections.sortGenres()
                        MusicType.PLAYLISTS -> HomeFragmentDirections.sortPlaylists()
                        MusicType.DOWNLOADS -> null
                    }
                if (directions != null) {
                    findNavController().navigateSafe(directions)
                }
                true
            }
            else -> {
                L.w("Unexpected menu item selected")
                false
            }
        }
    }

    private fun setupPager(binding: FragmentHomeBinding) {
        val activeTabType = homeModel.currentTabType.value
        val activeIndex = homeModel.currentTabTypes.indexOf(activeTabType).coerceAtLeast(0)

        isSettingUpPager = true
        binding.homeTabs.removeOnTabSelectedListener(tabSelectedListener)

        val adapter =
            HomePagerAdapter(
                homeModel.currentTabTypes.toMutableList(),
                childFragmentManager,
                viewLifecycleOwner,
            )
        homePagerAdapter = adapter
        binding.homePager.adapter = adapter
        binding.homePager.setCurrentItem(activeIndex, false)

        val toolbarParams = binding.homeToolbar.layoutParams as AppBarLayout.LayoutParams
        if (homeModel.currentTabTypes.size == 1) {
            // A single tab makes the tab layout redundant, hide it and disable the collapsing
            // behavior.
            L.d("Single tab shown, disabling TabLayout")
            binding.homeTabs.isVisible = false
            binding.homeAppbar.setExpanded(true, false)
            toolbarParams.scrollFlags = 0
        } else {
            binding.homeTabs.isVisible = true
            toolbarParams.scrollFlags =
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
        }

        binding.homeTabs.removeAllTabs()
        for (i in homeModel.currentTabTypes.indices) {
            val tab = binding.homeTabs.newTab().setText(homeModel.currentTabTypes[i].nameRes)
            binding.homeTabs.addTab(tab, i == activeIndex)
        }
        binding.homeTabs.addOnTabSelectedListener(tabSelectedListener)

        isSettingUpPager = false
        homeModel.synchronizeTabPosition(activeIndex)
        binding.homeTabs.post {
            binding.homeTabs.setScrollPosition(activeIndex, 0f, true)
        }

        attachTabLongClickListeners(binding, homeModel.currentTabTypes)
    }

    private fun attachTabLongClickListeners(
        binding: FragmentHomeBinding,
        tabOrder: List<MusicType>,
    ) {
        // Allow press & hold and drag horizontally on any library tab header to reorder it directly.
        for (i in 0 until binding.homeTabs.tabCount) {
            val tab = binding.homeTabs.getTabAt(i) ?: continue
            tab.tag = tabOrder.getOrNull(i)
            tab.view.setOnLongClickListener { view ->
                val tabType = tab.tag as? MusicType ?: return@setOnLongClickListener false
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val shadowBuilder = View.DragShadowBuilder(view)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    view.startDragAndDrop(null, shadowBuilder, tabType, 0)
                } else {
                    @Suppress("DEPRECATION")
                    view.startDrag(null, shadowBuilder, tabType, 0)
                }
                true
            }
        }
    }

    private fun setupTabDragReordering(binding: FragmentHomeBinding) {
        var draggedTabType: MusicType? = null
        var activeTabTypeBeforeDrag: MusicType? = null
        var workingOrder: MutableList<MusicType> = mutableListOf()
        var lastDragLocalX: Float = Float.NaN
        var autoScrollVelocityPx: Int = 0
        var isAutoScrollScheduled: Boolean = false

        fun evaluateDragSwap(localX: Float) {
            val tabType = draggedTabType ?: return
            val currentIndex = workingOrder.indexOf(tabType)
            if (currentIndex == -1) return

            val density = binding.homeTabs.resources.displayMetrics.density
            val snapEdgePx = 36f * density
            val contentX = localX + binding.homeTabs.scrollX

            var targetIndex = currentIndex
            if (localX <= snapEdgePx && binding.homeTabs.scrollX <= 0) {
                targetIndex = 0
            } else if (
                localX >= binding.homeTabs.width - snapEdgePx &&
                    !binding.homeTabs.canScrollHorizontally(1)
            ) {
                targetIndex = (binding.homeTabs.tabCount - 1).coerceAtLeast(0)
            } else {
                for (i in 0 until binding.homeTabs.tabCount) {
                    val tabView = binding.homeTabs.getTabAt(i)?.view ?: continue
                    val midpoint = (tabView.left + tabView.right) / 2f
                    if (i < currentIndex && contentX < midpoint) {
                        targetIndex = i
                        break
                    } else if (i > currentIndex && contentX > midpoint) {
                        targetIndex = i
                    }
                }
            }

            if (targetIndex != currentIndex) {
                val savedScrollX = binding.homeTabs.scrollX
                workingOrder.removeAt(currentIndex)
                workingOrder.add(targetIndex, tabType)
                homePagerAdapter?.moveTab(currentIndex, targetIndex)

                for (i in workingOrder.indices) {
                    val tab = binding.homeTabs.getTabAt(i) ?: continue
                    tab.tag = workingOrder[i]
                    tab.setText(workingOrder[i].nameRes)
                    tab.view.alpha = if (workingOrder[i] == tabType) 0.35f else 1.0f
                }

                // Keep the TabLayout visual underline on the section where the user was already located
                // without triggering TabLayout's scrollAnimator back toward activeIdx.
                val activeIdx = workingOrder.indexOf(activeTabTypeBeforeDrag)
                if (activeIdx > -1) {
                    binding.homeTabs.setScrollPosition(activeIdx, 0f, true)
                }
                binding.homeTabs.scrollX = savedScrollX
                binding.homeTabs.post {
                    val currentActiveIdx = workingOrder.indexOf(activeTabTypeBeforeDrag)
                    if (isDraggingTab && currentActiveIdx > -1) {
                        val currentScrollX = binding.homeTabs.scrollX
                        binding.homeTabs.setScrollPosition(currentActiveIdx, 0f, true)
                        binding.homeTabs.scrollX = currentScrollX
                    }
                }
                binding.homeTabs.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        val autoScrollRunnable =
            object : Runnable {
                override fun run() {
                    if (!isDraggingTab || autoScrollVelocityPx == 0) {
                        isAutoScrollScheduled = false
                        return
                    }
                    val canScroll =
                        if (autoScrollVelocityPx < 0) {
                            binding.homeTabs.scrollX > 0
                        } else {
                            binding.homeTabs.canScrollHorizontally(1)
                        }
                    if (canScroll) {
                        binding.homeTabs.scrollBy(autoScrollVelocityPx, 0)
                    }
                    if (!lastDragLocalX.isNaN()) {
                        evaluateDragSwap(lastDragLocalX)
                    }
                    if (canScroll) {
                        binding.homeTabs.postOnAnimation(this)
                    } else {
                        isAutoScrollScheduled = false
                    }
                }
            }

        binding.homeLayout.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    val tabType = event.localState as? MusicType ?: return@setOnDragListener false
                    isDraggingTab = true
                    draggedTabType = tabType
                    activeTabTypeBeforeDrag = homeModel.currentTabType.value
                    workingOrder = homeModel.currentTabTypes.toMutableList()
                    lastDragLocalX = Float.NaN
                    autoScrollVelocityPx = 0
                    isAutoScrollScheduled = false
                    for (i in 0 until binding.homeTabs.tabCount) {
                        binding.homeTabs.getTabAt(i)?.view?.alpha =
                            if (workingOrder.getOrNull(i) == tabType) 0.35f else 1.0f
                    }
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    if (draggedTabType == null) return@setOnDragListener true
                    val localX = event.x - binding.homeTabs.left
                    lastDragLocalX = localX

                    val density = binding.homeTabs.resources.displayMetrics.density
                    val edgeZonePx = 88f * density
                    val minScrollStepPx = 6f * density
                    val maxScrollStepPx = 22f * density

                    autoScrollVelocityPx =
                        when {
                            localX < edgeZonePx -> {
                                val fraction =
                                    ((edgeZonePx - localX) / edgeZonePx).coerceIn(0f, 1f)
                                -(minScrollStepPx + fraction * (maxScrollStepPx - minScrollStepPx))
                                    .toInt()
                            }
                            localX > binding.homeTabs.width - edgeZonePx -> {
                                val fraction =
                                    ((localX - (binding.homeTabs.width - edgeZonePx)) / edgeZonePx)
                                        .coerceIn(0f, 1f)
                                (minScrollStepPx + fraction * (maxScrollStepPx - minScrollStepPx))
                                    .toInt()
                            }
                            else -> 0
                        }

                    if (autoScrollVelocityPx != 0 && !isAutoScrollScheduled) {
                        isAutoScrollScheduled = true
                        binding.homeTabs.postOnAnimation(autoScrollRunnable)
                    }

                    evaluateDragSwap(localX)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    autoScrollVelocityPx = 0
                    isAutoScrollScheduled = false
                    lastDragLocalX = Float.NaN
                    binding.homeTabs.removeCallbacks(autoScrollRunnable)
                    draggedTabType = null

                    for (i in 0 until binding.homeTabs.tabCount) {
                        binding.homeTabs.getTabAt(i)?.view?.alpha = 1.0f
                    }

                    if (workingOrder.isNotEmpty() && workingOrder != homeModel.currentTabTypes) {
                        val savedScrollX = binding.homeTabs.scrollX
                        val activeIdx =
                            workingOrder.indexOf(activeTabTypeBeforeDrag).coerceAtLeast(0)
                        isSettingUpPager = true
                        if (binding.homePager.currentItem != activeIdx) {
                            binding.homePager.setCurrentItem(activeIdx, false)
                        }
                        binding.homeTabs.getTabAt(activeIdx)?.select()
                        binding.homeTabs.setScrollPosition(activeIdx, 0f, true)
                        binding.homeTabs.scrollX = savedScrollX
                        isSettingUpPager = false

                        homeModel.reorderVisibleTabs(workingOrder.toList())
                        homeModel.synchronizeTabPosition(activeIdx)
                    }

                    isDraggingTab = false
                    activeTabTypeBeforeDrag = null
                    true
                }
                else -> true
            }
        }
    }

    private fun updateCurrentTab(tabType: MusicType) {
        val binding = requireBinding()

        binding.homeNormalToolbar.menu.findItem(R.id.action_sort)?.isVisible =
            tabType != MusicType.DOWNLOADS

        // Update the scrolling view in AppBarLayout to align with the current tab's
        // scrolling state. This prevents the lift state from being confused as one
        // goes between different tabs.
        binding.homeAppbar.liftOnScrollTargetViewId =
            when (tabType) {
                MusicType.SONGS -> R.id.home_song_recycler
                MusicType.ALBUMS -> R.id.home_album_recycler
                MusicType.ARTISTS -> R.id.home_artist_recycler
                MusicType.GENRES -> R.id.home_genre_recycler
                MusicType.PLAYLISTS -> R.id.home_playlist_recycler
                MusicType.DOWNLOADS -> R.id.home_downloads_recycler
            }
    }

    private fun handleRecreate(recreate: Unit?) {
        if (recreate == null) return
        val binding = requireBinding()
        L.d("Recreating ViewPager")
        // Make sure tabs are set up to also follow the new ViewPager configuration
        // while keeping the user located on their current active section.
        setupPager(binding)
        homeModel.recreateTabs.consume()
    }

    private fun handleChooseFolders(unit: Unit?) {
        if (unit == null) {
            return
        }
        findNavController().navigateSafe(HomeFragmentDirections.chooseLocations())
        homeModel.chooseMusicLocations.consume()
    }

    private fun updateIndexerState(state: IndexingState?) {
        val binding = requireBinding()
        when (state) {
            is IndexingState.Completed -> {
                binding.homeIndexingContainer.isInvisible = state.error == null
                binding.homeIndexingProgress.isInvisible = state.error != null
                binding.homeIndexingError.isInvisible = state.error == null
                if (state.error != null) {
                    binding.homeIndexingContainer.setOnClickListener {
                        findNavController()
                            .navigateSafe(HomeFragmentDirections.reportError(state.error))
                    }
                } else {
                    binding.homeIndexingContainer.setOnClickListener(null)
                }
            }
            is IndexingState.Indexing -> {
                binding.homeIndexingContainer.isInvisible = false
                binding.homeIndexingProgress.apply {
                    isInvisible = false
                    when (state.progress) {
                        is IndexingProgress.Songs -> {
                            isIndeterminate = false
                            progress = state.progress.loaded
                            max = state.progress.explored
                        }
                        is IndexingProgress.Indeterminate -> {
                            isIndeterminate = true
                        }
                    }
                }
                binding.homeIndexingError.isInvisible = true
            }
            null -> {
                binding.homeIndexingContainer.isInvisible = true
            }
        }
    }

    private fun handlePlaylistDecision(decision: PlaylistDecision?) {
        if (decision == null) return
        val directions =
            when (decision) {
                is PlaylistDecision.New -> {
                    L.d("Creating new playlist")
                    HomeFragmentDirections.newPlaylist(
                        decision.songs.map { it.uid }.toTypedArray(),
                        decision.template,
                        decision.reason,
                    )
                }
                is PlaylistDecision.Import -> {
                    L.d("Importing playlist")
                    pendingImportTarget = decision.target
                    requireNotNull(getContentLauncher) {
                            "Content picker launcher was not available"
                        }
                        .launch(M3U.MIME_TYPE)
                    musicModel.playlistDecision.consume()
                    return
                }
                is PlaylistDecision.Rename -> {
                    L.d("Renaming ${decision.playlist}")
                    HomeFragmentDirections.renamePlaylist(
                        decision.playlist.uid,
                        decision.template,
                        decision.applySongs.map { it.uid }.toTypedArray(),
                        decision.reason,
                    )
                }
                is PlaylistDecision.Export -> {
                    L.d("Exporting ${decision.playlist}")
                    HomeFragmentDirections.exportPlaylist(decision.playlist.uid)
                }
                is PlaylistDecision.Delete -> {
                    L.d("Deleting ${decision.playlist}")
                    HomeFragmentDirections.deletePlaylist(decision.playlist.uid)
                }
                is PlaylistDecision.Add -> {
                    L.d("Adding ${decision.songs.size} to a playlist")
                    HomeFragmentDirections.addToPlaylist(
                        decision.songs.map { it.uid }.toTypedArray()
                    )
                }
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistMessage(message: PlaylistMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.playlistMessage.consume()
    }

    private fun handlePlaybackDecision(decision: PlaybackDecision?) {
        when (decision) {
            is PlaybackDecision.PlayFromArtist -> {
                findNavController()
                    .navigateSafe(HomeFragmentDirections.playFromArtist(decision.song.uid))
            }
            is PlaybackDecision.PlayFromGenre -> {
                findNavController()
                    .navigateSafe(HomeFragmentDirections.playFromGenre(decision.song.uid))
            }
            null -> {}
        }
    }

    private fun handleMenu(menu: Menu?) {
        if (menu == null) return
        val directions =
            when (menu) {
                is Menu.ForSong -> HomeFragmentDirections.openSongMenu(menu.parcel)
                is Menu.ForAlbum -> HomeFragmentDirections.openAlbumMenu(menu.parcel)
                is Menu.ForArtist -> HomeFragmentDirections.openArtistMenu(menu.parcel)
                is Menu.ForGenre -> HomeFragmentDirections.openGenreMenu(menu.parcel)
                is Menu.ForPlaylist -> HomeFragmentDirections.openPlaylistMenu(menu.parcel)
                is Menu.ForSelection -> HomeFragmentDirections.openSelectionMenu(menu.parcel)
            }
        findNavController().navigateSafe(directions)
    }

    private fun updateSelection(selected: List<Music>) {
        val binding = requireBinding()
        if (selected.isNotEmpty()) {
            binding.homeSelectionToolbar.title = getString(R.string.fmt_selected, selected.size)
            if (binding.homeToolbar.setVisible(R.id.home_selection_toolbar)) {
                // New selection started, show the AppBarLayout to indicate the new state.
                L.d("Significant selection occurred, expanding AppBar")
                binding.homeAppbar.expandWithScrollingRecycler()
            }
        } else {
            binding.homeToolbar.setVisible(R.id.home_normal_toolbar)
        }
    }

    /**
     * [FragmentStateAdapter] implementation for the [HomeFragment]'s [ViewPager2] instance.
     *
     * @param tabs The current tab configuration. This will define the [Fragment]s created.
     * @param fragmentManager The [FragmentManager] required by [FragmentStateAdapter].
     * @param lifecycleOwner The [LifecycleOwner], whose Lifecycle is required by
     *   [FragmentStateAdapter].
     */
    private class HomePagerAdapter(
        private val tabs: MutableList<MusicType>,
        fragmentManager: FragmentManager,
        lifecycleOwner: LifecycleOwner,
    ) : FragmentStateAdapter(fragmentManager, lifecycleOwner.lifecycle) {
        override fun getItemCount() = tabs.size

        override fun getItemId(position: Int): Long = tabs[position].intCode.toLong()

        override fun containsItem(itemId: Long): Boolean =
            tabs.any { it.intCode.toLong() == itemId }

        fun moveTab(fromIndex: Int, toIndex: Int) {
            if (fromIndex == toIndex || fromIndex !in tabs.indices || toIndex !in tabs.indices) {
                return
            }
            val moved = tabs.removeAt(fromIndex)
            tabs.add(toIndex, moved)
            notifyItemMoved(fromIndex, toIndex)
        }

        override fun createFragment(position: Int): Fragment =
            when (tabs[position]) {
                MusicType.SONGS -> SongListFragment()
                MusicType.ALBUMS -> AlbumListFragment()
                MusicType.ARTISTS -> ArtistListFragment()
                MusicType.GENRES -> GenreListFragment()
                MusicType.PLAYLISTS -> PlaylistListFragment()
                MusicType.DOWNLOADS -> RecentDownloadsListFragment()
            }
    }
}
