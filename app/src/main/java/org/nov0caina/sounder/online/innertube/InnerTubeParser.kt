package org.nov0caina.sounder.online.innertube

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.online.model.OnlineAlbumDetail
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.StreamInfo
import org.nov0caina.sounder.util.SongMatcher
import timber.log.Timber as L

/**
 * Handles parsing and normalization of responses from YouTube Music's InnerTube API.
 */
object InnerTubeParser {

    private val NON_ARTIST_PAGE_TYPES = setOf(
        "MUSIC_PAGE_TYPE_USER_CHANNEL",
        "MUSIC_PAGE_TYPE_USER_PROFILE",
        "MUSIC_PAGE_TYPE_PODCAST_SHOW",
        "MUSIC_PAGE_TYPE_PODCAST_EPISODE",
        "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
    )

    private val NON_ARTIST_SUBTITLE_LABELS = setOf(
        "profile", "perfil",
        "channel", "canal",
        "podcast", "podcasts",
        "episode", "episodio",
        "show", "programa",
        "audiobook", "audiolibro"
    )

    private val NON_MUSIC_AUDIO_LABELS = setOf(
        "episode", "episodio",
        "podcast", "podcasts",
        "audiobook", "audiolibro"
    )

    private val AUDIENCE_METRIC_KEYWORDS = listOf(
        "monthly audience", "monthly listener", "oyentes mensuales", "oyentes",
        "auditeurs", "hörer", "listeners", "ascoltatori", "ouvintes"
    )

    private val SUBSCRIBER_METRIC_KEYWORDS = listOf(
        "subscriber", "subscribers", "suscriptor", "suscriptores", "abonnés", "abonnenten", "inscritos"
    )

    private val GENERIC_CHANNEL_NAME_REGEX = Regex(
        """(?i)(\b(songs?|canciones|acordes|chords|radio|karaoke|tutorial|lyrics?|letras?|playlist|compilaci[oó]n|compilation|mix|mashup|covers?|tribute|8d|slowed|reverb|nightcore|sped\s+up|bass\s+boosted|ringtones?|podcast|podcasts|reupload|ai\s+music|musica\s+ia|ai\s+cover|suno|udio|fm|tv)\b|[-_\s](ia|ai)$)"""
    )

    private val GENRE_SPAM_CHANNEL_REGEX = Regex(
        """(?i)\b(metal|rock|pop|k-?pop|hip\s*hop|rap|trap|jazz|blues|classical|ambient|lofi|lo-fi|chill|focus|workout|party|gaming|edm|techno|house|reggaeton|salsa|cumbia|bachata|worship|christian|cristian[oa]s?|alabanza|gospel|phonk|synthwave|beats)\b"""
    )

    private val JUNK_TRACK_TITLE_REGEX = Regex(
        """(?i)\b(karaoke\s+version|backing\s+track|guitar\s+lesson|piano\s+tutorial|how\s+to\s+play|ringtone|full\s+podcast)\b"""
    )

    internal val EXPLICIT_TITLE_REGEX = Regex(
        """(?i)(?:[\(\[]|\b)(explicit|uncensored|sin\s+censura)(?:[\)\]]|\b)"""
    )

    internal val CLEAN_TITLE_REGEX = Regex(
        """(?i)(?:[\(\[]|\s+-\s+|\b)(clean(\s+version|\s+edit)?|radio\s+edit|censored|censurad[ao])(?:[\)\]]|\b)"""
    )

    internal val STRIP_EDITION_TAGS_REGEX = Regex(
        """(?i)(?:\s*[\(\[](?:clean(?:\s+version|\s+edit)?|radio\s+edit|censored|censurad[ao]|explicit|uncensored|sin\s+censura)[\)\]]|\s+-\s+(?:clean(?:\s+version|\s+edit)?|radio\s+edit|explicit))"""
    )

    internal fun parseExplicitFromBadges(badges: JSONArray?): Boolean {
        if (badges == null) return false
        for (i in 0 until badges.length()) {
            val badgeObj = badges.optJSONObject(i)?.optJSONObject("musicInlineBadgeRenderer") ?: continue
            val iconType = badgeObj.optJSONObject("icon")?.optString("iconType").orEmpty()
            if (iconType.equals("MUSIC_EXPLICIT_BADGE", ignoreCase = true)) {
                return true
            }
            val nestedLabel = badgeObj.optJSONObject("accessibilityData")
                ?.optJSONObject("accessibilityData")
                ?.optString("label")
                .orEmpty()
            val directLabel = badgeObj.optString("accessibilityData", "")
            if (nestedLabel.contains("explicit", ignoreCase = true) ||
                nestedLabel.contains("explícit", ignoreCase = true) ||
                directLabel.contains("explicit", ignoreCase = true) ||
                directLabel.contains("explícit", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    internal fun parseMetricCount(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*([kmb]|mil)?""").find(text.replace(",", "")) ?: return null
        val rawNum = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].lowercase()) {
            "k", "mil" -> rawNum * 1_000.0
            "m" -> rawNum * 1_000_000.0
            "b" -> rawNum * 1_000_000_000.0
            else -> rawNum
        }
    }

    internal fun hasMonthlyAudienceMetric(subtitle: String?): Boolean {
        if (subtitle.isNullOrBlank()) return false
        val lower = subtitle.lowercase()
        return AUDIENCE_METRIC_KEYWORDS.any { lower.contains(it) }
    }

    internal fun isLowQualityUserChannel(name: String, subtitle: String?): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return true
        if (hasMonthlyAudienceMetric(subtitle)) return false

        val lowerSub = subtitle?.lowercase().orEmpty()
        val hasSubscriberMetric = SUBSCRIBER_METRIC_KEYWORDS.any { lowerSub.contains(it) }
        if (hasSubscriberMetric) {
            if (GENERIC_CHANNEL_NAME_REGEX.containsMatchIn(trimmedName)) return true
            val count = parseMetricCount(lowerSub) ?: 0.0
            if (GENRE_SPAM_CHANNEL_REGEX.containsMatchIn(trimmedName) && count < 50_000.0) return true
        }
        return false
    }

    internal fun hasArtistMetricPart(text: String): Boolean {
        val lower = text.lowercase()
        return hasMonthlyAudienceMetric(text) || SUBSCRIBER_METRIC_KEYWORDS.any { lower.contains(it) }
    }

    private fun shouldDiscardArtistInParser(
        title: String,
        subtitle: String?,
        @Suppress("UNUSED_PARAMETER") pageType: String?
    ): Boolean = isLowQualityUserChannel(title, subtitle)


    /**
     * Parses search response JSON into categorized artists and tracks.
     */
    fun parseMultiCategorySearch(jsonString: String, preferStudio: Boolean = true): OnlineSearchResult {
        val artists = mutableListOf<OnlineArtistSummary>()
        val tracks = mutableListOf<OnlineTrack>()
        val seenArtistIds = mutableSetOf<String>()
        val seenTrackIds = mutableSetOf<String>()

        try {
            val root = JSONObject(jsonString)
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return OnlineSearchResult()

            if (tabs.length() == 0) return OnlineSearchResult()

            val tabRenderer = tabs.getJSONObject(0).optJSONObject("tabRenderer")
            val sectionListContents = tabRenderer?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return OnlineSearchResult()

            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.getJSONObject(i)

                // 1. Check musicCardShelfRenderer (top card result)
                val card = section.optJSONObject("musicCardShelfRenderer")
                if (card != null) {
                    parseCardShelf(card, artists, seenArtistIds, tracks, seenTrackIds)
                }

                // 2. Check musicShelfRenderer
                val musicShelf = section.optJSONObject("musicShelfRenderer")
                if (musicShelf != null) {
                    val contents = musicShelf.optJSONArray("contents") ?: continue
                    for (j in 0 until contents.length()) {
                        val item = contents.getJSONObject(j)
                        val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseSearchListItem(renderer, artists, seenArtistIds, tracks, seenTrackIds)
                    }
                }

                // 3. Check itemSectionRenderer
                val itemSection = section.optJSONObject("itemSectionRenderer")
                if (itemSection != null) {
                    val contents = itemSection.optJSONArray("contents") ?: continue
                    for (j in 0 until contents.length()) {
                        val item = contents.getJSONObject(j)
                        val renderer = item.optJSONObject("musicResponsiveListItemRenderer")
                        if (renderer != null) {
                            parseSearchListItem(renderer, artists, seenArtistIds, tracks, seenTrackIds)
                        }
                        val nestedShelf = item.optJSONObject("musicShelfRenderer")
                        if (nestedShelf != null) {
                            val nestedContents = nestedShelf.optJSONArray("contents") ?: continue
                            for (k in 0 until nestedContents.length()) {
                                val nestedRenderer = nestedContents.getJSONObject(k).optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                parseSearchListItem(nestedRenderer, artists, seenArtistIds, tracks, seenTrackIds)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed to parse multi-category search results")
        }

        return OnlineSearchResult(
            artists = artists,
            tracks = prioritizeExplicitTracks(if (preferStudio) prioritizeStudioTracks(tracks, true) else tracks)
        )
    }

    /**
     * Extracts the next search continuation token from an initial YouTube Music search response.
     */
    fun extractSearchContinuationToken(jsonString: String): String? {
        return try {
            val root = JSONObject(jsonString)
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs") ?: return null
            if (tabs.length() == 0) return null
            val sectionListContents = tabs.getJSONObject(0)
                .optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return null

            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.getJSONObject(i)
                val shelf = section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("itemSectionRenderer")
                        ?.optJSONArray("contents")
                        ?.optJSONObject(0)
                        ?.optJSONObject("musicShelfRenderer")
                val token = extractContinuationFromShelf(shelf)
                if (!token.isNullOrBlank()) return token
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a search continuation response JSON into results and an optional subsequent token.
     */
    fun parseSearchContinuation(jsonString: String, preferStudio: Boolean = true): Pair<OnlineSearchResult, String?> {
        val artists = mutableListOf<OnlineArtistSummary>()
        val tracks = mutableListOf<OnlineTrack>()
        val seenArtistIds = mutableSetOf<String>()
        val seenTrackIds = mutableSetOf<String>()
        var nextToken: String? = null

        try {
            val root = JSONObject(jsonString)
            val shelfContinuation = root.optJSONObject("continuationContents")
                ?.optJSONObject("musicShelfContinuation")
            if (shelfContinuation != null) {
                nextToken = extractContinuationFromShelf(shelfContinuation)
                val contents = shelfContinuation.optJSONArray("contents")
                if (contents != null) {
                    for (j in 0 until contents.length()) {
                        val renderer = contents.getJSONObject(j)
                            .optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseSearchListItem(renderer, artists, seenArtistIds, tracks, seenTrackIds)
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed to parse search continuation response")
        }

        val result = OnlineSearchResult(
            artists = artists,
            tracks = prioritizeExplicitTracks(if (preferStudio) prioritizeStudioTracks(tracks, true) else tracks)
        )
        return result to nextToken
    }

    private fun extractContinuationFromShelf(shelf: JSONObject?): String? {
        if (shelf == null) return null
        val continuations = shelf.optJSONArray("continuations")
        if (continuations != null) {
            for (i in 0 until continuations.length()) {
                val token = continuations.optJSONObject(i)
                    ?.optJSONObject("nextContinuationData")
                    ?.optString("continuation")
                if (!token.isNullOrBlank()) return token
            }
        }
        val contents = shelf.optJSONArray("contents")
        if (contents != null) {
            for (i in contents.length() - 1 downTo 0) {
                val token = contents.optJSONObject(i)
                    ?.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!token.isNullOrBlank()) return token
            }
        }
        return null
    }

    private fun parseCardShelf(
        card: JSONObject,
        artists: MutableList<OnlineArtistSummary>,
        seenArtistIds: MutableSet<String>,
        tracks: MutableList<OnlineTrack>,
        seenTrackIds: MutableSet<String>
    ) {
        val titleRuns = card.optJSONObject("title")?.optJSONArray("runs")
        val title = titleRuns?.optJSONObject(0)?.optString("text") ?: return
        val navEndpoint = titleRuns.optJSONObject(0)?.optJSONObject("navigationEndpoint")
        val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
        val browseId = browseEndpoint?.optString("browseId")
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")

        val subtitleRuns = card.optJSONObject("subtitle")?.optJSONArray("runs")
        val subtitleParts = mutableListOf<String>()
        if (subtitleRuns != null) {
            for (i in 0 until subtitleRuns.length()) {
                val text = subtitleRuns.getJSONObject(i).optString("text").trim()
                if (text != "•" && text.isNotEmpty()) subtitleParts.add(text)
            }
        }
        val hasArtistBadge = subtitleParts.any { it.equals("Artist", ignoreCase = true) || it.equals("Artista", ignoreCase = true) }
        val hasArtistMetric = subtitleParts.any(::hasArtistMetricPart)
        val hasNonArtistSubtitle = subtitleParts.any { part ->
            NON_ARTIST_SUBTITLE_LABELS.any { part.equals(it, ignoreCase = true) }
        }
        val isBlockedPageType = NON_ARTIST_PAGE_TYPES.contains(pageType)
        val isBlockedBrowseId = browseId?.startsWith("MPSP") == true || browseId?.startsWith("MPED") == true

        val isArtist = !isBlockedPageType && !isBlockedBrowseId && !hasNonArtistSubtitle &&
            (pageType == "MUSIC_PAGE_TYPE_ARTIST" || (browseId?.startsWith("UC") == true && (hasArtistBadge || hasArtistMetric))) &&
            navEndpoint?.optJSONObject("watchEndpoint") == null &&
            browseId?.startsWith("MPREb_") != true &&
            browseId?.startsWith("VL") != true &&
            browseId?.startsWith("PL") != true &&
            !title.equals("Various Artists", ignoreCase = true) &&
            !title.equals("Varios Artistas", ignoreCase = true)

        val thumbnails = card.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = if (thumbnails != null && thumbnails.length() > 0) {
            upgradeArtworkResolution(thumbnails.getJSONObject(thumbnails.length() - 1).optString("url"))
        } else null

        if (isArtist && !browseId.isNullOrBlank()) {
            val subtitle = subtitleParts
                .filterNot { it.equals("Artist", ignoreCase = true) || it.equals("Artista", ignoreCase = true) }
                .joinToString(" • ")
                .ifBlank { null }
            if (!shouldDiscardArtistInParser(title, subtitle, pageType) && seenArtistIds.add(browseId)) {
                artists.add(OnlineArtistSummary(id = browseId, name = title, avatarUrl = thumbUrl, subtitle = subtitle))
            }
        }
    }

    private fun parseSearchListItem(
        renderer: JSONObject,
        artists: MutableList<OnlineArtistSummary>,
        seenArtistIds: MutableSet<String>,
        tracks: MutableList<OnlineTrack>,
        seenTrackIds: MutableSet<String>
    ) {
        val flexColumns = renderer.optJSONArray("flexColumns") ?: return
        if (flexColumns.length() < 1) return

        val col0Runs = flexColumns.getJSONObject(0)
            .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
        val title = if (col0Runs != null && col0Runs.length() > 0) col0Runs.getJSONObject(0).optString("text") else null
        if (title.isNullOrBlank()) return

        // Check if item is an artist
        var browseId: String? = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
        if (browseId.isNullOrBlank() && col0Runs != null && col0Runs.length() > 0) {
            browseId = col0Runs.getJSONObject(0).optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId")
        }

        var navEndpoint = renderer.optJSONObject("navigationEndpoint")
        if (navEndpoint == null && col0Runs != null && col0Runs.length() > 0) {
            navEndpoint = col0Runs.getJSONObject(0).optJSONObject("navigationEndpoint")
        }
        val pageType = navEndpoint?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")

        val col1Runs = if (flexColumns.length() > 1) {
            flexColumns.getJSONObject(1)
                .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
        } else null

        val subtitleParts = mutableListOf<String>()
        if (col1Runs != null) {
            for (k in 0 until col1Runs.length()) {
                val text = col1Runs.getJSONObject(k).optString("text").trim()
                if (text != "•" && text.isNotEmpty()) {
                    subtitleParts.add(text)
                }
            }
        }

        val isFirstBadgeArtist = subtitleParts.firstOrNull()?.equals("Artist", ignoreCase = true) == true ||
            subtitleParts.firstOrNull()?.equals("Artista", ignoreCase = true) == true
        val hasArtistBadge = subtitleParts.any { it.equals("Artist", ignoreCase = true) || it.equals("Artista", ignoreCase = true) }
        val hasArtistMetric = subtitleParts.any(::hasArtistMetricPart)
        val hasNonArtistSubtitle = subtitleParts.any { part ->
            NON_ARTIST_SUBTITLE_LABELS.any { part.equals(it, ignoreCase = true) }
        }
        val isBlockedPageType = NON_ARTIST_PAGE_TYPES.contains(pageType)
        val isBlockedBrowseId = browseId?.startsWith("MPSP") == true || browseId?.startsWith("MPED") == true

        val hasWatchEndpoint = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint") != null ||
            col0Runs?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint") != null

        val isArtist = !isBlockedPageType && !isBlockedBrowseId && !hasNonArtistSubtitle &&
            (pageType == "MUSIC_PAGE_TYPE_ARTIST" || (browseId?.startsWith("UC") == true && (isFirstBadgeArtist || hasArtistBadge || hasArtistMetric))) &&
            !hasWatchEndpoint &&
            browseId?.startsWith("MPREb_") != true &&
            browseId?.startsWith("VL") != true &&
            browseId?.startsWith("PL") != true &&
            !title.equals("Various Artists", ignoreCase = true) &&
            !title.equals("Varios Artistas", ignoreCase = true)

        if (isArtist && !browseId.isNullOrBlank()) {
            val subtitle = subtitleParts
                .filterNot { it.equals("Artist", ignoreCase = true) || it.equals("Artista", ignoreCase = true) }
                .joinToString(" • ")
                .ifBlank { null }
            if (!shouldDiscardArtistInParser(title, subtitle, pageType) && seenArtistIds.add(browseId)) {
                val thumbnails = renderer.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                val thumbUrl = if (thumbnails != null && thumbnails.length() > 0) {
                    upgradeArtworkResolution(thumbnails.getJSONObject(thumbnails.length() - 1).optString("url"))
                } else null

                artists.add(OnlineArtistSummary(id = browseId, name = title, avatarUrl = thumbUrl, subtitle = subtitle))
            }
            return
        }

        val isPlaylist = pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || subtitleParts.any {
            it.equals("Playlist", ignoreCase = true) || it.equals("Lista de reproducción", ignoreCase = true) || it.equals("Lista de reproduccion", ignoreCase = true)
        }
        if (isPlaylist) {
            return
        }

        // Early block: non-music entities (user channels, profiles, podcast shows, podcast episodes) must not fall through to parseTrackItem
        val isNonMusicEntity = isBlockedPageType || isBlockedBrowseId || hasNonArtistSubtitle
        if (isNonMusicEntity) {
            return
        }

        // Attempt parsing as track
        val track = parseTrackItem(renderer)
        if (track != null && seenTrackIds.add(track.id)) {
            tracks.add(track)
        }
    }

    /**
     * Parses search response JSON into a list of [OnlineTrack] models.
     */
    fun parseSearchResults(jsonString: String, preferStudio: Boolean = true): List<OnlineTrack> {
        return parseMultiCategorySearch(jsonString, preferStudio).tracks
    }

    private fun extractMusicVideoType(renderer: JSONObject): String? {
        val paths = listOf(
            renderer.optJSONObject("doubleTapCommand")?.optJSONObject("watchEndpoint"),
            renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint"),
            renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
        )
        for (endpoint in paths) {
            val type = endpoint?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType")
            if (!type.isNullOrBlank()) return type
        }

        val flexColumns = renderer.optJSONArray("flexColumns")
        if (flexColumns != null && flexColumns.length() > 0) {
            val runs = flexColumns.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
            if (runs != null && runs.length() > 0) {
                val runEndpoint = runs.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                val type = runEndpoint?.optJSONObject("watchEndpointMusicSupportedConfigs")
                    ?.optJSONObject("watchEndpointMusicConfig")
                    ?.optString("musicVideoType")
                if (!type.isNullOrBlank()) return type
            }
        }
        return null
    }

    fun parseTrackItem(renderer: JSONObject): OnlineTrack? {
        // Extract video ID
        val playlistItemData = renderer.optJSONObject("playlistItemData")
        var videoId = playlistItemData?.optString("videoId")
        if (videoId.isNullOrBlank()) {
            val doubleTapCommand = renderer.optJSONObject("doubleTapCommand")
            videoId = doubleTapCommand?.optJSONObject("watchEndpoint")?.optString("videoId")
        }

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        if (flexColumns.length() < 2) return null

        // Column 0 contains Title
        val col0Runs = flexColumns.getJSONObject(0)
            .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return null

        val title = if (col0Runs.length() > 0) col0Runs.getJSONObject(0).optString("text") else return null
        if (JUNK_TRACK_TITLE_REGEX.containsMatchIn(title)) return null
        if (videoId.isNullOrBlank() && col0Runs.length() > 0) {
            videoId = col0Runs.getJSONObject(0).optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }

        if (videoId.isNullOrBlank()) return null

        val candidateEndpoints = listOfNotNull(
            renderer.optJSONObject("navigationEndpoint"),
            if (col0Runs.length() > 0) col0Runs.getJSONObject(0).optJSONObject("navigationEndpoint") else null,
            renderer.optJSONObject("doubleTapCommand")
        )
        for (endpoint in candidateEndpoints) {
            val browseEndpoint = endpoint.optJSONObject("browseEndpoint")
            val bId = browseEndpoint?.optString("browseId") ?: ""
            val pType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType") ?: ""
            val hasWatchEndpoint = endpoint.optJSONObject("watchEndpoint") != null
            if (NON_ARTIST_PAGE_TYPES.contains(pType) ||
                bId.startsWith("MPED") ||
                bId.startsWith("MPSP") ||
                (!hasWatchEndpoint && (pType == "MUSIC_PAGE_TYPE_ARTIST" || bId.startsWith("UC")))
            ) {
                return null
            }
        }

        var artist: String? = null
        var artistId: String? = null
        var album: String? = null
        var albumId: String? = null
        var durationMs = 0L

        // Check fixed columns for duration (common in album views)
        val fixedCols = renderer.optJSONArray("fixedColumns")
        if (fixedCols != null) {
            for (i in 0 until fixedCols.length()) {
                val fixedRuns = fixedCols.getJSONObject(i)
                    .optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs") ?: continue
                for (j in 0 until fixedRuns.length()) {
                    val t = fixedRuns.getJSONObject(j).optString("text").trim()
                    if (t.matches(Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}$"""))) {
                        durationMs = parseDuration(t)
                        break
                    }
                }
            }
        }

        val songLabels = setOf(
            "song", "canción", "cancion", "track", "pista", "chanson", "lied", "faixa", "brano"
        )
        val videoLabels = setOf(
            "video", "vídeo", "music video", "video musical", "clip", "vídeo musical"
        )
        var hasSongBadge = false
        var hasVideoBadge = false

        val ignoredTypeLabels = setOf(
            "song", "canción", "cancion",
            "video", "vídeo",
            "track", "pista",
            "single", "sencillo",
            "ep",
            "album", "álbum",
            "playlist", "lista de reproducción", "lista de reproduccion",
            "artist", "artista"
        )
        val artistSeparators = setOf(
            "&", ",", "feat.", "feat", "ft.", "ft", "y", "with", "+", "x", "vs.", "vs", "prod.", "prod"
        )
        val countRegex = Regex("""(?i)\d+([.,]\d+)?\s*(m|k|b|mil|millones|views|visualizaciones|plays|reproducciones|oyentes|suscriptores?|subscribers?|canciones?|songs?)""")
        val durationRegex = Regex("""^(\d{1,2}:)?\d{1,2}:\d{2}$""")

        val artistRuns = mutableListOf<String>()
        val unclassifiedTextRuns = mutableListOf<String>()

        for (colIdx in 1 until flexColumns.length()) {
            val runs = flexColumns.getJSONObject(colIdx)
                .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: continue

            for (k in 0 until runs.length()) {
                val runObj = runs.getJSONObject(k)
                val text = runObj.optString("text").trim()
                if (text.isEmpty() || text == "•" || text == "·") continue

                val navEndpoint = runObj.optJSONObject("navigationEndpoint")
                val browseEndpoint = navEndpoint?.optJSONObject("browseEndpoint")
                val browseId = browseEndpoint?.optString("browseId") ?: ""
                val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType") ?: ""

                val lowerText = text.lowercase()
                if (NON_ARTIST_PAGE_TYPES.contains(pageType) ||
                    browseId.startsWith("MPED") ||
                    browseId.startsWith("MPSP") ||
                    NON_MUSIC_AUDIO_LABELS.contains(lowerText)
                ) {
                    return null
                }
                when {
                    songLabels.contains(lowerText) -> {
                        hasSongBadge = true
                    }
                    videoLabels.contains(lowerText) -> {
                        hasVideoBadge = true
                    }
                    pageType == "MUSIC_PAGE_TYPE_ARTIST" || browseId.startsWith("UC") -> {
                        if (artistId == null && browseId.isNotBlank()) {
                            artistId = browseId
                        }
                        artistRuns.add(text)
                    }
                    pageType == "MUSIC_PAGE_TYPE_ALBUM" || browseId.startsWith("MPRE") -> {
                        if (album == null) {
                            album = text
                            if (browseId.isNotBlank()) albumId = browseId
                        }
                    }
                    durationRegex.matches(text) -> {
                        if (durationMs == 0L) durationMs = parseDuration(text)
                    }
                    ignoredTypeLabels.contains(lowerText) || artistSeparators.contains(lowerText) -> {
                        // Skip type label or artist separator (&, feat, ft, etc.)
                    }
                    countRegex.containsMatchIn(text) -> {
                        // Skip count/stats
                    }
                    else -> {
                        unclassifiedTextRuns.add(text)
                    }
                }
            }
        }

        if (artistRuns.isNotEmpty()) {
            artist = artistRuns.joinToString(", ")
        } else if (unclassifiedTextRuns.isNotEmpty()) {
            artist = unclassifiedTextRuns.removeAt(0)
        }

        if (artist.isNullOrBlank()) return null
        if (hasArtistMetricPart(artist)) return null
        val cleanArtistLower = artist.trim().lowercase()
        if (NON_MUSIC_AUDIO_LABELS.contains(cleanArtistLower) || cleanArtistLower == "show" || cleanArtistLower == "programa") {
            return null
        }
        if (artistId?.startsWith("MPSP") == true || artistId?.startsWith("MPED") == true) return null
        if (albumId?.startsWith("MPSP") == true || albumId?.startsWith("MPED") == true) return null

        if (album == null && unclassifiedTextRuns.isNotEmpty()) {
            val candidateAlbum = unclassifiedTextRuns.firstOrNull { 
                it.isNotBlank() && it !in artistSeparators && it.lowercase() !in artistSeparators 
            }
            if (candidateAlbum != null) {
                album = candidateAlbum
            }
        }

        // Badges check
        val badges = renderer.optJSONArray("badges")
        var hasExplicitBadge = parseExplicitFromBadges(badges)
        if (badges != null) {
            for (i in 0 until badges.length()) {
                val badgeObj = badges.optJSONObject(i)?.optJSONObject("musicInlineBadgeRenderer")
                val nestedLabel = badgeObj?.optJSONObject("accessibilityData")
                    ?.optJSONObject("accessibilityData")
                    ?.optString("label")
                val label = nestedLabel?.takeIf { it.isNotBlank() }
                    ?: badgeObj?.optJSONObject("icon")?.optString("iconType")
                    ?: badgeObj?.optString("accessibilityData")
                    ?: ""
                val lower = label.lowercase()
                if (NON_MUSIC_AUDIO_LABELS.any { lower.contains(it) }) return null
                if (videoLabels.any { lower.contains(it) }) hasVideoBadge = true
                if (songLabels.any { lower.contains(it) }) hasSongBadge = true
                if (lower.contains("explicit") || lower.contains("explícit")) hasExplicitBadge = true
            }
        }
        val isExplicit = hasExplicitBadge || EXPLICIT_TITLE_REGEX.containsMatchIn(title)

        val musicVideoType = extractMusicVideoType(renderer)
        val hasVideoInTitle = title.contains(Regex("""(?i)\b(official\s+video|video\s+oficial|music\s+video|official\s+music\s+video)\b"""))
        val isStudioTrack = when {
            musicVideoType == "MUSIC_VIDEO_TYPE_ATV" -> true
            musicVideoType == "MUSIC_VIDEO_TYPE_OMV" || musicVideoType == "MUSIC_VIDEO_TYPE_UGC" -> false
            hasVideoBadge -> false
            hasSongBadge -> true
            hasVideoInTitle -> false
            else -> true
        }

        // Thumbnail
        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        var artworkUrl: String? = null
        if (thumbnails != null && thumbnails.length() > 0) {
            val lastThumb = thumbnails.getJSONObject(thumbnails.length() - 1).optString("url")
            artworkUrl = upgradeArtworkResolution(lastThumb)
        }

        return OnlineTrack(
            id = videoId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUrl = artworkUrl,
            isStudioTrack = isStudioTrack,
            albumId = albumId,
            artistId = artistId,
            isExplicit = isExplicit
        )
    }

    /**
     * Parses artist catalog profile details from an InnerTube browse response.
     */
    fun parseArtistDetail(browseId: String, jsonString: String, preferStudio: Boolean = true): OnlineArtistDetail {
        var name = ""
        var description: String? = null
        var avatarUrl: String? = null
        var subscribers: String? = null
        val topSongs = mutableListOf<OnlineTrack>()
        val albums = mutableListOf<OnlineAlbumSummary>()
        val singles = mutableListOf<OnlineAlbumSummary>()
        val relatedArtists = mutableListOf<OnlineArtistSummary>()
        val seenAlbumIds = mutableSetOf<String>()
        val seenSingleIds = mutableSetOf<String>()
        var albumsBrowseId: String? = null
        var albumsBrowseParams: String? = null
        var singlesBrowseId: String? = null
        var singlesBrowseParams: String? = null

        fun normalizeHeader(raw: String): String =
            Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase()
                .trim()

        fun addAlbumRelease(target: MutableList<OnlineAlbumSummary>, seen: MutableSet<String>, release: OnlineAlbumSummary) {
            if (release.id.isNotBlank() && seen.add(release.id)) {
                target.add(release)
            }
        }

        fun extractMoreBrowseEndpoint(headerRenderer: JSONObject?, shelfOrCarousel: JSONObject?): JSONObject? {
            return headerRenderer?.optJSONObject("moreContentButton")
                ?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?: headerRenderer?.optJSONObject("title")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                ?: shelfOrCarousel?.optJSONObject("bottomEndpoint")
                    ?.optJSONObject("browseEndpoint")
        }

        fun parseTwoRowReleaseOrArtist(
            twoRow: JSONObject,
            headerNorm: String
        ) {
            val itemTitle = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
            val itemBrowseId = twoRow.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId") ?: ""
            if (itemBrowseId.startsWith("MPSP") || itemBrowseId.startsWith("MPED")) {
                return
            }
            val thumbs = twoRow.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            val itemThumb = if (thumbs != null && thumbs.length() > 0) {
                upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
            } else null

            val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
            val subtitleText = if (subtitleRuns != null) {
                (0 until subtitleRuns.length()).joinToString("") { subtitleRuns.getJSONObject(it).optString("text") }.trim()
            } else null

            val yearMatch = subtitleText?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value }
            val subtitleNorm = normalizeHeader(subtitleText.orEmpty())
            val isReleaseId = itemBrowseId.startsWith("MPREb_") || itemBrowseId.startsWith("OLAK5uy_")

            when {
                headerNorm.contains("album") ||
                    headerNorm.contains("release") ||
                    headerNorm.contains("lanzamiento") ||
                    headerNorm.contains("discogra") -> {
                    val release = OnlineAlbumSummary(id = itemBrowseId, title = itemTitle, year = yearMatch ?: subtitleText, coverUrl = itemThumb)
                    if (subtitleNorm.contains("single") || subtitleNorm.contains("sencillo") || Regex("""\bep\b""").containsMatchIn(subtitleNorm)) {
                        addAlbumRelease(singles, seenSingleIds, release)
                    } else {
                        addAlbumRelease(albums, seenAlbumIds, release)
                    }
                }
                headerNorm.contains("single") ||
                    headerNorm.contains("sencillo") ||
                    Regex("""\beps?\b""").containsMatchIn(headerNorm) -> {
                    addAlbumRelease(singles, seenSingleIds, OnlineAlbumSummary(id = itemBrowseId, title = itemTitle, year = yearMatch ?: subtitleText, coverUrl = itemThumb))
                }
                headerNorm.contains("fans") ||
                    headerNorm.contains("like") ||
                    headerNorm.contains("similar") ||
                    headerNorm.contains("gustan") ||
                    headerNorm.contains("similares") -> {
                    relatedArtists.add(OnlineArtistSummary(id = itemBrowseId, name = itemTitle, avatarUrl = itemThumb, subtitle = subtitleText))
                }
                isReleaseId -> {
                    val release = OnlineAlbumSummary(id = itemBrowseId, title = itemTitle, year = yearMatch ?: subtitleText, coverUrl = itemThumb)
                    if (subtitleNorm.contains("single") || subtitleNorm.contains("sencillo") || Regex("""\bep\b""").containsMatchIn(subtitleNorm)) {
                        addAlbumRelease(singles, seenSingleIds, release)
                    } else {
                        addAlbumRelease(albums, seenAlbumIds, release)
                    }
                }
            }
        }

        try {
            val root = JSONObject(jsonString)
            val header = root.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: root.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")
                ?: root.optJSONObject("header")?.optJSONObject("musicHeaderRenderer")
            if (header != null) {
                name = header.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val descRuns = header.optJSONObject("description")?.optJSONArray("runs")
                val rawDescription = if (descRuns != null) {
                    (0 until descRuns.length()).joinToString("") { descRuns.getJSONObject(it).optString("text") }
                } else null
                description = rawDescription?.let { cleanArtistDescription(it) }?.ifBlank { null }
                val thumbs = header.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                if (thumbs != null && thumbs.length() > 0) {
                    avatarUrl = upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
                }
                val candidateRunArrays = listOfNotNull(
                    header.optJSONObject("monthlyListenerCount")?.optJSONArray("runs"),
                    header.optJSONObject("subtitle")?.optJSONArray("runs"),
                    header.optJSONObject("subscriptionButton")
                        ?.optJSONObject("subscribeButtonRenderer")
                        ?.optJSONObject("longSubscriberCountText")
                        ?.optJSONArray("runs"),
                    header.optJSONObject("subscriptionButton")
                        ?.optJSONObject("subscribeButtonRenderer")
                        ?.optJSONObject("subscriberCountText")
                        ?.optJSONArray("runs")
                )
                for (runArray in candidateRunArrays) {
                    val texts = (0 until runArray.length())
                        .mapNotNull {
                            runArray.optJSONObject(it)?.optString("text")?.trim()
                                ?.takeIf { t -> t.isNotBlank() && t != "•" && t != "·" }
                        }
                    val metric = texts.firstOrNull { hasMonthlyAudienceMetric(it) }
                        ?: texts.firstOrNull { it.any(Char::isDigit) }
                    if (metric != null) {
                        subscribers = metric
                        break
                    }
                }
            }

            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
            val sections = if (tabs != null && tabs.length() > 0) {
                tabs.getJSONObject(0)
                    .optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
            } else null

            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val section = sections.getJSONObject(i)
                    val shelf = section.optJSONObject("musicShelfRenderer")
                    val carousel = section.optJSONObject("musicCarouselShelfRenderer")
                    val grid = section.optJSONObject("gridRenderer")

                    if (shelf != null) {
                        val contents = shelf.optJSONArray("contents") ?: continue
                        val shelfTitle = shelf.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                        val shelfHeaderNorm = normalizeHeader(shelfTitle)
                        for (j in 0 until contents.length()) {
                            val item = contents.getJSONObject(j)
                            val twoRow = item.optJSONObject("musicTwoRowItemRenderer")
                            if (twoRow != null) {
                                parseTwoRowReleaseOrArtist(twoRow, shelfHeaderNorm)
                                continue
                            }
                            val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            val itemBrowseId = renderer.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                                ?.optString("browseId") ?: ""
                            if (itemBrowseId.startsWith("MPREb_") || itemBrowseId.startsWith("OLAK5uy_")) {
                                parseResponsiveAlbumItem(renderer)?.let { rel ->
                                    if (shelfHeaderNorm.contains("single") || shelfHeaderNorm.contains("sencillo") || Regex("""\beps?\b""").containsMatchIn(shelfHeaderNorm)) {
                                        addAlbumRelease(singles, seenSingleIds, rel)
                                    } else {
                                        addAlbumRelease(albums, seenAlbumIds, rel)
                                    }
                                }
                            } else {
                                val track = parseTrackItem(renderer)
                                if (track != null) {
                                    topSongs.add(track)
                                }
                            }
                        }
                    } else if (carousel != null) {
                        val carouselHeader = carousel.optJSONObject("header")
                            ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                        val headerTitle = carouselHeader?.optJSONObject("title")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text") ?: ""
                        val headerNorm = normalizeHeader(headerTitle)

                        val moreEndpoint = extractMoreBrowseEndpoint(carouselHeader, carousel)
                        val moreBrowseId = moreEndpoint?.optString("browseId")?.takeIf { it.isNotBlank() }
                        val moreParams = moreEndpoint?.optString("params")?.takeIf { it.isNotBlank() }
                        when {
                            moreBrowseId != null && (headerNorm.contains("album") || headerNorm.contains("release") || headerNorm.contains("lanzamiento") || headerNorm.contains("discogra")) -> {
                                if (albumsBrowseId == null) {
                                    albumsBrowseId = moreBrowseId
                                    albumsBrowseParams = moreParams
                                }
                            }
                            moreBrowseId != null && (headerNorm.contains("single") || headerNorm.contains("sencillo") || Regex("""\beps?\b""").containsMatchIn(headerNorm)) -> {
                                if (singlesBrowseId == null) {
                                    singlesBrowseId = moreBrowseId
                                    singlesBrowseParams = moreParams
                                }
                            }
                        }

                        val contents = carousel.optJSONArray("contents") ?: continue
                        for (j in 0 until contents.length()) {
                            val item = contents.getJSONObject(j)
                            val twoRow = item.optJSONObject("musicTwoRowItemRenderer") ?: continue
                            parseTwoRowReleaseOrArtist(twoRow, headerNorm)
                        }
                    } else if (grid != null) {
                        val gridTitle = grid.optJSONObject("header")
                            ?.optJSONObject("gridHeaderRenderer")
                            ?.optJSONObject("title")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text") ?: ""
                        val gridHeaderNorm = normalizeHeader(gridTitle)
                        val items = grid.optJSONArray("items") ?: continue
                        for (j in 0 until items.length()) {
                            val twoRow = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                            parseTwoRowReleaseOrArtist(twoRow, gridHeaderNorm)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed to parse artist detail for %s", browseId)
        }

        val prioritizedTopSongs = prioritizeExplicitTracks(
            if (preferStudio) prioritizeStudioTracks(topSongs, true) else topSongs
        )
        val resolvedAvatarUrl = avatarUrl?.takeIf { it.isNotBlank() }
            ?: prioritizedTopSongs.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
            ?: albums.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
            ?: singles.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl

        return OnlineArtistDetail(
            id = browseId,
            name = name.ifBlank { "Unknown Artist" },
            avatarUrl = resolvedAvatarUrl,
            description = description,
            topSongs = prioritizedTopSongs,
            albums = albums,
            singles = singles,
            relatedArtists = relatedArtists,
            subscribers = subscribers,
            albumsBrowseId = albumsBrowseId,
            albumsBrowseParams = albumsBrowseParams,
            singlesBrowseId = singlesBrowseId,
            singlesBrowseParams = singlesBrowseParams
        )
    }

    private fun parseResponsiveAlbumItem(renderer: JSONObject): OnlineAlbumSummary? {
        val navEndpoint = renderer.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?: renderer.optJSONArray("flexColumns")
                ?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
        val albumBrowseId = navEndpoint?.optString("browseId") ?: return null
        if (!albumBrowseId.startsWith("MPREb_") && !albumBrowseId.startsWith("OLAK5uy_")) return null

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
        val title = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() } ?: return null

        var yearText: String? = null
        if (flexColumns.length() > 1) {
            val col1Runs = flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
            if (col1Runs != null) {
                val joined = (0 until col1Runs.length()).joinToString("") { col1Runs.getJSONObject(it).optString("text") }.trim()
                yearText = Regex("""\b(19|20)\d{2}\b""").find(joined)?.value ?: joined.takeIf { it.isNotBlank() }
            }
        }

        val thumbs = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val coverUrl = if (thumbs != null && thumbs.length() > 0) {
            upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
        } else null

        return OnlineAlbumSummary(
            id = albumBrowseId,
            title = title,
            year = yearText,
            coverUrl = coverUrl
        )
    }

    /**
     * Parses a full artist discography browse page (or continuation response) returning all
     * album/single releases alongside an optional continuation token.
     */
    fun parseArtistDiscographyBrowseWithContinuation(jsonString: String): Pair<List<OnlineAlbumSummary>, String?> {
        val releases = mutableListOf<OnlineAlbumSummary>()
        val seenIds = mutableSetOf<String>()
        var nextToken: String? = null

        fun addTwoRowIfRelease(twoRow: JSONObject) {
            val title = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            val browseId = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            if (title.isBlank() || browseId.isBlank()) return
            if (browseId.startsWith("MPSP") || browseId.startsWith("MPED")) return
            if (!browseId.startsWith("MPREb_") && !browseId.startsWith("OLAK5uy_")) return

            val thumbs = twoRow.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            val coverUrl = if (thumbs != null && thumbs.length() > 0) {
                upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
            } else null

            val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
            val subtitleText = if (subtitleRuns != null) {
                (0 until subtitleRuns.length()).joinToString("") { subtitleRuns.getJSONObject(it).optString("text") }.trim()
            } else null
            val yearMatch = subtitleText?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }

            if (seenIds.add(browseId)) {
                releases.add(
                    OnlineAlbumSummary(
                        id = browseId,
                        title = title,
                        year = yearMatch ?: subtitleText,
                        coverUrl = coverUrl
                    )
                )
            }
        }

        fun collectItemsArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                obj.optJSONObject("musicTwoRowItemRenderer")?.let(::addTwoRowIfRelease)
                obj.optJSONObject("musicResponsiveListItemRenderer")?.let { resp ->
                    parseResponsiveAlbumItem(resp)?.let { rel ->
                        if (seenIds.add(rel.id)) releases.add(rel)
                    }
                }
                obj.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { if (nextToken == null) nextToken = it }
            }
        }

        try {
            val root = JSONObject(jsonString)

            // 1. Standard browse response
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
            if (tabs != null && tabs.length() > 0) {
                val sectionContents = tabs.getJSONObject(0)
                    .optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                if (sectionContents != null) {
                    for (i in 0 until sectionContents.length()) {
                        val sec = sectionContents.optJSONObject(i) ?: continue
                        sec.optJSONObject("gridRenderer")?.let { grid ->
                            collectItemsArray(grid.optJSONArray("items"))
                            if (nextToken == null) nextToken = extractContinuationFromShelf(grid)
                        }
                        sec.optJSONObject("musicShelfRenderer")?.let { shelf ->
                            collectItemsArray(shelf.optJSONArray("contents"))
                            if (nextToken == null) nextToken = extractContinuationFromShelf(shelf)
                        }
                        sec.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                            collectItemsArray(carousel.optJSONArray("contents"))
                        }
                        sec.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")?.let { itemContents ->
                            for (k in 0 until itemContents.length()) {
                                val nested = itemContents.optJSONObject(k) ?: continue
                                nested.optJSONObject("gridRenderer")?.let { g ->
                                    collectItemsArray(g.optJSONArray("items"))
                                    if (nextToken == null) nextToken = extractContinuationFromShelf(g)
                                }
                                nested.optJSONObject("musicShelfRenderer")?.let { s ->
                                    collectItemsArray(s.optJSONArray("contents"))
                                    if (nextToken == null) nextToken = extractContinuationFromShelf(s)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Continuation response
            val cont = root.optJSONObject("continuationContents")
            cont?.optJSONObject("gridContinuation")?.let { gridCont ->
                collectItemsArray(gridCont.optJSONArray("items"))
                if (nextToken == null) nextToken = extractContinuationFromShelf(gridCont)
            }
            cont?.optJSONObject("musicShelfContinuation")?.let { shelfCont ->
                collectItemsArray(shelfCont.optJSONArray("contents"))
                if (nextToken == null) nextToken = extractContinuationFromShelf(shelfCont)
            }
        } catch (e: Exception) {
            L.w(e, "Failed to parse artist discography browse page")
        }

        return releases to nextToken
    }

    fun parseArtistDiscographyBrowse(jsonString: String): List<OnlineAlbumSummary> =
        parseArtistDiscographyBrowseWithContinuation(jsonString).first

    /**
     * Parses album details and ordered tracklist from an InnerTube browse response.
     */
    fun parseAlbumDetail(browseId: String, jsonString: String): OnlineAlbumDetail {
        if (browseId.startsWith("MPSP") || browseId.startsWith("MPED")) {
            return OnlineAlbumDetail(id = browseId, title = "Unknown", artist = "Unknown Artist", year = null, coverUrl = null, tracks = emptyList())
        }

        var title = ""
        var artist = "Unknown Artist"
        var year: String? = null
        var coverUrl: String? = null
        val tracks = mutableListOf<OnlineTrack>()

        try {
            val root = JSONObject(jsonString)
            val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")

            // 1. Header parsing
            val headerSection = twoCol?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveHeaderRenderer")

            val detailHeader = root.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")

            val targetHeader = headerSection ?: detailHeader
            if (targetHeader != null) {
                title = targetHeader.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val strapline = targetHeader.optJSONObject("straplineTextOne")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                if (!strapline.isNullOrBlank()) {
                    artist = strapline
                }

                val subtitleRuns = targetHeader.optJSONObject("subtitle")?.optJSONArray("runs")
                if (subtitleRuns != null) {
                    val subText = (0 until subtitleRuns.length()).joinToString("") { subtitleRuns.getJSONObject(it).optString("text") }
                    year = Regex("\\b(19|20)\\d{2}\\b").find(subText)?.value
                    if (artist == "Unknown Artist") {
                        for (k in 0 until subtitleRuns.length()) {
                            val t = subtitleRuns.getJSONObject(k).optString("text").trim()
                            if (t != "•" && t.isNotEmpty() && !t.equals("Album", true) && !t.matches(Regex("\\d{4}"))) {
                                artist = t
                                break
                            }
                        }
                    }
                }

                val thumbs = targetHeader.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                    ?: targetHeader.optJSONObject("thumbnail")
                        ?.optJSONObject("croppedSquareThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                if (thumbs != null && thumbs.length() > 0) {
                    coverUrl = upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
                }
            }

            // 2. Tracklist parsing from secondaryContents
            val secondarySections = twoCol?.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            if (secondarySections != null) {
                for (i in 0 until secondarySections.length()) {
                    val sec = secondarySections.getJSONObject(i)
                    val shelf = sec.optJSONObject("musicShelfRenderer") ?: continue
                    val contents = shelf.optJSONArray("contents") ?: continue

                    for (j in 0 until contents.length()) {
                        val item = contents.getJSONObject(j)
                        val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")
                            ?: renderer.optJSONObject("doubleTapCommand")?.optJSONObject("watchEndpoint")?.optString("videoId")
                            ?: continue

                        val flexColumns = renderer.optJSONArray("flexColumns")
                        val trackTitle = flexColumns?.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text") ?: "Track ${j + 1}"

                        var durationMs = 0L
                        val fixedColumns = renderer.optJSONArray("fixedColumns")
                        if (fixedColumns != null && fixedColumns.length() > 0) {
                            val durationText = fixedColumns.getJSONObject(0)
                                .optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text")
                            if (!durationText.isNullOrBlank()) {
                                durationMs = parseDuration(durationText)
                            }
                        }

                        if (durationMs == 0L && flexColumns != null && flexColumns.length() > 1) {
                            val col1Runs = flexColumns.getJSONObject(1)
                                .optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                ?.optJSONObject("text")
                                ?.optJSONArray("runs")
                            if (col1Runs != null) {
                                for (k in 0 until col1Runs.length()) {
                                    val t = col1Runs.getJSONObject(k).optString("text").trim()
                                    if (t.contains(":")) {
                                        durationMs = parseDuration(t)
                                        break
                                    }
                                }
                            }
                        }

                        val parsedIndex = renderer.optJSONObject("index")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text")
                            ?.toIntOrNull()
                        val trackNum = parsedIndex ?: (j + 1)
                        val albumYearInt = year?.toIntOrNull()
                        val trackExplicit = parseExplicitFromBadges(renderer.optJSONArray("badges")) ||
                            EXPLICIT_TITLE_REGEX.containsMatchIn(trackTitle)

                        tracks.add(
                            OnlineTrack(
                                id = videoId,
                                title = trackTitle,
                                artist = artist,
                                album = title.ifBlank { null },
                                durationMs = durationMs,
                                artworkUrl = coverUrl,
                                trackNumber = trackNum,
                                year = albumYearInt,
                                isStudioTrack = true,
                                isExplicit = trackExplicit
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            L.w(e, "Failed to parse album detail for %s", browseId)
        }

        return OnlineAlbumDetail(
            id = browseId,
            title = title.ifBlank { "Unknown Album" },
            artist = artist,
            year = year,
            coverUrl = coverUrl,
            tracks = tracks
        )
    }

    /**
     * Prioritizes studio audio tracks over music videos, deduplicating video versions of tracks
     * that already have a studio counterpart while retaining exclusive videos as fallbacks.
     */
    fun prioritizeStudioTracks(
        tracks: List<OnlineTrack>,
        preferStudio: Boolean = true
    ): List<OnlineTrack> {
        if (!preferStudio || tracks.isEmpty()) return tracks

        val (studioTracks, videoTracks) = tracks.partition { it.isStudioTrack }
        if (videoTracks.isEmpty()) return tracks
        if (studioTracks.isEmpty()) return tracks

        val studioKeys = studioTracks.map { track ->
            Pair(SongMatcher.normalizeTitle(track.title), SongMatcher.normalizeArtist(track.artist))
        }

        // Deduplicate video tracks that match a studio track by normalized title and artist
        val fallbackVideos = videoTracks.filterNot { video ->
            val vTitle = SongMatcher.normalizeTitle(video.title)
            val vArtist = SongMatcher.normalizeArtist(video.artist)
            studioKeys.any { (sTitle, sArtist) ->
                sTitle == vTitle && (sArtist.isEmpty() || vArtist.isEmpty() || sArtist == vArtist || sArtist.contains(vArtist) || vArtist.contains(sArtist))
            }
        }

        return studioTracks + fallbackVideos
    }

    /**
     * Prioritizes uncensored/explicit versions over clean or radio-edited versions of the same
     * song, removing redundant clean duplicates while keeping exclusive clean releases.
     */
    fun prioritizeExplicitTracks(tracks: List<OnlineTrack>): List<OnlineTrack> {
        if (tracks.size <= 1) return tracks

        fun baseTitle(rawTitle: String): String {
            val stripped = STRIP_EDITION_TAGS_REGEX.replace(rawTitle, " ").trim()
            return SongMatcher.normalizeTitle(stripped).ifBlank { SongMatcher.normalizeTitle(rawTitle) }
        }

        fun versionScore(track: OnlineTrack): Int {
            var score = 0
            if (track.isExplicit) score += 10
            if (track.isStudioTrack) score += 4
            if (CLEAN_TITLE_REGEX.containsMatchIn(track.title)) score -= 10
            return score
        }

        val bestBySignature = LinkedHashMap<String, OnlineTrack>()
        for (track in tracks) {
            val primaryArtist = SongMatcher.normalizeArtist(
                track.artist.split(",", "&", "feat.", "ft.").firstOrNull().orEmpty()
            )
            val signature = "${baseTitle(track.title)}|$primaryArtist"
            val existing = bestBySignature[signature]
            if (existing == null || versionScore(track) > versionScore(existing)) {
                bestBySignature[signature] = track
            }
        }
        return bestBySignature.values.toList()
    }

    /**
     * Converts a duration string such as "3:25" or "1:02:15" into milliseconds.
     */
    fun parseDuration(durationStr: String): Long {
        val parts = durationStr.split(":")
        var seconds = 0L
        try {
            for (part in parts) {
                seconds = seconds * 60 + part.toLong()
            }
        } catch (_: NumberFormatException) {
            return 0L
        }
        return seconds * 1000L
    }

    /**
     * Upgrades Google/YouTube image thumbnail URLs to high-resolution square artwork.
     */
    fun upgradeArtworkResolution(url: String): String {
        return url.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w544-h544-l90-rj")
            .replace(Regex("=s\\d+[^&?]*"), "=s800")
    }

    /**
     * Parses stream audio URLs from an InnerTube player response.
     */
    fun parseStreamUrl(playerJsonResponse: String, quality: AudioQuality): String? {
        try {
            val root = JSONObject(playerJsonResponse)
            val streamingData = root.optJSONObject("streamingData") ?: return null
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")

            var bestMp4Url: String? = null
            var bestMp4Bitrate = 0
            var bestFallbackUrl: String? = null
            var bestFallbackBitrate = 0

            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val mimeType = format.optString("mimeType").lowercase()
                    if (!mimeType.startsWith("audio/")) continue

                    val url = format.optString("url")
                    if (url.isBlank()) continue

                    val bitrate = format.optInt("bitrate", 0)
                    val isMp4 = mimeType.startsWith("audio/mp4")

                    if (quality == AudioQuality.DATA_SAVER) {
                        if (isMp4) {
                            if (bestMp4Url == null || (bitrate in 45000..128000 && bitrate < bestMp4Bitrate)) {
                                bestMp4Url = url
                                bestMp4Bitrate = bitrate
                            }
                        } else if (bestFallbackUrl == null || (bitrate in 45000..128000 && bitrate < bestFallbackBitrate)) {
                            bestFallbackUrl = url
                            bestFallbackBitrate = bitrate
                        }
                    } else {
                        if (isMp4) {
                            if (bestMp4Url == null || bitrate > bestMp4Bitrate) {
                                bestMp4Url = url
                                bestMp4Bitrate = bitrate
                            }
                        } else if (bestFallbackUrl == null || bitrate > bestFallbackBitrate) {
                            bestFallbackUrl = url
                            bestFallbackBitrate = bitrate
                        }
                    }
                }
            }

            if (!bestMp4Url.isNullOrBlank()) {
                return bestMp4Url
            }
            if (!bestFallbackUrl.isNullOrBlank()) {
                return bestFallbackUrl
            }

            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    val url = format.optString("url")
                    if (url.isNotBlank()) {
                        return url
                    }
                }
            }

            return null
        } catch (e: Exception) {
            L.w(e, "Error parsing stream URL from player response")
            return null
        }
    }

    /**
     * Parses stream audio URLs and duration/details from an InnerTube player response.
     */
    fun parseStreamInfo(playerJsonResponse: String, quality: AudioQuality): StreamInfo? {
        val streamUrl = parseStreamUrl(playerJsonResponse, quality) ?: return null
        var durationMs = 0L
        var author: String? = null

        try {
            val root = JSONObject(playerJsonResponse)
            val videoDetails = root.optJSONObject("videoDetails")
            if (videoDetails != null) {
                val lengthSec = videoDetails.optString("lengthSeconds").toLongOrNull() ?: 0L
                durationMs = lengthSec * 1000L
                author = videoDetails.optString("author").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            L.w(e, "Error extracting stream details from player response")
        }

        return StreamInfo(
            url = streamUrl,
            durationMs = durationMs,
            author = author
        )
    }

    /**
     * Cleans artist biography by removing external wiki/attribution trailers.
     */
    fun cleanArtistDescription(raw: String): String {
        return raw
            .replace(Regex("""(?is)[\s\n]*(From Wikipedia|De Wikipedia|Fuente:\s*Wikipedia).*$"""), "")
            .replace(Regex("""\(?\s*https?://\S+\s*\)?"""), "")
            .trim()
    }

    /**
     * Parses home feed data from YouTube Music browse responses (Home, Explore, and Charts).
     */
    fun parseHomeFeed(
        homeJson: String?,
        exploreJson: String?,
        chartsJson: String?,
        preferStudio: Boolean = true
    ): org.nov0caina.sounder.online.model.HomeFeedData {
        val trendingTracks = mutableListOf<OnlineTrack>()
        val quickPicks = mutableListOf<OnlineTrack>()
        val trendingAlbums = mutableListOf<OnlineAlbumSummary>()
        val popularArtists = mutableListOf<OnlineArtistSummary>()
        val seenTrackIds = mutableSetOf<String>()
        val seenAlbumIds = mutableSetOf<String>()
        val seenArtistIds = mutableSetOf<String>()

        // 1. Parse quick picks and trending tracks from FEmusic_home
        if (!homeJson.isNullOrBlank()) {
            try {
                val root = JSONObject(homeJson)
                val tabs = root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                if (tabs != null && tabs.length() > 0) {
                    val sections = tabs.getJSONObject(0)
                        .optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                    if (sections != null) {
                        for (i in 0 until sections.length()) {
                            val section = sections.getJSONObject(i)
                            val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                            val contents = carousel.optJSONArray("contents") ?: continue
                            for (j in 0 until contents.length()) {
                                val item = contents.getJSONObject(j)
                                val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                                val track = parseTrackItem(renderer)
                                if (track != null && seenTrackIds.add(track.id)) {
                                    if (quickPicks.size < 20) {
                                        quickPicks.add(track)
                                    } else {
                                        trendingTracks.add(track)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.w(e, "Error parsing FEmusic_home")
            }
        }

        // 2. Parse new / trending albums from FEmusic_explore
        if (!exploreJson.isNullOrBlank()) {
            try {
                val root = JSONObject(exploreJson)
                val tabs = root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                if (tabs != null && tabs.length() > 0) {
                    val sections = tabs.getJSONObject(0)
                        .optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                    if (sections != null) {
                        for (i in 0 until sections.length()) {
                            val section = sections.getJSONObject(i)
                            val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                            val contents = carousel.optJSONArray("contents") ?: continue
                            for (j in 0 until contents.length()) {
                                val item = contents.getJSONObject(j)
                                val twoRow = item.optJSONObject("musicTwoRowItemRenderer")
                                if (twoRow != null) {
                                    val browseId = twoRow.optJSONObject("navigationEndpoint")
                                        ?.optJSONObject("browseEndpoint")
                                        ?.optString("browseId") ?: ""
                                    if (browseId.startsWith("MPRE") && seenAlbumIds.add(browseId)) {
                                        val titleRuns = twoRow.optJSONObject("title")?.optJSONArray("runs")
                                        val albumTitle = if (titleRuns != null && titleRuns.length() > 0) {
                                            titleRuns.getJSONObject(0).optString("text")
                                        } else "Unknown Album"

                                        val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                                        val subtitle = if (subtitleRuns != null) {
                                            (0 until subtitleRuns.length()).joinToString("") {
                                                subtitleRuns.getJSONObject(it).optString("text")
                                            }.trim()
                                        } else null

                                        val thumbs = twoRow.optJSONObject("thumbnailRenderer")
                                            ?.optJSONObject("musicThumbnailRenderer")
                                            ?.optJSONObject("thumbnail")
                                            ?.optJSONArray("thumbnails")
                                        val thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                                            upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
                                        } else null

                                        trendingAlbums.add(
                                            OnlineAlbumSummary(
                                                id = browseId,
                                                title = albumTitle,
                                                year = subtitle,
                                                coverUrl = thumbUrl
                                            )
                                        )
                                    }
                                }

                                val respItem = item.optJSONObject("musicResponsiveListItemRenderer")
                                if (respItem != null) {
                                    val track = parseTrackItem(respItem)
                                    if (track != null && seenTrackIds.add(track.id)) {
                                        trendingTracks.add(track)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.w(e, "Error parsing FEmusic_explore")
            }
        }

        // 3. Parse popular artists from FEmusic_charts
        if (!chartsJson.isNullOrBlank()) {
            try {
                val root = JSONObject(chartsJson)
                val tabs = root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
                if (tabs != null && tabs.length() > 0) {
                    val sections = tabs.getJSONObject(0)
                        .optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                    if (sections != null) {
                        for (i in 0 until sections.length()) {
                            val section = sections.getJSONObject(i)
                            val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
                            val headerTitle = carousel.optJSONObject("header")
                                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                                ?.optJSONObject("title")
                                ?.optJSONArray("runs")
                                ?.optJSONObject(0)
                                ?.optString("text") ?: ""

                            val isArtistsSection = headerTitle.contains("artista", ignoreCase = true) ||
                                headerTitle.contains("artist", ignoreCase = true)

                            val contents = carousel.optJSONArray("contents") ?: continue
                            for (j in 0 until contents.length()) {
                                val item = contents.getJSONObject(j)
                                val respItem = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                                if (isArtistsSection) {
                                    val flexCols = respItem.optJSONArray("flexColumns") ?: continue
                                    val col0 = flexCols.optJSONObject(0)
                                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                        ?.optJSONObject("text")
                                        ?.optJSONArray("runs")
                                    val artistName = col0?.optJSONObject(0)?.optString("text") ?: continue

                                    var browseId = respItem.optJSONObject("navigationEndpoint")
                                        ?.optJSONObject("browseEndpoint")
                                        ?.optString("browseId")
                                    if (browseId.isNullOrBlank()) {
                                        browseId = col0.optJSONObject(0)
                                            ?.optJSONObject("navigationEndpoint")
                                            ?.optJSONObject("browseEndpoint")
                                            ?.optString("browseId")
                                    }

                                    if (!browseId.isNullOrBlank() && seenArtistIds.add(browseId)) {
                                        val col1 = if (flexCols.length() > 1) {
                                            flexCols.optJSONObject(1)
                                                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                                ?.optJSONObject("text")
                                                ?.optJSONArray("runs")
                                                ?.optJSONObject(0)
                                                ?.optString("text")
                                        } else null

                                        val thumbs = respItem.optJSONObject("thumbnail")
                                            ?.optJSONObject("musicThumbnailRenderer")
                                            ?.optJSONObject("thumbnail")
                                            ?.optJSONArray("thumbnails")
                                        val avatar = if (thumbs != null && thumbs.length() > 0) {
                                            upgradeArtworkResolution(thumbs.getJSONObject(thumbs.length() - 1).optString("url"))
                                        } else null

                                        popularArtists.add(
                                            OnlineArtistSummary(
                                                id = browseId,
                                                name = artistName,
                                                avatarUrl = avatar,
                                                subtitle = col1
                                            )
                                        )
                                    }
                                } else {
                                    val track = parseTrackItem(respItem)
                                    if (track != null && seenTrackIds.add(track.id)) {
                                        trendingTracks.add(track)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.w(e, "Error parsing FEmusic_charts")
            }
        }

        // If trending tracks list is empty, move some quick picks into trending
        val finalTrendingTracks = if (trendingTracks.isEmpty() && quickPicks.isNotEmpty()) {
            quickPicks.take(10)
        } else {
            trendingTracks
        }

        val prioritizedTrending = prioritizeExplicitTracks(
            if (preferStudio) prioritizeStudioTracks(finalTrendingTracks, true) else finalTrendingTracks
        )
        val prioritizedQuickPicks = prioritizeExplicitTracks(
            if (preferStudio) prioritizeStudioTracks(quickPicks, true) else quickPicks
        )

        return org.nov0caina.sounder.online.model.HomeFeedData(
            trendingTracks = prioritizedTrending,
            trendingAlbums = trendingAlbums,
            popularArtists = popularArtists,
            quickPicks = prioritizedQuickPicks
        )
    }
}
