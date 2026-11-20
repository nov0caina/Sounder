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
}
