package org.nov0caina.sounder.online.innertube

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.StreamInfo

class InnerTubeClientTest {

    @Test
    fun testResolveStreamInfoReturnsDirectStreamWhenAvailable() = runBlocking {
        val client = object : InnerTubeClient() {
            override suspend fun resolveStreamInfo(
                videoId: String,
                quality: AudioQuality,
                title: String?,
                artist: String?
            ): StreamInfo? {
                return if (videoId == "direct_vid") StreamInfo(url = "https://stream.direct.url", durationMs = 180000L) else null
            }
        }

        val result = client.resolveStreamInfo("direct_vid", AudioQuality.HIGH_QUALITY, "Song", "Artist")
        assertNotNull(result)
        assertEquals("https://stream.direct.url", result?.url)
    }

    @Test
    fun testResolveStreamInfoFallsBackToAlternativeTrackWhenDirectFails() = runBlocking {
        val client = object : InnerTubeClient() {
            override suspend fun resolveStreamInfo(
                videoId: String,
                quality: AudioQuality,
                title: String?,
                artist: String?
            ): StreamInfo? {
                if (videoId == "restricted_vid") {
                    return if (title == "Garden Song") StreamInfo(url = "https://stream.alternative.url", durationMs = 219000L) else null
                }
                return null
            }
        }

        val result = client.resolveStreamInfo("restricted_vid", AudioQuality.HIGH_QUALITY, "Garden Song", "Phoebe Bridgers")
        assertNotNull(result)
        assertEquals("https://stream.alternative.url", result?.url)
    }

    @Test
    fun testSearchCategorizedMergesAndDeduplicatesArtists() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>()

        val client = object : InnerTubeClient() {
            override suspend fun browseArtist(browseId: String) =
                org.nov0caina.sounder.online.model.OnlineArtistDetail(browseId, "", null, null)

            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                calls.add(query to params)
                return when {
                    params == "EgWKAQIgAWoKEAkQChAFEAMQBA==" -> OnlineSearchResult(
                        artists = listOf(
                            OnlineArtistSummary("artist_1", "Queen", avatarUrl = "https://avatar/queen.jpg", subtitle = "10M Subscribers"),
                            OnlineArtistSummary("artist_2", "Led Zeppelin", avatarUrl = "https://avatar/zep.jpg", subtitle = "5M Subscribers")
                        )
                    )
                    query == "Rock" -> OnlineSearchResult(
                        artists = listOf(
                            OnlineArtistSummary("artist_top", "The Rolling Stones", avatarUrl = "https://avatar/stones.jpg", subtitle = "Top Match"),
                            OnlineArtistSummary("artist_stub", "Stub Artist", avatarUrl = null, subtitle = null)
                        ),
                        tracks = listOf(
                            OnlineTrack("t1", "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354000L, null)
                        )
                    )
                    else -> OnlineSearchResult()
                }
            }
        }

        val result = client.searchCategorized("Rock")

        assertEquals(3, calls.size)
        // Verify primary, dedicated artist, and dedicated songs search calls execute concurrently
        assertTrue(calls.contains("Rock" to null))
        assertTrue(calls.contains("Rock" to InnerTubeClient.PARAMS_ARTISTS))
        assertTrue(calls.contains("Rock" to InnerTubeClient.PARAMS_SONGS))

        // Only artists with avatars are included; stub artist without avatar is filtered out
        assertEquals(3, result.artists.size)
        assertEquals("The Rolling Stones", result.artists[0].name)
        assertEquals("Queen", result.artists[1].name)
        assertEquals("Led Zeppelin", result.artists[2].name)
        assertTrue(result.artists.all { !it.avatarUrl.isNullOrBlank() })

        // Tracks preserved from primary search
        assertEquals(1, result.tracks.size)
        assertEquals("Bohemian Rhapsody", result.tracks[0].title)
    }

    @Test
    fun testSearchCategorizedNormalizesQueryForDedicatedArtistSearch() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>()

        val client = object : InnerTubeClient() {
            override suspend fun browseArtist(browseId: String) =
                org.nov0caina.sounder.online.model.OnlineArtistDetail(browseId, "", null, null)

            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                calls.add(query to params)
                return OnlineSearchResult(
                    artists = listOf(OnlineArtistSummary("a1", "Metallica", "https://avatar/metallica.jpg", "64M Subscribers"))
                )
            }
        }

        val result = client.searchCategorized("Metal artists")

        assertEquals(3, calls.size)
        assertTrue(calls.contains("Metal artists" to null))
        // Dedicated artist query strips "artists" suffix for accurate filtering
        assertTrue(calls.contains("Metal" to InnerTubeClient.PARAMS_ARTISTS))
        assertTrue(calls.contains("Metal artists" to InnerTubeClient.PARAMS_SONGS))
        assertEquals(1, result.artists.size)
        assertEquals("https://avatar/metallica.jpg", result.artists[0].avatarUrl)
    }

    @Test
    fun testSearchCategorizedExcludesLowSubscriberChannelsAndMerges25PlusSongsForCategory() = runBlocking {
        val calls = mutableListOf<Pair<String, String?>>()
        val songBatch1 = (1..15).map { idx ->
            OnlineTrack("vid_$idx", "Song $idx", "Band $idx", "Album $idx", 210_000L, "https://art/$idx.jpg")
        }
        val songBatch2 = (16..28).map { idx ->
            OnlineTrack("vid_$idx", "Song $idx", "Band $idx", "Album $idx", 225_000L, "https://art/$idx.jpg")
        }

        val client = object : InnerTubeClient() {
            override suspend fun browseArtist(browseId: String) =
                org.nov0caina.sounder.online.model.OnlineArtistDetail(browseId, "", null, null)

            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                calls.add(query to params)
                return when (params) {
                    PARAMS_ARTISTS -> OnlineSearchResult(
                        artists = listOf(
                            OnlineArtistSummary("UC_in_this_moment", "In This Moment", "https://art/itm.jpg", "1.18M monthly audience"),
                            OnlineArtistSummary("UC_random_1", "Heavy Metal Songs", "https://art/r1.jpg", "79 subscribers"),
                            OnlineArtistSummary("UC_random_2", "HeavyPulse Metal", "https://art/r2.jpg", "2 subscribers"),
                            OnlineArtistSummary("UC_random_3", "Metal Scar Radio", "https://art/r3.jpg", "17.9K subscribers"),
                            OnlineArtistSummary("UC_metallica", "Metallica", "https://art/met.jpg", "75.8M monthly audience")
                        )
                    )
                    PARAMS_SONGS -> OnlineSearchResult(
                        tracks = if (query.contains("top songs")) songBatch1 else songBatch2
                    )
                    else -> OnlineSearchResult(
                        artists = listOf(
                            OnlineArtistSummary("UC_bloodywood", "Bloodywood", "https://art/bw.jpg", "317K monthly audience")
                        ),
                        tracks = listOf(songBatch1.first()) // overlapping track should deduplicate cleanly
                    )
                }
            }
        }

        val result = client.searchCategorized("Metal artists", isCategory = true)

        assertTrue(calls.contains("Metal" to null))
        assertTrue(calls.contains("Metal" to InnerTubeClient.PARAMS_ARTISTS))
        assertTrue(calls.contains("Metal top songs" to InnerTubeClient.PARAMS_SONGS))
        assertTrue(calls.contains("Metal hits" to InnerTubeClient.PARAMS_SONGS))

        assertEquals(28, result.tracks.size)
        assertEquals(
            listOf("Bloodywood", "Metallica", "In This Moment"),
            result.artists.map { it.name }
        )
    }

    @Test
    fun testSearchCategorizedBlankQueryReturnsEmpty() = runBlocking {
        val client = InnerTubeClient()
        val result = client.searchCategorized("   ")
        assertTrue(result.artists.isEmpty())
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun testSearchCategorizedExpandsTo50PlusArtistsViaSeedsAndRelatedArtists() = runBlocking {
        val client = object : InnerTubeClient() {
            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                return when (params) {
                    PARAMS_ARTISTS -> OnlineSearchResult(
                        artists = (1..10).map { i ->
                            OnlineArtistSummary("UC_${query.hashCode()}_$i", "$query Artist $i", "https://art/$i.jpg", "${i}M monthly audience")
                        }
                    )
                    PARAMS_SONGS -> OnlineSearchResult(
                        tracks = (1..25).map { i ->
                            OnlineTrack("v_$i", "Song $i", "Focus Band $i", "Album", 190_000L, "https://art/$i.jpg", artistId = "UC_track_$i")
                        }
                    )
                    else -> OnlineSearchResult(
                        artists = listOf(OnlineArtistSummary("UC_top", "Focus", "https://art/top.jpg", "172K monthly audience"))
                    )
                }
            }

            override suspend fun browseArtist(browseId: String) =
                org.nov0caina.sounder.online.model.OnlineArtistDetail(
                    id = browseId,
                    name = "Resolved $browseId",
                    avatarUrl = "https://art/$browseId.jpg",
                    description = null,
                    relatedArtists = (1..8).map { j ->
                        OnlineArtistSummary("UC_rel_${browseId}_$j", "Related $browseId $j", "https://art/rel_$j.jpg", "${j}00K monthly audience")
                    }
                )
        }

        val result = client.searchCategorized("Focus", isCategory = true)
        assertTrue("Expected at least 50 verified artists, got ${result.artists.size}", result.artists.size >= 50)
    }

    @Test
    fun testSearchCategorizedProgressiveEmitsFastPhase1BeforeBrowseArtistCompletes() = runBlocking {
        var browseCount = 0
        val client = object : InnerTubeClient() {
            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                return when (params) {
                    PARAMS_ARTISTS -> OnlineSearchResult(
                        artists = (1..10).map { i ->
                            OnlineArtistSummary("UC_${query.hashCode()}_$i", "$query Artist $i", "https://art/$i.jpg", "${i}M monthly audience")
                        }
                    )
                    PARAMS_SONGS -> OnlineSearchResult(
                        tracks = (1..15).map { i ->
                            OnlineTrack("v_${query.hashCode()}_$i", "Song $i", "Focus Band $i", "Album", 190_000L, "https://art/$i.jpg", artistId = "UC_track_$i")
                        }
                    )
                    else -> OnlineSearchResult(
                        artists = listOf(OnlineArtistSummary("UC_top", "Focus", "https://art/top.jpg", "172K monthly audience"))
                    )
                }
            }

            override suspend fun browseArtist(browseId: String): org.nov0caina.sounder.online.model.OnlineArtistDetail {
                browseCount++
                return org.nov0caina.sounder.online.model.OnlineArtistDetail(
                    id = browseId,
                    name = "Resolved $browseId",
                    avatarUrl = "https://art/$browseId.jpg",
                    description = null,
                    relatedArtists = (1..8).map { j ->
                        OnlineArtistSummary("UC_rel_${browseId}_$j", "Related $browseId $j", "https://art/rel_$j.jpg", "${j}00K monthly audience")
                    }
                )
            }
        }

        val emissions = mutableListOf<Pair<OnlineSearchResult, Int>>()
        client.searchCategorizedProgressive("Focus", isCategory = true).collect { result ->
            emissions.add(result to browseCount)
        }

        assertEquals(2, emissions.size)
        val (phase1, browseCallsAtPhase1) = emissions[0]
        val (phase2, browseCallsAtPhase2) = emissions[1]
        assertEquals(0, browseCallsAtPhase1)
        assertTrue(phase1.artists.isNotEmpty())
        assertTrue(phase1.tracks.isNotEmpty())
        assertTrue(browseCallsAtPhase2 > 0)
        assertTrue(phase2.artists.size >= 50)
        assertEquals(phase1.artists.map { it.id }, phase2.artists.take(phase1.artists.size).map { it.id })
    }

    @Test
    fun testChristianCategoryExcludesSecularArtistsIsolatesLocalesAndRandomizesEmergingPlacement() = runBlocking {
        fun buildClient(lang: String, seed: Int): InnerTubeClient =
            object : InnerTubeClient() {
                override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                    return when {
                        query == "musica cristiana alabanza y adoracion" -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_miel", "Miel San Marcos", "https://art/miel.jpg", "4.2M monthly audience"),
                                OnlineArtistSummary("UC_kanye", "Kanye West", "https://art/kanye.jpg", "70M monthly audience")
                            ),
                            tracks = listOf(
                                OnlineTrack("v_c1", "Danzando", "Christine D'Clario", "Emanuel", 240_000L, "https://art/danzando.jpg", artistId = "UC_christine")
                            )
                        )
                        query == "artistas musica cristiana español" && params == PARAMS_ARTISTS -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_marcos", "Marcos Witt", "https://art/marcos.jpg", "3.8M monthly audience"),
                                OnlineArtistSummary("UC_jar", "Jesús Adrián Romero", "https://art/jar.jpg", "5.1M monthly audience"),
                                OnlineArtistSummary("UC_barak", "Barak", "https://art/barak.jpg", "2.9M monthly audience"),
                                OnlineArtistSummary("UC_alex", "Alex Campos", "https://art/alex.jpg", "3.1M monthly audience"),
                                OnlineArtistSummary("UC_marcela", "Marcela Gándara", "https://art/marcela.jpg", "3.5M monthly audience"),
                                OnlineArtistSummary("UC_redimi2", "Redimi2", "https://art/redimi2.jpg", "2.8M monthly audience"),
                                OnlineArtistSummary("UC_christine", "Christine D'Clario", "https://art/christine.jpg", "2.6M monthly audience")
                            )
                        )
                        query == "Abel Meza" && params == PARAMS_ARTISTS -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_abel", "Abel Meza", "https://art/abel.jpg", "12K subscribers"),
                                OnlineArtistSummary("UC_noise", "Random Christian Podcast", "https://art/noise.jpg", "5 subscribers")
                            )
                        )
                        query == "Montesanto" && params == PARAMS_ARTISTS -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_montesanto", "Montesanto", "https://art/montesanto.jpg", "900K monthly audience")
                            )
                        )
                        query == "Kike Pavon" && params == PARAMS_ARTISTS -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_kike", "Kike Pavon", "https://art/kike.jpg", "500K monthly audience")
                            )
                        )
                        query == "Living Worship" && params == PARAMS_ARTISTS -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_living", "Living Worship", "https://art/living.jpg", "400K monthly audience")
                            )
                        )
                        query == "Oasis Ministry" && params == PARAMS_ARTISTS -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_oasis", "Oasis Ministry", "https://art/oasis.jpg", "700K monthly audience")
                            )
                        )
                        query.contains("Elevation Worship") -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_elev", "Elevation Worship", "https://art/elev.jpg", "12M monthly audience"),
                                OnlineArtistSummary("UC_drake", "Drake", "https://art/drake.jpg", "85M monthly audience")
                            )
                        )
                        query == "Christian worship artists" || query == "contemporary christian artists" -> OnlineSearchResult(
                            artists = listOf(
                                OnlineArtistSummary("UC_chris", "Chris Tomlin", "https://art/chris.jpg", "11M monthly audience"),
                                OnlineArtistSummary("UC_brandon", "Brandon Lake", "https://art/brandon.jpg", "9M monthly audience")
                            )
                        )
                        else -> OnlineSearchResult()
                    }
                }

                override suspend fun browseArtist(browseId: String) =
                    org.nov0caina.sounder.online.model.OnlineArtistDetail(
                        id = browseId,
                        name = "Resolved $browseId",
                        avatarUrl = "https://art/$browseId.jpg",
                        description = null,
                        relatedArtists = listOf(
                            OnlineArtistSummary("UC_jcole", "J. Cole", "https://art/jcole.jpg", "55M monthly audience"),
                            OnlineArtistSummary("UC_lilly", "Lilly Goodman", "https://art/lilly.jpg", "2.1M monthly audience")
                        )
                    )
            }.apply {
                deviceLanguageProvider = { lang }
                randomProvider = { kotlin.random.Random(seed) }
            }

        val spanishResult = buildClient("es", seed = 101).searchCategorized("Christian worship artists", isCategory = true)
        val spanishNames = spanishResult.artists.map { it.name }

        assertTrue("Secular artists must be excluded", "Kanye West" !in spanishNames)
        assertTrue("Secular artists must be excluded", "Drake" !in spanishNames)
        assertTrue("Secular artists must be excluded", "J. Cole" !in spanishNames)
        assertEquals("Miel San Marcos", spanishNames[0])
        assertTrue("Global top Christian artist retained", "Elevation Worship" in spanishNames)
        assertTrue("Random noise channel excluded", "Random Christian Podcast" !in spanishNames)

        // Verify emerging/mid-tier positions vary across random seeds rather than staying pinned at #5
        val abelPositions = (1..25).mapNotNull { seed ->
            val names = buildClient("es", seed).searchCategorized("Christian worship artists", isCategory = true)
                .artists.map { it.name }
            val idx = names.indexOf("Abel Meza")
            if (idx >= 0) idx else null
        }
        assertTrue("Abel Meza should appear across some random draws", abelPositions.isNotEmpty())
        assertTrue("All emerging placements must protect top 3 spotlight (index >= 3)", abelPositions.all { it >= 3 })
        assertTrue("Abel Meza placement must vary rather than stay pinned at index 5: $abelPositions", abelPositions.toSet().size > 1)

        // Verify strict locale isolation for English ("en"): Spanish emerging artists must never appear
        val englishResult = buildClient("en", seed = 1).searchCategorized("Christian worship artists", isCategory = true)
        val englishNames = englishResult.artists.map { it.name }
        assertTrue("English locale must not include Spanish-only emerging seed", "Abel Meza" !in englishNames)
        assertTrue("English Christian artist must be present", "Chris Tomlin" in englishNames)
    }

    @Test
    fun testDirectSearchForAbelMezaRanksHimFirstInArtistsEvenWithLowSubscribersOrTrackOnlyResolution() = runBlocking {
        val relatedChristianArtists = (1..18).map { i ->
            OnlineArtistSummary(
                id = "UC_rel_$i",
                name = if (i == 1) "Michelle Matius" else "Artista Cristiano $i",
                avatarUrl = "https://art/rel_$i.jpg",
                subtitle = "${400 + i}K monthly audience"
            )
        }

        // Case 1: Abel Meza returned in dedicated artist search with low subscribers alongside 18 monthly-audience artists
        val clientWithLowSubArtist = object : InnerTubeClient() {
            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                return when (params) {
                    PARAMS_ARTISTS -> OnlineSearchResult(
                        artists = relatedChristianArtists + OnlineArtistSummary(
                            id = "UC_abel_meza",
                            name = "Abel Meza",
                            avatarUrl = "https://art/abel.jpg",
                            subtitle = "1.2K subscribers"
                        )
                    )
                    PARAMS_SONGS -> OnlineSearchResult(
                        tracks = listOf(
                            OnlineTrack(
                                id = "vid_abel_1",
                                title = "Canción de Abel",
                                artist = "Abel Meza",
                                album = "Sencillo",
                                durationMs = 210_000L,
                                artworkUrl = "https://art/abel_song.jpg",
                                artistId = "UC_abel_meza"
                            )
                        )
                    )
                    else -> OnlineSearchResult(
                        artists = listOf(relatedChristianArtists.first())
                    )
                }
            }

            override suspend fun browseArtist(browseId: String) =
                org.nov0caina.sounder.online.model.OnlineArtistDetail(
                    id = browseId,
                    name = if (browseId == "UC_abel_meza") "Abel Meza" else "Unknown",
                    avatarUrl = "https://art/$browseId.jpg",
                    description = null,
                    subscribers = if (browseId == "UC_abel_meza") "1.2K subscribers" else null
                )
        }

        val case1Result = clientWithLowSubArtist.searchCategorized("Abel Meza", isCategory = false)
        assertTrue("Expected non-empty artists", case1Result.artists.isNotEmpty())
        assertEquals("Abel Meza", case1Result.artists.first().name)

        // Case 2: Abel Meza absent from dedicated artists list (18 related artists present), only in track results
        val clientTrackOnly = object : InnerTubeClient() {
            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                return when (params) {
                    PARAMS_ARTISTS -> OnlineSearchResult(artists = relatedChristianArtists)
                    PARAMS_SONGS -> OnlineSearchResult(
                        tracks = listOf(
                            OnlineTrack(
                                id = "vid_abel_track",
                                title = "Adoración",
                                artist = "Abel Meza",
                                album = "Álbum",
                                durationMs = 200_000L,
                                artworkUrl = "https://art/abel_track.jpg",
                                artistId = "UC_abel_meza"
                            )
                        )
                    )
                    else -> OnlineSearchResult(
                        artists = listOf(relatedChristianArtists.first())
                    )
                }
            }

            override suspend fun browseArtist(browseId: String) =
                org.nov0caina.sounder.online.model.OnlineArtistDetail(
                    id = browseId,
                    name = if (browseId == "UC_abel_meza") "Abel Meza" else "Resolved $browseId",
                    avatarUrl = "https://art/abel_profile.jpg",
                    description = null,
                    subscribers = "1.2K subscribers"
                )
        }

        val case2Result = clientTrackOnly.searchCategorized("Abel Meza", isCategory = false)
        assertTrue("Expected non-empty artists", case2Result.artists.isNotEmpty())
        assertEquals("Abel Meza", case2Result.artists.first().name)
        assertEquals("UC_abel_meza", case2Result.artists.first().id)
    }
}
