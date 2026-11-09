package org.nov0caina.sounder.online.lyrics

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.nov0caina.sounder.online.model.LyricsResult
import timber.log.Timber as L

/**
 * HTTP client for fetching synchronized and plain lyrics from LrcLib (https://lrclib.net).
 */
@Singleton
open class LrcLibClient @Inject constructor() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val userAgent = "Sounder-Music-Player (https://github.com/nov0caina/Sounder)"

    /**
     * Retrieves lyrics for a track using a progressive multi-tier strategy:
     * 1. Exact match with clean title and artist (without strict album/duration constraints).
     * 2. Exact match with album name (if verified distinct album exists).
     * 3. Clean search query fallback.
     *
     * @param trackName Name of the track.
     * @param artistName Name of the artist.
     * @param albumName Optional album name.
     * @param durationSeconds Optional track duration in seconds.
     * @return [LyricsResult] containing parsed synced lines or plain text lyrics.
     */
    suspend fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Int? = null
    ): LyricsResult = withContext(Dispatchers.IO) {
        val cleanTitle = LyricsSanitizer.cleanTitle(trackName, artistName)
        val cleanArtist = LyricsSanitizer.cleanArtist(artistName)

        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return@withContext LyricsResult()
        }

        // Tier 1: Clean Exact match (track_name + artist_name only)
        // This targets LRCLIB's indexed fields directly without album/duration mismatch 503s.
        val tier1Result = fetchExactLyrics(cleanTitle, cleanArtist, albumName = null, durationSeconds = null)
        if (tier1Result != null && !tier1Result.isEmpty) {
            return@withContext tier1Result
        }

        // Tier 2: Exact match with verified album name if distinct from track title
        val isMeaningfulAlbum = !albumName.isNullOrBlank() &&
            !albumName.equals(trackName, ignoreCase = true) &&
            !albumName.equals(cleanTitle, ignoreCase = true) &&
            !albumName.equals("Single", ignoreCase = true)

        if (isMeaningfulAlbum) {
            val tier2Result = fetchExactLyrics(cleanTitle, cleanArtist, albumName = albumName, durationSeconds = null)
            if (tier2Result != null && !tier2Result.isEmpty) {
                return@withContext tier2Result
            }
        }

        // Tier 3: Search fallback with clean terms
        fetchSearchLyrics(cleanTitle, cleanArtist)
    }

    open internal fun fetchExactLyrics(
        trackName: String,
        artistName: String,
        albumName: String?,
        durationSeconds: Int?
    ): LyricsResult? {
        val urlBuilder = StringBuilder("https://lrclib.net/api/get?")
        urlBuilder.append("track_name=").append(URLEncoder.encode(trackName, "UTF-8"))
        urlBuilder.append("&artist_name=").append(URLEncoder.encode(artistName, "UTF-8"))
        if (!albumName.isNullOrBlank()) {
            urlBuilder.append("&album_name=").append(URLEncoder.encode(albumName, "UTF-8"))
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.append("&duration=").append(durationSeconds)
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("User-Agent", userAgent)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 503) {
                        L.w("LRCLIB /api/get returned 503 ServerOverloaded for %s - %s", artistName, trackName)
                    }
                    return null
                }
                val body = response.body?.string() ?: return null
                parseLyricsJson(JSONObject(body))
            }
        } catch (e: Exception) {
            L.w(e, "Error fetching exact lyrics for %s - %s", artistName, trackName)
            null
        }
    }

    open internal fun fetchSearchLyrics(trackName: String, artistName: String): LyricsResult {
        val query = "$artistName $trackName"
        val url = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 503) {
                        L.w("LRCLIB /api/search returned 503 ServerOverloaded for query %s", query)
                    }
                    return LyricsResult()
                }
                val body = response.body?.string() ?: return LyricsResult()
                val jsonArray = JSONArray(body)
                if (jsonArray.length() == 0) return LyricsResult()

                var fallbackResult: LyricsResult? = null
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val res = parseLyricsJson(obj)
                    if (res.hasSyncedLyrics) {
                        return res
                    }
                    if (fallbackResult == null && (res.hasPlainLyrics || res.isInstrumental)) {
                        fallbackResult = res
                    }
                }
                fallbackResult ?: LyricsResult()
            }
        } catch (e: Exception) {
            L.w(e, "Error searching lyrics for %s", query)
            LyricsResult()
        }
    }

    internal fun parseLyricsJson(json: JSONObject): LyricsResult {
        val isInstrumental = json.optBoolean("instrumental", false)
        val plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() }
        val syncedLyricsStr = json.optString("syncedLyrics").takeIf { it.isNotBlank() }

        val syncedLines = if (syncedLyricsStr != null) {
            LrcParser.parse(syncedLyricsStr)
        } else {
            emptyList()
        }

        return LyricsResult(
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLines,
            isInstrumental = isInstrumental
        )
    }
}
