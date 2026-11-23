package org.nov0caina.sounder.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.playback.ui.SeekBarHapticThrottle
import org.nov0caina.sounder.ui.MotionPhysics

class PlaybackMicrointeractionsTest {

    @Test
    fun `resolveCoverScale switches between full scale on play and subtle shrink on pause`() {
        assertEquals(1.0f, MotionPhysics.resolveCoverScale(isPlaying = true), 0.0001f)
        assertEquals(0.94f, MotionPhysics.resolveCoverScale(isPlaying = false), 0.0001f)
    }

    @Test
    fun `resolveCoverSpring pairs buoyant rebound with play and softer settle with pause`() {
        val playSpring = MotionPhysics.resolveCoverSpring(isPlaying = true)
        val pauseSpring = MotionPhysics.resolveCoverSpring(isPlaying = false)

        assertTrue(playSpring.dampingRatio < pauseSpring.dampingRatio)
        assertTrue(playSpring.stiffness < pauseSpring.stiffness)
    }

    @Test
    fun `resolveSeekBarSpring keeps grab and release underdamped for fluid thumb motion`() {
        val grabSpring = MotionPhysics.resolveSeekBarSpring(isTracking = true)
        val releaseSpring = MotionPhysics.resolveSeekBarSpring(isTracking = false)

        assertTrue(grabSpring.dampingRatio < 0.7f)
        assertTrue(releaseSpring.dampingRatio < 0.7f)
        assertTrue(grabSpring.stiffness >= releaseSpring.stiffness)
    }

    @Test
    fun `resolveExpandedThumbHeight scales base height and honors max cap`() {
        // 48 * 1.35 = 64.8 -> 65, well below cap 120
        assertEquals(65, MotionPhysics.resolveExpandedThumbHeight(baseHeightPx = 48, maxHeightPx = 120))
        // 100 * 1.35 = 135 -> clamped to cap 120
        assertEquals(120, MotionPhysics.resolveExpandedThumbHeight(baseHeightPx = 100, maxHeightPx = 120))
    }

    @Test
    fun `resolveExpandedThumbWidth guarantees noticeable minimum width delta`() {
        assertEquals(18, MotionPhysics.resolveExpandedThumbWidth(baseWidthPx = 12, minDeltaPx = 2))
        assertEquals(6, MotionPhysics.resolveExpandedThumbWidth(baseWidthPx = 4, minDeltaPx = 2))
    }

    @Test
    fun `seekbar haptic throttle emits only at new second boundaries after interval`() {
        val throttle = SeekBarHapticThrottle(minIntervalMs = 80L)
        throttle.start(initialPositionDs = 10L) // second 1

        // Same second -> no tick
        assertFalse(throttle.shouldEmitTick(positionDs = 15L, nowMs = 1000L))
        assertFalse(throttle.shouldEmitTick(positionDs = 19L, nowMs = 1020L))

        // First crossing into second 2 -> emits immediately
        assertTrue(throttle.shouldEmitTick(positionDs = 20L, nowMs = 1030L))

        // Rapid scrub into second 3 only 40ms later -> throttled
        assertFalse(throttle.shouldEmitTick(positionDs = 30L, nowMs = 1070L))

        // Once 80ms elapses, second 4 emits
        assertTrue(throttle.shouldEmitTick(positionDs = 40L, nowMs = 1110L))

        // Backwards scrub across boundary after interval -> emits
        assertTrue(throttle.shouldEmitTick(positionDs = 29L, nowMs = 1200L))

        // Reset clears prior boundary so re-emitting second 2 after reset works cleanly
        throttle.reset()
        throttle.start(initialPositionDs = 50L)
        assertTrue(throttle.shouldEmitTick(positionDs = 29L, nowMs = 1210L))
    }
}
