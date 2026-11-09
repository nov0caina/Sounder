/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 Sounder Project
 * SounderApplication.kt is part of Sounder.
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
 
package org.nov0caina.sounder

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.nov0caina.sounder.home.HomeSettings
import org.nov0caina.sounder.image.ImageSettings
import org.nov0caina.sounder.playback.PlaybackSettings
import org.nov0caina.sounder.ui.UISettings
import org.nov0caina.sounder.util.CopyleftNoticeTree
import timber.log.Timber

/**
 * A modern, rational music player for Android with online streaming and local playback.
 *
 * @author Alexander Capehart (OxygenCobalt)
 * @author nov0caina
 */
@HiltAndroidApp
class SounderApplication : Application() {
    @Inject lateinit var imageSettings: ImageSettings
    @Inject lateinit var playbackSettings: PlaybackSettings
    @Inject lateinit var uiSettings: UISettings
    @Inject lateinit var homeSettings: HomeSettings

    override fun onCreate() {
        super.onCreate()
        @Suppress("KotlinConstantConditions")
        if (
            BuildConfig.APPLICATION_ID != "org.nov0caina.sounder" &&
                BuildConfig.APPLICATION_ID != "org.nov0caina.sounder.debug"
        ) {
            Timber.plant(CopyleftNoticeTree())
        } else if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Migrate any settings that may have changed in an app update.
        imageSettings.migrate()
        playbackSettings.migrate()
        uiSettings.migrate()
        homeSettings.migrate()

        ShortcutManagerCompat.addDynamicShortcuts(
            this,
            listOf(
                ShortcutInfoCompat.Builder(this, SHORTCUT_SHUFFLE_ID)
                    .setShortLabel(getString(R.string.lbl_shuffle_shortcut_short))
                    .setLongLabel(getString(R.string.lbl_shuffle_shortcut_long))
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_shuffle_24))
                    .setIntent(
                        Intent(this, MainActivity::class.java)
                            .setAction(INTENT_KEY_SHORTCUT_SHUFFLE)
                    )
                    .build()
            ),
        )
    }

    companion object {
        /** The [Intent] name for the "Shuffle All" shortcut. */
        const val INTENT_KEY_SHORTCUT_SHUFFLE = BuildConfig.APPLICATION_ID + ".action.SHUFFLE_ALL"
        /** The ID of the "Shuffle All" shortcut. */
        private const val SHORTCUT_SHUFFLE_ID = "shortcut_shuffle"
    }
}
