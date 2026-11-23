package org.nov0caina.sounder.online.innertube

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.lastOrNull
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
import org.nov0caina.sounder.online.model.OnlineAlbumSummary
import org.nov0caina.sounder.online.model.OnlineArtistDetail
import org.nov0caina.sounder.online.model.OnlineArtistSummary
import org.nov0caina.sounder.online.model.OnlineSearchResult
import org.nov0caina.sounder.online.model.OnlineTrack
import org.nov0caina.sounder.online.model.StreamInfo
import org.nov0caina.sounder.search.FuzzySearchMatcher
import org.nov0caina.sounder.util.SongMatcher
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

    internal var deviceLanguageProvider: () -> String = {
        java.util.Locale.getDefault().language.lowercase()
    }

    internal var randomProvider: () -> kotlin.random.Random = {
        kotlin.random.Random.Default
    }

    /**
     * Executes a multi-category search returning both verified artists and expanded tracks.
     */
    open suspend fun searchCategorized(query: String): OnlineSearchResult =
        searchCategorized(query, isCategory = false)

    /**
     * Executes an enriched multi-category search returning verified music artists and expanded
     * tracks via concurrent general, dedicated artist, and dedicated song queries.
     */
    open suspend fun searchCategorized(query: String, isCategory: Boolean): OnlineSearchResult =
        searchCategorizedProgressive(query, isCategory = isCategory).lastOrNull() ?: OnlineSearchResult()

    /**
     * Two-phase progressive search flow:
     * - Phase 1 runs a single parallel burst (primary + artists page 1 + songs) with zero
     *   sequential continuations or browseArtist calls for fast ~0.5s first paint.
     * - Phase 2 enriches artists (continuations, category seeds, related artists graph) and
     *   additional category tracks in the background without disturbing Phase 1 ordering.
     */
    open fun searchCategorizedProgressive(
        query: String,
        isCategory: Boolean = false
    ): Flow<OnlineSearchResult> = flow {
        if (query.isBlank()) {
            emit(OnlineSearchResult())
            return@flow
        }

        val cleanQuery = query
            .replace(Regex("(?i)\\b(artists|artistas|composers|canciones|songs|music|musica)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { query.trim() }

        val isChristian = isCategory && isChristianCategory(cleanQuery)
        val deviceLang = deviceLanguageProvider()
        val primaryQuery = when {
            isChristian && deviceLang == "es" -> "musica cristiana alabanza y adoracion"
            isChristian && deviceLang == "pt" -> "musica gospel adoracao"
            isChristian && deviceLang == "fr" -> "musique chretienne louange"
            isCategory -> cleanQuery
            else -> query
        }
        val dedicatedArtistQuery = when {
            isChristian && deviceLang == "es" -> "artistas musica cristiana español"
            isChristian && deviceLang == "pt" -> "cantores gospel brasil"
            isChristian && deviceLang == "fr" -> "artistes musique chretienne"
            else -> cleanQuery
        }

        try {
            val (primaryResult, artistsPage1, songsResult) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val primaryDeferred = async { fetchSearchPayload(primaryQuery) }
                    val artistsPage1Deferred = async {
                        try {
                            fetchSearchPayload(dedicatedArtistQuery, params = PARAMS_ARTISTS)
                        } catch (e: Exception) {
                            L.w(e, "Dedicated artist query failed for: %s", dedicatedArtistQuery)
                            null
                        }
                    }
                    val songsDeferred = async {
                        try {
                            val songQuery = when {
                                isChristian && deviceLang == "es" -> "musica cristiana alabanza exitos"
                                isChristian && deviceLang == "pt" -> "louvor e adoracao sucessos"
                                isCategory -> "$cleanQuery top songs"
                                else -> query
                            }
                            fetchSearchPayload(songQuery, params = PARAMS_SONGS)
                        } catch (e: Exception) {
                            L.w(e, "Dedicated songs query failed for: %s", query)
                            null
                        }
                    }
                    Triple(
                        primaryDeferred.await(),
                        artistsPage1Deferred.await(),
                        songsDeferred.await()
                    )
                }
            }

            val seenArtistIds = mutableSetOf<String>()
            val seenArtistNames = mutableSetOf<String>()
            val globalArtistIds = mutableSetOf<String>()

            val normCleanQuery = FuzzySearchMatcher.normalize(cleanQuery)
            val artistTokenSplitRegex = Regex("""\s*(?:,|&|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)

            fun splitArtistTokens(artistField: String): List<String> =
                artistTokenSplitRegex.split(artistField)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

            fun matchesDirectSearchQuery(artistName: String): Boolean {
                if (isCategory || normCleanQuery.isEmpty()) return false
                val normCandidate = FuzzySearchMatcher.normalize(artistName)
                if (normCandidate.isEmpty()) return false
                return normCandidate == normCleanQuery ||
                    normCandidate.contains(normCleanQuery) ||
                    FuzzySearchMatcher.scoreMatch(artistName, cleanQuery) >= 85
            }

            fun backfillArtistAvatar(
                artist: OnlineArtistSummary,
                tracks: List<OnlineTrack>
            ): OnlineArtistSummary {
                if (!artist.avatarUrl.isNullOrBlank()) return artist
                val normArtist = FuzzySearchMatcher.normalize(artist.name)
                val fallbackArt = tracks.firstOrNull { track ->
                    !track.artworkUrl.isNullOrBlank() && (
                        (!track.artistId.isNullOrBlank() && track.artistId == artist.id) ||
                            splitArtistTokens(track.artist).any {
                                FuzzySearchMatcher.normalize(it) == normArtist
                            }
                        )
                }?.artworkUrl
                return if (fallbackArt != null) artist.copy(avatarUrl = fallbackArt) else artist
            }

            fun isBlockedForCategory(artistName: String): Boolean {
                if (!isChristian) return false
                val norm = artistName.trim().lowercase()
                return CHRISTIAN_SECULAR_BLOCKLIST.any { blocked ->
                    norm == blocked || norm.contains(blocked)
                }
            }

            fun addVerifiedInto(
                target: MutableList<OnlineArtistSummary>,
                artist: OnlineArtistSummary,
                isGlobalSecondary: Boolean = false
            ) {
                if (artist.avatarUrl.isNullOrBlank()) return
                if (isBlockedForCategory(artist.name)) return
                val normName = artist.name.trim().lowercase()
                val isDirectMatch = !isCategory && matchesDirectSearchQuery(artist.name)
                if (!isDirectMatch && InnerTubeParser.isLowQualityUserChannel(artist.name, artist.subtitle)) return
                if (seenArtistIds.add(artist.id) && seenArtistNames.add(normName)) {
                    if (isGlobalSecondary) {
                        globalArtistIds.add(artist.id)
                    }
                    target.add(artist)
                }
            }

            fun scoreArtist(artist: OnlineArtistSummary): Double {
                val hasAud = InnerTubeParser.hasMonthlyAudienceMetric(artist.subtitle)
                val count = InnerTubeParser.parseMetricCount(artist.subtitle) ?: 0.0
                return (if (hasAud) 1_000_000_000_000.0 else 0.0) + count
            }

            fun directRankScore(artist: OnlineArtistSummary): Int =
                if (matchesDirectSearchQuery(artist.name)) {
                    FuzzySearchMatcher.scoreMatch(artist.name, cleanQuery)
                } else {
                    Int.MIN_VALUE
                }

            fun rankArtistGroup(list: List<OnlineArtistSummary>, preserveFirst: Boolean): List<OnlineArtistSummary> {
                if (list.size <= 1) return list
                if (!isCategory) {
                    return list.sortedByDescending(::directRankScore)
                }
                val head = if (preserveFirst) listOf(list.first()) else emptyList()
                val tail = if (preserveFirst) list.drop(1) else list
                val sortedTail = if (isChristian) {
                    val (globalTail, localeTail) = tail.partition { it.id in globalArtistIds }
                    val sortedLocale = localeTail.sortedByDescending(::scoreArtist)
                    val cappedGlobal = globalTail
                        .sortedByDescending(::scoreArtist)
                        .take(MAX_GLOBAL_ARTISTS_IN_LOCALE_CHRISTIAN)
                    sortedLocale + cappedGlobal
                } else {
                    tail.sortedByDescending(::scoreArtist)
                }
                return head + sortedTail
            }

            val phase1Tracks = mergeUniqueTracks(
                primaryResult.tracks,
                songsResult?.tracks.orEmpty()
            )
            val primaryBackfilled = primaryResult.artists.map { backfillArtistAvatar(it, phase1Tracks) }
            val page1Dedicated = artistsPage1?.artists.orEmpty().map { backfillArtistAvatar(it, phase1Tracks) }

            val phase1Artists = mutableListOf<OnlineArtistSummary>()
            if (!isCategory) {
                for (artist in primaryBackfilled.filter { matchesDirectSearchQuery(it.name) }) {
                    addVerifiedInto(phase1Artists, artist)
                }
                for (artist in page1Dedicated.filter { matchesDirectSearchQuery(it.name) }) {
                    addVerifiedInto(phase1Artists, artist)
                }
            }
            for (artist in primaryBackfilled) {
                addVerifiedInto(phase1Artists, artist)
            }
            for (artist in page1Dedicated.filter { InnerTubeParser.hasMonthlyAudienceMetric(it.subtitle) }) {
                addVerifiedInto(phase1Artists, artist)
            }
            for (artist in page1Dedicated.filterNot { InnerTubeParser.hasMonthlyAudienceMetric(it.subtitle) }) {
                addVerifiedInto(phase1Artists, artist)
            }
            if (!isCategory && phase1Artists.none { matchesDirectSearchQuery(it.name) }) {
                val trackMatch = phase1Tracks.firstOrNull { track ->
                    val artistId = track.artistId
                    !artistId.isNullOrBlank() &&
                        artistId.startsWith("UC") &&
                        !track.artworkUrl.isNullOrBlank() &&
                        (matchesDirectSearchQuery(track.artist) || splitArtistTokens(track.artist).any(::matchesDirectSearchQuery))
                }
                val matchedId = trackMatch?.artistId
                if (trackMatch != null && !matchedId.isNullOrBlank()) {
                    val matchedName = splitArtistTokens(trackMatch.artist)
                        .firstOrNull(::matchesDirectSearchQuery)
                        ?: trackMatch.artist.trim()
                    val normName = matchedName.lowercase()
                    if (seenArtistIds.add(matchedId) && seenArtistNames.add(normName)) {
                        phase1Artists.add(
                            0,
                            OnlineArtistSummary(
                                id = matchedId,
                                name = matchedName,
                                avatarUrl = trackMatch.artworkUrl,
                                subtitle = null
                            )
                        )
                    }
                }
            }

            val rankedPhase1Artists = rankArtistGroup(phase1Artists, preserveFirst = true)
            val phase1Result = OnlineSearchResult(
                artists = rankedPhase1Artists,
                tracks = phase1Tracks
            )

            val emittedPhase1 = phase1Result.artists.isNotEmpty() || phase1Result.tracks.isNotEmpty()
            if (emittedPhase1) {
                emit(phase1Result)
            }

            val rng = randomProvider()
            // Phase 2: silent background enrichment for additional tracks and category artists
            val enrichedResult = withContext(Dispatchers.IO) {
                coroutineScope {
                    val localeSeeds = if (isCategory) categoryArtistSeeds(cleanQuery, deviceLang, rng) else emptyList()
                    val globalChristianSeeds = if (isChristian && deviceLang != "en") {
                        listOf("Elevation Worship Hillsong Maverick City")
                    } else {
                        emptyList()
                    }
                    val localeSeedDeferred = localeSeeds.map { seed ->
                        seed to async {
                            try {
                                fetchSearchPayload(seed, params = PARAMS_ARTISTS)
                            } catch (_: Exception) {
                                OnlineSearchResult()
                            }
                        }
                    }
                    val globalSeedDeferred = globalChristianSeeds.map { seed ->
                        seed to async {
                            try {
                                fetchSearchPayload(seed, params = PARAMS_ARTISTS)
                            } catch (_: Exception) {
                                OnlineSearchResult()
                            }
                        }
                    }
                    val extraCategorySongsDeferred = if (isCategory) {
                        async {
                            try {
                                val extraSongQuery = when {
                                    isChristian && deviceLang == "es" -> "alabanza y adoracion canciones"
                                    isChristian && deviceLang == "pt" -> "musicas gospel mais tocadas"
                                    else -> "$cleanQuery hits"
                                }
                                fetchSearchPayload(extraSongQuery, params = PARAMS_SONGS)
                            } catch (e: Exception) {
                                L.w(e, "Extra category songs query failed for: %s", cleanQuery)
                                null
                            }
                        }
                    } else null

                    val continuationToken = latestArtistContinuationToken.remove(dedicatedArtistQuery to PARAMS_ARTISTS)
                    val continuationPageDeferred = if (!continuationToken.isNullOrBlank() && rankedPhase1Artists.size < MIN_TARGET_ARTISTS) {
                        async {
                            try {
                                fetchSearchContinuation(continuationToken)
                            } catch (_: Exception) {
                                OnlineSearchResult() to null
                            }
                        }
                    } else null

                    val localeSeedResults = localeSeedDeferred.map { (seed, deferred) -> seed to deferred.await() }
                    val globalSeedResults = globalSeedDeferred.map { (_, deferred) -> deferred.await() }
                    val extraCategorySongsResult = extraCategorySongsDeferred?.await()
                    val (contPage1, contToken2) = continuationPageDeferred?.await() ?: (OnlineSearchResult() to null)

                    val phase2Tracks = mergeUniqueTracks(
                        phase1Tracks,
                        extraCategorySongsResult?.tracks.orEmpty()
                    )

                    val phase2Extras = mutableListOf<OnlineArtistSummary>()
                    val allLocaleExtras = buildList {
                        addAll(contPage1.artists)
                        for ((seed, res) in localeSeedResults) {
                            if (ES_CHRISTIAN_EMERGING_SEEDS.any { it.equals(seed, ignoreCase = true) }) {
                                // Only take the matching emerging artist entry, avoiding unrelated namesakes
                                val match = res.artists.firstOrNull {
                                    it.name.trim().equals(seed, ignoreCase = true) && !it.avatarUrl.isNullOrBlank()
                                } ?: res.artists.firstOrNull {
                                    it.name.contains(seed, ignoreCase = true) && !it.avatarUrl.isNullOrBlank()
                                }
                                if (match != null) add(match)
                            } else {
                                addAll(res.artists)
                            }
                        }
                    }
                    for (artist in allLocaleExtras.filter { InnerTubeParser.hasMonthlyAudienceMetric(it.subtitle) }) {
                        addVerifiedInto(phase2Extras, artist, isGlobalSecondary = false)
                    }
                    for (artist in allLocaleExtras.filterNot { InnerTubeParser.hasMonthlyAudienceMetric(it.subtitle) }) {
                        addVerifiedInto(phase2Extras, artist, isGlobalSecondary = false)
                    }

                    for (res in globalSeedResults) {
                        for (artist in res.artists.filter { InnerTubeParser.hasMonthlyAudienceMetric(it.subtitle) }) {
                            addVerifiedInto(phase2Extras, artist, isGlobalSecondary = true)
                        }
                    }

                    // Fetch a second continuation page only if still below target
                    if (!contToken2.isNullOrBlank() && (rankedPhase1Artists.size + phase2Extras.size) < MIN_TARGET_ARTISTS) {
                        try {
                            val (contPage2, _) = fetchSearchContinuation(contToken2)
                            for (artist in contPage2.artists) {
                                addVerifiedInto(phase2Extras, artist, isGlobalSecondary = false)
                            }
                        } catch (_: Exception) {
                        }
                    }

                    // Resolve direct-query track artists in non-category searches even if >= MIN_TARGET_ARTISTS
                    // related artists were already returned, so the searched artist appears as #1 ("Top Result").
                    val directTrackMatches = if (!isCategory) {
                        phase2Tracks.filter { track ->
                            val artistId = track.artistId
                            !artistId.isNullOrBlank() &&
                                artistId.startsWith("UC") &&
                                (matchesDirectSearchQuery(track.artist) || splitArtistTokens(track.artist).any(::matchesDirectSearchQuery))
                        }
                    } else {
                        emptyList()
                    }
                    val directTrackArtistIds = directTrackMatches.mapNotNull { it.artistId }.distinct().take(4)
                    val directDetails = if (directTrackArtistIds.isNotEmpty()) {
                        directTrackArtistIds.map { artistId ->
                            async {
                                try {
                                    if (this@InnerTubeClient::class.java == InnerTubeClient::class.java) {
                                        browseArtistCore(artistId, fetchFullDiscography = false)
                                    } else {
                                        browseArtist(artistId)
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }.mapNotNull { it.await() }
                    } else {
                        emptyList()
                    }

                    for (detail in directDetails) {
                        for (related in detail.relatedArtists) {
                            addVerifiedInto(phase2Extras, related)
                        }
                    }

                    // Expand via single-hop browseArtist + relatedArtists graph only when needed.
                    // Never run unconstrained 2nd-hop graph expansion during category browsing to prevent
                    // cross-genre leakage (e.g., Christian -> Kanye West -> Drake / J. Cole).
                    val skipGraphExpansionForChristian = isChristian && (rankedPhase1Artists.size + phase2Extras.size) >= 18
                    if (!skipGraphExpansionForChristian && (rankedPhase1Artists.size + phase2Extras.size) < MIN_TARGET_ARTISTS) {
                        val combinedSoFar = rankedPhase1Artists + phase2Extras
                        val trackArtistIds = if (isChristian) {
                            emptyList()
                        } else {
                            phase2Tracks
                                .mapNotNull { it.artistId?.takeIf { id -> id.startsWith("UC") && id !in directTrackArtistIds } }
                                .distinct()
                        }
                        val seedBrowseIds = (combinedSoFar.take(6).map { it.id } + trackArtistIds)
                            .filterNot { it in directTrackArtistIds }
                            .distinct()
                            .take(14)

                        val artistDetails = seedBrowseIds.map { artistId ->
                            async {
                                try {
                                    if (this@InnerTubeClient::class.java == InnerTubeClient::class.java) {
                                        browseArtistCore(artistId, fetchFullDiscography = false)
                                    } else {
                                        browseArtist(artistId)
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }.mapNotNull { it.await() }

                        for (detail in artistDetails) {
                            val avatar = detail.avatarUrl
                            val name = detail.name.takeIf { it.isNotBlank() && it != "Unknown Artist" }
                            if (!avatar.isNullOrBlank() && name != null) {
                                addVerifiedInto(
                                    phase2Extras,
                                    OnlineArtistSummary(
                                        id = detail.id,
                                        name = name,
                                        avatarUrl = avatar,
                                        subtitle = detail.subscribers,
                                    )
                                )
                            }
                            for (related in detail.relatedArtists) {
                                addVerifiedInto(phase2Extras, related)
                            }
                        }

                        if (!isCategory && (rankedPhase1Artists.size + phase2Extras.size) < MIN_TARGET_ARTISTS && seedBrowseIds.isNotEmpty()) {
                            val alreadyChecked = (seedBrowseIds + directTrackArtistIds).toSet()
                            val secondHopIds = (rankedPhase1Artists + phase2Extras)
                                .map { it.id }
                                .filter { it.startsWith("UC") && it !in alreadyChecked }
                                .take(8)

                            val hopDetails = secondHopIds.map { artistId ->
                                async {
                                    try {
                                        if (this@InnerTubeClient::class.java == InnerTubeClient::class.java) {
                                            browseArtistCore(artistId, fetchFullDiscography = false)
                                        } else {
                                            browseArtist(artistId)
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }.mapNotNull { it.await() }

                            for (detail in hopDetails) {
                                for (related in detail.relatedArtists) {
                                    addVerifiedInto(phase2Extras, related)
                                }
                            }
                        }
                    }

                    val rankedPhase2Extras = rankArtistGroup(phase2Extras, preserveFirst = false)
                    val combinedArtists = (rankedPhase1Artists + rankedPhase2Extras).toMutableList()
                    for (detail in directDetails) {
                        val fallbackTrack = directTrackMatches.firstOrNull { it.artistId == detail.id }
                        val resolvedAvatar = detail.avatarUrl?.takeIf { it.isNotBlank() }
                            ?: fallbackTrack?.artworkUrl
                            ?: continue
                        val fallbackTrackName = fallbackTrack?.let { tr ->
                            splitArtistTokens(tr.artist).firstOrNull(::matchesDirectSearchQuery) ?: tr.artist.trim()
                        }
                        val resolvedName = detail.name.takeIf {
                            it.isNotBlank() && it != "Unknown Artist" && !it.startsWith("Resolved ")
                        } ?: fallbackTrackName ?: detail.name
                        val updatedSummary = OnlineArtistSummary(
                            id = detail.id,
                            name = resolvedName,
                            avatarUrl = resolvedAvatar,
                            subtitle = detail.subscribers
                        )
                        val existingIdx = combinedArtists.indexOfFirst { it.id == detail.id }
                        if (existingIdx >= 0) {
                            combinedArtists[existingIdx] = updatedSummary
                        } else if (!isBlockedForCategory(resolvedName)) {
                            seenArtistIds.add(detail.id)
                            seenArtistNames.add(resolvedName.lowercase())
                            combinedArtists.add(0, updatedSummary)
                        }
                    }
                    val finalArtists = if (!isCategory) {
                        combinedArtists.sortedByDescending(::directRankScore).toMutableList()
                    } else {
                        combinedArtists
                    }
                    if (isChristian && deviceLang == "es" && finalArtists.size > 3) {
                        val emergingNormSet = ES_CHRISTIAN_EMERGING_SEEDS.map { it.lowercase() }.toSet()
                        val extractedEmerging = mutableListOf<OnlineArtistSummary>()
                        val iter = finalArtists.listIterator(3)
                        while (iter.hasNext()) {
                            val candidate = iter.next()
                            if (candidate.name.trim().lowercase() in emergingNormSet) {
                                extractedEmerging.add(candidate)
                                iter.remove()
                            }
                        }
                        if (finalArtists.size > 4) {
                            val shuffleEnd = minOf(finalArtists.size, 20)
                            val midTier = finalArtists.subList(3, shuffleEnd).toMutableList()
                            val reordered = midTier.chunked(5).flatMap { it.shuffled(rng) }
                            for (i in reordered.indices) {
                                finalArtists[3 + i] = reordered[i]
                            }
                        }
                        for (emerging in extractedEmerging) {
                            val minIdx = minOf(3, finalArtists.size)
                            val maxInclusive = minOf(20, finalArtists.size)
                            val targetIdx = if (maxInclusive > minIdx) {
                                rng.nextInt(minIdx, maxInclusive + 1)
                            } else {
                                finalArtists.size
                            }
                            finalArtists.add(targetIdx, emerging)
                        }
                    }
                    OnlineSearchResult(
                        artists = finalArtists,
                        tracks = phase2Tracks
                    )
                }
            }

            val changedAfterPhase1 = enrichedResult.artists != phase1Result.artists ||
                enrichedResult.tracks != phase1Result.tracks
            if (!emittedPhase1 || changedAfterPhase1) {
                emit(enrichedResult)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w(e, "Error executing enriched search for query: %s", query)
            emit(withContext(Dispatchers.IO) { fetchSearchPayload(query) })
        }
    }

    private fun mergeUniqueTracks(vararg sources: List<OnlineTrack>): List<OnlineTrack> {
        val merged = mutableListOf<OnlineTrack>()
        val seenIds = mutableSetOf<String>()
        val signatureToIndex = mutableMapOf<String, Int>()

        fun baseTitleKey(rawTitle: String): String {
            val stripped = InnerTubeParser.STRIP_EDITION_TAGS_REGEX.replace(rawTitle, " ").trim()
            return SongMatcher.normalizeTitle(stripped).ifBlank { rawTitle.trim().lowercase() }
        }

        fun explicitScore(track: OnlineTrack): Int {
            var score = 0
            if (track.isExplicit) score += 10
            if (track.isStudioTrack) score += 4
            if (InnerTubeParser.CLEAN_TITLE_REGEX.containsMatchIn(track.title)) score -= 10
            return score
        }

        for (source in sources) {
            for (track in source) {
                if (!seenIds.add(track.id)) continue
                val primaryArtist = track.artist.split(",", "&", "feat.", "ft.")
                    .firstOrNull()?.trim()?.lowercase().orEmpty()
                val dedupSignature = "${baseTitleKey(track.title)}|$primaryArtist"
                val existingIdx = signatureToIndex[dedupSignature]
                if (existingIdx == null) {
                    signatureToIndex[dedupSignature] = merged.size
                    merged.add(track)
                } else {
                    val existing = merged[existingIdx]
                    if (explicitScore(track) > explicitScore(existing)) {
                        merged[existingIdx] = track
                    }
                }
            }
        }
        return merged
    }

    private fun isChristianCategory(cleanQuery: String): Boolean {
        val lower = cleanQuery.lowercase()
        return lower.contains("christian") ||
            lower.contains("worship") ||
            lower.contains("cristian") ||
            lower.contains("alabanza") ||
            lower.contains("gospel")
    }

    private fun categoryArtistSeeds(
        cleanQuery: String,
        deviceLang: String,
        rng: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<String> =
        when {
            isChristianCategory(cleanQuery) -> when (deviceLang) {
                "es" -> listOf(
                    "alabanza y adoracion artistas",
                    "musica cristiana en español artistas",
                    "Marcos Witt Miel San Marcos Jesus Adrian Romero",
                    "Barak Redimi2 Christine D'Clario",
                    "Generacion 12 Un Corazon Averly Morillo",
                ) + ES_CHRISTIAN_EMERGING_SEEDS.shuffled(rng).take(3)
                "pt" -> listOf(
                    "musica gospel adoracao artistas",
                    "Aline Barros Fernandinho Gabriela Rocha",
                    "Morada Preto no Branco Thalles Roberto",
                )
                "fr" -> listOf(
                    "musique chretienne louange artistes",
                    "Dan Luiten Impact Exo",
                )
                else -> listOf(
                    "contemporary christian artists",
                    "modern worship bands",
                    "gospel worship artists",
                )
            }
            cleanQuery.lowercase() == "focus" -> listOf("ambient instrumental", "lofi study", "classical piano", "post rock")
            cleanQuery.lowercase() == "chill" -> listOf("chillout", "lofi beats", "downtempo ambient")
            cleanQuery.lowercase() == "workout" -> listOf("electronic workout", "rock workout", "hip hop fitness")
            cleanQuery.lowercase() == "party" -> listOf("dance pop", "electronic dance", "club hits")
            cleanQuery.lowercase() == "metal" -> listOf("heavy metal", "thrash metal", "symphonic metal")
            cleanQuery.lowercase() == "rock" -> listOf("classic rock", "alternative rock", "hard rock")
            else -> listOf("$cleanQuery band", "top $cleanQuery")
        }

    private val latestArtistContinuationToken = java.util.concurrent.ConcurrentHashMap<Pair<String, String?>, String>()

    open internal suspend fun fetchSearchContinuation(continuation: String): Pair<OnlineSearchResult, String?> =
        withContext(Dispatchers.IO) {
            if (continuation.isBlank()) return@withContext OnlineSearchResult() to null
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
                    put("continuation", continuation)
                }
                val request = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/search")
                    .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://music.youtube.com/")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext OnlineSearchResult() to null
                    val body = response.body?.string() ?: return@withContext OnlineSearchResult() to null
                    val preferStudio = onlineSettings?.preferStudioAudio ?: true
                    return@withContext InnerTubeParser.parseSearchContinuation(body, preferStudio)
                }
            } catch (e: Exception) {
                L.w(e, "Error fetching search continuation")
                return@withContext OnlineSearchResult() to null
            }
        }

    companion object {
        const val PARAMS_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA=="
        const val PARAMS_SONGS = "EgWKAQIIAWoKEAkQChAFEAMQBA=="
        const val MIN_TARGET_ARTISTS = 55
        private const val MAX_GLOBAL_ARTISTS_IN_LOCALE_CHRISTIAN = 6

        private val ES_CHRISTIAN_EMERGING_SEEDS = listOf(
            "Abel Meza",
            "Montesanto",
            "Kike Pavon",
            "Living Worship",
            "Oasis Ministry",
        )

        private val CHRISTIAN_SECULAR_BLOCKLIST = setOf(
            "kanye west", "ye", "drake", "j. cole", "j cole", "kendrick lamar",
            "travis scott", "dj khaled", "lil baby", "future", "nicki minaj",
            "eminem", "jay-z", "jay z", "21 savage", "lil wayne", "post malone",
            "the weeknd", "bad bunny", "justin bieber", "snoop dogg", "chris brown",
            "playboi carti", "young thug", "gunna", "metro boomin", "tyler, the creator",
            "cardi b", "megan thee stallion", "doja cat", "sza", "beyonce", "beyoncé",
            "rihanna", "taylor swift", "ariana grande", "billie eilish", "dr. dre",
            "50 cent", "wiz khalifa", "kid cudi", "a\$ap rocky", "asap rocky"
        )
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
            val nextToken = InnerTubeParser.extractSearchContinuationToken(responseBody)
            if (!nextToken.isNullOrBlank()) {
                latestArtistContinuationToken[query to params] = nextToken
            }
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

    private fun fetchBrowseJson(
        browseId: String,
        params: String? = null,
        continuation: String? = null,
        hl: String = "es"
    ): String? {
        return try {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240101.01.00")
                        put("hl", hl)
                        put("gl", "US")
                    })
                })
                if (!continuation.isNullOrBlank()) {
                    put("continuation", continuation)
                } else {
                    put("browseId", browseId)
                    if (!params.isNullOrBlank()) {
                        put("params", params)
                    }
                }
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
     * Browses artist catalog (avatar, bio, top songs, complete discography, related artists).
     */
    open suspend fun browseArtist(browseId: String): OnlineArtistDetail =
        browseArtistCore(browseId, fetchFullDiscography = true)

    private suspend fun browseArtistCore(
        browseId: String,
        fetchFullDiscography: Boolean
    ): OnlineArtistDetail = withContext(Dispatchers.IO) {
        if (browseId.isBlank()) return@withContext OnlineArtistDetail(browseId, "", null, null)

        try {
            val responseBody = fetchBrowseJson(browseId, hl = "en")
                ?: return@withContext OnlineArtistDetail(browseId, "", null, null)
            val preferStudio = onlineSettings?.preferStudioAudio ?: true
            val baseDetail = InnerTubeParser.parseArtistDetail(browseId, responseBody, preferStudio)
            if (!fetchFullDiscography) {
                return@withContext baseDetail
            }
            return@withContext enrichFullArtistDiscography(baseDetail)
        } catch (e: Exception) {
            L.w(e, "Error browsing artist for %s", browseId)
            return@withContext OnlineArtistDetail(browseId, "", null, null)
        }
    }

    private suspend fun enrichFullArtistDiscography(detail: OnlineArtistDetail): OnlineArtistDetail = coroutineScope {
        val albumsDeferred = detail.albumsBrowseId?.takeIf { it.isNotBlank() }?.let { bId ->
            async { fetchFullReleaseList(bId, detail.albumsBrowseParams) }
        }
        val singlesDeferred = detail.singlesBrowseId?.takeIf { it.isNotBlank() }?.let { bId ->
            async { fetchFullReleaseList(bId, detail.singlesBrowseParams) }
        }

        val expandedAlbums = albumsDeferred?.await().orEmpty()
        val expandedSingles = singlesDeferred?.await().orEmpty()

        val mergedAlbums = mergeReleaseLists(detail.albums, expandedAlbums)
        val mergedSingles = mergeReleaseLists(detail.singles, expandedSingles)

        detail.copy(
            albums = mergedAlbums,
            singles = mergedSingles
        )
    }

    private fun fetchFullReleaseList(browseId: String, params: String?): List<OnlineAlbumSummary> {
        val collected = mutableListOf<OnlineAlbumSummary>()
        val firstJson = fetchBrowseJson(browseId = browseId, params = params, hl = "en") ?: return collected
        var (pageItems, token) = InnerTubeParser.parseArtistDiscographyBrowseWithContinuation(firstJson)
        collected.addAll(pageItems)

        var pageCount = 0
        while (!token.isNullOrBlank() && pageCount < 2) {
            val contJson = fetchBrowseJson(browseId = browseId, continuation = token, hl = "en") ?: break
            val (moreItems, nextToken) = InnerTubeParser.parseArtistDiscographyBrowseWithContinuation(contJson)
            if (moreItems.isEmpty()) break
            collected.addAll(moreItems)
            token = nextToken
            pageCount++
        }
        return collected
    }

    private fun mergeReleaseLists(
        initial: List<OnlineAlbumSummary>,
        expanded: List<OnlineAlbumSummary>
    ): List<OnlineAlbumSummary> {
        if (expanded.isEmpty()) return initial
        val merged = mutableListOf<OnlineAlbumSummary>()
        val seenIds = mutableSetOf<String>()
        // Prefer full discography ordering when available, appending any missing items from overview
        for (item in expanded + initial) {
            if (item.id.isNotBlank() && seenIds.add(item.id)) {
                merged.add(item)
            }
        }
        return merged
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
                put("contentCheckOk", true)
                put("racyCheckOk", true)
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
                put("contentCheckOk", true)
                put("racyCheckOk", true)
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
                put("contentCheckOk", true)
                put("racyCheckOk", true)
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
                val sortedCandidates = searchResults
                    .filter { it.id.isNotBlank() && it.id != excludeVideoId }
                    .sortedByDescending { cand ->
                        var score = 0
                        if (cand.isExplicit) score += 10
                        if (cand.isStudioTrack) score += 4
                        if (InnerTubeParser.CLEAN_TITLE_REGEX.containsMatchIn(cand.title)) score -= 10
                        score
                    }
                val candidateIds = sortedCandidates.map { it.id }

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
