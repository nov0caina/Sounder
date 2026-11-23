/*
 * Copyright (c) 2023 Auxio Project
 * SearchEngine.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.nov0caina.sounder.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import org.nov0caina.sounder.music.resolve
import org.nov0caina.sounder.music.resolveName
import org.nov0caina.sounder.music.resolveNames
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/**
 * Implements the fuzzy-ish searching algorithm used in the search view.
 *
 * @author Alexander Capehart
 */
interface SearchEngine {
    /**
     * Begin a search.
     *
     * @param items The items to search over.
     * @param query The query to search for.
     * @return A list of items filtered by the given query.
     */
    suspend fun search(items: Items, query: String): Items

    /**
     * Input/output data to use with [SearchEngine].
     *
     * @param songs A list of [Song]s, null if empty.
     * @param albums A list of [Album]s, null if empty.
     * @param artists A list of [Artist]s, null if empty.
     * @param genres A list of [Genre]s, null if empty.
     * @param playlists A list of [Playlist], null if empty.
     */
    data class Items(
        val songs: Collection<Song>? = null,
        val albums: Collection<Album>? = null,
        val artists: Collection<Artist>? = null,
        val genres: Collection<Genre>? = null,
        val playlists: Collection<Playlist>? = null,
    )
}

class SearchEngineImpl @Inject constructor(@ApplicationContext private val context: Context) :
    SearchEngine {
    override suspend fun search(items: SearchEngine.Items, query: String): SearchEngine.Items {
        L.d("Launching search for $query")
        return SearchEngine.Items(
            songs =
                items.songs?.searchListImpl(query) { q, song ->
                    val artistScore = FuzzySearchMatcher.scoreMatch(song.artists.resolveNames(context), q)
                    val albumScore = FuzzySearchMatcher.scoreMatch(song.album.name.resolve(context), q)
                    val pathScore = song.path.name?.let { FuzzySearchMatcher.scoreMatch(it, q) } ?: 0
                    maxOf(
                        if (artistScore > 0) artistScore - 5 else 0,
                        if (albumScore > 0) albumScore - 10 else 0,
                        if (pathScore > 0) pathScore - 15 else 0,
                    )
                },
            albums = items.albums?.searchListImpl(query),
            artists = items.artists?.searchListImpl(query),
            genres = items.genres?.searchListImpl(query),
            playlists = items.playlists?.searchListImpl(query),
        )
    }

    /**
     * Search a given [Music] list using exact, normalized, and typo-tolerant matching, ranking
     * higher-relevance matches first.
     */
    private inline fun <T : Music> Collection<T>.searchListImpl(
        query: String,
        fallbackScore: (String, T) -> Int = { _, _ -> 0 },
    ) =
        mapNotNull { item ->
                val name = item.name
                val resolvedName = (item as? Playlist)?.resolveName(context) ?: name.resolve(context)

                val primaryScore = when {
                    resolvedName.equals(query, ignoreCase = true) -> 105
                    resolvedName.contains(query, ignoreCase = true) -> 95
                    else -> {
                        val sortScore = if (name is Name.Known && name.sort != null) {
                            val sortName = name.sort!!
                            if (sortName.contains(query, ignoreCase = true)) {
                                90
                            } else {
                                FuzzySearchMatcher.scoreMatch(sortName, query)
                            }
                        } else {
                            0
                        }
                        val normalizedName =
                            NORMALIZE_POST_PROCESSING_REGEX.replace(
                                Normalizer.normalize(resolvedName, Normalizer.Form.NFKD),
                                "",
                            )
                        val normScore = if (normalizedName.contains(query, ignoreCase = true)) {
                            88
                        } else {
                            0
                        }
                        maxOf(
                            FuzzySearchMatcher.scoreMatch(resolvedName, query),
                            sortScore,
                            normScore,
                        )
                    }
                }

                val totalScore = maxOf(primaryScore, fallbackScore(query, item))
                if (totalScore > 0) item to totalScore else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .ifEmpty { null }

    private companion object {
        /**
         * Converts the output of [Normalizer] to remove any junk characters added by it's
         * replacements, alongside punctuation.
         */
        val NORMALIZE_POST_PROCESSING_REGEX by lazy {
            Regex("(\\p{InCombiningDiacriticalMarks}+)|(\\p{Punct})")
        }
    }
}
