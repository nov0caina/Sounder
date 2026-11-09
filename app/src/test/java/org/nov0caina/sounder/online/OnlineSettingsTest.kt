package org.nov0caina.sounder.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.nov0caina.sounder.online.model.AudioQuality

class OnlineSettingsTest {

    @Test
    fun testAudioQualityFromString() {
        assertEquals(AudioQuality.HIGH_QUALITY, AudioQuality.fromString("HIGH_QUALITY"))
        assertEquals(AudioQuality.DATA_SAVER, AudioQuality.fromString("DATA_SAVER"))
        assertEquals(AudioQuality.HIGH_QUALITY, AudioQuality.fromString("invalid"))
        assertEquals(AudioQuality.HIGH_QUALITY, AudioQuality.fromString(null))
    }

    @Test
    fun testPreferStudioAudioDefaultBehavior() {
        // Simulated SharedPreferences store for OnlineSettings
        val prefs = mutableMapOf<String, Any>()
        fun getPreferStudioAudio(key: String): Boolean =
            prefs[key] as? Boolean ?: true

        // By default should be true
        assertTrue(getPreferStudioAudio("sounder_prefer_studio_audio"))

        // When toggled off
        prefs["sounder_prefer_studio_audio"] = false
        assertFalse(getPreferStudioAudio("sounder_prefer_studio_audio"))

        // When toggled on
        prefs["sounder_prefer_studio_audio"] = true
        assertTrue(getPreferStudioAudio("sounder_prefer_studio_audio"))
    }
}

