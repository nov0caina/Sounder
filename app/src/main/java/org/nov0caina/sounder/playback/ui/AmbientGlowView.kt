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
import android.graphics.Matrix
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
import androidx.annotation.IdRes
import androidx.core.graphics.ColorUtils
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.toBitmap
import kotlin.math.max
import kotlin.math.min
import org.nov0caina.sounder.R
import org.nov0caina.sounder.util.getAttrColorCompat
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A custom view that renders a rich, vibrant, seamless ambient artwork glow behind album covers,
 * artist portraits, and media thumbnails.
 *
 * It uses a hardware-accelerated RenderEffect blur and radial gradient falloff mask to guarantee that:
 * 1. The glow radiates richly, prominently, and vibrantly around the artwork.
 * 2. The glow fades smoothly and organically to 0% opacity with zero abrupt rectangular cutoffs.
 * 3. In light themes, shadows are gracefully lifted so dark artwork never creates dirty smudges.
 * 4. The glow sits in the background plane, enhancing the aesthetic without obscuring controls.
 */
class AmbientGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @IdRes
    var anchorViewId: Int = NO_ID
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var glowSpreadPx: Float = -1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var glowBlurRadius: Float = DEFAULT_BLUR_RADIUS
        set(value) {
            if (field != value) {
                field = value
                applyBlurEffect()
                invalidate()
            }
        }

    var animateTransitions: Boolean = true

    var glowShape: Int = SHAPE_CIRCLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var currentKey: Any? = null
    private var glowBitmap: Bitmap? = null
    private var oldBitmap: Bitmap? = null
    private var transitionProgress: Float = 1f
    private var activeDisposable: Disposable? = null

    var showFallbackAura: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightColorFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                1.1f, 0f, 0f, 0f, 40f,
                0f, 1.1f, 0f, 0f, 40f,
                0f, 0f, 1.1f, 0f, 40f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val maskMatrix = Matrix()
    private var animator: ValueAnimator? = null

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(attrs, R.styleable.AmbientGlowView)
            anchorViewId = ta.getResourceId(R.styleable.AmbientGlowView_glowAnchorView, NO_ID)
            glowSpreadPx = ta.getDimension(R.styleable.AmbientGlowView_glowSpread, -1f)
            glowBlurRadius = ta.getFloat(R.styleable.AmbientGlowView_glowBlurRadius, DEFAULT_BLUR_RADIUS)
            animateTransitions = ta.getBoolean(R.styleable.AmbientGlowView_glowAnimateTransitions, true)
            glowShape = ta.getInt(R.styleable.AmbientGlowView_glowShape, SHAPE_CIRCLE)
            ta.recycle()
        }
        applyBlurEffect()
    }

    private fun applyBlurEffect() {
        // Hardware-accelerated smooth Gaussian blur on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (glowBlurRadius > 0f) {
                setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        glowBlurRadius,
                        glowBlurRadius,
                        Shader.TileMode.DECAL
                    )
                )
            } else {
                setRenderEffect(null)
            }
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
        bindData(song.cover, song.uid, imageLoader)
    }

    /**
     * Binds arbitrary image data (such as a Cover, URL, Uri, File, or CoverComposition)
     * to this ambient glow view.
     */
    fun bindData(
        data: Any?,
        key: Any? = data,
        imageLoader: ImageLoader = SingletonImageLoader.get(context),
    ) {
        if (data == null) {
            clear()
            return
        }

        if (key != null && key == currentKey && glowBitmap != null) return

        activeDisposable?.dispose()
        activeDisposable = null
        animator?.cancel()
        currentKey = key

        if (!animateTransitions && glowBitmap != null) {
            // Avoid showing a previous recycled item's glow while the new bitmap loads asynchronously
            glowBitmap = null
            oldBitmap = null
            transitionProgress = 1f
            invalidate()
        }

        val decodeSize = if (animateTransitions) 128 else 64
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(decodeSize, decodeSize)
            .scale(Scale.FILL)
            .target(
                onSuccess = { image ->
                    if (currentKey != key) return@target
                    val bmp = image.toBitmap()
                    L.d("AmbientGlowView: successfully loaded cover bitmap ${bmp.width}x${bmp.height}")
                    setGlowBitmap(bmp)
                },
                onError = {
                    if (currentKey != key) return@target
                    L.w("AmbientGlowView: failed to load cover bitmap for $data")
                    clear()
                }
            )
            .build()

        activeDisposable = imageLoader.enqueue(request)
    }

    fun clearGlow() {
        clear()
    }

    private fun setGlowBitmap(newBmp: Bitmap) {
        animator?.cancel()
        if (!animateTransitions) {
            oldBitmap = null
            glowBitmap = newBmp
            transitionProgress = 1f
            invalidate()
            return
        }

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
        activeDisposable?.dispose()
        activeDisposable = null
        animator?.cancel()
        currentKey = null
        glowBitmap = null
        oldBitmap = null
        transitionProgress = 1f
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun resolveAnchor(parentGroup: ViewGroup?): View? {
        if (parentGroup == null) return null
        if (anchorViewId != NO_ID) {
            return parentGroup.findViewById(anchorViewId)
        }
        return parentGroup.findViewById(R.id.playback_pager)
            ?: parentGroup.findViewById(R.id.playlist_cover_card)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val parentGroup = parent as? ViewGroup
        val anchor = resolveAnchor(parentGroup)
        var cx = w / 2f
        var cy = h * 0.38f
        var coverW = w - dp(48f)
        var coverH = coverW

        if (anchor != null) {
            if (anchor.width <= 0 || anchor.height <= 0) return
            var offsetX = anchor.x
            var offsetY = anchor.y
            var p = anchor.parent
            while (p is View && p !== parentGroup) {
                offsetX += p.x
                offsetY += p.y
                p = p.parent
            }
            // Adjust for this view's own offset within parentGroup
            offsetX -= x
            offsetY -= y
            cx = offsetX + anchor.width / 2f
            cy = offsetY + anchor.height / 2f
            coverW = anchor.width.toFloat().coerceAtLeast(1f)
            coverH = anchor.height.toFloat().coerceAtLeast(1f)
        }

        val isDialogAnchor = anchor?.id == R.id.playlist_cover_card
        val spread = if (glowSpreadPx >= 0f) {
            glowSpreadPx
        } else {
            dp(if (isDialogAnchor) 52f else 64f)
        }

        val isElliptical = glowShape == SHAPE_RECT && kotlin.math.abs(coverW - coverH) > 1f
        val coverRx = if (isElliptical) coverW / 2f else max(coverW, coverH) / 2f
        val coverRy = if (isElliptical) coverH / 2f else coverRx
        val glowRx = coverRx + spread
        val glowRy = coverRy + spread
        if (glowRx <= 0f || glowRy <= 0f) return

        val glowLeft = cx - glowRx
        val glowTop = cy - glowRy
        val glowRight = cx + glowRx
        val glowBottom = cy + glowRy

        val current = glowBitmap
        if (current == null) {
            if (!showFallbackAura) return
            val primaryColor = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
            val fallbackShader = RadialGradient(
                cx,
                cy,
                glowRx,
                intArrayOf(
                    ColorUtils.setAlphaComponent(primaryColor, 95),
                    ColorUtils.setAlphaComponent(primaryColor, 45),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            if (isElliptical) {
                maskMatrix.setScale(1f, glowRy / glowRx, cx, cy)
                fallbackShader.setLocalMatrix(maskMatrix)
            }
            layerPaint.shader = fallbackShader
            layerPaint.alpha = 255
            canvas.drawRect(glowLeft, glowTop, glowRight, glowBottom, layerPaint)
            layerPaint.shader = null
            return
        }

        // Adapt opacity based on dark or light surface theme
        val surfaceColor = context.getAttrColorCompat(com.google.android.material.R.attr.colorSurface).defaultColor
        val isDark = ColorUtils.calculateLuminance(surfaceColor) < 0.5f
        val baseAlpha = if (isDark) 0.85f else 0.40f

        layerPaint.alpha = (baseAlpha * 255).toInt()
        val saveCount = canvas.saveLayer(
            min(0f, glowLeft),
            min(0f, glowTop),
            max(w, glowRight),
            max(h, glowBottom),
            layerPaint
        )

        // In light theme, lift shadows so dark album art never creates dirty soot/smudges
        bitmapPaint.colorFilter = if (!isDark) lightColorFilter else null

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
        val minCoverR = min(coverRx, coverRy)
        val minGlowR = min(glowRx, glowRy)
        val ratioEdge = (minCoverR / minGlowR).coerceIn(0.45f, 0.85f)
        val radialMask = RadialGradient(
            cx, cy, glowRx,
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
        if (isElliptical) {
            maskMatrix.setScale(1f, glowRy / glowRx, cx, cy)
            radialMask.setLocalMatrix(maskMatrix)
        }

        maskPaint.shader = radialMask
        canvas.drawRect(glowLeft, glowTop, glowRight, glowBottom, maskPaint)

        canvas.restoreToCount(saveCount)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    companion object {
        const val SHAPE_CIRCLE = 0
        const val SHAPE_RECT = 1
        private const val DEFAULT_BLUR_RADIUS = 80f
    }
}
