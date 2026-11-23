/*
 * Copyright (c) 2026 Auxio Project
 * BackdropBlurView.kt is part of Auxio / Sounder.
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.R as MR
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import org.nov0caina.sounder.util.getAttrColorCompat

/**
 * Pure-JVM visual policy controlling translucent frosted-glass appearance and sheet background fade for
 * the minimized playback bar and bottom navigation bar.
 */
object PlaybackBarGlassPolicy {
    const val FROSTED_GLASS_ALPHA = 218 // ~85% translucent frosted glass wash
    const val BLUR_RADIUS_DP = 28f
    const val DOWNSCALE_FACTOR = 0.25f
    const val SOLID_TRANSITION_START_RATIO = 0.02f
    const val SOLID_TRANSITION_END_RATIO = 0.45f
    const val AMOLED_LUMINANCE_THRESHOLD = 0.02f
    const val DARK_LUMINANCE_THRESHOLD = 0.50f

    /**
     * Alpha of the playback sheet background:
     * When a dedicated [BackdropBlurView] is active behind the sheet (`hasBackdropBlur = true`),
     * the sheet background starts at 0 (transparent) while collapsed so the frosted glass layer
     * shines through cleanly without double-tinting, and smoothly ramps to 100% solid opacity (255)
     * as the playback sheet expands toward the panel reveal midpoint (`0.45f`).
     */
    fun sheetBackgroundAlpha(playbackRatio: Float, hasBackdropBlur: Boolean = false): Int {
        val startAlpha = if (hasBackdropBlur) 0 else FROSTED_GLASS_ALPHA
        val clamped = playbackRatio.coerceIn(0f, 1f)
        if (clamped <= SOLID_TRANSITION_START_RATIO) return startAlpha
        if (clamped >= SOLID_TRANSITION_END_RATIO) return 255
        val t =
            (clamped - SOLID_TRANSITION_START_RATIO) /
                (SOLID_TRANSITION_END_RATIO - SOLID_TRANSITION_START_RATIO)
        val smooth = t * t * (3f - 2f * t)
        return (startAlpha + smooth * (255f - startAlpha)).roundToInt().coerceIn(0, 255)
    }

    /** Complement ratio of the glass state. */
    fun glassViewAlpha(playbackRatio: Float): Float {
        val currentAlpha = sheetBackgroundAlpha(playbackRatio, hasBackdropBlur = false)
        return 1f - ((currentAlpha - FROSTED_GLASS_ALPHA).toFloat() / (255f - FROSTED_GLASS_ALPHA)).coerceIn(0f, 1f)
    }

    fun isGlassActive(playbackRatio: Float, sheetVisible: Boolean): Boolean =
        sheetVisible && playbackRatio < SOLID_TRANSITION_END_RATIO

    /**
     * Whether the minimized playback bar glass layer currently covers the bottom navigation region
     * so both bars share one uninterrupted frosted pane without double-blurring or seams.
     */
    fun isPlaybackGlassCoveringBottomNav(
        playbackRatio: Float,
        sheetVisible: Boolean,
        sheetTop: Int,
        bottomNavTop: Int,
    ): Boolean = isGlassActive(playbackRatio, sheetVisible) && sheetTop < bottomNavTop

    /**
     * Whether the standalone bottom-navigation backdrop blur should render (when bottom nav is
     * visible and the playback sheet is not already providing the glass/surface behind it).
     */
    fun isBottomNavGlassActive(
        bottomNavVisible: Boolean,
        sheetVisible: Boolean,
        sheetTop: Int,
        bottomNavTop: Int,
    ): Boolean = bottomNavVisible && (!sheetVisible || sheetTop >= bottomNavTop)

    fun resolveTintAlpha(surfaceLuminance: Float, hasHardwareBlur: Boolean): Int {
        val fraction =
            if (hasHardwareBlur) {
                when {
                    surfaceLuminance < AMOLED_LUMINANCE_THRESHOLD -> 0.74f
                    surfaceLuminance < DARK_LUMINANCE_THRESHOLD -> 0.72f
                    else -> 0.72f
                }
            } else {
                when {
                    surfaceLuminance < AMOLED_LUMINANCE_THRESHOLD -> 0.90f
                    surfaceLuminance < DARK_LUMINANCE_THRESHOLD -> 0.88f
                    else -> 0.88f
                }
            }
        return (fraction * 255f).roundToInt().coerceIn(0, 255)
    }

    fun resolveRimAlpha(isDarkTheme: Boolean): Int = if (isDarkTheme) 42 else 92

    fun computeScaledDimension(fullPx: Int, downscale: Float = DOWNSCALE_FACTOR): Int =
        ceil(fullPx * downscale).toInt().coerceAtLeast(1)

    fun computeScaledBlurRadius(
        density: Float,
        blurRadiusDp: Float = BLUR_RADIUS_DP,
        downscale: Float = DOWNSCALE_FACTOR,
    ): Float = (blurRadiusDp * density * downscale).coerceAtLeast(1f)
}

/**
 * Hardware-accelerated frosted backdrop blur view placed behind the minimized playback bar controls.
 * Uses a downscaled offscreen [RenderNode] + Gaussian [RenderEffect] on Android 12+ (API 31+) and
 * falls back gracefully to a frosted translucent wash on API 24–30.
 */
class BackdropBlurView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {

    var backdropSource: View? = null
        set(value) {
            if (field !== value) {
                field = value
                resetCachedCapture()
                if (isAttachedToWindow && value != null) {
                    invalidate()
                }
            }
        }

    var cornerRadiusPx: Float = 0f
        set(value) {
            if (abs(field - value) > 0.01f) {
                field = value
                cachedPathWidth = -1
                invalidate()
            }
        }

    private val surfaceBaseColor = context.getAttrColorCompat(MR.attr.colorSurface).defaultColor
    private val glassSurfaceColor =
        context.getAttrColorCompat(MR.attr.colorSurfaceContainerLow).defaultColor
    private val surfaceLuminance = ColorUtils.calculateLuminance(surfaceBaseColor).toFloat()
    private val isDarkTheme = surfaceLuminance < PlaybackBarGlassPolicy.DARK_LUMINANCE_THRESHOLD
    private val rimBaseColor =
        if (isDarkTheme) {
            Color.WHITE
        } else {
            context.getAttrColorCompat(MR.attr.colorOutlineVariant).defaultColor
        }

    private var hardwareBackdrop: Any? = null
    private var isCapturing = false
    private var isBackdropDirty = true
    private var lastDx = Int.MIN_VALUE
    private var lastDy = Int.MIN_VALUE
    private var lastVisibleHeight = -1

    private val selfLoc = IntArray(2)
    private val sourceLoc = IntArray(2)
    private val clipRectF = RectF()
    private val arcRectF = RectF()
    private val clipPath = Path()
    private val rimPath = Path()
    private val cornerRadii = FloatArray(8)
    private var cachedPathWidth = -1
    private var cachedPathHeight = -1
    private var cachedPathRadius = -1f

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1f * resources.displayMetrics.density).coerceAtLeast(1f)
            color =
                ColorUtils.setAlphaComponent(
                    rimBaseColor,
                    PlaybackBarGlassPolicy.resolveRimAlpha(isDarkTheme),
                )
        }

    /**
     * Marks the underlying [backdropSource] content as changed so the next pre-draw cycle records a
     * fresh blurred slice without causing idle invalidation loops.
     */
    fun markBackdropDirty() {
        if (!isCapturing) {
            isBackdropDirty = true
        }
    }

    /**
     * Re-record the region of [backdropSource] currently behind this view. Invoked from
     * `MainFragment.onPreDraw()` only while the minimized glass bar is visible so scrolling and
     * sheet motion blur live without triggering idle redraw loops.
     */
    fun refreshBackdrop() {
        captureFromSource(allowInvalidate = true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (w <= 0 || height <= 0) return
        val drawHeight = lastVisibleHeight.takeIf { it > 0 } ?: height
        if (drawHeight <= 0) return

        ensurePaths(w, drawHeight, cornerRadiusPx)

        val canHardwareBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated
        var holder = if (canHardwareBlur) hardwareBackdrop as? Api31RenderNodeBackdrop else null
        if (canHardwareBlur && (holder == null || !holder.hasRecorded) && backdropSource != null) {
            captureFromSource(allowInvalidate = false)
            holder = hardwareBackdrop as? Api31RenderNodeBackdrop
        }
        val useHardwareBlur = canHardwareBlur && holder != null && holder.hasRecorded

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        if (useHardwareBlur) {
            holder.draw(canvas)
        }
        tintPaint.color =
            ColorUtils.setAlphaComponent(
                glassSurfaceColor,
                PlaybackBarGlassPolicy.resolveTintAlpha(surfaceLuminance, useHardwareBlur),
            )
        canvas.drawRect(0f, 0f, w.toFloat(), drawHeight.toFloat(), tintPaint)
        canvas.restoreToCount(saveCount)

        canvas.drawPath(rimPath, rimPaint)
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (hardwareBackdrop as? Api31RenderNodeBackdrop)?.discard()
        }
        resetCachedCapture()
        super.onDetachedFromWindow()
    }

    private fun captureFromSource(allowInvalidate: Boolean) {
        if (isCapturing || !isAttachedToWindow || width <= 0 || height <= 0 || !isVisible) return
        val source = backdropSource ?: return
        if (source.width <= 0 || source.height <= 0) return

        getLocationInWindow(selfLoc)
        source.getLocationInWindow(sourceLoc)
        val dx = selfLoc[0] - sourceLoc[0]
        val dy = selfLoc[1] - sourceLoc[1]
        val visibleHeight = (sourceLoc[1] + source.height - selfLoc[1]).coerceIn(0, height)
        if (visibleHeight <= 0) return

        val geometryChanged = dx != lastDx || dy != lastDy || visibleHeight != lastVisibleHeight
        val contentDirty = isBackdropDirty
        lastDx = dx
        lastDy = dy
        lastVisibleHeight = visibleHeight

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val holder =
                (hardwareBackdrop as? Api31RenderNodeBackdrop)
                    ?: Api31RenderNodeBackdrop().also { hardwareBackdrop = it }
            val hadRecorded = holder.hasRecorded
            if (!geometryChanged && !contentDirty && hadRecorded) {
                return
            }
            isCapturing = true
            try {
                holder.record(
                    source = source,
                    viewWidth = width,
                    visibleHeight = visibleHeight,
                    dx = dx,
                    dy = dy,
                    density = resources.displayMetrics.density,
                    surfaceBaseColor = surfaceBaseColor,
                )
            } finally {
                isCapturing = false
            }
            isBackdropDirty = false
            if (allowInvalidate && (geometryChanged || contentDirty || !hadRecorded)) {
                invalidate()
            }
        } else {
            isBackdropDirty = false
            if (allowInvalidate && geometryChanged) {
                invalidate()
            }
        }
    }

    private fun resetCachedCapture() {
        isBackdropDirty = true
        lastDx = Int.MIN_VALUE
        lastDy = Int.MIN_VALUE
        lastVisibleHeight = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (hardwareBackdrop as? Api31RenderNodeBackdrop)?.resetState()
        }
    }

    private fun ensurePaths(widthPx: Int, heightPx: Int, requestedRadius: Float) {
        val r = requestedRadius.coerceIn(0f, min(widthPx / 2f, heightPx.toFloat()))
        if (
            cachedPathWidth == widthPx &&
                cachedPathHeight == heightPx &&
                abs(cachedPathRadius - r) < 0.01f
        ) {
            return
        }
        cachedPathWidth = widthPx
        cachedPathHeight = heightPx
        cachedPathRadius = r

        clipPath.reset()
        clipRectF.set(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        cornerRadii[0] = r
        cornerRadii[1] = r
        cornerRadii[2] = r
        cornerRadii[3] = r
        cornerRadii[4] = 0f
        cornerRadii[5] = 0f
        cornerRadii[6] = 0f
        cornerRadii[7] = 0f
        clipPath.addRoundRect(clipRectF, cornerRadii, Path.Direction.CW)

        rimPath.reset()
        val inset = rimPaint.strokeWidth / 2f
        if (r > inset) {
            rimPath.moveTo(inset, r)
            arcRectF.set(inset, inset, 2f * r - inset, 2f * r - inset)
            rimPath.arcTo(arcRectF, 180f, 90f, false)
            rimPath.lineTo(widthPx - r, inset)
            arcRectF.set(widthPx - 2f * r + inset, inset, widthPx - inset, 2f * r - inset)
            rimPath.arcTo(arcRectF, 270f, 90f, false)
        } else {
            rimPath.moveTo(0f, inset)
            rimPath.lineTo(widthPx.toFloat(), inset)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Api31RenderNodeBackdrop {
        private val renderNode = RenderNode("PlaybackBarBackdrop")
        private var lastRadius = -1f
        private var currentDownscale = PlaybackBarGlassPolicy.DOWNSCALE_FACTOR
        private var currentPadY = 0
        var hasRecorded = false
            private set

        fun record(
            source: View,
            viewWidth: Int,
            visibleHeight: Int,
            dx: Int,
            dy: Int,
            density: Float,
            surfaceBaseColor: Int,
        ) {
            val downscale = PlaybackBarGlassPolicy.DOWNSCALE_FACTOR
            val scaledRadius = PlaybackBarGlassPolicy.computeScaledBlurRadius(density, downscale = downscale)
            val nodeW = PlaybackBarGlassPolicy.computeScaledDimension(viewWidth, downscale)
            val nodeH = PlaybackBarGlassPolicy.computeScaledDimension(visibleHeight, downscale)
            val padY = ceil(scaledRadius).toInt()
            currentDownscale = downscale
            currentPadY = padY

            renderNode.setPosition(0, 0, nodeW, nodeH + 2 * padY)
            if (abs(lastRadius - scaledRadius) > 0.05f) {
                renderNode.setRenderEffect(
                    RenderEffect.createBlurEffect(scaledRadius, scaledRadius, Shader.TileMode.CLAMP)
                )
                lastRadius = scaledRadius
            }

            var recordedOk = false
            try {
                val rec = renderNode.beginRecording()
                try {
                    rec.drawColor(surfaceBaseColor)
                    rec.scale(downscale, downscale)
                    rec.translate(-dx.toFloat(), -dy.toFloat() + padY / downscale)
                    source.draw(rec)
                    recordedOk = true
                } finally {
                    renderNode.endRecording()
                }
            } catch (_: Throwable) {
                recordedOk = false
            }
            hasRecorded = recordedOk
        }

        fun draw(canvas: Canvas) {
            if (!hasRecorded) return
            val saveCount = canvas.save()
            canvas.scale(1f / currentDownscale, 1f / currentDownscale)
            canvas.translate(0f, -currentPadY.toFloat())
            canvas.drawRenderNode(renderNode)
            canvas.restoreToCount(saveCount)
        }

        fun resetState() {
            hasRecorded = false
        }

        fun discard() {
            hasRecorded = false
            renderNode.discardDisplayList()
        }
    }
}
