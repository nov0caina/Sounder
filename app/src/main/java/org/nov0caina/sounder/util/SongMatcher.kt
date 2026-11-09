package org.nov0caina.sounder.util

import org.nov0caina.sounder.online.lyrics.LyricsSanitizer

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
}
