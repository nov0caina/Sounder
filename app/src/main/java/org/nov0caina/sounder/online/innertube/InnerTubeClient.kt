package org.nov0caina.sounder.online.innertube

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.nov0caina.sounder.online.OnlineSettings
import org.nov0caina.sounder.online.lyrics.LyricsSanitizer
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.StreamInfo
import timber.log.Timber as L

/**
 * Client for querying YouTube Music's InnerTube API endpoints.
 */
open class InnerTubeClient(
    private val onlineSettings: OnlineSettings? = null
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Executes a multi-category search returning both matching artists and tracks,
     * enriched by concurrently querying for dedicated artists to expand the artist catalog.
     */
    open suspend fun searchCategorized(query: String): OnlineSearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext OnlineSearchResult()

        try {
            coroutineScope {
                val primaryDeferred = async { fetchSearchPayload(query) }

                val cleanArtistQuery = query
                    .replace(Regex("(?i)\\b(artists|artistas|composers|canciones|songs|music|musica)\\b"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { query.trim() }

                val artistsDeferred = async {
                    fetchSearchPayload(cleanArtistQuery, params = "EgWKAQIgAWoKEAkQChAFEAMQBA==")
                }

                val primaryResult = primaryDeferred.await()
                val artistsResult = try {
                    artistsDeferred.await()
                } catch (e: Exception) {
                    L.w(e, "Dedicated artist query failed for: %s", cleanArtistQuery)
                    null
                }

                val mergedArtists = mutableListOf<OnlineArtistSummary>()
                val seenArtistIds = mutableSetOf<String>()

                // 1. Prioritize verified artists from primary search (e.g. top MusicCardShelf exact match)
                for (artist in primaryResult.artists) {
                    if (!artist.avatarUrl.isNullOrBlank() && seenArtistIds.add(artist.id)) {
                        mergedArtists.add(artist)
                    }
                }

                // 2. Add verified artists from dedicated artist search
                if (artistsResult != null) {
                    for (artist in artistsResult.artists) {
                        if (!artist.avatarUrl.isNullOrBlank() && seenArtistIds.add(artist.id)) {
                            mergedArtists.add(artist)
                        }
                    }
                }

                OnlineSearchResult(
                    artists = mergedArtists,
                    tracks = primaryResult.tracks
                )
            }
        } catch (e: Exception) {
            L.w(e, "Error executing enriched search for query: %s", query)
            fetchSearchPayload(query)
        }
    }

    open internal suspend fun fetchSearchPayload(query: String, params: String? = null): OnlineSearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext OnlineSearchResult()

        try {
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                if (!params.isNullOrBlank()) {
                    put("params", params)
                }
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                L.w("Search failed with HTTP code: %d", response.code)
                return@withContext OnlineSearchResult()
            }

            val responseBody = response.body?.string() ?: return@withContext OnlineSearchResult()
            val preferStudio = onlineSettings?.preferStudioAudio ?: true
            return@withContext InnerTubeParser.parseMultiCategorySearch(responseBody, preferStudio)
        } catch (e: Exception) {
            L.w(e, "Error executing search for query: %s", query)
            return@withContext OnlineSearchResult()
        }
    }

    /**
     * Executes an online music search on YouTube Music returning songs.
     */
    open suspend fun search(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        searchCategorized(query).tracks
    }

    /**
     * Browses home feed, optionally filtered by a specific music category or mood.
     */
    open suspend fun browseHomeFeed(category: String? = null): org.nov0caina.sounder.online.model.HomeFeedData = withContext(Dispatchers.IO) {
        val normCat = category?.trim()?.lowercase()
        if (normCat.isNullOrBlank() || normCat == "all" || normCat == "todo") {
            return@withContext coroutineScope {
                val homeDeferred = async { fetchBrowseJson("FEmusic_home") }
                val exploreDeferred = async { fetchBrowseJson("FEmusic_explore") }
                val chartsDeferred = async { fetchBrowseJson("FEmusic_charts") }

                val homeJson = homeDeferred.await()
                val exploreJson = exploreDeferred.await()
                val chartsJson = chartsDeferred.await()

                val preferStudio = onlineSettings?.preferStudioAudio ?: true
                var feed = InnerTubeParser.parseHomeFeed(homeJson, exploreJson, chartsJson, preferStudio)

                if (feed.trendingTracks.isEmpty()) {
                    val searchFallback = searchCategorized("Tendencias musica")
                    feed = feed.copy(
                        trendingTracks = searchFallback.tracks,
                        popularArtists = if (feed.popularArtists.isEmpty()) searchFallback.artists else feed.popularArtists
                    )
                }

                if (feed.trendingTracks.size < 20) {
                    val seenIds = feed.trendingTracks.map { it.id }.toMutableSet()
                    val additional = mutableListOf<OnlineTrack>()
                    for (qp in feed.quickPicks) {
                        if (seenIds.add(qp.id)) {
                            additional.add(qp)
                            if (feed.trendingTracks.size + additional.size >= 20) break
                        }
                    }
                    if (feed.trendingTracks.size + additional.size < 20) {
                        try {
                            val moreFallback = searchCategorized("Top 50 musica mundial").tracks
                            for (t in moreFallback) {
                                if (seenIds.add(t.id)) {
                                    additional.add(t)
                                    if (feed.trendingTracks.size + additional.size >= 20) break
                                }
                            }
                        } catch (e: Exception) {
                            L.w(e, "Error fetching additional trending tracks for home feed")
                        }
                    }
                    if (additional.isNotEmpty()) {
                        feed = feed.copy(trendingTracks = feed.trendingTracks + additional)
                    }
                }

                feed
            }
        }

        coroutineScope {
            val moodTrackQuery = when (normCat) {
                "party" -> "party hits dance"
                "chill" -> "chillout lofi chill"
                "workout" -> "workout motivation gym hits"
                "focus" -> "focus study instrumental ambient"
                "latin" -> "latin hits reggaeton pop"
                "rock" -> "rock classics alternative"
                else -> "$category hits trending"
            }
            val moodArtistQuery = when (normCat) {
                "party" -> "dance party"
                "chill" -> "lofi chill"
                "workout" -> "fitness workout"
                "focus" -> "ambient focus"
                "latin" -> "latin pop"
                "rock" -> "rock band"
                else -> "$category"
            }

            val tracksDeferred = async { searchCategorized(moodTrackQuery) }
            val moreTracksDeferred = async { searchCategorized("$category top songs") }
            val directArtistsDeferred = async {
                fetchSearchPayload(category, params = "EgWKAQIgAWoKEAkQChAFEAMQBA==")
            }
            val extraArtistsDeferred = async {
                fetchSearchPayload(moodArtistQuery, params = "EgWKAQIgAWoKEAkQChAFEAMQBA==")
            }

            val tracksResult = tracksDeferred.await()
            val moreTracksResult = moreTracksDeferred.await()
            val directArtistsResult = directArtistsDeferred.await()
            val extraArtistsResult = extraArtistsDeferred.await()

            val combinedTracks = mutableListOf<OnlineTrack>()
            val seenTrackIds = mutableSetOf<String>()
            for (track in tracksResult.tracks) {
                if (seenTrackIds.add(track.id)) combinedTracks.add(track)
            }
            for (track in moreTracksResult.tracks) {
                if (seenTrackIds.add(track.id)) combinedTracks.add(track)
            }

            val quickPicks = if (combinedTracks.size > 24) combinedTracks.take(24) else combinedTracks

            fun isValidArtist(a: OnlineArtistSummary): Boolean =
                !a.name.contains("Various Artists", ignoreCase = true) &&
                !a.name.contains("Varios Artistas", ignoreCase = true) &&
                !a.id.startsWith("MPREb_") &&
                !a.id.startsWith("VL") &&
                !a.id.startsWith("PL")

            // Extract artists directly from mood tracks so top artists of the mood are guaranteed
            val trackArtists = mutableListOf<OnlineArtistSummary>()
            val seenTrackArtistNames = mutableSetOf<String>()
            for (track in combinedTracks) {
                val primaryArtist = track.artist.split(",", "&", "feat.", "ft.", "with", "/").firstOrNull()?.trim() ?: track.artist.trim()
                if (primaryArtist.isNotBlank() &&
                    !primaryArtist.equals("Various Artists", ignoreCase = true) &&
                    !primaryArtist.equals("Varios Artistas", ignoreCase = true) &&
                    seenTrackArtistNames.add(primaryArtist.lowercase())
                ) {
                    val aId = track.artistId ?: ("art_${primaryArtist.hashCode()}")
                    trackArtists.add(
                        OnlineArtistSummary(
                            id = aId,
                            name = primaryArtist,
                            avatarUrl = track.artworkUrl,
                            subtitle = null
                        )
                    )
                }
            }

            val mergedArtists = mutableListOf<OnlineArtistSummary>()
            val seenArtistNames = mutableSetOf<String>()

            fun addArtist(a: OnlineArtistSummary) {
                val normName = a.name.trim().lowercase()
                if (isValidArtist(a) && seenArtistNames.add(normName)) {
                    mergedArtists.add(a)
                }
            }

            // 1. Add dedicated artists from artist search (who have artist avatars)
            for (a in directArtistsResult.artists) {
                if (!a.avatarUrl.isNullOrBlank()) addArtist(a)
            }
            for (a in extraArtistsResult.artists) {
                if (!a.avatarUrl.isNullOrBlank()) addArtist(a)
            }

            // 2. Add top artists from mood tracks
            for (a in trackArtists) {
                addArtist(a)
            }

            // 3. Add any remaining artists from direct results
            for (a in directArtistsResult.artists) addArtist(a)
            for (a in extraArtistsResult.artists) addArtist(a)

            val finalArtists = mergedArtists.take(15)

            org.nov0caina.sounder.online.model.HomeFeedData(
                trendingTracks = combinedTracks,
                trendingAlbums = emptyList(),
                popularArtists = finalArtists,
                quickPicks = quickPicks
            )
        }
    }

    private fun fetchBrowseJson(browseId: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "es")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            L.w(e, "Error fetching browse endpoint for %s", browseId)
            null
        }
    }

    /**
     * Browses artist catalog (avatar, bio, top songs, discography, related artists).
     */
    open suspend fun browseArtist(browseId: String): OnlineArtistDetail = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext OnlineArtistDetail(browseId, "", null, null)

        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                L.w("browseArtist failed with HTTP code: %d for %s", response.code, browseId)
                return@withContext OnlineArtistDetail(browseId, "", null, null)
            }

            val responseBody = response.body?.string() ?: return@withContext OnlineArtistDetail(browseId, "", null, null)
            val preferStudio = onlineSettings?.preferStudioAudio ?: true
            return@withContext InnerTubeParser.parseArtistDetail(browseId, responseBody, preferStudio)
        } catch (e: Exception) {
            L.w(e, "Error browsing artist for %s", browseId)
            return@withContext OnlineArtistDetail(browseId, "", null, null)
        }
    }

    /**
     * Browses album details (cover, year, tracklist with videoIds).
     */
    open suspend fun browseAlbum(browseId: String): OnlineAlbumDetail = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext OnlineAlbumDetail(browseId, "", "", null, null)

        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", browseId)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                L.w("browseAlbum failed with HTTP code: %d for %s", response.code, browseId)
                return@withContext OnlineAlbumDetail(browseId, "", "", null, null)
            }

            val responseBody = response.body?.string() ?: return@withContext OnlineAlbumDetail(browseId, "", "", null, null)
            return@withContext InnerTubeParser.parseAlbumDetail(browseId, responseBody)
        } catch (e: Exception) {
            L.w(e, "Error browsing album for %s", browseId)
            return@withContext OnlineAlbumDetail(browseId, "", "", null, null)
        }
    }

    data class PlayerResolutionResult(
        val streamInfo: StreamInfo?,
        val detectedTitle: String? = null,
        val detectedAuthor: String? = null
    )

    /**
     * Resolves playable audio stream info (URL and duration) for a given video ID,
     * with resilient multi-client player fallbacks (Android, iOS, TV) and alternative track search.
     */
    open suspend fun resolveStreamInfo(
        videoId: String,
        quality: AudioQuality,
        title: String? = null,
        artist: String? = null
    ): StreamInfo? = withContext(Dispatchers.IO) {
        val androidResult = tryResolveAndroidPlayerInfo(videoId, quality)
        if (androidResult.streamInfo != null && androidResult.streamInfo.url.isNotBlank()) {
            L.d("Resolved direct stream info via Android InnerTube client for %s (duration=%d ms)", videoId, androidResult.streamInfo.durationMs)
            return@withContext androidResult.streamInfo
        }

        val iosResult = tryResolveIosPlayerInfo(videoId, quality)
        if (iosResult.streamInfo != null && iosResult.streamInfo.url.isNotBlank()) {
            L.d("Resolved direct stream info via iOS InnerTube client for %s", videoId)
            return@withContext iosResult.streamInfo
        }

        val tvResult = tryResolveTvPlayerInfo(videoId, quality)
        if (tvResult.streamInfo != null && tvResult.streamInfo.url.isNotBlank()) {
            L.d("Resolved direct stream info via TV InnerTube client for %s", videoId)
            return@withContext tvResult.streamInfo
        }

        val resolvedTitle = title ?: androidResult.detectedTitle ?: iosResult.detectedTitle ?: tvResult.detectedTitle
        val resolvedArtist = artist ?: androidResult.detectedAuthor ?: iosResult.detectedAuthor ?: tvResult.detectedAuthor
        if (!resolvedTitle.isNullOrBlank()) {
            val altInfo = tryResolveAlternativeTrack(resolvedTitle, resolvedArtist, quality, excludeVideoId = videoId)
            if (altInfo != null && altInfo.url.isNotBlank()) {
                L.d("Resolved alternative stream info for %s (%s - %s)", videoId, resolvedArtist, resolvedTitle)
                return@withContext altInfo
            }
        }

        val fallbackUrl = tryResolveFallbackStream(videoId, quality)
        if (!fallbackUrl.isNullOrBlank()) {
            L.d("Resolved fallback stream URL for %s", videoId)
            return@withContext StreamInfo(url = fallbackUrl)
        }

        L.w("Could not resolve playable stream info for %s", videoId)
        return@withContext null
    }

    /**
     * Resolves the playable direct audio stream URL for a given video ID.
     */
    open suspend fun resolveStreamUrl(
        videoId: String,
        quality: AudioQuality,
        title: String? = null,
        artist: String? = null
    ): String? = withContext(Dispatchers.IO) {
        resolveStreamInfo(videoId, quality, title, artist)?.url
    }

    private fun tryResolveAndroidPlayerInfo(videoId: String, quality: AudioQuality): PlayerResolutionResult {
        var detectedTitle: String? = null
        var detectedAuthor: String? = null
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "21.26.364")
                        put("androidSdkVersion", 30)
                        put("osName", "Android")
                        put("osVersion", "11")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        try {
                            val root = JSONObject(body)
                            val vd = root.optJSONObject("videoDetails")
                            if (vd != null) {
                                detectedTitle = vd.optString("title").takeIf { it.isNotBlank() }
                                detectedAuthor = vd.optString("author").takeIf { it.isNotBlank() }
                            }
                        } catch (e: Exception) {
                            // ignore json parse errors for metadata fallback
                        }
                        val streamInfo = InnerTubeParser.parseStreamInfo(body, quality)
                        if (streamInfo != null && streamInfo.url.isNotBlank()) {
                            return PlayerResolutionResult(streamInfo, detectedTitle, detectedAuthor)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Android player resolution failed for %s", videoId)
        }
        return PlayerResolutionResult(null, detectedTitle, detectedAuthor)
    }

    private fun tryResolveIosPlayerInfo(videoId: String, quality: AudioQuality): PlayerResolutionResult {
        var detectedTitle: String? = null
        var detectedAuthor: String? = null
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.29.1")
                        put("deviceModel", "iPhone16,2")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        try {
                            val root = JSONObject(body)
                            val vd = root.optJSONObject("videoDetails")
                            if (vd != null) {
                                detectedTitle = vd.optString("title").takeIf { it.isNotBlank() }
                                detectedAuthor = vd.optString("author").takeIf { it.isNotBlank() }
                            }
                        } catch (e: Exception) {
                            // ignore json parse errors
                        }
                        val streamInfo = InnerTubeParser.parseStreamInfo(body, quality)
                        if (streamInfo != null && streamInfo.url.isNotBlank()) {
                            return PlayerResolutionResult(streamInfo, detectedTitle, detectedAuthor)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "iOS player resolution failed for %s", videoId)
        }
        return PlayerResolutionResult(null, detectedTitle, detectedAuthor)
    }

    private fun tryResolveTvPlayerInfo(videoId: String, quality: AudioQuality): PlayerResolutionResult {
        var detectedTitle: String? = null
        var detectedAuthor: String? = null
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
                        put("clientVersion", "2.0")
                        put("hl", "en")
                        put("gl", "US")
                    })
                    put("thirdParty", JSONObject().apply {
                        put("embedUrl", "https://www.youtube.com/embed/$videoId")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (PlayStation 4 5.05) AppleWebKit/605.1.15 (KHTML, like Gecko)")
                .header("Referer", "https://www.youtube.com/embed/$videoId")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        try {
                            val root = JSONObject(body)
                            val vd = root.optJSONObject("videoDetails")
                            if (vd != null) {
                                detectedTitle = vd.optString("title").takeIf { it.isNotBlank() }
                                detectedAuthor = vd.optString("author").takeIf { it.isNotBlank() }
                            }
                        } catch (e: Exception) {
                            // ignore json parse errors
                        }
                        val streamInfo = InnerTubeParser.parseStreamInfo(body, quality)
                        if (streamInfo != null && streamInfo.url.isNotBlank()) {
                            return PlayerResolutionResult(streamInfo, detectedTitle, detectedAuthor)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "TV player resolution failed for %s", videoId)
        }
        return PlayerResolutionResult(null, detectedTitle, detectedAuthor)
    }

    private fun tryResolveAlternativeTrack(
        title: String,
        artist: String?,
        quality: AudioQuality,
        excludeVideoId: String
    ): StreamInfo? {
        try {
            val cleanTitle = LyricsSanitizer.cleanTitle(title, artist ?: "")
            val cleanArtist = artist?.let { LyricsSanitizer.cleanArtist(it) } ?: ""
            val query = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20231211.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
            }

            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val preferStudio = onlineSettings?.preferStudioAudio ?: true
                val searchResults = InnerTubeParser.parseSearchResults(body, preferStudio)
                val candidateIds = searchResults
                    .map { it.id }
                    .filter { it.isNotBlank() && it != excludeVideoId }

                for (candId in candidateIds.take(4)) {
                    val candidateResult = tryResolveAndroidPlayerInfo(candId, quality)
                    val stream = candidateResult.streamInfo ?: tryResolveIosPlayerInfo(candId, quality).streamInfo
                    if (stream != null && stream.url.isNotBlank()) {
                        return stream
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Error finding alternative track for %s - %s", artist, title)
        }
        return null
    }

    private fun tryResolveAndroidPlayer(videoId: String, quality: AudioQuality): String? {
        return tryResolveAndroidPlayerInfo(videoId, quality).streamInfo?.url
    }

    private fun tryResolveFallbackStream(videoId: String, quality: AudioQuality): String? {
        val fallbackInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi.drgns.space"
        )

        for (instance in fallbackInstances) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val root = JSONObject(body)
                            val audioStreams = root.optJSONArray("audioStreams")
                            if (audioStreams != null) {
                                var bestUrl: String? = null
                                var bestBitrate = 0

                                for (i in 0 until audioStreams.length()) {
                                    val stream = audioStreams.getJSONObject(i)
                                    val url = stream.optString("url")
                                    val bitrate = stream.optInt("bitrate", 0)
                                    if (url.isBlank()) continue

                                    if (quality == AudioQuality.DATA_SAVER) {
                                        if (bestUrl == null || bitrate in 45000..128000) {
                                            bestUrl = url
                                            bestBitrate = bitrate
                                        }
                                    } else {
                                        if (bitrate > bestBitrate) {
                                            bestUrl = url
                                            bestBitrate = bitrate
                                        }
                                    }
                                }

                                if (!bestUrl.isNullOrBlank()) {
                                    return bestUrl
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next fallback instance
            }
        }
        return null
    }
}
