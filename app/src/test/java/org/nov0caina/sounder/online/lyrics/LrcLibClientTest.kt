package org.nov0caina.sounder.online.lyrics

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.online.model.LyricsResult
import org.nov0caina.sounder.online.model.LrcLine

class LrcLibClientTest {

    private val client = LrcLibClient()

    @Test
    fun testParseLyricsJsonWithSyncedLyrics() {
        val json = JSONObject().apply {
            put("id", 12345)
            put("trackName", "Bohemian Rhapsody")
            put("artistName", "Queen")
            put("albumName", "A Night at the Opera")
            put("duration", 354.0)
            put("instrumental", false)
            put("plainLyrics", "Is this the real life?\nIs this just fantasy?")
            put("syncedLyrics", "[00:00.80] Is this the real life?\n[00:04.50] Is this just fantasy?")
        }

        val result = client.parseLyricsJson(json)
        assertTrue(result.hasSyncedLyrics)
        assertTrue(result.hasPlainLyrics)
        assertFalse(result.isInstrumental)
        assertEquals(2, result.syncedLyrics.size)
        assertEquals(800L, result.syncedLyrics[0].timeMs)
        assertEquals("Is this the real life?", result.syncedLyrics[0].text)
        assertEquals(4500L, result.syncedLyrics[1].timeMs)
        assertEquals("Is this just fantasy?", result.syncedLyrics[1].text)
    }

    @Test
    fun testParseLyricsJsonInstrumental() {
        val json = JSONObject().apply {
            put("id", 99999)
            put("instrumental", true)
            put("plainLyrics", "")
            put("syncedLyrics", "")
        }

        val result = client.parseLyricsJson(json)
        assertTrue(result.isInstrumental)
        assertFalse(result.hasSyncedLyrics)
        assertFalse(result.hasPlainLyrics)
        assertFalse(result.isEmpty)
    }

    @Test
    fun testParseLyricsJsonPlainOnly() {
        val json = JSONObject().apply {
            put("id", 88888)
            put("instrumental", false)
            put("plainLyrics", "Only plain lyrics here")
            put("syncedLyrics", "")
        }

        val result = client.parseLyricsJson(json)
        assertFalse(result.isInstrumental)
        assertFalse(result.hasSyncedLyrics)
        assertTrue(result.hasPlainLyrics)
        assertEquals("Only plain lyrics here", result.plainLyrics)
    }

    @Test
    fun testParseLyricsJsonEmpty() {
        val json = JSONObject()
        val result = client.parseLyricsJson(json)
        assertTrue(result.isEmpty)
        assertFalse(result.hasSyncedLyrics)
        assertFalse(result.hasPlainLyrics)
        assertFalse(result.isInstrumental)
    }

    @Test
    fun testGetLyricsTier1Success() = runBlocking {
        val calls = mutableListOf<String>()
        val mockClient = object : LrcLibClient() {
            override fun fetchExactLyrics(
                trackName: String,
                artistName: String,
                albumName: String?,
                durationSeconds: Int?
            ): LyricsResult? {
                calls.add("exact(track=$trackName, artist=$artistName, album=$albumName)")
                return LyricsResult(
                    syncedLyrics = listOf(LrcLine(1000L, "Hello world"))
                )
            }

            override fun fetchSearchLyrics(trackName: String, artistName: String): LyricsResult {
                calls.add("search(track=$trackName, artist=$artistName)")
                return LyricsResult()
            }
        }

        val result = mockClient.getLyrics(
            trackName = "Bohemian Rhapsody (Official Video)",
            artistName = "Queen",
            albumName = "A Night at the Opera"
        )

        assertTrue(result.hasSyncedLyrics)
        assertEquals(1, calls.size)
        assertEquals("exact(track=Bohemian Rhapsody, artist=Queen, album=null)", calls[0])
    }

    @Test
    fun testGetLyricsTier2AlbumLookupWhenTier1Fails() = runBlocking {
        val calls = mutableListOf<String>()
        val mockClient = object : LrcLibClient() {
            override fun fetchExactLyrics(
                trackName: String,
                artistName: String,
                albumName: String?,
                durationSeconds: Int?
            ): LyricsResult? {
                calls.add("exact(track=$trackName, artist=$artistName, album=$albumName)")
                return if (albumName != null) {
                    LyricsResult(syncedLyrics = listOf(LrcLine(500L, "Found on album")))
                } else {
                    null // Tier 1 fails
                }
            }

            override fun fetchSearchLyrics(trackName: String, artistName: String): LyricsResult {
                calls.add("search(track=$trackName, artist=$artistName)")
                return LyricsResult()
            }
        }

        val result = mockClient.getLyrics(
            trackName = "That Funny Feeling",
            artistName = "Bo Burnham",
            albumName = "Inside"
        )

        assertTrue(result.hasSyncedLyrics)
        assertEquals(2, calls.size)
        assertEquals("exact(track=That Funny Feeling, artist=Bo Burnham, album=null)", calls[0])
        assertEquals("exact(track=That Funny Feeling, artist=Bo Burnham, album=Inside)", calls[1])
    }

    @Test
    fun testGetLyricsTier2SkippedWhenAlbumMatchesTitle() = runBlocking {
        val calls = mutableListOf<String>()
        val mockClient = object : LrcLibClient() {
            override fun fetchExactLyrics(
                trackName: String,
                artistName: String,
                albumName: String?,
                durationSeconds: Int?
            ): LyricsResult? {
                calls.add("exact(track=$trackName, artist=$artistName, album=$albumName)")
                return null // Fails
            }

            override fun fetchSearchLyrics(trackName: String, artistName: String): LyricsResult {
                calls.add("search(track=$trackName, artist=$artistName)")
                return LyricsResult(plainLyrics = "Search result")
            }
        }

        val result = mockClient.getLyrics(
            trackName = "California Love",
            artistName = "2Pac",
            albumName = "California Love" // Same as track name, should skip Tier 2
        )

        assertTrue(result.hasPlainLyrics)
        assertEquals(2, calls.size)
        assertEquals("exact(track=California Love, artist=2Pac, album=null)", calls[0])
        assertEquals("search(track=California Love, artist=2Pac)", calls[1])
    }

    @Test
    fun testGetLyricsBlankTitleReturnsEmpty() = runBlocking {
        val result = client.getLyrics("   ", "Artist")
        assertTrue(result.isEmpty)
    }
}

