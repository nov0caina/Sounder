package org.nov0caina.sounder.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.core.view.HapticFeedbackConstantsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationsTest {

    @Test
    fun `resolveTouchScale compresses on down and rebounds on release or cancel`() {
        assertEquals(
            MotionPhysics.DEFAULT_SQUISH_SCALE,
            MotionPhysics.resolveTouchScale(MotionEvent.ACTION_DOWN)!!,
            0.0001f,
        )
        assertEquals(1.0f, MotionPhysics.resolveTouchScale(MotionEvent.ACTION_UP)!!, 0.0001f)
        assertEquals(1.0f, MotionPhysics.resolveTouchScale(MotionEvent.ACTION_CANCEL)!!, 0.0001f)
        assertNull(MotionPhysics.resolveTouchScale(MotionEvent.ACTION_MOVE))
    }

    @Test
    fun `clampSquishScale keeps touch compression within safe visual bounds`() {
        assertEquals(
            MotionPhysics.MIN_SQUISH_SCALE,
            MotionPhysics.clampSquishScale(0.25f),
            0.0001f,
        )
        assertEquals(1.0f, MotionPhysics.clampSquishScale(1.4f), 0.0001f)
        assertEquals(0.92f, MotionPhysics.clampSquishScale(0.92f), 0.0001f)
    }

    @Test
    fun `touch feedback never consumes events so parent scrolling and clicks stay intact`() {
        assertFalse(MotionPhysics.shouldConsumeTouchEvent())
    }

    @Test
    fun `resolveHapticConstant falls back gracefully below Android R`() {
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            MotionPhysics.resolveHapticConstant(
                HapticFeedbackConstantsCompat.CONFIRM,
                Build.VERSION_CODES.N,
            ),
        )
        assertEquals(
            HapticFeedbackConstants.VIRTUAL_KEY,
            MotionPhysics.resolveHapticConstant(
                HapticFeedbackConstantsCompat.CONFIRM,
                Build.VERSION_CODES.Q,
            ),
        )
        assertEquals(
            HapticFeedbackConstantsCompat.CONFIRM,
            MotionPhysics.resolveHapticConstant(
                HapticFeedbackConstantsCompat.CONFIRM,
                Build.VERSION_CODES.R,
            ),
        )
        assertEquals(
            HapticFeedbackConstants.CLOCK_TICK,
            MotionPhysics.resolveHapticConstant(
                HapticFeedbackConstants.CLOCK_TICK,
                Build.VERSION_CODES.N,
            ),
        )
    }

    @Test
    fun `squish defaults use expressive tactile scale targets`() {
        assertEquals(0.95f, MotionPhysics.DEFAULT_SQUISH_SCALE, 0.0001f)
        assertEquals(0.90f, MotionPhysics.ICON_SQUISH_SCALE, 0.0001f)
    }

    @Test
    fun `resolveTouchSpring uses cushioned press and underdamped fluid rebound`() {
        val pressSpring = MotionPhysics.resolveTouchSpring(MotionEvent.ACTION_DOWN)!!
        val releaseSpring = MotionPhysics.resolveTouchSpring(MotionEvent.ACTION_UP)!!
        val cancelSpring = MotionPhysics.resolveTouchSpring(MotionEvent.ACTION_CANCEL)!!

        assertTrue(pressSpring.dampingRatio > 0.6f)
        assertTrue(releaseSpring.dampingRatio < 0.6f)
        assertEquals(releaseSpring, cancelSpring)
        assertNull(MotionPhysics.resolveTouchSpring(MotionEvent.ACTION_MOVE))
    }

    @Test
    fun `resolvePopStartVelocity produces smooth positive impulse scaling with target and compression`() {
        val normalVelocity = MotionPhysics.resolvePopStartVelocity(popScale = 1.25f, startScale = 1.0f)
        val largerVelocity = MotionPhysics.resolvePopStartVelocity(popScale = 1.35f, startScale = 1.0f)
        val compressedVelocity = MotionPhysics.resolvePopStartVelocity(popScale = 1.25f, startScale = 0.90f)

        assertTrue(normalVelocity > 0f)
        assertTrue(largerVelocity > normalVelocity)
        assertTrue(compressedVelocity > normalVelocity)
    }

    @Test
    fun `shouldTriggerFavoritePop only activates when transition lands on favorited`() {
        var popInvocations = 0
        var lastHapticUsed: Int? = null

        val transitions = listOf(true, false, true)
        for (isFavoriteNow in transitions) {
            if (MotionPhysics.shouldTriggerFavoritePop(isFavoriteNow)) {
                popInvocations++
                lastHapticUsed = MotionPhysics.resolveHapticConstant(
                    HapticFeedbackConstantsCompat.CONFIRM,
                    Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                )
            }
        }

        assertEquals(1.25f, MotionPhysics.DEFAULT_POP_SCALE, 0.0001f)
        assertEquals(2, popInvocations)
        assertEquals(HapticFeedbackConstantsCompat.CONFIRM, lastHapticUsed)
    }
}
