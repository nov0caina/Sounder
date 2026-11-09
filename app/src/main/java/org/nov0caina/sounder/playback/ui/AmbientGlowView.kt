/*
 * Copyright (c) 2026 Auxio Project
 * AmbientGlowView.kt is part of Auxio / Sounder.
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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.toBitmap
import org.nov0caina.sounder.util.getAttrColorCompat
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A custom view that renders a rich, vibrant, seamless ambient artwork glow behind the playback album cover.
 *
 * It uses a hardware-accelerated RenderEffect blur and radial gradient falloff mask to guarantee that:
 * 1. The glow radiates richly, prominently, and vibrantly around the album art.
 * 2. The glow fades smoothly and organically to 0% opacity with zero abrupt rectangular cutoffs.
 * 3. In light themes, shadows are gracefully lifted so dark albums never create dirty smudges.
 * 4. The glow sits in the background plane, enhancing the aesthetic without obscuring controls.
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentSongUid: Any? = null
    private var glowBitmap: Bitmap? = null
    private var oldBitmap: Bitmap? = null
    private var transitionProgress: Float = 1f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val srcRect = Rect()
    private val dstRect = RectF()
    private var animator: ValueAnimator? = null

    init {
        // Hardware-accelerated smooth Gaussian blur on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    80f,
                    80f,
                    Shader.TileMode.DECAL
                )
            )
        }
    }

    /**
     * Binds the current song's album art to this ambient glow view using Coil.
     */
    fun bind(song: Song?, imageLoader: ImageLoader) {
        if (song == null) {
            clear()
            return
        }

        if (song.uid == currentSongUid && glowBitmap != null) return
        currentSongUid = song.uid

        val request = ImageRequest.Builder(context)
            .data(song.cover)
            .size(128, 128)
            .scale(Scale.FILL)
            .target(
                onSuccess = { image ->
                    val bmp = image.toBitmap()
                    L.d("AmbientGlowView: successfully loaded cover bitmap ${bmp.width}x${bmp.height}")
                    setGlowBitmap(bmp)
                },
                onError = {
                    L.w("AmbientGlowView: failed to load cover bitmap for $song")
                    clear()
                }
            )
            .build()

        imageLoader.enqueue(request)
    }

    private fun setGlowBitmap(newBmp: Bitmap) {
        animator?.cancel()
        oldBitmap = glowBitmap
        glowBitmap = newBmp
        transitionProgress = 0f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                transitionProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun clear() {
        animator?.cancel()
        currentSongUid = null
        glowBitmap = null
        oldBitmap = null
        transitionProgress = 1f
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = glowBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Adapt opacity based on dark or light surface theme
        val surfaceColor = context.getAttrColorCompat(com.google.android.material.R.attr.colorSurface).defaultColor
        val isDark = ColorUtils.calculateLuminance(surfaceColor) < 0.5f
        val baseAlpha = if (isDark) 0.85f else 0.40f

        layerPaint.alpha = (baseAlpha * 255).toInt()
        val saveCount = canvas.saveLayer(0f, 0f, w, h, layerPaint)

        // Find playback_pager to center glow precisely behind the album cover
        val pager = (parent as? ViewGroup)?.findViewById<View>(org.nov0caina.sounder.R.id.playback_pager)
        val cx = pager?.let { it.x + it.width / 2f } ?: (w / 2f)
        val cy = pager?.let { it.y + it.height / 2f } ?: (h * 0.38f)
        val coverSize = pager?.let { it.width.toFloat().coerceAtLeast(1f) } ?: (w - dp(48f))
        val coverRadius = coverSize / 2f

        // Expand glow generously (64dp beyond cover edges) for a prominent atmospheric aura
        val glowSpread = dp(64f)
        val glowRadius = coverRadius + glowSpread

        val glowLeft = cx - glowRadius
        val glowTop = cy - glowRadius
        val glowRight = cx + glowRadius
        val glowBottom = cy + glowRadius

        // In light theme, lift shadows so dark album art never creates dirty soot/smudges
        if (!isDark) {
            val cm = ColorMatrix(floatArrayOf(
                1.1f, 0f, 0f, 0f, 40f,
                0f, 1.1f, 0f, 0f, 40f,
                0f, 0f, 1.1f, 0f, 40f,
                0f, 0f, 0f, 1f, 0f
            ))
            bitmapPaint.colorFilter = ColorMatrixColorFilter(cm)
        } else {
            bitmapPaint.colorFilter = null
        }

        // Draw old bitmap during crossfade if present
        val old = oldBitmap
        if (old != null && transitionProgress < 1f) {
            val oldAlpha = ((1f - transitionProgress) * 255).toInt()
            bitmapPaint.alpha = oldAlpha
            srcRect.set(0, 0, old.width, old.height)
            dstRect.set(glowLeft, glowTop, glowRight, glowBottom)
            canvas.drawBitmap(old, srcRect, dstRect, bitmapPaint)
        }

        // Draw current bitmap
        val currentAlpha = (transitionProgress * 255).toInt()
        bitmapPaint.alpha = currentAlpha
        srcRect.set(0, 0, current.width, current.height)
        dstRect.set(glowLeft, glowTop, glowRight, glowBottom)
        canvas.drawBitmap(current, srcRect, dstRect, bitmapPaint)

        // Smooth radial fade mask: guarantees 100% glow intensity around the cover,
        // and perfectly continuous drop to 0% alpha at glowRadius.
        // This ensures zero rectangular borders, zero cutoff lines, and an organic aura.
        val ratioEdge = (coverRadius / glowRadius).coerceIn(0.5f, 0.85f)
        val radialMask = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.WHITE,
                Color.WHITE,
                Color.argb(210, 255, 255, 255),
                Color.argb(70, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                ratioEdge * 0.8f,
                ratioEdge + (1f - ratioEdge) * 0.35f,
                ratioEdge + (1f - ratioEdge) * 0.75f,
                1f
            ),
            Shader.TileMode.CLAMP
        )

        maskPaint.shader = radialMask
        canvas.drawRect(glowLeft, glowTop, glowRight, glowBottom, maskPaint)

        canvas.restoreToCount(saveCount)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
