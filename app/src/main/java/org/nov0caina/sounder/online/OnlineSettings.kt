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
    val downloadStorage: StorageDestination
    fun setDownloadStorage(destination: StorageDestination)

    interface Listener {
        fun onAudioQualityChanged() {}
        fun onWifiOnlyDownloadsChanged() {}
        fun onDownloadStorageChanged() {}
        fun onPreferStudioAudioChanged() {}
    }
}

@Singleton
class OnlineSettingsImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : Settings.Impl<OnlineSettings.Listener>(context), OnlineSettings {

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

    override val preferStudioAudio: Boolean
        get() {
            val key = context.getString(R.string.set_key_prefer_studio_audio)
            return sharedPreferences.getBoolean(key, true)
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

    override fun onSettingChanged(key: String, listener: OnlineSettings.Listener) {
        when (key) {
            context.getString(R.string.set_key_online_quality) -> listener.onAudioQualityChanged()
            context.getString(R.string.set_key_wifi_only_downloads) -> listener.onWifiOnlyDownloadsChanged()
            context.getString(R.string.set_key_download_storage) -> listener.onDownloadStorageChanged()
            context.getString(R.string.set_key_prefer_studio_audio) -> listener.onPreferStudioAudioChanged()
        }
    }
}

