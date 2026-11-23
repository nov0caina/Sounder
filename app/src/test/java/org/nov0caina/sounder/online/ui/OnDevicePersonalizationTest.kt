package org.nov0caina.sounder.online.ui

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.R
import org.nov0caina.sounder.online.db.CachedTrackEntity
import org.nov0caina.sounder.online.db.PlaybackRecord
import org.nov0caina.sounder.online.model.HomeFeedData
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineTrack

class OnDevicePersonalizationTest {

    private fun track(id: String, title: String, artist: String) =
        OnlineTrack(
            id = id,
            title = title,
            artist = artist,
            album = null,
            durationMs = 200_000L,
            artworkUrl = "https://img/$id.jpg",
        )

    private fun record(
        trackId: String,
        title: String,
        artist: String,
        timeOfDay: Int,
        dayOfWeek: Int = Calendar.MONDAY,
        timestamp: Long = 10_000L,
    ) = PlaybackRecord(
        trackId = trackId,
        title = title,
        artist = artist,
        artworkUrl = "https://img/$trackId.jpg",
        durationMs = 200_000L,
        playedMs = 180_000L,
        timestamp = timestamp,
        timeOfDay = timeOfDay,
        dayOfWeek = dayOfWeek,
        sourceType = PlaybackRecord.SOURCE_STREAM,
    )

    @Test
    fun scoreAndSelectQuickAccessTracks_adaptsToCurrentTimeOfDayAndDayOfWeek() {
        val records = listOf(
            // Track 1: played 3 times at NIGHT on TUESDAY
            record("night1", "Night Groove", "Artist N", PlaybackRecord.TIME_NIGHT, Calendar.TUESDAY, 1_000L),
            record("night1", "Night Groove", "Artist N", PlaybackRecord.TIME_NIGHT, Calendar.TUESDAY, 2_000L),
            record("night1", "Night Groove", "Artist N", PlaybackRecord.TIME_NIGHT, Calendar.TUESDAY, 3_000L),
            // Track 2: played twice in MORNING on MONDAY -> higher contextual score at Morning+Monday
            record("morn1", "Sunrise Riff", "Artist M", PlaybackRecord.TIME_MORNING, Calendar.MONDAY, 4_000L),
            record("morn1", "Sunrise Riff", "Artist M", PlaybackRecord.TIME_MORNING, Calendar.MONDAY, 5_000L),
        )
        val feed = HomeFeedData(
            quickPicks = listOf(
                track("qp1", "Editorial 1", "Ed Artist 1"),
                track("qp2", "Editorial 2", "Ed Artist 2"),
                track("qp3", "Editorial 3", "Ed Artist 3"),
                track("qp4", "Editorial 4", "Ed Artist 4"),
            )
        )

        // In Morning + Monday: morn1 scores 2*1.0 + 2*2.5 + 2*1.5 = 10.0 vs night1 = 3*1.0 = 3.0
        val morningList = HomeFeedViewModel.scoreAndSelectQuickAccessTracks(
            records = records,
            feed = feed,
            currentTimeOfDay = PlaybackRecord.TIME_MORNING,
            currentDayOfWeek = Calendar.MONDAY,
            insightsEnabled = true,
            maxTracks = 5,
        )
        assertEquals(listOf("morn1", "night1", "qp1", "qp2", "qp3"), morningList.map { it.id })

        // In Night + Tuesday: night1 outranks morn1
        val nightList = HomeFeedViewModel.scoreAndSelectQuickAccessTracks(
            records = records,
            feed = feed,
            currentTimeOfDay = PlaybackRecord.TIME_NIGHT,
            currentDayOfWeek = Calendar.TUESDAY,
            insightsEnabled = true,
            maxTracks = 5,
        )
        assertEquals(listOf("night1", "morn1", "qp1", "qp2", "qp3"), nightList.map { it.id })
    }

    @Test
    fun scoreAndSelectQuickAccessTracks_disabledFallsBackToEditorialFeed() {
        val records = listOf(
            record("hist1", "History Song", "History Artist", PlaybackRecord.TIME_MORNING),
        )
        val feed = HomeFeedData(
            quickPicks = listOf(
                track("qp1", "Editorial 1", "Ed Artist 1"),
                track("qp2", "Editorial 2", "Ed Artist 2"),
            )
        )

        val result = HomeFeedViewModel.scoreAndSelectQuickAccessTracks(
            records = records,
            feed = feed,
            currentTimeOfDay = PlaybackRecord.TIME_MORNING,
            currentDayOfWeek = Calendar.MONDAY,
            insightsEnabled = false,
            maxTracks = 5,
        )
        assertEquals(listOf("qp1", "qp2"), result.map { it.id })
    }

    @Test
    fun selectTopSeedArtist_requiresThresholdAndRespectsPrivacyToggle() {
        val now = 1_000_000L
        val tooFew = listOf(
            record("1", "S1", "Alpha", PlaybackRecord.TIME_MORNING, timestamp = now),
            record("2", "S2", "Alpha", PlaybackRecord.TIME_MORNING, timestamp = now),
        )
        assertNull(HomeFeedViewModel.selectTopSeedArtist(tooFew, sinceTimestampMs = now - 500_000L, minPlays = 3))

        val enough = tooFew + record("3", "S3", "Alpha", PlaybackRecord.TIME_AFTERNOON, timestamp = now)
        assertEquals(
            "Alpha",
            HomeFeedViewModel.selectTopSeedArtist(enough, sinceTimestampMs = now - 500_000L, insightsEnabled = true, minPlays = 3)
        )
        assertNull(
            HomeFeedViewModel.selectTopSeedArtist(enough, sinceTimestampMs = now - 500_000L, insightsEnabled = false, minPlays = 3)
        )
    }

    @Test
    fun selectTopSeedArtist_tier1_prioritizesFavoriteArtistsOverPlaybackRecords() {
        val now = 1_000_000L
        val records = listOf(
            record("1", "S1", "RecordArtist", PlaybackRecord.TIME_MORNING, timestamp = now),
            record("2", "S2", "RecordArtist", PlaybackRecord.TIME_MORNING, timestamp = now),
            record("3", "S3", "RecordArtist", PlaybackRecord.TIME_MORNING, timestamp = now),
        )
        val favs = listOf(
            OnlineArtistSummary("fav1", "FavoriteArtist", null, null)
        )

        val result = HomeFeedViewModel.selectTopSeedArtist(
            records = records,
            sinceTimestampMs = now - 500_000L,
            insightsEnabled = true,
            minPlays = 3,
            favoriteArtists = favs
        )
        assertEquals("FavoriteArtist", result)
    }

    @Test
    fun selectTopSeedArtist_tier3_fallsBackToCachedTracksWhenRecordsAreSparse() {
        val now = 1_000_000L
        val sparseRecords = listOf(
            record("1", "S1", "SparseArtist", PlaybackRecord.TIME_MORNING, timestamp = now),
        )
        val cached = listOf(
            CachedTrackEntity(
                videoId = "v1",
                title = "Cached Song",
                artist = "CachedArtist",
                album = "Album",
                durationMs = 200_000L,
                artworkUrl = null,
                localCoverPath = null,
                localLyricsPath = null,
                artistId = null,
                albumId = null,
                playCount = 5,
                lastPlayedMs = now
            )
        )

        val result = HomeFeedViewModel.selectTopSeedArtist(
            records = sparseRecords,
            sinceTimestampMs = now - 500_000L,
            insightsEnabled = true,
            minPlays = 3,
            favoriteArtists = emptyList(),
            cachedTracks = cached
        )
        assertEquals("CachedArtist", result)
    }

    @Test
    fun selectTopSeedArtist_tier4_fallsBackToRecentArtistsWhenCacheIsEmpty() {
        val now = 1_000_000L
        val recents = listOf(
            OnlineArtistSummary("rec1", "RecentSearchArtist", null, null)
        )

        val result = HomeFeedViewModel.selectTopSeedArtist(
            records = emptyList(),
            sinceTimestampMs = now - 500_000L,
            insightsEnabled = true,
            minPlays = 3,
            favoriteArtists = emptyList(),
            cachedTracks = emptyList(),
            recentArtists = recents
        )
        assertEquals("RecentSearchArtist", result)
    }

    @Test
    fun selectTopSeedArtist_returnsNullWhenAllSourcesAreEmpty() {
        val result = HomeFeedViewModel.selectTopSeedArtist(
            records = emptyList(),
            sinceTimestampMs = 0L,
            insightsEnabled = true,
            minPlays = 3,
            favoriteArtists = emptyList(),
            cachedTracks = emptyList(),
            recentArtists = emptyList()
        )
        assertNull(result)
    }

    @Test
    fun blendTrendingAlbums_prioritizesArtistAlbumsAndFallsBackToCatalog() {
        val artistAlbums = listOf(
            OnlineAlbumSummary(id = "alb1", title = "Lateralus", year = "2001"),
            OnlineAlbumSummary(id = "alb2", title = "10,000 Days", year = "2006"),
        )
        val catalogAlbums = listOf(
            OnlineAlbumSummary(id = "alb2_dup", title = "10,000 Days", year = "2006"),
            OnlineAlbumSummary(id = "cat1", title = "Top Pop Record", year = "2026"),
        )

        val blended = HomeFeedViewModel.blendTrendingAlbums(
            artistAlbums = artistAlbums,
            catalogTrendingAlbums = catalogAlbums,
            insightsEnabled = true,
            maxAlbums = 10,
        )
        assertEquals(listOf("alb1", "alb2", "cat1"), blended.map { it.id })
    }

    @Test
    fun blendTrendingAlbums_respectsInsightsDisabledFlag() {
        val artistAlbums = listOf(
            OnlineAlbumSummary(id = "alb1", title = "Lateralus", year = "2001"),
        )
        val catalogAlbums = listOf(
            OnlineAlbumSummary(id = "cat1", title = "Catalog Album", year = "2026"),
        )

        val blended = HomeFeedViewModel.blendTrendingAlbums(
            artistAlbums = artistAlbums,
            catalogTrendingAlbums = catalogAlbums,
            insightsEnabled = false,
        )
        assertEquals(listOf("cat1"), blended.map { it.id })
    }

    @Test
    fun blendSimilarArtistTracks_interleavesSeedAndSimilarArtistsAndFiltersMetrics() {
        val seedTracks = listOf(
            track("s1", "Sober", "TOOL"),
            track("s2", "Schism", "TOOL"),
            track("metric_bad", "Artist Header", "7.01M monthly audience"),
        )
        val similarTracks = listOf(
            track("r1", "Judith", "A Perfect Circle"),
            track("r2", "Change", "Deftones"),
            track("r3", "Them Bones", "Alice in Chains"),
        )

        val result = HomeFeedViewModel.blendSimilarArtistTracks(
            seedArtist = "TOOL",
            seedTracks = seedTracks,
            similarTracks = similarTracks,
            maxTracks = 10,
        )

        assertTrue(result.any { it.artist == "TOOL" })
        assertTrue(result.any { it.artist != "TOOL" })
        assertTrue(result.none { it.id == "metric_bad" })
        // Check interleaving: seed (s1), similar (r1, r2), seed (s2), similar (r3)
        assertEquals(listOf("s1", "r1", "r2", "s2", "r3"), result.map { it.id })
    }

    @Test
    fun blendSimilarArtistTracks_excludesSongsTitledWithSeedArtistByUnrelatedArtists() {
        val seedTracks = listOf(
            track("s1", "Twenty Years", "Placebo"),
            // Title matches "Placebo" but artist is Kenia OS or TAXMEN
            track("s2_bad", "Placebo", "Kenia OS"),
            track("s3_bad", "PLACEBO", "TAXMEN, PUNPEE, OM..."),
        )
        val similarTracks = listOf(
            track("r1", "Song 2", "Blur"),
            track("r2", "Special K", "Placebo"),
            // Unrelated artist with song titled "Placebo" in similar tracks
            track("r3_bad", "Placebo", "Random Artist"),
        )

        val result = HomeFeedViewModel.blendSimilarArtistTracks(
            seedArtist = "Placebo",
            seedTracks = seedTracks,
            similarTracks = similarTracks,
            maxTracks = 10,
        )

        val resultIds = result.map { it.id }
        assertTrue("Must include legitimate Placebo track", resultIds.contains("s1"))
        assertTrue("Must include similar artist track (Blur)", resultIds.contains("r1"))
        assertTrue("Must include legitimate Placebo track from similar pool", resultIds.contains("r2"))

        // Strictly verify that Kenia OS, TAXMEN, and Random Artist tracks titled 'Placebo' are excluded
        assertTrue("Must exclude Kenia OS song titled Placebo", !resultIds.contains("s2_bad"))
        assertTrue("Must exclude TAXMEN song titled PLACEBO", !resultIds.contains("s3_bad"))
        assertTrue("Must exclude Random Artist song titled Placebo", !resultIds.contains("r3_bad"))
    }

    @Test
    fun buildPersonalizedQuickPicks_prioritizesUserHistoryAndFavoritesOverCatalog() {
        val records = listOf(
            record("h1", "History Heavy", "Artist A", PlaybackRecord.TIME_MORNING, timestamp = 1_000L),
            record("h1", "History Heavy", "Artist A", PlaybackRecord.TIME_MORNING, timestamp = 2_000L),
            record("h2", "History Light", "Artist B", PlaybackRecord.TIME_NIGHT, timestamp = 3_000L),
        )
        val favTracks = listOf(
            track("f1", "Fav Artist Track", "Fav Artist"),
        )
        val catalog = listOf(
            track("c1", "Generic Pop 1", "Pop Star"),
            track("c2", "Generic Pop 2", "Pop Star 2"),
        )

        val result = HomeFeedViewModel.buildPersonalizedQuickPicks(
            records = records,
            favoriteAndContextualTracks = favTracks,
            catalogQuickPicks = catalog,
            insightsEnabled = true,
            maxTracks = 10,
        )
        // Interleaves ranked history (h1, h2) with favTracks (f1), then appends catalog (c1, c2)
        assertEquals(listOf("h1", "f1", "h2", "c1", "c2"), result.map { it.id })
    }

    @Test
    fun buildPersonalizedQuickPicks_fallsBackToCatalogOnColdStartOrDisabled() {
        val records = listOf(
            record("h1", "History Song", "Artist A", PlaybackRecord.TIME_MORNING, timestamp = 1_000L),
        )
        val catalog = listOf(
            track("c1", "Generic Pop 1", "Pop Star"),
            track("c2", "Generic Pop 2", "Pop Star 2"),
        )

        val disabledResult = HomeFeedViewModel.buildPersonalizedQuickPicks(
            records = records,
            favoriteAndContextualTracks = listOf(track("f1", "Fav", "Fav Artist")),
            catalogQuickPicks = catalog,
            insightsEnabled = false,
        )
        assertEquals(listOf("c1", "c2"), disabledResult.map { it.id })

        val coldStartResult = HomeFeedViewModel.buildPersonalizedQuickPicks(
            records = emptyList(),
            favoriteAndContextualTracks = emptyList(),
            catalogQuickPicks = catalog,
            insightsEnabled = true,
        )
        assertEquals(listOf("c1", "c2"), coldStartResult.map { it.id })
    }

    @Test
    fun buildOrderedSectionQueue_preservesFull24CarouselStartingAtClickedTrack() {
        val picks = (1..24).map { track("qp$it", "Pick $it", "Artist $it") }
        val selected = picks[4] // qp5

        val queue = HomeFeedViewModel.buildOrderedSectionQueue(selected, picks)
        assertEquals(24, queue.size)
        assertEquals("qp5", queue.first().id)
        assertEquals("qp6", queue[1].id)
        assertEquals("qp24", queue[19].id)
        assertEquals("qp1", queue[20].id)
        assertEquals("qp4", queue.last().id)
    }

    @Test
    fun filterContextualRadioTracks_isolatesRadioAndFiltersTitleConfusion() {
        val seed = track("seed1", "Let It Die", "Ozzy Osbourne")
        val candidates = listOf(
            track("r1", "Crazy Train", "Ozzy Osbourne"),
            track("r2", "War Pigs", "Black Sabbath"),
            track("confused", "Ozzy Osbourne", "Unrelated Pop Band"), // title matches artist name
            track("metric", "Invalid Artist Track", "45M monthly audience"),
            track("seed1", "Let It Die", "Ozzy Osbourne"), // duplicate seed
        )

        val radio = HomeFeedViewModel.filterContextualRadioTracks(seed, candidates)
        assertEquals(listOf("seed1", "r1", "r2"), radio.map { it.id })
        assertFalse(radio.any { it.id == "confused" || it.id == "metric" })
    }

    @Test
    fun buildUserTopTrends_ranksByPlayCountAndFallsBackOnColdStart() {
        val records = listOf(
            record("t1", "Frequent Song", "Artist 1", PlaybackRecord.TIME_MORNING, timestamp = 1_000L),
            record("t1", "Frequent Song", "Artist 1", PlaybackRecord.TIME_AFTERNOON, timestamp = 2_000L),
            record("t1", "Frequent Song", "Artist 1", PlaybackRecord.TIME_NIGHT, timestamp = 3_000L),
            record("t2", "Second Song", "Artist 2", PlaybackRecord.TIME_EVENING, timestamp = 5_000L),
            record("t2", "Second Song", "Artist 2", PlaybackRecord.TIME_EVENING, timestamp = 6_000L),
            record("t3", "Recent Single", "Artist 3", PlaybackRecord.TIME_NIGHT, timestamp = 9_000L),
        )

        val personalTrends = HomeFeedViewModel.buildUserTopTrends(records, insightsEnabled = true)
        assertEquals(listOf("t1", "t2", "t3"), personalTrends.map { it.id })

        val coldStart = HomeFeedViewModel.buildUserTopTrends(emptyList(), insightsEnabled = true)
        assertTrue(coldStart.isEmpty())

        val disabled = HomeFeedViewModel.buildUserTopTrends(records, insightsEnabled = false)
        assertTrue(disabled.isEmpty())
    }

    @Test
    fun selectHeroSpotlight_adaptsToHistoryAndTimeOrColdStartTrending() {
        val nationalTrending = track("nat1", "Sin Fin De Lucro", "LA DIFERENCIA")
        val feed = HomeFeedData(trendingTracks = listOf(nationalTrending))

        // Cold start: national #1
        val coldStart = HomeFeedViewModel.selectHeroSpotlight(
            records = emptyList(),
            becauseYouListenTracks = emptyList(),
            favoriteArtistTracks = emptyList(),
            feed = feed,
            currentTimeOfDay = PlaybackRecord.TIME_MORNING,
            currentDayOfWeek = Calendar.MONDAY,
            insightsEnabled = true,
        )
        assertEquals("nat1", coldStart?.track?.id)
        assertFalse(coldStart?.isPersonalized ?: true)
        assertEquals(R.string.header_hero_spotlight_tag, coldStart?.badgeResId)

        // With history in afternoon: personalized spotlight
        val records = listOf(
            record("fav1", "Let It Die", "Ozzy Osbourne", PlaybackRecord.TIME_AFTERNOON, timestamp = 1_000L)
        )
        val recTrack = track("rec1", "Mama I'm Coming Home", "Ozzy Osbourne")
        val personalized = HomeFeedViewModel.selectHeroSpotlight(
            records = records,
            becauseYouListenTracks = listOf(recTrack),
            favoriteArtistTracks = emptyList(),
            feed = feed,
            currentTimeOfDay = PlaybackRecord.TIME_AFTERNOON,
            currentDayOfWeek = Calendar.MONDAY,
            insightsEnabled = true,
        )
        assertEquals("rec1", personalized?.track?.id)
        assertTrue(personalized?.isPersonalized ?: false)
        assertEquals(R.string.lbl_hero_spotlight_time_afternoon, personalized?.badgeResId)
    }

    @Test
    fun discoverActivityMoods_areDeduplicatedFromGenreCards() {
        val moodIds = DiscoverViewModel.ACTIVITY_MOODS.map { it.id }
        assertEquals(listOf("all", "workout", "focus", "party", "chill"), moodIds)
        assertFalse("Mood chips must not duplicate Rock genre", moodIds.contains("rock"))
        assertFalse("Mood chips must not duplicate Latin genre", moodIds.contains("latin"))
    }
}
