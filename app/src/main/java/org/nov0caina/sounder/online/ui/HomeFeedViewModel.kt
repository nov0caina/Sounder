package org.nov0caina.sounder.online.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.cache.OfflineBackupFilter
import org.nov0caina.sounder.online.cache.OfflineBackupFilterMode
import org.nov0caina.sounder.online.cache.OfflineBackupFilterState
import org.nov0caina.sounder.online.cache.StreamingCacheManager
import org.nov0caina.sounder.online.db.CachedTrackEntity
import org.nov0caina.sounder.online.db.FavoriteArtistDao
import org.nov0caina.sounder.online.db.PlaybackRecord
import org.nov0caina.sounder.online.db.PlaybackRecordDao
import org.nov0caina.sounder.online.db.RecentArtistsDao
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadManager
import org.nov0caina.sounder.online.innertube.InnerTubeParser
import org.nov0caina.sounder.online.model.HomeFeedData
import org.nov0caina.sounder.online.model.HomeMoodItem
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.image.CoverProvider
import org.nov0caina.sounder.online.model.OnlineSong
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.QuickAccessItem
import org.nov0caina.sounder.online.repository.OnlineMusicRepository
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.playback.PlaySong
import org.nov0caina.sounder.playback.state.ShuffleMode
import org.nov0caina.sounder.util.MediaDeletionUtil
import org.nov0caina.sounder.util.NetworkUtil
import org.nov0caina.sounder.util.SongMatcher
import org.oxycblt.musikr.Song
import timber.log.Timber as L

data class HeroSpotlightState(
    val track: OnlineTrack,
    val isPersonalized: Boolean,
    val badgeResId: Int,
)

@HiltViewModel
class HomeFeedViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: OnlineMusicRepository,
    val downloadManager: DownloadManager,
    private val streamingCacheManager: StreamingCacheManager,
    private val playbackRecordDao: PlaybackRecordDao,
    private val favoriteArtistDao: FavoriteArtistDao,
    private val recentArtistsDao: RecentArtistsDao,
    private val onlineSettings: OnlineSettings,
) : ViewModel() {

    private val _feedData = MutableStateFlow(HomeFeedData())
    val feedData: StateFlow<HomeFeedData> = _feedData.asStateFlow()

    private val _quickAccessItems = MutableStateFlow<List<QuickAccessItem>>(emptyList())
    val quickAccessItems: StateFlow<List<QuickAccessItem>> = _quickAccessItems.asStateFlow()

    private val _recentRecords = MutableStateFlow<List<PlaybackRecord>>(emptyList())

    val favoriteArtists: StateFlow<List<OnlineArtistSummary>> =
        favoriteArtistDao.observeFavoriteArtists()
            .map { entities -> entities.map { it.toSummary() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _becauseYouListenArtist = MutableStateFlow<String?>(null)
    val becauseYouListenArtist: StateFlow<String?> = _becauseYouListenArtist.asStateFlow()

    private val _becauseYouListenTracks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val becauseYouListenTracks: StateFlow<List<OnlineTrack>> = _becauseYouListenTracks.asStateFlow()

    private val _personalizedTrendingAlbums = MutableStateFlow<List<OnlineAlbumSummary>>(emptyList())
    val personalizedTrendingAlbums: StateFlow<List<OnlineAlbumSummary>> = _personalizedTrendingAlbums.asStateFlow()

    private val _personalizedQuickPicks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val personalizedQuickPicks: StateFlow<List<OnlineTrack>> = _personalizedQuickPicks.asStateFlow()

    private val _userTopTrends = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val userTopTrends: StateFlow<List<OnlineTrack>> = _userTopTrends.asStateFlow()

    private val _isShowingPersonalTrends = MutableStateFlow(false)
    val isShowingPersonalTrends: StateFlow<Boolean> = _isShowingPersonalTrends.asStateFlow()

    private val _heroSpotlight = MutableStateFlow<HeroSpotlightState?>(null)
    val heroSpotlight: StateFlow<HeroSpotlightState?> = _heroSpotlight.asStateFlow()

    private val _artistRecommendedAlbums = MutableStateFlow<List<OnlineAlbumSummary>>(emptyList())
    private val _favoriteArtistTracks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    private var lastLoadedSeedKey: String? = null
    private var becauseYouListenJob: Job? = null
    private val resolvedArtworkCache = ConcurrentHashMap<String, String>()
    private val attemptedArtworkLookups = ConcurrentHashMap.newKeySet<String>()

    private val _isTopChartsExpanded = MutableStateFlow(false)
    val isTopChartsExpanded: StateFlow<Boolean> = _isTopChartsExpanded.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val activeDownloadsCount: StateFlow<Int> = downloadManager.activeCount
    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads

    private val _loadingTrackId = MutableStateFlow<String?>(null)
    val loadingTrackId: StateFlow<String?> = _loadingTrackId.asStateFlow()

    val offlineBackupTracks: StateFlow<List<OnlineTrack>> =
        streamingCacheManager.observePlayableCachedTracks()
            .map { entities -> entities.map { it.toOnlineTrack() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _offlineBackupFilter = MutableStateFlow(OfflineBackupFilterState())
    val offlineBackupFilter: StateFlow<OfflineBackupFilterState> = _offlineBackupFilter.asStateFlow()

    val filteredOfflineBackupTracks: StateFlow<List<OnlineTrack>> =
        combine(offlineBackupTracks, offlineBackupFilter) { tracks, filter ->
            OfflineBackupFilter.filterTracks(tracks, filter)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cacheSizeBytes: StateFlow<Long> = streamingCacheManager.cacheSizeBytes

    init {
        loadFeed()
        observeNetworkChanges()
        observePlaybackRecords()
        observeFavoriteArtists()
    }

    private fun observeFavoriteArtists() {
        viewModelScope.launch {
            favoriteArtistDao.observeFavoriteArtists().collect {
                refreshBecauseYouListen(_recentRecords.value)
            }
        }
    }

    private fun observePlaybackRecords() {
        viewModelScope.launch {
            playbackRecordDao.observeRecentRecords(300).collect { records ->
                _recentRecords.value = records
                rebuildQuickAccess(_feedData.value, records)
                refreshBecauseYouListen(records)
            }
        }
    }

    /** Re-evaluates privacy-aware personalization widgets (e.g. after returning from Settings). */
    fun refreshPersonalizationState() {
        val records = _recentRecords.value
        rebuildQuickAccess(_feedData.value, records)
        refreshBecauseYouListen(records)
    }

    private fun refreshBecauseYouListen(records: List<PlaybackRecord>) {
        val insightsEnabled = onlineSettings.enableLocalListeningInsights
        val thirtyDaysAgoMs = System.currentTimeMillis() - THIRTY_DAYS_MS

        viewModelScope.launch {
            val favs = favoriteArtistDao.getAll().map { it.toSummary() }
            val cached = streamingCacheManager.getPlayableCachedTracks()
            val recents = recentArtistsDao.getRecentArtists().map { it.toSummary() }

            val topArtist = selectTopSeedArtist(
                records = records,
                sinceTimestampMs = thirtyDaysAgoMs,
                insightsEnabled = insightsEnabled,
                favoriteArtists = favs,
                cachedTracks = cached,
                recentArtists = recents,
            )
            val seedArtists = selectSeedArtistsList(
                records = records,
                sinceTimestampMs = thirtyDaysAgoMs,
                insightsEnabled = insightsEnabled,
                minPlays = 2,
                favoriteArtists = favs,
                cachedTracks = cached,
                recentArtists = recents,
                maxArtists = 3,
            )

            if (topArtist == null) {
                _becauseYouListenArtist.value = null
                _becauseYouListenTracks.value = emptyList()
            } else {
                _becauseYouListenArtist.value = topArtist
            }

            if (seedArtists.isEmpty()) {
                becauseYouListenJob?.cancel()
                lastLoadedSeedKey = null
                _artistRecommendedAlbums.value = emptyList()
                _favoriteArtistTracks.value = emptyList()
                rebuildPersonalizedFeedSections(_feedData.value, records)
                return@launch
            }

            val seedKey = "${topArtist.orEmpty()}|${seedArtists.joinToString(",")}"
            if (seedKey == lastLoadedSeedKey &&
                (_becauseYouListenTracks.value.isNotEmpty() || topArtist == null) &&
                _artistRecommendedAlbums.value.isNotEmpty()
            ) {
                rebuildPersonalizedFeedSections(_feedData.value, records)
                return@launch
            }
            if (!NetworkUtil.isConnected(context)) return@launch

            becauseYouListenJob?.cancel()
            becauseYouListenJob = viewModelScope.launch {
                try {
                    val primaryArtist = topArtist ?: seedArtists.first()
                    val normPrimary = SongMatcher.normalizeArtist(primaryArtist)
                    fun isSeedArtistTrack(track: OnlineTrack): Boolean {
                        val norm = SongMatcher.normalizeArtist(track.artist)
                        return norm.isNotEmpty() && normPrimary.isNotEmpty() &&
                            (norm == normPrimary || norm.contains(normPrimary) || norm.startsWith(normPrimary))
                    }

                    val artistSearch = repository.queryCategorized(primaryArtist)
                    val radioSearch = try {
                        repository.queryCategorized("$primaryArtist radio")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        artistSearch
                    }

                    val collectedAlbums = mutableListOf<OnlineAlbumSummary>()
                    val seedTracks = artistSearch.tracks.filter(::isSeedArtistTrack).toMutableList()
                    val relatedTracks = mutableListOf<OnlineTrack>()

                    fun findKnownArtistId(artistName: String): String? =
                        (favs + recents)
                            .firstOrNull { it.name.equals(artistName, ignoreCase = true) && it.id.isNotBlank() }
                            ?.id

                    val primaryArtistId = findKnownArtistId(primaryArtist)
                        ?: artistSearch.artists.firstOrNull()?.id
                    if (!primaryArtistId.isNullOrBlank()) {
                        try {
                            val detail = repository.browseArtist(primaryArtistId, recordRecent = false)
                            val primaryAlbums = detail.albums.take(6).ifEmpty { detail.singles.take(4) }
                            collectedAlbums.addAll(primaryAlbums)
                            seedTracks.addAll(detail.topSongs.filter(::isSeedArtistTrack))

                            val relatedArtists = detail.relatedArtists.take(4)
                            for (related in relatedArtists) {
                                try {
                                    val normRelated = SongMatcher.normalizeArtist(related.name)
                                    val tracks = repository.queryCategorized(related.name).tracks.filter { t ->
                                        val norm = SongMatcher.normalizeArtist(t.artist)
                                        norm.isNotEmpty() && normRelated.isNotEmpty() &&
                                            (norm == normRelated || norm.contains(normRelated) || norm.startsWith(normRelated))
                                    }.take(4)
                                    relatedTracks.addAll(tracks)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w(e, "Failed to browse seed artist detail for %s", primaryArtist)
                        }
                    }

                    for (extraArtist in seedArtists) {
                        if (extraArtist.equals(primaryArtist, ignoreCase = true)) continue
                        try {
                            val knownId = findKnownArtistId(extraArtist)
                            val extraSearch = repository.queryCategorized(extraArtist)
                            val extraArtistId = knownId ?: extraSearch.artists.firstOrNull()?.id
                            if (!extraArtistId.isNullOrBlank()) {
                                val extraDetail = repository.browseArtist(extraArtistId, recordRecent = false)
                                val extraAlbums = extraDetail.albums.take(4).ifEmpty { extraDetail.singles.take(3) }
                                collectedAlbums.addAll(extraAlbums)
                            }
                            val normExtra = SongMatcher.normalizeArtist(extraArtist)
                            val extraTracks = extraSearch.tracks.filter { t ->
                                val norm = SongMatcher.normalizeArtist(t.artist)
                                norm.isNotEmpty() && normExtra.isNotEmpty() &&
                                    (norm == normExtra || norm.contains(normExtra) || norm.startsWith(normExtra))
                            }.take(4)
                            relatedTracks.addAll(extraTracks)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }

                    val blendedTracks = blendSimilarArtistTracks(
                        seedArtist = primaryArtist,
                        seedTracks = seedTracks,
                        similarTracks = radioSearch.tracks + relatedTracks,
                        maxTracks = 18,
                    )

                    if (onlineSettings.enableLocalListeningInsights) {
                        lastLoadedSeedKey = seedKey
                        _artistRecommendedAlbums.value = collectedAlbums
                        _favoriteArtistTracks.value = blendedTracks
                        if (topArtist != null && blendedTracks.isNotEmpty()) {
                            _becauseYouListenTracks.value = blendedTracks
                        }
                        rebuildPersonalizedFeedSections(_feedData.value, _recentRecords.value)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Failed to load contextual recommendations for %s", topArtist ?: seedArtists.firstOrNull())
                }
            }
        }
    }

    private fun observeNetworkChanges() {
        viewModelScope.launch {
            NetworkUtil.observeConnectivity(context).collect { connected ->
                if (!connected) {
                    _isOffline.value = true
                    _isLoading.value = false
                } else {
                    val wasOffline = _isOffline.value
                    _isOffline.value = false
                    val hasExistingData = _feedData.value.trendingTracks.isNotEmpty() ||
                        _feedData.value.trendingAlbums.isNotEmpty() ||
                        _feedData.value.popularArtists.isNotEmpty()
                    if (wasOffline || !hasExistingData) {
                        loadFeed(force = !hasExistingData, category = null)
                    }
                }
            }
        }
    }

    fun selectBackupFilterMode(mode: OfflineBackupFilterMode) {
        _offlineBackupFilter.value = OfflineBackupFilterState(mode = mode, selectedValue = null)
    }

    fun selectBackupFilterValue(value: String?) {
        val current = _offlineBackupFilter.value
        val nextValue = if (current.selectedValue.equals(value, ignoreCase = true)) null else value
        _offlineBackupFilter.value = current.copy(selectedValue = nextValue)
    }

    fun playOfflineBackup(
        startTrack: OnlineTrack? = null,
        shuffle: Boolean = false,
        playbackModel: PlaybackViewModel,
    ) {
        val visibleTracks = filteredOfflineBackupTracks.value.ifEmpty { offlineBackupTracks.value }
        if (visibleTracks.isEmpty()) return

        val queue = visibleTracks.map { track ->
            val localFile = downloadManager.getDownloadedFile(track)
            if (localFile != null && localFile.exists()) {
                OnlineSong(track, Uri.fromFile(localFile))
            } else {
                OnlineSong(track, Uri.parse("sounder://stream/${track.id}"))
            }
        }
        val targetSong = if (startTrack != null) {
            queue.firstOrNull { (it as? OnlineSong)?.onlineTrack?.id == startTrack.id } ?: queue.first()
        } else if (shuffle) {
            queue.random()
        } else {
            queue.first()
        }
        playbackModel.play(targetSong, queue, if (shuffle) ShuffleMode.ON else ShuffleMode.OFF)
    }

    private var loadFeedJob: Job? = null
    private var currentLoadedCategory: String? = null

    fun toggleTopChartsExpanded() {
        _isTopChartsExpanded.value = !_isTopChartsExpanded.value
    }

    fun loadFeed(force: Boolean = false, category: String? = null) {
        val hasExistingData = _feedData.value.trendingTracks.isNotEmpty() ||
            _feedData.value.trendingAlbums.isNotEmpty() ||
            _feedData.value.popularArtists.isNotEmpty()

        if (!force && currentLoadedCategory == category && hasExistingData) {
            _isLoading.value = false
            _isOffline.value = false
            return
        }

        // Fast offline check: if device has no connection and no cached data, avoid 30s timeouts
        if (!NetworkUtil.isConnected(context)) {
            if (!hasExistingData) {
                _isOffline.value = true
                _isLoading.value = false
                _error.value = null
                return
            }
        }

        loadFeedJob?.cancel()
        loadFeedJob = viewModelScope.launch {
            _isLoading.value = true
            _isOffline.value = false
            _error.value = null
            try {
                val feed = repository.getHomeFeed(category = category, forceRefresh = force)
                currentLoadedCategory = category
                _feedData.value = feed
                rebuildQuickAccess(feed)
                refreshBecauseYouListen(_recentRecords.value)
                if (feed.trendingTracks.isEmpty() && feed.trendingAlbums.isEmpty() && feed.popularArtists.isEmpty()) {
                    if (!NetworkUtil.isConnected(context)) {
                        _isOffline.value = true
                    } else {
                        _error.value = "No se pudieron cargar las tendencias"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w(e, "Error loading home feed")
                if (!NetworkUtil.isConnected(context)) {
                    _isOffline.value = true
                } else {
                    _error.value = e.message ?: "Error al cargar tendencias"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun rebuildQuickAccess(
        feed: HomeFeedData,
        records: List<PlaybackRecord> = _recentRecords.value,
    ) {
        val list = mutableListOf<QuickAccessItem>()

        // 1. Favorites Card
        list.add(
            QuickAccessItem(
                id = "quick_favorites",
                title = context.getString(org.nov0caina.sounder.R.string.home_quick_favorites),
                subtitle = context.getString(org.nov0caina.sounder.R.string.lbl_favorites_playlist),
                iconRes = org.nov0caina.sounder.R.drawable.ic_favorite_24,
                isFavorites = true
            )
        )

        // 2..6. Up to 5 playable songs only (weighted by local time-of-day & day-of-week habits)
        val (currentTimeOfDay, currentDayOfWeek) = PlaybackRecord.currentContext()
        val selectedTracks = scoreAndSelectQuickAccessTracks(
            records = records,
            feed = feed,
            currentTimeOfDay = currentTimeOfDay,
            currentDayOfWeek = currentDayOfWeek,
            insightsEnabled = onlineSettings.enableLocalListeningInsights,
            maxTracks = 5,
        ).map(::enrichTrackArtwork)
        for (track in selectedTracks) {
            list.add(
                QuickAccessItem(
                    id = "track_${track.id}",
                    title = track.title,
                    subtitle = track.artist,
                    imageUrl = track.artworkUrl,
                    track = track
                )
            )
        }

        _quickAccessItems.value = list
        rebuildPersonalizedFeedSections(feed, records)
        scheduleArtworkLookupIfMissing(selectedTracks + _personalizedQuickPicks.value)
    }

    private fun rebuildPersonalizedFeedSections(
        feed: HomeFeedData = _feedData.value,
        records: List<PlaybackRecord> = _recentRecords.value,
    ) {
        val insightsEnabled = onlineSettings.enableLocalListeningInsights
        _personalizedTrendingAlbums.value = blendTrendingAlbums(
            artistAlbums = _artistRecommendedAlbums.value,
            catalogTrendingAlbums = feed.trendingAlbums,
            insightsEnabled = insightsEnabled,
        )
        val picks = buildPersonalizedQuickPicks(
            records = records,
            favoriteAndContextualTracks = _favoriteArtistTracks.value + _becauseYouListenTracks.value,
            catalogQuickPicks = feed.quickPicks,
            insightsEnabled = insightsEnabled,
        ).map(::enrichTrackArtwork)
        _personalizedQuickPicks.value = picks

        val personalTrends = buildUserTopTrends(
            records = records,
            insightsEnabled = insightsEnabled,
            maxTracks = 20,
        ).map(::enrichTrackArtwork)
        _userTopTrends.value = personalTrends
        _isShowingPersonalTrends.value = insightsEnabled && personalTrends.isNotEmpty()

        val (currentTimeOfDay, currentDayOfWeek) = PlaybackRecord.currentContext()
        _heroSpotlight.value = selectHeroSpotlight(
            records = records,
            becauseYouListenTracks = _becauseYouListenTracks.value,
            favoriteArtistTracks = _favoriteArtistTracks.value,
            feed = feed,
            currentTimeOfDay = currentTimeOfDay,
            currentDayOfWeek = currentDayOfWeek,
            insightsEnabled = insightsEnabled,
        )?.let { state ->
            state.copy(track = enrichTrackArtwork(state.track))
        }
    }

    private fun enrichTrackArtwork(track: OnlineTrack): OnlineTrack {
        if (!track.artworkUrl.isNullOrBlank() || !track.localCoverPath.isNullOrBlank()) {
            return track
        }

        val normKey = "${SongMatcher.normalizeTitle(track.title)}|${SongMatcher.normalizeArtist(track.artist)}"
        val cachedUrl = resolvedArtworkCache[normKey]
        if (!cachedUrl.isNullOrBlank()) {
            return track.copy(artworkUrl = cachedUrl)
        }

        StreamingCacheManager.findCachedCoverFile(track.id)
            ?.takeIf { it.exists() && it.length() > 0L }
            ?.let { cachedCover ->
                return track.copy(localCoverPath = cachedCover.absolutePath)
            }

        downloadManager.getDownloadedFile(track)?.takeIf { it.exists() }?.let { downloadedFile ->
            findCompanionCoverFile(downloadedFile.parentFile, downloadedFile.nameWithoutExtension)?.let { coverFile ->
                return track.copy(localCoverPath = coverFile.absolutePath)
            }
        }

        downloadManager.findSongInLibrary(track)?.let { libSong ->
            libSong.cover?.id?.takeIf { it.isNotBlank() }?.let { coverId ->
                val coverUri = CoverProvider.CONTENT_URI
                    .buildUpon()
                    .appendPath(coverId)
                    .build()
                    .toString()
                return track.copy(artworkUrl = coverUri)
            }
            MediaDeletionUtil.resolveSongFile(context, libSong)?.let { songFile ->
                findCompanionCoverFile(songFile.parentFile, songFile.nameWithoutExtension)?.let { coverFile ->
                    return track.copy(localCoverPath = coverFile.absolutePath)
                }
            }
        }

        val catalogPool = _feedData.value.quickPicks +
            _feedData.value.trendingTracks +
            _favoriteArtistTracks.value +
            _becauseYouListenTracks.value
        val catalogMatch = catalogPool.firstOrNull { candidate ->
            !candidate.artworkUrl.isNullOrBlank() &&
                (candidate.id == track.id || SongMatcher.matches(candidate.title, candidate.artist, track.title, track.artist))
        }
        if (catalogMatch != null && !catalogMatch.artworkUrl.isNullOrBlank()) {
            resolvedArtworkCache[normKey] = catalogMatch.artworkUrl
            return track.copy(artworkUrl = catalogMatch.artworkUrl)
        }

        return track
    }

    private fun findCompanionCoverFile(parentDir: File?, stem: String): File? {
        if (parentDir == null || !parentDir.exists()) return null
        return File(parentDir, "$stem.jpg").takeIf { it.exists() && it.length() > 0L }
            ?: File(parentDir, "cover.jpg").takeIf { it.exists() && it.length() > 0L }
    }

    private fun scheduleArtworkLookupIfMissing(tracks: List<OnlineTrack>) {
        if (!NetworkUtil.isConnected(context)) return
        val missing = tracks.filter {
            it.artworkUrl.isNullOrBlank() &&
                it.localCoverPath.isNullOrBlank() &&
                it.title.isNotBlank()
        }
        if (missing.isEmpty()) return

        for (track in missing) {
            val normKey = "${SongMatcher.normalizeTitle(track.title)}|${SongMatcher.normalizeArtist(track.artist)}"
            if (!attemptedArtworkLookups.add(normKey)) continue

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val query = "${track.title} ${track.artist}".trim()
                    val result = repository.queryCategorized(query)
                    val matched = result.tracks.firstOrNull { candidate ->
                        !candidate.artworkUrl.isNullOrBlank() &&
                            (candidate.id == track.id ||
                                SongMatcher.matches(candidate.title, candidate.artist, track.title, track.artist))
                    } ?: result.tracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }

                    val resolvedUrl = matched?.artworkUrl
                    if (!resolvedUrl.isNullOrBlank()) {
                        resolvedArtworkCache[normKey] = resolvedUrl
                        rebuildQuickAccess(_feedData.value, _recentRecords.value)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Failed background artwork lookup for %s", track.title)
                }
            }
        }
    }

    private fun toSong(track: OnlineTrack, streamUrl: String? = null): Song {
        val localFile = downloadManager.getDownloadedFile(track)
        if (localFile != null && localFile.exists()) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
        }
        val localSong = downloadManager.findSongInLibrary(track)
        if (localSong != null) {
            return localSong
        }
        if (streamingCacheManager.isTrackFullyCached(track.id)) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse("sounder://stream/${track.id}"))
        }
        val targetUri = when {
            !streamUrl.isNullOrBlank() -> Uri.parse(streamUrl)
            !track.streamUrl.isNullOrBlank() -> Uri.parse(track.streamUrl)
            else -> Uri.parse("https://music.youtube.com/watch?v=${track.id}")
        }
        val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
        return OnlineSong(track.copy(durationMs = resolvedDuration), targetUri)
    }

    private suspend fun resolveToPlayableSong(track: OnlineTrack): Song {
        val localFile = downloadManager.getDownloadedFile(track)
        if (localFile != null && localFile.exists()) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
        }
        val localSong = downloadManager.findSongInLibrary(track)
        if (localSong != null) {
            return localSong
        }
        if (streamingCacheManager.isTrackFullyCached(track.id)) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse("sounder://stream/${track.id}"))
        }
        val lookupId = if (track.id.contains("-") && track.id.length > 16) "" else track.id
        val streamInfo = repository.resolveStreamInfo(lookupId, title = track.title, artist = track.artist)
        if (streamInfo != null && streamInfo.url.isNotBlank()) {
            val resolvedDuration = if (track.durationMs > 0) track.durationMs else streamInfo.durationMs
            return OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse(streamInfo.url))
        }
        return toSong(track)
    }

    private suspend fun ensureAtLeast20Tracks(
        baseTracks: List<OnlineTrack>,
        fallbackCategory: String? = null,
        allowTrendingFallback: Boolean = false,
        seedTrack: OnlineTrack? = null,
    ): List<OnlineTrack> {
        if (baseTracks.size >= 20) return baseTracks
        val result = baseTracks.toMutableList()
        val seenIds = result.map { it.id }.toMutableSet()

        if (allowTrendingFallback) {
            val feed = _feedData.value
            for (t in feed.trendingTracks + feed.quickPicks) {
                if (seenIds.add(t.id)) {
                    result.add(t)
                    if (result.size >= 25) return result
                }
            }

            if (result.size < 20 && NetworkUtil.isConnected(context)) {
                try {
                    val query = when {
                        !fallbackCategory.isNullOrBlank() -> "$fallbackCategory top hits"
                        else -> "Top 50 musica mundial"
                    }
                    val searchResult = repository.queryCategorized(query)
                    for (t in searchResult.tracks) {
                        if (seenIds.add(t.id)) {
                            result.add(t)
                            if (result.size >= 25) break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Error fetching trending fallback tracks to reach 20")
                }
            }
            return result
        }

        // Personalized sections must NEVER inject national trendingTracks.
        // Complement strictly with contextual radio or user's affine tracks.
        val seed = seedTrack ?: baseTracks.firstOrNull()
        if (seed != null && seed.artist.isNotBlank() && NetworkUtil.isConnected(context)) {
            try {
                val radioSearch = repository.queryCategorized("${seed.artist} radio")
                val filteredRadio = filterContextualRadioTracks(seed, radioSearch.tracks, maxTracks = 25)
                for (t in filteredRadio) {
                    if (seenIds.add(t.id)) {
                        result.add(t)
                        if (result.size >= 25) return result
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w(e, "Error fetching contextual backfill tracks for %s", seed.artist)
            }
        }

        for (t in _favoriteArtistTracks.value + _becauseYouListenTracks.value + _personalizedQuickPicks.value) {
            if (isValidMusicTrack(t) && seenIds.add(t.id)) {
                result.add(t)
                if (result.size >= 25) break
            }
        }
        return result
    }

    private suspend fun fetchContextualRadioTracks(seedTrack: OnlineTrack): List<OnlineTrack> {
        val collected = mutableListOf<OnlineTrack>()
        val seedArtist = seedTrack.artist.trim()

        if (NetworkUtil.isConnected(context) && seedArtist.isNotBlank()) {
            try {
                val radioResult = repository.queryCategorized("$seedArtist radio")
                collected.addAll(radioResult.tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w(e, "Radio search failed for %s", seedArtist)
            }

            if (collected.size < 15) {
                try {
                    val artistSearch = repository.queryCategorized(seedArtist)
                    collected.addAll(artistSearch.tracks)
                    val artistId = artistSearch.artists.firstOrNull()?.id
                    if (!artistId.isNullOrBlank()) {
                        val detail = repository.browseArtist(artistId, recordRecent = false)
                        collected.addAll(detail.topSongs)
                        for (related in detail.relatedArtists.take(3)) {
                            try {
                                val relResult = repository.queryCategorized(related.name)
                                collected.addAll(relResult.tracks.take(4))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Fallback artist radio browse failed for %s", seedArtist)
                }
            }
        }

        // Offline or sparse backfill: stay strictly within user's affinity pools, NEVER trendingTracks
        val normSeedArtist = SongMatcher.normalizeArtist(seedArtist)
        val sameArtistLocal = (_becauseYouListenTracks.value + _favoriteArtistTracks.value + _personalizedQuickPicks.value)
            .filter { cand ->
                val normCand = SongMatcher.normalizeArtist(cand.artist)
                normCand.isNotEmpty() && normSeedArtist.isNotEmpty() &&
                    (normCand == normSeedArtist || normCand.contains(normSeedArtist))
            }
        collected.addAll(sameArtistLocal)
        collected.addAll(_becauseYouListenTracks.value)
        collected.addAll(_favoriteArtistTracks.value)
        collected.addAll(_personalizedQuickPicks.value)
        return collected
    }

    fun playRadioForTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            try {
                val (targetSong, radioCandidates) = coroutineScope {
                    val songDeferred = async { resolveToPlayableSong(track) }
                    val radioDeferred = async { fetchContextualRadioTracks(track) }
                    songDeferred.await() to radioDeferred.await()
                }
                val orderedTracks = filterContextualRadioTracks(track, radioCandidates, maxTracks = 25)
                val queue = orderedTracks.map { t ->
                    if (t.id == track.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) {
                    queue
                } else {
                    listOf(targetSong) + queue
                }

                playbackModel.play(targetSong, finalQueue)

                launch(Dispatchers.IO) {
                    orderedTracks.filter { it.id != track.id }.take(20).forEach { t ->
                        try {
                            if (downloadManager.getDownloadedFile(t)?.exists() != true &&
                                downloadManager.findSongInLibrary(t) == null
                            ) {
                                repository.resolveStreamInfo(t.id, title = t.title, artist = t.artist)
                            }
                        } catch (e: Exception) {
                            L.w(e, "Radio prefetch failed for track %s", t.title)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e, "Error starting contextual radio for %s", track.title)
            } finally {
                _loadingTrackId.value = null
            }
        }
    }

    fun playTrackWithQueue(
        track: OnlineTrack,
        sectionTracks: List<OnlineTrack>,
        playbackModel: PlaybackViewModel,
        allowTrendingFallback: Boolean = false,
    ) {
        viewModelScope.launch {
            _loadingTrackId.value = track.id
            try {
                val targetSong = resolveToPlayableSong(track)
                val enrichedTracks = ensureAtLeast20Tracks(
                    baseTracks = sectionTracks,
                    fallbackCategory = currentLoadedCategory,
                    allowTrendingFallback = allowTrendingFallback,
                    seedTrack = track,
                )

                val queue = enrichedTracks.map { t ->
                    if (t.id == track.id) targetSong else toSong(t)
                }
                val finalQueue = if (queue.any { it.uid.toString() == targetSong.uid.toString() }) {
                    queue
                } else {
                    listOf(targetSong) + queue
                }

                playbackModel.play(targetSong, finalQueue)

                // Background prefetch for remaining tracks so ExoPlayer transitions seamlessly
                launch(Dispatchers.IO) {
                    enrichedTracks.filter { it.id != track.id }.take(20).forEach { t ->
                        try {
                            if (downloadManager.getDownloadedFile(t)?.exists() != true &&
                                downloadManager.findSongInLibrary(t) == null
                            ) {
                                repository.resolveStreamInfo(t.id, title = t.title, artist = t.artist)
                            }
                        } catch (e: Exception) {
                            L.w(e, "Prefetch failed for track %s", t.title)
                        }
                    }
                }
            } catch (e: Exception) {
                L.e(e, "Error playing track %s with queue", track.title)
            } finally {
                _loadingTrackId.value = null
            }
        }
    }

    fun playTrendingTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val ordered = buildOrderedSectionQueue(track, _feedData.value.trendingTracks)
        playTrackWithQueue(track, ordered, playbackModel, allowTrendingFallback = true)
    }

    fun playHeroSpotlight(spotlight: HeroSpotlightState, playbackModel: PlaybackViewModel) {
        if (spotlight.isPersonalized) {
            playRadioForTrack(spotlight.track, playbackModel)
        } else {
            playTrendingTrack(spotlight.track, playbackModel)
        }
    }

    fun playTopChartsTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        if (_isShowingPersonalTrends.value && _userTopTrends.value.isNotEmpty()) {
            val ordered = buildOrderedSectionQueue(track, _userTopTrends.value)
            playTrackWithQueue(track, ordered, playbackModel, allowTrendingFallback = false)
        } else {
            playTrendingTrack(track, playbackModel)
        }
    }

    fun playQuickPickTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val picks = _personalizedQuickPicks.value.ifEmpty { _feedData.value.quickPicks }.take(24)
        val ordered = buildOrderedSectionQueue(track, picks)
        playTrackWithQueue(track, ordered, playbackModel, allowTrendingFallback = false)
    }

    fun playQuickAccessTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        playRadioForTrack(track, playbackModel)
    }

    fun playBecauseYouListenTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val ordered = buildOrderedSectionQueue(track, _becauseYouListenTracks.value)
        playTrackWithQueue(track, ordered, playbackModel, allowTrendingFallback = false)
    }

    fun playTrack(track: OnlineTrack, playbackModel: PlaybackViewModel) {
        val picks = _personalizedQuickPicks.value.ifEmpty { _feedData.value.quickPicks }
        val userTrends = _userTopTrends.value
        val feed = _feedData.value
        when {
            picks.any { it.id == track.id } -> playQuickPickTrack(track, playbackModel)
            _becauseYouListenTracks.value.any { it.id == track.id } -> playBecauseYouListenTrack(track, playbackModel)
            userTrends.any { it.id == track.id } -> playTopChartsTrack(track, playbackModel)
            feed.trendingTracks.any { it.id == track.id } -> playTrendingTrack(track, playbackModel)
            else -> playRadioForTrack(track, playbackModel)
        }
    }

    fun playArtist(artist: OnlineArtistSummary, playbackModel: PlaybackViewModel) {
        viewModelScope.launch {
            try {
                val resolvedId = if (artist.id.isBlank() && artist.name.isNotBlank()) {
                    val results = repository.queryCategorized(artist.name)
                    results.artists.firstOrNull()?.id ?: ""
                } else {
                    artist.id
                }
                if (resolvedId.isNotBlank()) {
                    val detail = repository.browseArtist(resolvedId)
                    var artistTracks = detail.topSongs
                    if (artistTracks.size < 20) {
                        try {
                            val searchResult = repository.queryCategorized("${detail.name} songs")
                            val combined = artistTracks.toMutableList()
                            for (t in searchResult.tracks) {
                                if (combined.none { it.id == t.id }) {
                                    combined.add(t)
                                    if (combined.size >= 25) break
                                }
                            }
                            artistTracks = combined
                        } catch (e: Exception) {
                            L.w(e, "Error fetching artist tracks")
                        }
                    }
                    val firstTrack = artistTracks.firstOrNull() ?: return@launch
                    playTrackWithQueue(firstTrack, artistTracks, playbackModel)
                }
            } catch (e: Exception) {
                L.e(e, "Error playing artist %s", artist.name)
            }
        }
    }

    fun enqueueTrack(
        track: OnlineTrack,
        playbackModel: PlaybackViewModel,
        onEnqueued: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                val localFile = downloadManager.getDownloadedFile(track)
                if (localFile != null && localFile.exists()) {
                    val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
                    val onlineSong = OnlineSong(track.copy(durationMs = resolvedDuration), Uri.fromFile(localFile))
                    playbackModel.addToQueue(onlineSong)
                    onEnqueued?.invoke()
                    return@launch
                }
                val localSong = downloadManager.findSongInLibrary(track)
                if (localSong != null) {
                    playbackModel.addToQueue(localSong)
                    onEnqueued?.invoke()
                    return@launch
                }
                if (streamingCacheManager.isTrackFullyCached(track.id)) {
                    val resolvedDuration = if (track.durationMs > 0) track.durationMs else 0L
                    val cachedSong = OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse("sounder://stream/${track.id}"))
                    playbackModel.addToQueue(cachedSong)
                    onEnqueued?.invoke()
                    return@launch
                }
                val lookupId = if (track.id.contains("-") && track.id.length > 16) "" else track.id
        val streamInfo = repository.resolveStreamInfo(lookupId, title = track.title, artist = track.artist)
                if (streamInfo != null && streamInfo.url.isNotBlank()) {
                    val resolvedDuration = if (track.durationMs > 0) track.durationMs else streamInfo.durationMs
                    val onlineSong = OnlineSong(track.copy(durationMs = resolvedDuration), Uri.parse(streamInfo.url))
                    playbackModel.addToQueue(onlineSong)
                    onEnqueued?.invoke()
                } else {
                    L.w("Failed to resolve stream for track %s to enqueue", track.id)
                }
            } catch (e: Exception) {
                L.e(e, "Error resolving online track %s for queue", track.title)
            }
        }
    }

    fun downloadTrack(track: OnlineTrack) {
        downloadManager.enqueue(track)
    }

    fun isDownloaded(track: OnlineTrack): Boolean = downloadManager.isDownloaded(track)

    fun isDownloaded(trackId: String): Boolean = downloadManager.isDownloaded(trackId)

    fun isEnqueuedOrDownloading(track: OnlineTrack): Boolean = downloadManager.isEnqueuedOrDownloading(track)

    fun isEnqueuedOrDownloading(trackId: String): Boolean = downloadManager.isEnqueuedOrDownloading(trackId)

    fun recordRecentArtist(artist: OnlineArtistSummary) {
        viewModelScope.launch {
            repository.recordRecentArtist(artist)
        }
    }

    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L

        internal fun selectQuickAccessTracks(
            feed: HomeFeedData,
            maxTracks: Int = 5
        ): List<OnlineTrack> {
            val seenIds = mutableSetOf<String>()
            val result = mutableListOf<OnlineTrack>()
            for (track in feed.quickPicks + feed.trendingTracks) {
                if (track.id.isBlank()) continue
                if (InnerTubeParser.hasMonthlyAudienceMetric(track.artist) || InnerTubeParser.hasArtistMetricPart(track.artist)) {
                    continue
                }
                if (seenIds.add(track.id)) {
                    result.add(track)
                    if (result.size >= maxTracks) break
                }
            }
            return result
        }

        private fun isValidMusicTrack(track: OnlineTrack): Boolean {
            return track.id.isNotBlank() &&
                track.title.isNotBlank() &&
                !InnerTubeParser.hasMonthlyAudienceMetric(track.artist) &&
                !InnerTubeParser.hasArtistMetricPart(track.artist)
        }

        private fun addUniqueTrack(
            target: MutableList<OnlineTrack>,
            seenIds: MutableSet<String>,
            seenKeys: MutableSet<String>,
            track: OnlineTrack,
        ): Boolean {
            if (!isValidMusicTrack(track)) return false
            val key = "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
            if (!seenIds.add(track.id) || !seenKeys.add(key)) return false
            target.add(track)
            return true
        }

        private fun groupValidPlaybackRecords(records: List<PlaybackRecord>): List<List<PlaybackRecord>> {
            return records
                .filter { it.trackId.isNotBlank() && it.title.isNotBlank() }
                .groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                .values
                .toList()
        }

        private fun pickRepresentativeTrack(group: List<PlaybackRecord>): OnlineTrack {
            val representative = group.firstOrNull {
                it.sourceType == PlaybackRecord.SOURCE_STREAM && !it.artworkUrl.isNullOrBlank()
            } ?: group.firstOrNull {
                !it.artworkUrl.isNullOrBlank()
            } ?: group.firstOrNull {
                it.sourceType == PlaybackRecord.SOURCE_STREAM
            } ?: group.first()
            return representative.toOnlineTrack()
        }

        /**
         * Scores recent local playback records by contextual affinity (all-time plays + time-of-day
         * boost + day-of-week boost) and fills any remaining slots with editorial feed tracks.
         */
        internal fun scoreAndSelectQuickAccessTracks(
            records: List<PlaybackRecord>,
            feed: HomeFeedData,
            currentTimeOfDay: Int,
            currentDayOfWeek: Int,
            insightsEnabled: Boolean = true,
            maxTracks: Int = 5,
        ): List<OnlineTrack> {
            val selected = mutableListOf<OnlineTrack>()
            val seenIds = mutableSetOf<String>()
            val seenKeys = mutableSetOf<String>()

            if (insightsEnabled && records.isNotEmpty()) {
                val ranked = groupValidPlaybackRecords(records).map { group ->
                    val allTimePlays = group.size
                    val timeOfDayPlays = group.count { it.timeOfDay == currentTimeOfDay }
                    val dayOfWeekPlays = group.count { it.dayOfWeek == currentDayOfWeek }
                    val score = (allTimePlays * 1.0) + (timeOfDayPlays * 2.5) + (dayOfWeekPlays * 1.5)
                    val latestTs = group.maxOf { it.timestamp }
                    Triple(pickRepresentativeTrack(group), score, latestTs)
                }.sortedWith(
                    compareByDescending<Triple<OnlineTrack, Double, Long>> { it.second }
                        .thenByDescending { it.third }
                )

                for ((track, _, _) in ranked) {
                    if (selected.size >= maxTracks) break
                    addUniqueTrack(selected, seenIds, seenKeys, track)
                }
            }

            if (selected.size < maxTracks) {
                for (track in selectQuickAccessTracks(feed, maxTracks = maxTracks * 2)) {
                    if (selected.size >= maxTracks) break
                    addUniqueTrack(selected, seenIds, seenKeys, track)
                }
            }

            return selected
        }

        /**
         * Returns the prioritized list of seed artists across 4 tiers:
         * 1. Favorite artists
         * 2. Verified playback records (>= [minPlays] since [sinceTimestampMs])
         * 3. Cached streaming tracks
         * 4. Recently explored artists
         */
        internal fun selectSeedArtistsList(
            records: List<PlaybackRecord>,
            sinceTimestampMs: Long,
            insightsEnabled: Boolean = true,
            minPlays: Int = 2,
            favoriteArtists: List<OnlineArtistSummary> = emptyList(),
            cachedTracks: List<CachedTrackEntity> = emptyList(),
            recentArtists: List<OnlineArtistSummary> = emptyList(),
            maxArtists: Int = 3,
        ): List<String> {
            if (!insightsEnabled) return emptyList()
            val result = mutableListOf<String>()
            val seen = mutableSetOf<String>()

            fun addArtist(name: String?) {
                val trimmed = name?.trim() ?: return
                if (trimmed.isNotBlank() && seen.add(trimmed.lowercase())) {
                    result.add(trimmed)
                }
            }

            // Tier 1: User's explicitly favorited artists
            favoriteArtists.forEach { if (result.size < maxArtists) addArtist(it.name) }

            // Tier 2: Verified playback records
            if (result.size < maxArtists && records.isNotEmpty()) {
                records
                    .filter { it.timestamp >= sinceTimestampMs && it.artist.isNotBlank() }
                    .groupBy { it.artist.trim() }
                    .mapValues { (_, list) -> list.size to (list.maxOfOrNull { it.timestamp } ?: 0L) }
                    .filter { it.value.first >= minPlays }
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                            .thenByDescending { it.value.second }
                    )
                    .forEach { if (result.size < maxArtists) addArtist(it.key) }
            }

            // Tier 3: Cached streaming tracks
            if (result.size < maxArtists && cachedTracks.isNotEmpty()) {
                cachedTracks
                    .filter { it.artist.isNotBlank() }
                    .groupBy { it.artist.trim() }
                    .mapValues { (_, list) -> list.sumOf { it.playCount } to (list.maxOfOrNull { it.lastPlayedMs } ?: 0L) }
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                            .thenByDescending { it.value.second }
                    )
                    .forEach { if (result.size < maxArtists) addArtist(it.key) }
            }

            // Tier 4: Recently explored artists
            recentArtists.forEach { if (result.size < maxArtists) addArtist(it.name) }

            return result
        }

        /**
         * Returns the prioritized seed artist based on the 4-tier fallback cascade.
         */
        internal fun selectTopSeedArtist(
            records: List<PlaybackRecord>,
            sinceTimestampMs: Long,
            insightsEnabled: Boolean = true,
            minPlays: Int = 3,
            favoriteArtists: List<OnlineArtistSummary> = emptyList(),
            cachedTracks: List<CachedTrackEntity> = emptyList(),
            recentArtists: List<OnlineArtistSummary> = emptyList(),
        ): String? = selectSeedArtistsList(
            records = records,
            sinceTimestampMs = sinceTimestampMs,
            insightsEnabled = insightsEnabled,
            minPlays = minPlays,
            favoriteArtists = favoriteArtists,
            cachedTracks = cachedTracks,
            recentArtists = recentArtists,
            maxArtists = 1,
        ).firstOrNull()

        /**
         * Blends albums associated with the user's preferred artists ahead of catalog trending albums,
         * deduplicating by ID and normalized title.
         */
        internal fun blendTrendingAlbums(
            artistAlbums: List<OnlineAlbumSummary>,
            catalogTrendingAlbums: List<OnlineAlbumSummary>,
            insightsEnabled: Boolean = true,
            maxAlbums: Int = 15,
        ): List<OnlineAlbumSummary> {
            val result = mutableListOf<OnlineAlbumSummary>()
            val seenIds = mutableSetOf<String>()
            val seenTitles = mutableSetOf<String>()

            fun addAlbum(album: OnlineAlbumSummary) {
                if (album.id.isBlank() || album.title.isBlank()) return
                val normTitle = album.title.trim().lowercase()
                if (seenIds.add(album.id) && seenTitles.add(normTitle)) {
                    result.add(album)
                }
            }

            if (insightsEnabled) {
                for (album in artistAlbums) {
                    if (result.size >= maxAlbums) break
                    addAlbum(album)
                }
            }
            for (album in catalogTrendingAlbums) {
                if (result.size >= maxAlbums) break
                addAlbum(album)
            }
            return result
        }

        /**
         * Interleaves songs from [seedArtist] with tracks from similar/related artists while
         * filtering out non-song artist metric artifacts.
         */
        internal fun blendSimilarArtistTracks(
            seedArtist: String,
            seedTracks: List<OnlineTrack>,
            similarTracks: List<OnlineTrack>,
            maxTracks: Int = 18,
        ): List<OnlineTrack> {
            val seedNorm = SongMatcher.normalizeArtist(seedArtist)
            val seedTitleNorm = SongMatcher.normalizeTitle(seedArtist)

            fun matchesSeedArtist(track: OnlineTrack): Boolean {
                val normArtist = SongMatcher.normalizeArtist(track.artist)
                return normArtist.isNotEmpty() && seedNorm.isNotEmpty() &&
                    (normArtist == seedNorm || normArtist.contains(seedNorm) || normArtist.startsWith(seedNorm))
            }

            fun isUnrelatedTitleConfusion(track: OnlineTrack): Boolean {
                val trackTitleNorm = SongMatcher.normalizeTitle(track.title)
                return trackTitleNorm == seedTitleNorm && !matchesSeedArtist(track)
            }

            val validSeeds = seedTracks.filter(::isValidMusicTrack).filter(::matchesSeedArtist)
            val validSimilar = similarTracks.filter(::isValidMusicTrack).filterNot(::isUnrelatedTitleConfusion)

            val primary = validSeeds.toMutableList()
            val related = mutableListOf<OnlineTrack>()
            for (t in validSimilar) {
                if (matchesSeedArtist(t)) {
                    if (primary.none { it.id == t.id || SongMatcher.normalizeTitle(it.title) == SongMatcher.normalizeTitle(t.title) }) {
                        primary.add(t)
                    }
                } else {
                    related.add(t)
                }
            }

            val result = mutableListOf<OnlineTrack>()
            val seenIds = mutableSetOf<String>()
            val seenKeys = mutableSetOf<String>()

            var pIdx = 0
            var rIdx = 0
            while (result.size < maxTracks && (pIdx < primary.size || rIdx < related.size)) {
                if (pIdx < primary.size) {
                    addUniqueTrack(result, seenIds, seenKeys, primary[pIdx++])
                }
                if (result.size < maxTracks && rIdx < related.size) {
                    addUniqueTrack(result, seenIds, seenKeys, related[rIdx++])
                }
                if (result.size < maxTracks && rIdx < related.size) {
                    addUniqueTrack(result, seenIds, seenKeys, related[rIdx++])
                }
            }
            return result
        }

        /**
         * Builds "Quick Picks" prioritized from user playback history and favorite/contextual
         * artist tracks, falling back to catalog quick picks when local activity is sparse.
         */
        internal fun buildPersonalizedQuickPicks(
            records: List<PlaybackRecord>,
            favoriteAndContextualTracks: List<OnlineTrack>,
            catalogQuickPicks: List<OnlineTrack>,
            insightsEnabled: Boolean = true,
            maxTracks: Int = 24,
        ): List<OnlineTrack> {
            val result = mutableListOf<OnlineTrack>()
            val seenIds = mutableSetOf<String>()
            val seenKeys = mutableSetOf<String>()

            if (insightsEnabled && (records.isNotEmpty() || favoriteAndContextualTracks.isNotEmpty())) {
                val rankedHistory = groupValidPlaybackRecords(records)
                    .map { group ->
                        Triple(pickRepresentativeTrack(group), group.size, group.maxOf { it.timestamp })
                    }
                    .sortedWith(
                        compareByDescending<Triple<OnlineTrack, Int, Long>> { it.second }
                            .thenByDescending { it.third }
                    )
                    .map { it.first }

                var hIdx = 0
                var cIdx = 0
                while (result.size < maxTracks && (hIdx < rankedHistory.size || cIdx < favoriteAndContextualTracks.size)) {
                    if (hIdx < rankedHistory.size) {
                        addUniqueTrack(result, seenIds, seenKeys, rankedHistory[hIdx++])
                    }
                    if (result.size < maxTracks && cIdx < favoriteAndContextualTracks.size) {
                        addUniqueTrack(result, seenIds, seenKeys, favoriteAndContextualTracks[cIdx++])
                    }
                }
            }

            for (track in catalogQuickPicks) {
                if (result.size >= maxTracks) break
                addUniqueTrack(result, seenIds, seenKeys, track)
            }

            return result
        }

        /**
         * Orders a section queue starting at [selectedTrack] followed by the remaining tracks in sequence.
         */
        internal fun buildOrderedSectionQueue(
            selectedTrack: OnlineTrack,
            sectionTracks: List<OnlineTrack>,
        ): List<OnlineTrack> {
            if (sectionTracks.isEmpty()) return listOf(selectedTrack)
            val idx = sectionTracks.indexOfFirst {
                it.id == selectedTrack.id ||
                    SongMatcher.matches(it.title, it.artist, selectedTrack.title, selectedTrack.artist)
            }
            if (idx == -1) return listOf(selectedTrack) + sectionTracks
            return listOf(selectedTrack) + sectionTracks.drop(idx + 1) + sectionTracks.take(idx)
        }

        /**
         * Filters and deduplicates contextual radio candidates around [seedTrack], keeping the seed first
         * and rejecting unrelated tracks whose title matches the artist name.
         */
        internal fun filterContextualRadioTracks(
            seedTrack: OnlineTrack,
            candidates: List<OnlineTrack>,
            maxTracks: Int = 25,
        ): List<OnlineTrack> {
            val result = mutableListOf<OnlineTrack>()
            val seenIds = mutableSetOf<String>()
            val seenKeys = mutableSetOf<String>()
            if (isValidMusicTrack(seedTrack)) {
                addUniqueTrack(result, seenIds, seenKeys, seedTrack)
            } else {
                result.add(seedTrack)
                seenIds.add(seedTrack.id)
            }

            val artistNorm = SongMatcher.normalizeArtist(seedTrack.artist)
            val artistTitleNorm = SongMatcher.normalizeTitle(seedTrack.artist)

            for (t in candidates) {
                if (result.size >= maxTracks) break
                if (!isValidMusicTrack(t)) continue
                val tTitleNorm = SongMatcher.normalizeTitle(t.title)
                val tArtistNorm = SongMatcher.normalizeArtist(t.artist)
                val matchesSeedArtist = artistNorm.isNotEmpty() && tArtistNorm.isNotEmpty() &&
                    (tArtistNorm == artistNorm || tArtistNorm.contains(artistNorm) || tArtistNorm.startsWith(artistNorm))
                if (artistTitleNorm.isNotEmpty() && tTitleNorm == artistTitleNorm && !matchesSeedArtist) {
                    continue
                }
                addUniqueTrack(result, seenIds, seenKeys, t)
            }
            return result
        }

        /**
         * Ranks the user's most played tracks from on-device playback records for personal Top Charts.
         * Returns empty list when history is absent or insights are disabled (triggering cold-start fallback).
         */
        internal fun buildUserTopTrends(
            records: List<PlaybackRecord>,
            insightsEnabled: Boolean = true,
            maxTracks: Int = 20,
        ): List<OnlineTrack> {
            if (!insightsEnabled || records.isEmpty()) return emptyList()
            val result = mutableListOf<OnlineTrack>()
            val seenIds = mutableSetOf<String>()
            val seenKeys = mutableSetOf<String>()

            val ranked = groupValidPlaybackRecords(records)
                .map { group ->
                    Triple(pickRepresentativeTrack(group), group.size, group.maxOf { it.timestamp })
                }
                .sortedWith(
                    compareByDescending<Triple<OnlineTrack, Int, Long>> { it.second }
                        .thenByDescending { it.third }
                )
                .map { it.first }

            for (track in ranked) {
                if (result.size >= maxTracks) break
                addUniqueTrack(result, seenIds, seenKeys, track)
            }
            return result
        }

        /**
         * Selects the Hero Spotlight track dynamically:
         * - Cold-start (no playback history or insights disabled): national trending #1.
         * - With listening history: personalized recommendation adapted to the time of day.
         */
        internal fun selectHeroSpotlight(
            records: List<PlaybackRecord>,
            becauseYouListenTracks: List<OnlineTrack>,
            favoriteArtistTracks: List<OnlineTrack>,
            feed: HomeFeedData,
            currentTimeOfDay: Int,
            currentDayOfWeek: Int,
            insightsEnabled: Boolean = true,
        ): HeroSpotlightState? {
            val timeBadgeRes = when (currentTimeOfDay) {
                PlaybackRecord.TIME_MORNING -> org.nov0caina.sounder.R.string.lbl_hero_spotlight_time_morning
                PlaybackRecord.TIME_AFTERNOON -> org.nov0caina.sounder.R.string.lbl_hero_spotlight_time_afternoon
                else -> org.nov0caina.sounder.R.string.lbl_hero_spotlight_time_evening
            }

            if (insightsEnabled && (records.isNotEmpty() || becauseYouListenTracks.isNotEmpty() || favoriteArtistTracks.isNotEmpty())) {
                val validPersonalized = (becauseYouListenTracks + favoriteArtistTracks).filter(::isValidMusicTrack)
                val timeMatchedRecord = groupValidPlaybackRecords(records)
                    .map { group ->
                        val timeMatches = group.count { it.timeOfDay == currentTimeOfDay }
                        val dayMatches = group.count { it.dayOfWeek == currentDayOfWeek }
                        val score = (group.size * 1.0) + (timeMatches * 3.0) + (dayMatches * 1.5)
                        val latestTs = group.maxOf { it.timestamp }
                        Triple(pickRepresentativeTrack(group), score, latestTs)
                    }
                    .sortedWith(
                        compareByDescending<Triple<OnlineTrack, Double, Long>> { it.second }
                            .thenByDescending { it.third }
                    )
                    .firstOrNull()
                    ?.first

                val spotlightTrack = validPersonalized.firstOrNull { cand ->
                    cand.id != timeMatchedRecord?.id
                } ?: timeMatchedRecord ?: validPersonalized.firstOrNull()

                if (spotlightTrack != null) {
                    return HeroSpotlightState(
                        track = spotlightTrack,
                        isPersonalized = true,
                        badgeResId = timeBadgeRes,
                    )
                }
            }

            val coldStartTrack = feed.trendingTracks.firstOrNull() ?: return null
            return HeroSpotlightState(
                track = coldStartTrack,
                isPersonalized = false,
                badgeResId = org.nov0caina.sounder.R.string.header_hero_spotlight_tag,
            )
        }
    }
}
