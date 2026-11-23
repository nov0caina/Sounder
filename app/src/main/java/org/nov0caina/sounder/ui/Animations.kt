/*
 * Copyright (c) 2024 Auxio Project
 * Animations.kt is part of Auxio.
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
 
package org.nov0caina.sounder.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.R as MR
import com.google.android.material.motion.MotionUtils
import com.google.android.material.shape.MaterialShapeDrawable
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.nov0caina.sounder.R
import org.nov0caina.sounder.util.getDrawableCompat
import org.nov0caina.sounder.util.scale

private const val MIN_VISIBLE_CHANGE_DRAWABLE_ALPHA = 1f

class Spatial private constructor(@AttrRes val attr: Int, val defaultStyle: Int) {
    internal fun resolve(context: Context) =
        MotionUtils.resolveThemeSpringForce(context, attr, defaultStyle)

    fun scale(view: View, to: Float, jumpOnCancellation: Boolean = false): SpringAnimation {
        val from = view.scale
        val springForce = resolve(view.context)
        return SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce
            setStartValue(from)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
            addUpdateListener { _, value, _ -> view.scale = value }
            addEndListener { _, canceled, value, _ ->
                view.scale = if (!canceled || jumpOnCancellation) to else value
            }
            animateToFinalPosition(to)
        }
    }

    fun translateX(view: View, to: Float, jumpOnCancellation: Boolean = false): SpringAnimation {
        val from = view.translationX
        val springForce = resolve(view.context)
        return SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce
            setStartValue(from)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
            addUpdateListener { _, value, _ -> view.translationX = value }
            addEndListener { _, canceled, value, _ ->
                view.translationX = if (!canceled || jumpOnCancellation) to else value
            }
            animateToFinalPosition(to)
        }
    }

    fun translateZ(view: View, to: Float, jumpOnCancellation: Boolean = false): SpringAnimation {
        val from = view.translationZ
        val springForce = resolve(view.context)
        return SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce
            setStartValue(from)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
            addUpdateListener { _, value, _ -> view.translationZ = value }
            addEndListener { _, canceled, value, _ ->
                view.translationZ = if (!canceled || jumpOnCancellation) to else value
            }
            animateToFinalPosition(to)
        }
    }

    fun elevation(
        context: Context,
        drawable: MaterialShapeDrawable,
        to: Float,
        jumpOnCancellation: Boolean = false,
    ): SpringAnimation {
        val from = drawable.elevation
        val springForce = resolve(context)
        return SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce
            setStartValue(from)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
            addUpdateListener { _, value, _ -> drawable.elevation = value }
            addEndListener { _, canceled, value, _ ->
                drawable.elevation = if (!canceled || jumpOnCancellation) to else value
            }
            animateToFinalPosition(to)
        }
    }

    fun corners(
        context: Context,
        drawable: MaterialShapeDrawable,
        to: Float,
        jumpOnCancellation: Boolean = false,
    ): SpringAnimation {
        val from = drawable.topRightCornerResolvedSize
        val springForce = resolve(context)
        return SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce
            setStartValue(from)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
            addUpdateListener { _, value, _ -> drawable.setCornerSize(value) }
            addEndListener { _, canceled, value, _ ->
                drawable.setCornerSize(if (!canceled || jumpOnCancellation) to else value)
            }
            animateToFinalPosition(to)
        }
    }

    /** Animate an integer property smoothly using Material 3 spring dynamics. */
    fun intValue(
        context: Context,
        from: Int,
        to: Int,
        onUpdate: (Int) -> Unit,
        jumpOnCancellation: Boolean = false,
    ): SpringAnimation {
        val springForce = resolve(context)
        return SpringAnimation(FloatValueHolder(from.toFloat())).apply {
            spring = springForce
            setStartValue(from.toFloat())
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
            addUpdateListener { _, value, _ -> onUpdate(value.roundToInt()) }
            addEndListener { _, canceled, value, _ ->
                onUpdate(if (!canceled || jumpOnCancellation) to else value.roundToInt())
            }
            animateToFinalPosition(to.toFloat())
        }
    }

    @SuppressLint("PrivateResource")
    companion object {
        val FAST =
            Spatial(
                MR.attr.motionSpringFastSpatial,
                MR.style.Motion_Material3_Spring_Standard_Fast_Spatial,
            )
        val DEFAULT =
            Spatial(
                MR.attr.motionSpringDefaultSpatial,
                MR.style.Motion_Material3_Spring_Standard_Default_Spatial,
            )
        //        val SLOW =
        //            Spatial(
        //                MR.attr.motionSpringSlowSpatial,
        //                MR.style.Motion_Material3_Spring_Standard_Slow_Spatial,
        //            )
    }
}

class Effect private constructor(@AttrRes val attr: Int, @StyleRes val defaultStyle: Int) {
    fun resolve(context: Context) = MotionUtils.resolveThemeSpringForce(context, attr, defaultStyle)

    fun alpha(view: View, to: Float, jumpOnCancellation: Boolean = false): SpringAnimation {
        val from = view.alpha
        val springForce = resolve(view.context)
        return SpringAnimation(FloatValueHolder(from)).apply {
            spring = springForce
            setStartValue(from)
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
            addUpdateListener { _, value, _ ->
                view.alpha = value
                view.isInvisible = view.alpha == 0f
            }
            addEndListener { _, canceled, value, _ ->
                view.alpha = if (!canceled || jumpOnCancellation) to else value
                view.isInvisible = view.alpha == 0f
            }
            animateToFinalPosition(to)
        }
    }

    fun alpha(
        context: Context,
        drawable: Drawable,
        to: Int,
        jumpOnCancellation: Boolean = false,
    ): SpringAnimation {
        val from = drawable.alpha
        val springForce = resolve(context)
        return SpringAnimation(FloatValueHolder(from.toFloat())).apply {
            spring = springForce
            setStartValue(from.toFloat())
            setMinimumVisibleChange(MIN_VISIBLE_CHANGE_DRAWABLE_ALPHA)
            addUpdateListener { _, value, _ -> drawable.alpha = value.toInt() }
            addEndListener { _, canceled, value, _ ->
                drawable.alpha = (if (!canceled || jumpOnCancellation) to else value).toInt()
            }
            animateToFinalPosition(to.toFloat())
        }
    }

    companion object {
        val DEFAULT =
            Effect(
                MR.attr.motionSpringDefaultEffects,
                MR.style.Motion_Material3_Spring_Standard_Default_Effects,
            )
        //        val SLOW =
        //            Effect(
        //                MR.attr.motionSpringSlowEffects,
        //                MR.style.Motion_Material3_Spring_Standard_Slow_Effects,
        //            )
        val FAST =
            Effect(
                MR.attr.motionSpringFastEffects,
                MR.style.Motion_Material3_Spring_Standard_Fast_Effects,
            )
    }
}

/** Pure-JVM spring stiffness and damping configuration for fluid microinteractions. */
data class SpringSpec(val stiffness: Float, val dampingRatio: Float)

/** Pure-JVM motion physics policy and scaling helpers for unit-tested UI microinteractions. */
object MotionPhysics {
    const val DEFAULT_SQUISH_SCALE = 0.95f
    const val ICON_SQUISH_SCALE = 0.90f
    const val MIN_SQUISH_SCALE = 0.80f
    const val DEFAULT_POP_SCALE = 1.25f
    const val COVER_PLAYING_SCALE = 1.0f
    const val COVER_PAUSED_SCALE = 0.94f
    const val SEEKBAR_THUMB_HEIGHT_MULTIPLIER = 1.35f
    const val SEEKBAR_THUMB_WIDTH_MULTIPLIER = 1.5f
    const val SEEKBAR_POSITION_SCALE = 1.08f
    const val SEEKBAR_HAPTIC_MIN_INTERVAL_MS = 80L
    const val HAPTIC_CONFIRM_API_LEVEL = Build.VERSION_CODES.R

    val TOUCH_PRESS_SPRING = SpringSpec(stiffness = 380f, dampingRatio = 0.78f)
    val TOUCH_RELEASE_SPRING = SpringSpec(stiffness = 260f, dampingRatio = 0.44f)
    val POP_SPRING = SpringSpec(stiffness = 280f, dampingRatio = 0.38f)
    val COVER_PLAY_SPRING = SpringSpec(stiffness = 210f, dampingRatio = 0.48f)
    val COVER_PAUSE_SPRING = SpringSpec(stiffness = 260f, dampingRatio = 0.68f)
    val SEEKBAR_GRAB_SPRING = SpringSpec(stiffness = 340f, dampingRatio = 0.50f)
    val SEEKBAR_RELEASE_SPRING = SpringSpec(stiffness = 280f, dampingRatio = 0.52f)
    val PLAY_PAUSE_CORNER_SPRING = SpringSpec(stiffness = 380f, dampingRatio = 0.56f)

    fun clampSquishScale(scaleDown: Float): Float = scaleDown.coerceIn(MIN_SQUISH_SCALE, 1.0f)

    /**
     * Resolve target view scale for a touch action without consuming scrolling gestures. Returns null
     * for intermediate move events so active springs remain undisturbed.
     */
    fun resolveTouchScale(actionMasked: Int, scaleDown: Float = DEFAULT_SQUISH_SCALE): Float? =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> clampSquishScale(scaleDown)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> 1.0f
            else -> null
        }

    fun resolveTouchSpring(actionMasked: Int): SpringSpec? =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> TOUCH_PRESS_SPRING
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> TOUCH_RELEASE_SPRING
            else -> null
        }

    fun shouldConsumeTouchEvent(): Boolean = false

    fun resolveCoverScale(isPlaying: Boolean): Float =
        if (isPlaying) COVER_PLAYING_SCALE else COVER_PAUSED_SCALE

    fun resolveCoverSpring(isPlaying: Boolean): SpringSpec =
        if (isPlaying) COVER_PLAY_SPRING else COVER_PAUSE_SPRING

    fun resolveSeekBarSpring(isTracking: Boolean): SpringSpec =
        if (isTracking) SEEKBAR_GRAB_SPRING else SEEKBAR_RELEASE_SPRING

    /**
     * Compute a continuous underdamped launch velocity targeting resting scale 1.0 so a single
     * harmonic spring peaks naturally near [popScale] without mid-flight parameter jumps.
     */
    fun resolvePopStartVelocity(
        popScale: Float = DEFAULT_POP_SCALE,
        startScale: Float = 1.0f,
        spec: SpringSpec = POP_SPRING,
    ): Float {
        val overshoot = (popScale - 1.0f).coerceAtLeast(0.05f)
        val zeta = spec.dampingRatio.coerceIn(0.15f, 0.95f)
        val omegaN = sqrt(spec.stiffness.coerceAtLeast(1f))
        val sqrtTerm = sqrt(1f - zeta * zeta)
        val phi = atan2(sqrtTerm, zeta)
        val baseVelocity = overshoot * omegaN * exp(zeta * phi / sqrtTerm)
        val travelCompensation = (1.0f - startScale).coerceAtLeast(0f) * omegaN * 0.65f
        return baseVelocity + travelCompensation
    }

    fun resolveExpandedThumbHeight(baseHeightPx: Int, maxHeightPx: Int): Int {
        val safeBase = baseHeightPx.coerceAtLeast(1)
        val safeMax = maxHeightPx.coerceAtLeast(safeBase)
        return (safeBase * SEEKBAR_THUMB_HEIGHT_MULTIPLIER).roundToInt().coerceIn(safeBase, safeMax)
    }

    fun resolveExpandedThumbWidth(baseWidthPx: Int, minDeltaPx: Int = 2): Int {
        val safeBase = baseWidthPx.coerceAtLeast(1)
        return maxOf((safeBase * SEEKBAR_THUMB_WIDTH_MULTIPLIER).roundToInt(), safeBase + minDeltaPx)
    }

    fun shouldTriggerFavoritePop(isFavoriteNow: Boolean): Boolean = isFavoriteNow

    fun resolveHapticConstant(requestedConstant: Int, sdkInt: Int): Int =
        if (
            requestedConstant == HapticFeedbackConstantsCompat.CONFIRM &&
                sdkInt < HAPTIC_CONFIRM_API_LEVEL
        ) {
            HapticFeedbackConstants.VIRTUAL_KEY
        } else {
            requestedConstant
        }
}

private val VIEW_SCALE_PROPERTY =
    object : FloatPropertyCompat<View>("viewScale") {
        override fun getValue(view: View): Float = view.scale

        override fun setValue(view: View, value: Float) {
            view.scale = value
        }
    }

private fun View.getOrCreateScaleSpring(): SpringAnimation {
    val existing = getTag(R.id.tag_spring_scale_anim) as? SpringAnimation
    if (existing != null) return existing
    return SpringAnimation(this, VIEW_SCALE_PROPERTY).apply {
        spring = SpringForce(1.0f)
        setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        setTag(R.id.tag_spring_scale_anim, this)
    }
}

/**
 * Reuse a persistent scale [SpringAnimation] on this [View] so rapid reversals preserve existing
 * spring velocity instead of resetting momentum.
 */
fun View.animateScaleSpring(
    targetScale: Float,
    spec: SpringSpec,
    startVelocity: Float? = null,
): SpringAnimation {
    val anim = getOrCreateScaleSpring()
    val force = anim.spring ?: SpringForce(targetScale).also { anim.spring = it }
    force.stiffness = spec.stiffness
    force.dampingRatio = spec.dampingRatio
    if (!anim.isRunning) {
        anim.setStartValue(scale)
    }
    if (startVelocity != null) {
        anim.setStartVelocity(startVelocity)
    }
    anim.animateToFinalPosition(targetScale)
    return anim
}

/** Cancel any active scale spring on this [View] and optionally reset scale to resting 1.0. */
fun View.cancelScaleSpring(resetScale: Boolean = false) {
    (getTag(R.id.tag_spring_scale_anim) as? SpringAnimation)?.cancel()
    setTag(R.id.tag_spring_scale_anim, null)
    (getTag(R.id.tag_spring_pop_anim) as? SpringAnimation)?.cancel()
    setTag(R.id.tag_spring_pop_anim, null)
    if (resetScale) {
        scale = 1.0f
    }
}

/**
 * Attach non-blocking spring touch squish feedback to a clickable card, avatar, or button. Always
 * returns false from touch handling so click listeners and parent scrolling containers continue
 * uninterrupted.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.attachSpringTouchFeedback(scaleDown: Float = MotionPhysics.DEFAULT_SQUISH_SCALE) {
    setOnTouchListener { v, event ->
        val targetScale = MotionPhysics.resolveTouchScale(event.actionMasked, scaleDown)
        val springSpec = MotionPhysics.resolveTouchSpring(event.actionMasked)
        if (targetScale != null && springSpec != null) {
            v.animateScaleSpring(targetScale, springSpec)
        }
        MotionPhysics.shouldConsumeTouchEvent()
    }

    // Reset scale if a ViewHolder is recycled mid-press so detached items never remain compressed.
    (getTag(R.id.tag_spring_attach_listener) as? View.OnAttachStateChangeListener)?.let(
        ::removeOnAttachStateChangeListener
    )
    val attachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                v.cancelScaleSpring(resetScale = true)
            }
        }
    setTag(R.id.tag_spring_attach_listener, attachListener)
    addOnAttachStateChangeListener(attachListener)
}

/**
 * Trigger a single continuous underdamped elastic pop spring animation paired with crisp haptic
 * confirmation. Designed for favorite heart toggles and primary emphasis actions.
 */
fun View.performSpringPop(
    popScale: Float = MotionPhysics.DEFAULT_POP_SCALE,
    hapticConstant: Int? = HapticFeedbackConstantsCompat.CONFIRM,
) {
    if (hapticConstant != null) {
        performHapticFeedback(
            MotionPhysics.resolveHapticConstant(hapticConstant, Build.VERSION.SDK_INT)
        )
    }

    val currentScale = scale.coerceIn(MotionPhysics.MIN_SQUISH_SCALE, 1.0f)
    val velocity =
        MotionPhysics.resolvePopStartVelocity(
            popScale = popScale,
            startScale = currentScale,
            spec = MotionPhysics.POP_SPRING,
        )
    animateScaleSpring(
        targetScale = 1.0f,
        spec = MotionPhysics.POP_SPRING,
        startVelocity = velocity,
    )
}

/**
 * Render an animated multi-bar equalizer indicator when a track item is actively playing,
 * ensuring lifecycle-safe start/stop across RecyclerView attach/detach recycling.
 */
fun ImageView.setAnimatedPlayingIndicator(isPlaying: Boolean) {
    if (getTag(R.id.tag_playing_indicator_attach_listener) == null) {
        val attachListener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    if (v.isVisible) {
                        ((v as? ImageView)?.drawable as? AnimationDrawable)?.start()
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    ((v as? ImageView)?.drawable as? AnimationDrawable)?.stop()
                }
            }
        setTag(R.id.tag_playing_indicator_attach_listener, attachListener)
        addOnAttachStateChangeListener(attachListener)
    }

    if (isPlaying) {
        isVisible = true
        val animDrawable =
            (drawable as? AnimationDrawable)
                ?: (context.getDrawableCompat(R.drawable.ic_playing_indicator_24).mutate()
                        as? AnimationDrawable)
                    ?.also { setImageDrawable(it) }
        if (animDrawable != null && isAttachedToWindow) {
            post {
                if (isVisible && isAttachedToWindow) {
                    animDrawable.start()
                }
            }
        }
    } else {
        (drawable as? AnimationDrawable)?.stop()
        isVisible = false
    }
}

