package org.nov0caina.sounder.online.model

/**
 * User-selectable audio quality preference for streaming and downloads.
 */
enum class AudioQuality {
    DATA_SAVER,
    HIGH_QUALITY;

    companion object {
        fun fromString(value: String?): AudioQuality =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: HIGH_QUALITY
    }
}

