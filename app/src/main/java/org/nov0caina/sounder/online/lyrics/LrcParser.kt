package org.nov0caina.sounder.online.lyrics

import org.nov0caina.sounder.online.model.LrcLine

/**
 * Parser for standard LRC synchronized lyrics format.
 */
object LrcParser {

    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    /**
     * Parses an LRC string into a list of [LrcLine] objects sorted by time.
     *
     * @param lrcContent The raw LRC string.
     * @return Sorted list of timestamped lines.
     */
    fun parse(lrcContent: String): List<LrcLine> {
        if (lrcContent.isBlank()) return emptyList()

        val result = mutableListOf<LrcLine>()

        lrcContent.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Extract all timestamps in this line
            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach

            // Extract the lyric text following the timestamps
            val lastMatch = matches.last()
            val textStartIndex = lastMatch.range.last + 1
            val text = if (textStartIndex < trimmed.length) {
                trimmed.substring(textStartIndex).trim()
            } else {
                ""
            }

            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val millisString = match.groupValues.getOrNull(3).orEmpty()
                val millis = parseMillis(millisString)

                val timeMs = minutes * 60_000L + seconds * 1000L + millis
                result.add(LrcLine(timeMs = timeMs, text = text))
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun parseMillis(millisString: String): Long {
        return when (millisString.length) {
            1 -> millisString.toLongOrNull()?.times(100) ?: 0L
            2 -> millisString.toLongOrNull()?.times(10) ?: 0L
            3 -> millisString.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}

