/*
 * Copyright (c) 2022 Auxio Project
 * StyledSeekBar.kt is part of Auxio.
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
 
package org.nov0caina.sounder.playback.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.slider.Slider
import kotlin.math.max
import kotlin.math.roundToInt
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ViewSeekBarBinding
import org.nov0caina.sounder.playback.formatDurationDs
import org.nov0caina.sounder.ui.MotionPhysics
import org.nov0caina.sounder.ui.SpringSpec
import org.nov0caina.sounder.ui.animateScaleSpring
import org.nov0caina.sounder.ui.cancelScaleSpring
import org.nov0caina.sounder.util.inflater
import timber.log.Timber as L

/** Throttle discrete second-boundary scrub ticks to avoid continuous vibration buzz. */
internal class SeekBarHapticThrottle(
    private val minIntervalMs: Long = MotionPhysics.SEEKBAR_HAPTIC_MIN_INTERVAL_MS
) {
    private var lastSecond: Long = Long.MIN_VALUE
    private var lastTickTimeMs: Long = Long.MIN_VALUE

    fun start(initialPositionDs: Long) {
        lastSecond = initialPositionDs / 10L
        lastTickTimeMs = Long.MIN_VALUE
    }

    fun shouldEmitTick(positionDs: Long, nowMs: Long): Boolean {
        val currentSecond = positionDs / 10L
        if (currentSecond == lastSecond) return false
        val enoughTimeElapsed =
            lastTickTimeMs == Long.MIN_VALUE || (nowMs - lastTickTimeMs) >= minIntervalMs
        if (!enoughTimeElapsed) return false
        lastSecond = currentSecond
        lastTickTimeMs = nowMs
        return true
    }

    fun reset() {
        lastSecond = Long.MIN_VALUE
        lastTickTimeMs = Long.MIN_VALUE
    }
}

/**
 * A wrapper around [Slider] that shows position and duration values and sanitizes input to reduce
 * crashes from invalid values.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class StyledSeekBar
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    ForcedLTRFrameLayout(context, attrs, defStyleAttr),
    Slider.OnSliderTouchListener,
    Slider.OnChangeListener {
    private val binding = ViewSeekBarBinding.inflate(context.inflater, this, true)
    private val hapticThrottle = SeekBarHapticThrottle()
    private var baseThumbHeightPx: Int = 0
    private var baseThumbWidthPx: Int = 0
    private var thumbHeightAnimation: SpringAnimation? = null
    private var thumbWidthAnimation: SpringAnimation? = null

    init {
        binding.seekBarSlider.addOnSliderTouchListener(this)
        binding.seekBarSlider.addOnChangeListener(this)
    }

    /** Enables/disables wavy active-track rendering to match playback state. */
    fun setWaveEnabled(enabled: Boolean) {
        binding.seekBarSlider.setWaveEnabled(enabled)
    }

    /** The current [Listener] attached to this instance. */
    var listener: Listener? = null

    /**
     * The current position, in deci-seconds(1/10th of a second). This is the current value of the
     * SeekBar and is indicated by the start TextView in the layout.
     */
    var positionDs: Long
        get() = binding.seekBarSlider.value.toLong()
        set(value) {
            // Sanity check 1: Ensure that no negative values are sneaking their way into
            // this component.
            val from = max(value, 0)
            // Sanity check 2: Ensure that this value is within the duration and will not crash
            // the app, and that the user is not currently seeking (which would cause the SeekBar
            // to jump around).
            if (from <= durationDs && !isActivated) {
                binding.seekBarSlider.value = from.toFloat()
                // We would want to keep this in the listener, but the listener only fires when
                // a value changes completely, and sometimes that does not happen with this view.
                binding.seekBarPosition.text = from.formatDurationDs(true)
            }
        }

    /**
     * The current duration, in deci-seconds (1/10th of a second). This is the end value of the
     * SeekBar and is indicated by the end TextView in the layout.
     */
    var durationDs: Long
        get() = binding.seekBarSlider.valueTo.toLong()
        set(value) {
            // Sanity check 1: If this is a value so low that it effectively rounds down to
            // zero, use 1 instead and disable the SeekBar.
            val to = max(value, 1)
            isEnabled = value > 0
            L.d("Value sanitization finished [to=$to, enabled=$isEnabled]")
            // Sanity check 2: If the current value exceeds the new duration value, clamp it
            // down so that we don't crash and instead have an annoying visual flicker.
            if (positionDs > to) {
                L.d("Clamping invalid position [current: $positionDs new max: $to]")
                binding.seekBarSlider.value = to.toFloat()
            }
            binding.seekBarSlider.valueTo = to.toFloat()
            binding.seekBarDuration.text = value.formatDurationDs(false)
        }

    override fun onStartTrackingTouch(slider: Slider) {
        L.d("Starting seek mode")
        // User has begun seeking, place the SeekBar into a "Suspended" mode in which no
        // position updates are sent and is indicated by the position value turning accented.
        isActivated = true
        ensureBaseThumbDimensions()
        hapticThrottle.start(slider.value.toLong())

        val maxThumbHeightPx = (40 * resources.displayMetrics.density).roundToInt()
        val expandedHeight = MotionPhysics.resolveExpandedThumbHeight(baseThumbHeightPx, maxThumbHeightPx)
        val minWidthDeltaPx = (2 * resources.displayMetrics.density).roundToInt().coerceAtLeast(2)
        val expandedWidth = MotionPhysics.resolveExpandedThumbWidth(baseThumbWidthPx, minWidthDeltaPx)
        animateThumbDimensions(
            targetHeight = expandedHeight,
            targetWidth = expandedWidth,
            isTracking = true,
        )

        binding.seekBarPosition.animateScaleSpring(
            targetScale = MotionPhysics.SEEKBAR_POSITION_SCALE,
            spec = MotionPhysics.resolveSeekBarSpring(isTracking = true),
        )
    }

    override fun onStopTrackingTouch(slider: Slider) {
        L.d("Confirming seek")
        // End of seek event, send off new value to listener.
        isActivated = false
        hapticThrottle.reset()
        if (baseThumbHeightPx > 0 && baseThumbWidthPx > 0) {
            animateThumbDimensions(
                targetHeight = baseThumbHeightPx,
                targetWidth = baseThumbWidthPx,
                isTracking = false,
            )
        }
        binding.seekBarPosition.animateScaleSpring(
            targetScale = 1.0f,
            spec = MotionPhysics.resolveSeekBarSpring(isTracking = false),
        )
        listener?.onSeekConfirmed(slider.value.toLong())
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        binding.seekBarPosition.text = value.toLong().formatDurationDs(true)
        if (
            fromUser &&
                isActivated &&
                hapticThrottle.shouldEmitTick(value.toLong(), SystemClock.uptimeMillis())
        ) {
            slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onDetachedFromWindow() {
        thumbHeightAnimation?.cancel()
        thumbHeightAnimation = null
        thumbWidthAnimation?.cancel()
        thumbWidthAnimation = null
        binding.seekBarPosition.cancelScaleSpring(resetScale = true)
        if (baseThumbHeightPx > 0) {
            binding.seekBarSlider.thumbHeight = baseThumbHeightPx
        }
        if (baseThumbWidthPx > 0) {
            binding.seekBarSlider.thumbWidth = baseThumbWidthPx
        }
        hapticThrottle.reset()
        super.onDetachedFromWindow()
    }

    private fun ensureBaseThumbDimensions() {
        if (baseThumbHeightPx <= 0) {
            baseThumbHeightPx =
                binding.seekBarSlider.thumbHeight.takeIf { it > 0 }
                    ?: resources.getDimensionPixelSize(R.dimen.slider_thumb_height)
        }
        if (baseThumbWidthPx <= 0) {
            baseThumbWidthPx =
                binding.seekBarSlider.thumbWidth.takeIf { it > 0 }
                    ?: (4 * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        }
    }

    private fun animateThumbDimensions(targetHeight: Int, targetWidth: Int, isTracking: Boolean) {
        val slider = binding.seekBarSlider
        val spec = MotionPhysics.resolveSeekBarSpring(isTracking)
        thumbHeightAnimation =
            animateSliderProperty(
                existing = thumbHeightAnimation,
                slider = slider,
                property = THUMB_HEIGHT_PROPERTY,
                currentVal = slider.thumbHeight.takeIf { it > 0 } ?: baseThumbHeightPx,
                targetVal = targetHeight,
                spec = spec,
            )
        thumbWidthAnimation =
            animateSliderProperty(
                existing = thumbWidthAnimation,
                slider = slider,
                property = THUMB_WIDTH_PROPERTY,
                currentVal = slider.thumbWidth.takeIf { it > 0 } ?: baseThumbWidthPx,
                targetVal = targetWidth,
                spec = spec,
            )
    }

    private fun animateSliderProperty(
        existing: SpringAnimation?,
        slider: Slider,
        property: FloatPropertyCompat<Slider>,
        currentVal: Int,
        targetVal: Int,
        spec: SpringSpec,
    ): SpringAnimation {
        val anim =
            existing
                ?: SpringAnimation(slider, property).apply {
                    spring = SpringForce(targetVal.toFloat())
                    setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
                }
        val force = anim.spring ?: SpringForce(targetVal.toFloat()).also { anim.spring = it }
        force.stiffness = spec.stiffness
        force.dampingRatio = spec.dampingRatio
        if (!anim.isRunning) {
            anim.setStartValue(currentVal.toFloat())
        }
        anim.animateToFinalPosition(targetVal.toFloat())
        return anim
    }

    private companion object {
        val THUMB_HEIGHT_PROPERTY =
            object : FloatPropertyCompat<Slider>("thumbHeight") {
                override fun getValue(slider: Slider): Float = slider.thumbHeight.toFloat()

                override fun setValue(slider: Slider, value: Float) {
                    slider.thumbHeight = value.roundToInt().coerceAtLeast(1)
                }
            }

        val THUMB_WIDTH_PROPERTY =
            object : FloatPropertyCompat<Slider>("thumbWidth") {
                override fun getValue(slider: Slider): Float = slider.thumbWidth.toFloat()

                override fun setValue(slider: Slider, value: Float) {
                    slider.thumbWidth = value.roundToInt().coerceAtLeast(1)
                }
            }
    }

    /** A listener for SeekBar interactions. */
    interface Listener {
        /**
         * Called when the internal [Slider] was scrubbed to a new position, requesting that a seek
         * be performed.
         *
         * @param positionDs The position to seek to, in deci-seconds (1/10th of a second).
         */
        fun onSeekConfirmed(positionDs: Long)
    }
}
