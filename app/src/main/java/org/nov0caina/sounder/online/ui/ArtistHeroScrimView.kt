/*
 * Copyright (c) 2026 Auxio Project
 * ArtistHeroScrimView.kt is part of Auxio / Sounder.
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
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import org.nov0caina.sounder.util.getAttrColorCompat

/**
 * A scrim overlay that smoothly melts the top of the artist hero into the toolbar/status bar
 * and the bottom into the page surface background ([com.google.android.material.R.attr.colorSurface]),
 * eliminating any harsh cutoff lines or border seams.
 */
class ArtistHeroScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SCRIM_HEIGHT_DP = 64f
    }

    private val topScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var topShader: LinearGradient? = null
    private var bottomShader: LinearGradient? = null
    private var lastHeight = 0
    private var lastWidth = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScrims(w, h)
    }

    private fun updateScrims(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        lastWidth = w
        lastHeight = h

        val surfaceColor = context.getAttrColorCompat(com.google.android.material.R.attr.colorSurface).defaultColor

        val density = resources.displayMetrics.density
        val topScrimHeight = SCRIM_HEIGHT_DP * density
        val bottomScrimHeight = SCRIM_HEIGHT_DP * density

        val surfaceQuarter = ColorUtils.setAlphaComponent(surfaceColor, 90)
        val surfaceHalf = ColorUtils.setAlphaComponent(surfaceColor, 180)

        // Top scrim: Seamless, gentle melt from the toolbar's surfaceColor down into the hero image (mirroring the bottom scrim)
        topShader = LinearGradient(
            0f, 0f, 0f, topScrimHeight,
            intArrayOf(
                surfaceColor,
                surfaceHalf,
                surfaceQuarter,
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                0.25f,
                0.60f,
                1f
            ),
            Shader.TileMode.CLAMP
        )
        topScrimPaint.shader = topShader

        // Bottom scrim: Subtle, gentle melt into the page's colorSurface right at the bottom edge
        val bottomStart = (h.toFloat() - bottomScrimHeight).coerceAtLeast(0f)

        bottomShader = LinearGradient(
            0f, bottomStart, 0f, h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                surfaceQuarter,
                surfaceHalf,
                surfaceColor
            ),
            floatArrayOf(
                0f,
                0.40f,
                0.75f,
                1f
            ),
            Shader.TileMode.CLAMP
        )
        bottomScrimPaint.shader = bottomShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (topShader == null || lastWidth != width || lastHeight != height) {
            updateScrims(width, height)
        }

        val density = resources.displayMetrics.density
        val topScrimHeight = SCRIM_HEIGHT_DP * density
        val bottomScrimHeight = SCRIM_HEIGHT_DP * density
        val bottomStart = (h - bottomScrimHeight).coerceAtLeast(0f)

        // Draw top scrim
        topShader?.let {
            canvas.drawRect(0f, 0f, w, topScrimHeight, topScrimPaint)
        }

        // Draw bottom scrim
        bottomShader?.let {
            canvas.drawRect(0f, bottomStart, w, h, bottomScrimPaint)
        }
    }
}
