package org.nov0caina.sounder.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashTransitionHelperTest {

    @Test
    fun `calculateScaleAndAlpha starts at 1x scale and full opacity`() {
        val start = SplashTransitionHelper.calculateScaleAndAlpha(0f)
        assertEquals(SplashTransitionHelper.INITIAL_SCALE, start.scale, 0.001f)
        assertEquals(1.0f, start.iconAlpha, 0.001f)
        assertEquals(1.0f, start.containerAlpha, 0.001f)
    }

    @Test
    fun `calculateScaleAndAlpha scales up smoothly and fades out cleanly at completion`() {
        val mid = SplashTransitionHelper.calculateScaleAndAlpha(0.5f)
        assertTrue(mid.scale > SplashTransitionHelper.INITIAL_SCALE)
        assertTrue(mid.scale < SplashTransitionHelper.TARGET_SCALE)
        assertTrue(mid.iconAlpha in 0.0f..1.0f)

        val end = SplashTransitionHelper.calculateScaleAndAlpha(1.0f)
        assertEquals(SplashTransitionHelper.TARGET_SCALE, end.scale, 0.001f)
        assertEquals(0.0f, end.iconAlpha, 0.001f)
        assertEquals(0.0f, end.containerAlpha, 0.001f)
    }

    @Test
    fun `calculateScaleAndAlpha clamps out-of-range inputs`() {
        val under = SplashTransitionHelper.calculateScaleAndAlpha(-0.5f)
        assertEquals(SplashTransitionHelper.INITIAL_SCALE, under.scale, 0.001f)
        assertEquals(1.0f, under.iconAlpha, 0.001f)

        val over = SplashTransitionHelper.calculateScaleAndAlpha(2.5f)
        assertEquals(SplashTransitionHelper.TARGET_SCALE, over.scale, 0.001f)
        assertEquals(0.0f, over.iconAlpha, 0.001f)
    }
}
