/*
 * Copyright (c) 2026 Sounder Project
 * UpdateManager.kt is part of Sounder.
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

package org.nov0caina.sounder.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.nov0caina.sounder.BuildConfig
import org.nov0caina.sounder.util.NetworkUtil
import timber.log.Timber as L

/**
 * Information regarding a detected application update from GitHub Releases.
 *
 * @param tagName The release tag identifier (e.g. `v1.0.1-sounder`).
 * @param name The title of the release.
 * @param body The release changelog description / body.
 * @param htmlUrl The URL leading to the release page on GitHub.
 */
data class UpdateInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
)

/**
 * Result returned by [UpdateManager.checkForUpdate].
 */
sealed class UpdateResult {
    /** A newer version is available. */
    data class NewUpdate(val updateInfo: UpdateInfo) : UpdateResult()

    /** The running application is already on the latest version or update is ignored. */
    object UpToDate : UpdateResult()

    /** Sounder was installed via F-Droid or an F-Droid client; updates are delegated to F-Droid. */
    object ManagedByFDroid : UpdateResult()

    /** An error occurred while checking for updates. */
    data class Error(val throwable: Throwable) : UpdateResult()
}

/**
 * Semantic version representation for parsing and sequential component comparison.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        fun parse(raw: String): SemVer? {
            return try {
                val clean = raw.trim()
                    .removePrefix("v")
                    .removePrefix("V")
                    .split("-")[0]
                    .split("+")[0]
                val parts = clean.split(".")
                if (parts.isEmpty()) return null
                val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
                SemVer(major, minor, patch)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Manages checking for application updates, respecting F-Droid distribution policies,
 * rate-limiting automated requests, and persisting user preferences.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Determines whether the app was installed via an F-Droid client.
     */
    fun isInstalledFromFDroid(): Boolean {
        val installer = getInstallerPackageName()
        val initiator = getInitiatingPackageName()
        val isFDroid = (installer != null && FDROID_INSTALLER_PACKAGES.contains(installer)) ||
            (initiator != null && FDROID_INSTALLER_PACKAGES.contains(initiator))
        L.d("Installer source check: installer=$installer, initiator=$initiator, isFDroid=$isFDroid")
        return isFDroid
    }

    private fun getInstallerPackageName(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = context.packageManager.getInstallSourceInfo(context.packageName)
                info.installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            L.w(e, "Failed to retrieve installing package name")
            null
        }
    }

    private fun getInitiatingPackageName(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = context.packageManager.getInstallSourceInfo(context.packageName)
                info.initiatingPackageName
            } else {
                null
            }
        } catch (e: Exception) {
            L.w(e, "Failed to retrieve initiating package name")
            null
        }
    }

    /**
     * Timestamp in milliseconds of the last successful or attempted update check.
     */
    fun getLastCheckTimestamp(): Long =
        sharedPreferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun setLastCheckTimestamp(timestamp: Long = System.currentTimeMillis()) {
        sharedPreferences.edit().putLong(KEY_LAST_UPDATE_CHECK, timestamp).apply()
    }

    /**
     * Retrieves the version tag previously ignored by the user, if any.
     */
    fun getIgnoredVersion(): String? =
        sharedPreferences.getString(KEY_IGNORED_UPDATE_TAG, null)

    /**
     * Stores a version tag to suppress future automated prompts for it.
     */
    fun setIgnoredVersion(versionTag: String) {
        sharedPreferences.edit().putString(KEY_IGNORED_UPDATE_TAG, versionTag).apply()
    }

    /**
     * Clears any previously ignored version tag.
     */
    fun clearIgnoredVersion() {
        sharedPreferences.edit().remove(KEY_IGNORED_UPDATE_TAG).apply()
    }

    /**
     * Returns true if at least 9 hours have elapsed since the previous update check.
     */
    fun shouldCheckAutomatically(
        now: Long = System.currentTimeMillis(),
        lastCheck: Long = getLastCheckTimestamp(),
    ): Boolean {
        return isCheckIntervalElapsed(lastCheck, now)
    }

    /**
     * Checks for an update against GitHub Releases API.
     *
     * @param isManual True if explicitly invoked by the user (bypasses 24-hour rate limit and ignored tag suppression).
     * @return [UpdateResult] describing the outcome.
     */
    suspend fun checkForUpdate(isManual: Boolean): UpdateResult = withContext(Dispatchers.IO) {
        if (isInstalledFromFDroid()) {
            return@withContext UpdateResult.ManagedByFDroid
        }

        if (!isManual && !shouldCheckAutomatically()) {
            L.d("Skipping automatic update check (checked within 9 hours)")
            return@withContext UpdateResult.UpToDate
        }

        if (!NetworkUtil.isConnected(context)) {
            L.d("Update check aborted: no network connection")
            return@withContext UpdateResult.Error(IOException("No network connection"))
        }

        try {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_LATEST_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", USER_AGENT)
                .build()

            httpClient.newCall(request).execute().use { response ->
                setLastCheckTimestamp()

                if (!response.isSuccessful) {
                    L.w("GitHub API update check returned HTTP ${response.code}")
                    return@withContext UpdateResult.Error(
                        IOException("GitHub API returned HTTP ${response.code}")
                    )
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    L.w("GitHub API returned empty response")
                    return@withContext UpdateResult.Error(
                        IOException("Empty response from GitHub API")
                    )
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val name = json.optString("name", tagName).trim()
                val releaseNotes = json.optString("body", "").trim()
                val htmlUrl = json.optString("html_url", GITHUB_RELEASES_PAGE_URL)

                val current = SemVer.parse(BuildConfig.VERSION_NAME)
                val remote = SemVer.parse(tagName)

                L.d("Update check parsed versions: current=$current (${BuildConfig.VERSION_NAME}), remote=$remote ($tagName)")

                val isNewer = if (current != null && remote != null) {
                    remote > current
                } else {
                    false
                }

                if (!isNewer) {
                    L.d("App is up to date")
                    return@withContext UpdateResult.UpToDate
                }

                val ignoredTag = getIgnoredVersion()
                if (!isManual && ignoredTag != null && ignoredTag == tagName) {
                    L.d("Update $tagName is currently ignored by user")
                    return@withContext UpdateResult.UpToDate
                }

                val updateInfo = UpdateInfo(
                    tagName = tagName,
                    name = name,
                    body = releaseNotes,
                    htmlUrl = htmlUrl,
                )
                return@withContext UpdateResult.NewUpdate(updateInfo)
            }
        } catch (e: Exception) {
            L.w(e, "Error checking for updates")
            return@withContext UpdateResult.Error(e)
        }
    }

    companion object {
        const val KEY_LAST_UPDATE_CHECK = "sounder_last_update_check"
        const val KEY_IGNORED_UPDATE_TAG = "sounder_ignored_update_tag"
        const val CHECK_INTERVAL_MS = 9 * 60 * 60 * 1000L // 9 hours

        /**
         * Checks whether the 9-hour cooldown interval has elapsed between [lastCheck] and [now].
         */
        fun isCheckIntervalElapsed(lastCheck: Long, now: Long = System.currentTimeMillis()): Boolean {
            return (now - lastCheck) >= CHECK_INTERVAL_MS
        }

        private const val GITHUB_RELEASES_LATEST_URL =
            "https://api.github.com/repos/nov0caina/Sounder/releases/latest"
        private const val GITHUB_RELEASES_PAGE_URL =
            "https://github.com/nov0caina/Sounder/releases/latest"
        private const val USER_AGENT = "Sounder-Music-Player"

        val FDROID_INSTALLER_PACKAGES = setOf(
            "org.fdroid.fdroid",
            "org.fdroid.fdroid.privileged",
            "com.looker.droidify",
            "com.machiav3lli.neo",
            "com.aurora.adroid",
        )
    }
}
