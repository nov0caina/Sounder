package org.nov0caina.sounder.online

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.nov0caina.sounder.R
import org.nov0caina.sounder.online.model.AudioQuality
import org.nov0caina.sounder.settings.Settings

enum class StorageDestination {
    INTERNAL,
    SD_CARD;

    companion object {
        fun fromString(str: String?): StorageDestination =
            when (str) {
                "SD_CARD" -> SD_CARD
                else -> INTERNAL
            }
    }
}

/**
 * User preferences for online music streaming quality and download constraints.
 */
interface OnlineSettings : Settings<OnlineSettings.Listener> {
    val audioQuality: AudioQuality
    val wifiOnlyDownloads: Boolean
    val autoDownloadFavorites: Boolean
    val preferStudioAudio: Boolean
    val showChristianGenre: Boolean
    val showKpopGenre: Boolean
    val enableLocalListeningInsights: Boolean
    val showMonthlyListeningBanner: Boolean
        get() = true
    fun setShowMonthlyListeningBanner(show: Boolean) {}
    val downloadStorage: StorageDestination
    fun setDownloadStorage(destination: StorageDestination)
    val streamingCacheSizeMb: Int
    val streamingCacheSizeBytes: Long
        get() = streamingCacheSizeMb.toLong() * 1024L * 1024L
    fun setStreamingCacheSizeMb(sizeMb: Int)

    interface Listener {
        fun onAudioQualityChanged() {}
        fun onWifiOnlyDownloadsChanged() {}
        fun onDownloadStorageChanged() {}
        fun onPreferStudioAudioChanged() {}
        fun onStreamingCacheSizeChanged() {}
        fun onGenreVisibilityChanged() {}
        fun onListeningInsightsChanged() {}
    }
}

@Singleton
class OnlineSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : Settings.Impl<OnlineSettings.Listener>(context), OnlineSettings {

    companion object {
        const val MIN_STREAMING_CACHE_MB = 100
        const val MAX_STREAMING_CACHE_MB = 700
        const val DEFAULT_STREAMING_CACHE_MB = 300
    }

    override val audioQuality: AudioQuality
        get() {
            val key = context.getString(R.string.set_key_online_quality)
            val str = sharedPreferences.getString(key, "HIGH_QUALITY")
            return AudioQuality.fromString(str)
        }

    override val wifiOnlyDownloads: Boolean
        get() {
            val key = context.getString(R.string.set_key_wifi_only_downloads)
            return sharedPreferences.getBoolean(key, false)
        }

    override val autoDownloadFavorites: Boolean
        get() {
            val key = context.getString(R.string.set_key_auto_download_favorites)
            return sharedPreferences.getBoolean(key, false)
        }

    @Volatile
    private var cachedShowChristian: Boolean =
        sharedPreferences.getBoolean(context.getString(R.string.set_key_genre_christian), true)

    @Volatile
    private var cachedShowKpop: Boolean =
        sharedPreferences.getBoolean(context.getString(R.string.set_key_genre_kpop), true)

    override val showChristianGenre: Boolean
        get() = cachedShowChristian

    override val showKpopGenre: Boolean
        get() = cachedShowKpop

    override val preferStudioAudio: Boolean
        get() {
            val key = context.getString(R.string.set_key_prefer_studio_audio)
            return sharedPreferences.getBoolean(key, true)
        }

    override val enableLocalListeningInsights: Boolean
        get() {
            val key = context.getString(R.string.set_key_local_listening_insights)
            return sharedPreferences.getBoolean(key, true)
        }

    override val showMonthlyListeningBanner: Boolean
        get() {
            val key = context.getString(R.string.set_key_show_monthly_listening_banner)
            return sharedPreferences.getBoolean(key, true)
        }

    override fun setShowMonthlyListeningBanner(show: Boolean) {
        val key = context.getString(R.string.set_key_show_monthly_listening_banner)
        sharedPreferences.edit().putBoolean(key, show).apply()
    }

    override val downloadStorage: StorageDestination
        get() {
            val key = context.getString(R.string.set_key_download_storage)
            val str = sharedPreferences.getString(key, "INTERNAL")
            return StorageDestination.fromString(str)
        }

    override fun setDownloadStorage(destination: StorageDestination) {
        val key = context.getString(R.string.set_key_download_storage)
        sharedPreferences.edit().putString(key, destination.name).apply()
    }

    override val streamingCacheSizeMb: Int
        get() {
            val key = context.getString(R.string.set_key_streaming_cache_size)
            val parsed = sharedPreferences.getString(key, DEFAULT_STREAMING_CACHE_MB.toString())
                ?.toIntOrNull()
                ?: DEFAULT_STREAMING_CACHE_MB
            return parsed.coerceIn(MIN_STREAMING_CACHE_MB, MAX_STREAMING_CACHE_MB)
        }

    override fun setStreamingCacheSizeMb(sizeMb: Int) {
        val key = context.getString(R.string.set_key_streaming_cache_size)
        val clamped = sizeMb.coerceIn(MIN_STREAMING_CACHE_MB, MAX_STREAMING_CACHE_MB)
        sharedPreferences.edit().putString(key, clamped.toString()).apply()
    }

    override fun onSettingChanged(key: String, listener: OnlineSettings.Listener) {
        when (key) {
            context.getString(R.string.set_key_online_quality) -> listener.onAudioQualityChanged()
            context.getString(R.string.set_key_wifi_only_downloads) -> listener.onWifiOnlyDownloadsChanged()
            context.getString(R.string.set_key_download_storage) -> listener.onDownloadStorageChanged()
            context.getString(R.string.set_key_prefer_studio_audio) -> listener.onPreferStudioAudioChanged()
            context.getString(R.string.set_key_streaming_cache_size) -> listener.onStreamingCacheSizeChanged()
            context.getString(R.string.set_key_genre_christian) -> {
                cachedShowChristian = sharedPreferences.getBoolean(key, true)
                listener.onGenreVisibilityChanged()
            }
            context.getString(R.string.set_key_genre_kpop) -> {
                cachedShowKpop = sharedPreferences.getBoolean(key, true)
                listener.onGenreVisibilityChanged()
            }
            context.getString(R.string.set_key_local_listening_insights),
            context.getString(R.string.set_key_show_monthly_listening_banner) -> {
                listener.onListeningInsightsChanged()
            }
        }
    }
}

