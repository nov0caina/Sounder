package org.nov0caina.sounder.online.model

/**
 * Represents a single timed line in a synchronized LRC lyrics format.
 *
 * @param timeMs The timestamp in milliseconds when this line begins.
 * @param text The lyric text content.
 */
data class LrcLine(
    val timeMs: Long,
    val text: String
)

/**
 * Result of fetching lyrics for a track, supporting both synchronized and plain forms.
 */
data class LyricsResult(
    val plainLyrics: String? = null,
    val syncedLyrics: List<LrcLine> = emptyList(),
    val isInstrumental: Boolean = false
) {
    val hasSyncedLyrics: Boolean get() = syncedLyrics.isNotEmpty()
    val hasPlainLyrics: Boolean get() = !plainLyrics.isNullOrBlank()
    val isEmpty: Boolean get() = !hasSyncedLyrics && !hasPlainLyrics && !isInstrumental
}

