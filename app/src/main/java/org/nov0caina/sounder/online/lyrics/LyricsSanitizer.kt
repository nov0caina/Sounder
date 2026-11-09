package org.nov0caina.sounder.online.lyrics

/**
 * Utility for sanitizing song titles and artist names from online sources (e.g. YouTube Music)
 * prior to querying lyrics services or writing audio metadata tags.
 */
object LyricsSanitizer {

    private val VIDEO_TAGS_REGEX = Regex(
        """\[.*?\]|\(.*?(?:official|music\s+video|audio|video|lyric|lyrics|remastered|remaster|hd|hq|4k|feat\.?|ft\.?|visualizer|explicit|live|performance|prod\.?).*?\)""",
        RegexOption.IGNORE_CASE
    )

    private val EXTRA_SPACES_REGEX = Regex("""\s{2,}""")

    /**
     * Cleans a track title by removing video tags, bracketed commentary,
     * featuring labels, and prefix artist redundancy.
     */
    fun cleanTitle(title: String, artist: String? = null): String {
        if (title.isBlank()) return ""
        var cleaned = title

        // 1. If title starts with "Artist - Track" or "Artist - Track (Official)", remove the prefix
        if (!artist.isNullOrBlank()) {
            val prefix = "$artist - "
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.substring(prefix.length)
            } else if (cleaned.contains(" - ")) {
                val parts = cleaned.split(" - ", limit = 2)
                if (parts[0].trim().equals(artist.trim(), ignoreCase = true)) {
                    cleaned = parts[1]
                }
            }
        }

        // 2. Strip brackets and parentheses containing common video/audio/feature descriptors
        cleaned = cleaned.replace(VIDEO_TAGS_REGEX, "")

        // 3. Normalize whitespace
        cleaned = cleaned.replace(EXTRA_SPACES_REGEX, " ").trim()

        // 4. If the cleaning stripped too much, fallback to original title trimmed
        return cleaned.ifBlank { title.trim() }
    }

    /**
     * Cleans an artist name, e.g. removing " - Topic" from YouTube Music auto-generated channels,
     * or separating primary artist from featuring tags.
     */
    fun cleanArtist(artist: String): String {
        if (artist.isBlank()) return ""
        var cleaned = artist
        if (cleaned.endsWith(" - Topic", ignoreCase = true)) {
            cleaned = cleaned.substring(0, cleaned.length - " - Topic".length)
        }
        if (cleaned.contains(" feat. ", ignoreCase = true)) {
            cleaned = cleaned.substringBefore(" feat. ")
        } else if (cleaned.contains(" ft. ", ignoreCase = true)) {
            cleaned = cleaned.substringBefore(" ft. ")
        }
        return cleaned.replace(EXTRA_SPACES_REGEX, " ").trim().ifBlank { artist.trim() }
    }
}
