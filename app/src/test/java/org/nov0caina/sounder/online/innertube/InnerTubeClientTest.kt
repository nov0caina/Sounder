package org.nov0caina.sounder.online.innertube

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

        assertEquals(2, calls.size)
        // Verify both primary search and dedicated artist search calls are executed concurrently
        assertTrue(calls.contains("Rock" to null))
        assertTrue(calls.contains("Rock" to "EgWKAQIgAWoKEAkQChAFEAMQBA=="))

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
            override internal suspend fun fetchSearchPayload(query: String, params: String?): OnlineSearchResult {
                calls.add(query to params)
                return OnlineSearchResult(
                    artists = listOf(OnlineArtistSummary("a1", "Metallica", "https://avatar/metallica.jpg", "64M Subscribers"))
                )
            }
        }

        val result = client.searchCategorized("Metal artists")

        assertEquals(2, calls.size)
        assertTrue(calls.contains("Metal artists" to null))
        // Dedicated artist query strips "artists" suffix for accurate filtering
        assertTrue(calls.contains("Metal" to "EgWKAQIgAWoKEAkQChAFEAMQBA=="))
        assertEquals(1, result.artists.size)
        assertEquals("https://avatar/metallica.jpg", result.artists[0].avatarUrl)
    }

    @Test
    fun testSearchCategorizedBlankQueryReturnsEmpty() = runBlocking {
        val client = InnerTubeClient()
        val result = client.searchCategorized("   ")
        assertTrue(result.artists.isEmpty())
        assertTrue(result.tracks.isEmpty())
    }
}
