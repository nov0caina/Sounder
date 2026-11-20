/*
 * Copyright (c) 2026 Sounder Project
 * GridCompositionFetcher.kt is part of Sounder.
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

package org.nov0caina.sounder.image.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer as CoilKeyer
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse
import javax.inject.Inject
import kotlin.math.min
import org.nov0caina.sounder.online.model.OnlineCover
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.covers.Cover

/**
 * Pure normalized geometry rectangle for a collage cell inside a square canvas.
 */
data class GridCellBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class GridCoverComposition(
    val orderedCovers: List<Cover>,
    val gapPx: Float,
    @ColorInt val backgroundColor: Int,
)

/**
 * Extracts up to [limit] unique [Cover]s strictly in the given [songs] order without
 * duplicating identical album or artwork entries.
 */
fun extractOrderedDistinctCovers(songs: List<Song>, limit: Int = 4): List<Cover> {
    if (limit <= 0 || songs.isEmpty()) return emptyList()
    val result = ArrayList<Cover>(min(limit, songs.size))
    val seenKeys = HashSet<String>()
    for (song in songs) {
        val cover = song.cover ?: continue
        val dedupKey = (cover as? OnlineCover)?.artworkUrl?.takeIf { it.isNotBlank() } ?: cover.id
        if (seenKeys.add(dedupKey)) {
            result.add(cover)
            if (result.size == limit) break
        }
    }
    return result
}

/**
 * Computes adaptive cell bounds for 1, 2, 3, or 4+ distinct covers inside a [size]x[size] square
 * separated by [gap].
 */
fun computeGridCellBounds(cellCount: Int, size: Float, gap: Float): List<GridCellBounds> {
    if (cellCount <= 0 || size <= 0f) return emptyList()
    val safeGap = gap.coerceIn(0f, size * 0.1f)
    val halfLeftEnd = (size - safeGap) / 2f
    val halfRightStart = (size + safeGap) / 2f
    return when (cellCount) {
        1 -> listOf(GridCellBounds(0f, 0f, size, size))
        2 -> listOf(
            GridCellBounds(0f, 0f, halfLeftEnd, size),
            GridCellBounds(halfRightStart, 0f, size, size),
        )
        3 -> listOf(
            GridCellBounds(0f, 0f, halfLeftEnd, size),
            GridCellBounds(halfRightStart, 0f, size, halfLeftEnd),
            GridCellBounds(halfRightStart, halfRightStart, size, size),
        )
        else -> listOf(
            GridCellBounds(0f, 0f, halfLeftEnd, halfLeftEnd),
            GridCellBounds(halfRightStart, 0f, size, halfLeftEnd),
            GridCellBounds(0f, halfRightStart, halfLeftEnd, size),
            GridCellBounds(halfRightStart, halfRightStart, size, size),
        )
    }
}

/**
 * A [Fetcher] that composes 1–4 ordered [Cover] bitmaps into an adaptive grid collage separated
 * by a subtle gap in exact track order.
 */
class GridCompositionFetcher
private constructor(
    private val context: Context,
    private val data: GridCoverComposition,
    private val size: Size,
) : Fetcher {

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override suspend fun fetch(): FetchResult? {
        if (data.orderedCovers.isEmpty()) return null

        val bitmaps = ArrayList<Bitmap>(4)
        for (cover in data.orderedCovers) {
            val stream = cover.open() ?: continue
            val bitmap = try {
                BitmapFactory.decodeStream(stream)
            } finally {
                stream.close()
            }
            if (bitmap != null) {
                bitmaps.add(bitmap)
                if (bitmaps.size == 4) break
            }
        }

        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) {
            return ImageFetchResult(
                image = bitmaps[0].toDrawable(context.resources).asImage(),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        }

        val squareSize =
            min(size.width.pxOrElse { 512 }, size.height.pxOrElse { 512 }).coerceAtLeast(1)
        val composed = composeGrid(bitmaps, squareSize)
        return ImageFetchResult(
            image = composed.toDrawable(context.resources).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun composeGrid(bitmaps: List<Bitmap>, size: Int): Bitmap {
        val sizef = size.toFloat()
        val result = createBitmap(size, size)
        val canvas = Canvas(result)
        canvas.drawColor(data.backgroundColor)

        val cells = computeGridCellBounds(bitmaps.size, sizef, data.gapPx)
        cells.forEachIndexed { index, bounds ->
            val dest = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
            drawCenterCropped(canvas, bitmaps[index], dest)
        }
        return result
    }

    private fun drawCenterCropped(canvas: Canvas, bitmap: Bitmap, dest: RectF) {
        val destWidth = dest.width()
        val destHeight = dest.height()
        if (destWidth <= 0f || destHeight <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return

        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destRatio = destWidth / destHeight
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)

        if (bitmapRatio > destRatio) {
            val newWidth = (bitmap.height * destRatio).toInt().coerceAtLeast(1)
            val xOffset = ((bitmap.width - newWidth) / 2).coerceAtLeast(0)
            srcRect.left = xOffset
            srcRect.right = (xOffset + newWidth).coerceAtMost(bitmap.width)
        } else {
            val newHeight = (bitmap.width / destRatio).toInt().coerceAtLeast(1)
            val yOffset = ((bitmap.height - newHeight) / 2).coerceAtLeast(0)
            srcRect.top = yOffset
            srcRect.bottom = (yOffset + newHeight).coerceAtMost(bitmap.height)
        }

        canvas.drawBitmap(bitmap, srcRect, dest, drawPaint)
    }

    class Factory @Inject constructor() : Fetcher.Factory<GridCoverComposition> {
        override fun create(
            data: GridCoverComposition,
            options: Options,
            imageLoader: ImageLoader,
        ) = GridCompositionFetcher(options.context, data, options.size)
    }

    class Keyer @Inject constructor() : CoilKeyer<GridCoverComposition> {
        override fun key(data: GridCoverComposition, options: Options): String =
            buildCacheKey(data, options.size)
    }

    companion object {
        internal fun buildCacheKey(data: GridCoverComposition, size: Size): String {
            val coverIds = data.orderedCovers.joinToString(",") { it.id }
            return "g:$coverIds.${size.width}.${size.height}.${data.gapPx}.${data.backgroundColor}"
        }
    }
}
