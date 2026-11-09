/*
 * Copyright (c) 2026 Auxio Project
 * ArtistHeroImageView.kt is part of Auxio / Sounder.
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

package org.nov0caina.sounder.online.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * An [AppCompatImageView] for the artist hero banner that preserves crisp facial details in the center
 * while softly feathering the top and bottom perimeters into the underlying blurred background layer.
 */
class ArtistHeroImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var maskShader: LinearGradient? = null
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMask(w, h)
    }

    private fun updateMask(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        lastWidth = w
        lastHeight = h

        val density = resources.displayMetrics.density
        // Subtle edge feathering matching top and bottom transitions so the vast majority of the photo remains crisp and clear
        val topFade = 44f * density
        val bottomFade = 44f * density

        val topRatio = (topFade / h.toFloat()).coerceIn(0.1f, 0.35f)
        val bottomRatio = ((h.toFloat() - bottomFade) / h.toFloat()).coerceIn(0.55f, 0.85f)

        maskShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.WHITE,
                Color.WHITE,
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                topRatio,
                bottomRatio,
                1f
            ),
            Shader.TileMode.CLAMP
        )
        maskPaint.shader = maskShader
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }

        if (maskShader == null || lastWidth != width || lastHeight != height) {
            updateMask(width, height)
        }

        val count = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)
        maskShader?.let {
            canvas.drawRect(0f, 0f, w, h, maskPaint)
        }
        canvas.restoreToCount(count)
    }
}
