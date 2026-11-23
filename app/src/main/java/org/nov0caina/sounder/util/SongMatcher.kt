package org.nov0caina.sounder.util

import android.content.Context
import org.nov0caina.sounder.music.resolveNames
import org.nov0caina.sounder.online.lyrics.LyricsSanitizer
import org.nov0caina.sounder.online.model.OnlineSong
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

/**
 * Utility to match online tracks and local songs by normalized title and artist,
 * handling variations such as "(Official Video)", "[Remastered]", track number prefixes,
 * punctuation, and casing differences.
 */
object SongMatcher {
    private val PUNCTUATION_AND_SPECIAL = Regex("""[^a-zA-Z0-9]""")
    private val LEADING_TRACK_NUMBER_REGEX = Regex("""^\d{1,3}[\s.\-_]+""")

    fun normalizeTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val withoutTrackNumber = title.trim().replace(LEADING_TRACK_NUMBER_REGEX, "")
        val cleaned = LyricsSanitizer.cleanTitle(withoutTrackNumber)
        return cleaned.lowercase()
            .replace(PUNCTUATION_AND_SPECIAL, "")
            .trim()
    }

    fun normalizeArtist(artist: String?): String {
        if (artist.isNullOrBlank()) return ""
        val cleaned = LyricsSanitizer.cleanArtist(artist)
        return cleaned.lowercase()
            .replace(PUNCTUATION_AND_SPECIAL, "")
            .trim()
    }

    fun matches(
        title1: String?,
        artist1: String?,
        title2: String?,
        artist2: String?
    ): Boolean {
        val normT1 = normalizeTitle(title1)
        val normT2 = normalizeTitle(title2)
        if (normT1.isEmpty() || normT2.isEmpty()) return false
        if (normT1 != normT2) return false

        val normA1 = normalizeArtist(artist1)
        val normA2 = normalizeArtist(artist2)
        if (normA1.isEmpty() || normA2.isEmpty()) return true
        return normA1 == normA2 || normA1.contains(normA2) || normA2.contains(normA1)
    }

    /**
     * Deduplicates a list of songs semantically by normalized title and artist (or matching
     * OnlineTrack ID), prioritizing playable local audio files over un-downloaded online streaming tracks.
     */
    fun deduplicateSongs(songs: List<Song>, context: Context? = null): List<Song> {
        if (songs.size <= 1) return songs

        val result = mutableListOf<Song>()
        val seenUids = mutableSetOf<String>()
        val seenTrackIds = mutableSetOf<String>()
        val seenSemanticKeys = mutableMapOf<String, Int>()

        for (song in songs) {
            val uidStr = song.uid.toString()
            if (seenUids.contains(uidStr)) continue

            val trackId = (song as? OnlineSong)?.onlineTrack?.id
            if (trackId != null && seenTrackIds.contains(trackId)) continue

            val title = song.name.raw
            val artist = if (song.artists.isNotEmpty() && song.artists.all { it.name is Name.Known }) {
                song.artists.joinToString(", ") { (it.name as Name.Known).raw }
            } else {
                context?.let { try { song.artists.resolveNames(it) } catch (_: Exception) { null } } ?: ""
            }
            val normT = normalizeTitle(title)
            val normA = normalizeArtist(artist)
            val semanticKey = if (normT.isNotEmpty() && normA.isNotEmpty()) "$normT|$normA" else null

            if (semanticKey != null && seenSemanticKeys.containsKey(semanticKey)) {
                val existingIndex = seenSemanticKeys[semanticKey]!!
                val existingSong = result[existingIndex]
                // Prioritize local audio Song over streaming OnlineSong
                if (existingSong is OnlineSong && song !is OnlineSong) {
                    result[existingIndex] = song
                    seenUids.add(uidStr)
                }
                continue
            }

            seenUids.add(uidStr)
            if (trackId != null) seenTrackIds.add(trackId)
            if (semanticKey != null) {
                seenSemanticKeys[semanticKey] = result.size
            }
            result.add(song)
        }
        return result
    }
}
