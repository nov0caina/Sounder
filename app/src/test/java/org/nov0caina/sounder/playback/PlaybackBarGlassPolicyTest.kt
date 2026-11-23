package org.nov0caina.sounder.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.playback.ui.PlaybackBarGlassPolicy

class PlaybackBarGlassPolicyTest {

    @Test
    fun `sheetBackgroundAlpha stays translucent when collapsed and smoothly ramps to solid`() {
        assertEquals(PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA, PlaybackBarGlassPolicy.sheetBackgroundAlpha(-0.2f))
        assertEquals(PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA, PlaybackBarGlassPolicy.sheetBackgroundAlpha(0f))
        assertEquals(PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA, PlaybackBarGlassPolicy.sheetBackgroundAlpha(0.02f))

        val midEarly = PlaybackBarGlassPolicy.sheetBackgroundAlpha(0.15f)
        val midLate = PlaybackBarGlassPolicy.sheetBackgroundAlpha(0.35f)
        assertTrue(midEarly in (PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA + 1)..254)
        assertTrue(midLate in (PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA + 1)..254)
        assertTrue(midLate > midEarly)

        assertEquals(255, PlaybackBarGlassPolicy.sheetBackgroundAlpha(0.45f))
        assertEquals(255, PlaybackBarGlassPolicy.sheetBackgroundAlpha(1.0f))
        assertEquals(255, PlaybackBarGlassPolicy.sheetBackgroundAlpha(1.2f))
    }

    @Test
    fun `glassViewAlpha acts as smooth complement to sheet background fade`() {
        assertEquals(1.0f, PlaybackBarGlassPolicy.glassViewAlpha(0f), 0.0001f)
        assertEquals(0.0f, PlaybackBarGlassPolicy.glassViewAlpha(0.45f), 0.0001f)
        val midGlass = PlaybackBarGlassPolicy.glassViewAlpha(0.25f)
        assertTrue(midGlass > 0f && midGlass < 1f)
    }

    @Test
    fun `isGlassActive only enables blur while visible and below solid threshold`() {
        assertTrue(PlaybackBarGlassPolicy.isGlassActive(playbackRatio = 0f, sheetVisible = true))
        assertTrue(PlaybackBarGlassPolicy.isGlassActive(playbackRatio = 0.25f, sheetVisible = true))
        assertFalse(PlaybackBarGlassPolicy.isGlassActive(playbackRatio = 0.45f, sheetVisible = true))
        assertFalse(PlaybackBarGlassPolicy.isGlassActive(playbackRatio = 1.0f, sheetVisible = true))
        assertFalse(PlaybackBarGlassPolicy.isGlassActive(playbackRatio = 0f, sheetVisible = false))
    }

    @Test
    fun `unified bottom nav and playback glass avoid double blur and fill when sheet is hidden`() {
        val bottomNavTop = 2000

        // Collapsed playback bar active: playback glass owns the continuous bottom region, standalone nav glass stays off
        assertTrue(
            PlaybackBarGlassPolicy.isPlaybackGlassCoveringBottomNav(
                playbackRatio = 0f,
                sheetVisible = true,
                sheetTop = 1800,
                bottomNavTop = bottomNavTop,
            )
        )
        assertFalse(
            PlaybackBarGlassPolicy.isBottomNavGlassActive(
                bottomNavVisible = true,
                sheetVisible = true,
                sheetTop = 1800,
                bottomNavTop = bottomNavTop,
            )
        )

        // No active song / hidden playback bar: standalone bottom-nav glass renders
        assertFalse(
            PlaybackBarGlassPolicy.isPlaybackGlassCoveringBottomNav(
                playbackRatio = 0f,
                sheetVisible = false,
                sheetTop = 2200,
                bottomNavTop = bottomNavTop,
            )
        )
        assertTrue(
            PlaybackBarGlassPolicy.isBottomNavGlassActive(
                bottomNavVisible = true,
                sheetVisible = false,
                sheetTop = 2200,
                bottomNavTop = bottomNavTop,
            )
        )

        // Sheet slid below bottomNavTop: standalone bottom-nav glass takes over seamlessly
        assertFalse(
            PlaybackBarGlassPolicy.isPlaybackGlassCoveringBottomNav(
                playbackRatio = 0f,
                sheetVisible = true,
                sheetTop = 2000,
                bottomNavTop = bottomNavTop,
            )
        )
        assertTrue(
            PlaybackBarGlassPolicy.isBottomNavGlassActive(
                bottomNavVisible = true,
                sheetVisible = true,
                sheetTop = 2000,
                bottomNavTop = bottomNavTop,
            )
        )

        // Expanded player (bottom nav faded out & sheet past solid threshold): both glass layers disabled
        assertFalse(
            PlaybackBarGlassPolicy.isPlaybackGlassCoveringBottomNav(
                playbackRatio = 0.50f,
                sheetVisible = true,
                sheetTop = 900,
                bottomNavTop = bottomNavTop,
            )
        )
        assertFalse(
            PlaybackBarGlassPolicy.isBottomNavGlassActive(
                bottomNavVisible = false,
                sheetVisible = true,
                sheetTop = 900,
                bottomNavTop = bottomNavTop,
            )
        )
    }

    @Test
    fun `resolveTintAlpha keeps hardware blur more translucent than fallback across themes`() {
        val luminances = listOf(0.0f, 0.12f, 0.92f) // AMOLED, Dark, Light
        for (lum in luminances) {
            val blurAlpha = PlaybackBarGlassPolicy.resolveTintAlpha(lum, hasHardwareBlur = true)
            val fallbackAlpha = PlaybackBarGlassPolicy.resolveTintAlpha(lum, hasHardwareBlur = false)
            assertTrue(blurAlpha in 1..255)
            assertTrue(fallbackAlpha in 1..255)
            assertTrue(blurAlpha < fallbackAlpha)
        }
    }

    @Test
    fun `resolveRimAlpha returns visible subtle stroke alpha for light and dark themes`() {
        val darkRim = PlaybackBarGlassPolicy.resolveRimAlpha(isDarkTheme = true)
        val lightRim = PlaybackBarGlassPolicy.resolveRimAlpha(isDarkTheme = false)
        assertTrue(darkRim in 1..255)
        assertTrue(lightRim in 1..255)
        assertTrue(lightRim > darkRim)
    }

    @Test
    fun `downscaled blur geometry helpers clamp safely to minimum positive bounds`() {
        assertEquals(1, PlaybackBarGlassPolicy.computeScaledDimension(0))
        assertEquals(270, PlaybackBarGlassPolicy.computeScaledDimension(1080, 0.25f))
        assertEquals(1f, PlaybackBarGlassPolicy.computeScaledBlurRadius(density = 0f), 0.0001f)
        assertEquals(18f, PlaybackBarGlassPolicy.computeScaledBlurRadius(density = 3f, 24f, 0.25f), 0.0001f)
    }

    @Test
    fun `bottomNavBackgroundAlpha avoids double-layer opacity stacking when playback sheet is visible`() {
        val bottomNavTop = 2000
        val isCoveredBySheet =
            PlaybackBarGlassPolicy.isPlaybackGlassCoveringBottomNav(
                playbackRatio = 0f,
                sheetVisible = true,
                sheetTop = 1800,
                bottomNavTop = bottomNavTop,
            )
        val coveredAlpha = if (isCoveredBySheet) 0 else PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA
        assertEquals(0, coveredAlpha)

        val isNotCovered =
            PlaybackBarGlassPolicy.isPlaybackGlassCoveringBottomNav(
                playbackRatio = 0f,
                sheetVisible = false,
                sheetTop = 2200,
                bottomNavTop = bottomNavTop,
            )
        val standaloneAlpha = if (isNotCovered) 0 else PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA
        assertEquals(PlaybackBarGlassPolicy.FROSTED_GLASS_ALPHA, standaloneAlpha)
    }
}
