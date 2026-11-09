/*
 * Copyright (c) 2026 Auxio Project / Sounder
 * SwipeToQueueHelper.kt is part of Sounder.
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

package org.nov0caina.sounder.list.recycler

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.R
import org.nov0caina.sounder.util.getAttrColorCompat

/**
 * An [ItemTouchHelper.SimpleCallback] implementing modern Spotify-style swipe-to-queue (swipe right).
 * Reveals a background that dynamically adapts to the user's currently selected theme palette
 * (`?attr/colorPrimary` and `?attr/colorOnPrimary`), triggers haptic feedback on threshold crossing,
 * and smoothly springs back to rest (`translationX = 0`) upon release.
 */
class SwipeToQueueHelper(
    private val canSwipe: (RecyclerView.ViewHolder) -> Boolean = { true },
    private val onEnqueue: (RecyclerView.ViewHolder) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var queueIcon: Drawable? = null
    private var lastContext: Context? = null

    // Track active swipe state
    private var activeViewHolder: RecyclerView.ViewHolder? = null
    private var isSwiping = false
    private var reachedThreshold = false
    private var hasTriggeredHaptic = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        return if (canSwipe(viewHolder)) {
            makeMovementFlags(0, ItemTouchHelper.RIGHT)
        } else {
            0
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        // Return Float.MAX_VALUE so ItemTouchHelper never treats it as a permanent dismissal
        // and always runs its recover animation back to 0 translation when the user releases.
        return Float.MAX_VALUE
    }

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
        // Prevent fast flings from flinging the item off-screen.
        return Float.MAX_VALUE
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Fallback for direct invocations or unit tests
        onEnqueue(viewHolder)
        viewHolder.itemView.translationX = 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val itemView = viewHolder.itemView
        val width = itemView.width.toFloat()
        val threshold = width * THRESHOLD_RATIO

        // Apply elastic resistance if dragged beyond MAX_DRAG_RATIO
        val effectiveDx = if (width > 0f && dX > width * MAX_DRAG_RATIO) {
            val base = width * MAX_DRAG_RATIO
            base + (dX - base) * DAMPING_FACTOR
        } else {
            dX
        }

        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0f) {
            val icon = getTintedQueueIcon(recyclerView.context)
            drawBackground(
                canvas = c,
                itemView = itemView,
                dX = effectiveDx,
                threshold = threshold,
                icon = icon,
                paint = bgPaint,
                context = recyclerView.context,
            )

            if (isCurrentlyActive) {
                activeViewHolder = viewHolder
                isSwiping = true
                if (effectiveDx >= threshold) {
                    if (!hasTriggeredHaptic) {
                        itemView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        hasTriggeredHaptic = true
                    }
                    reachedThreshold = true
                } else {
                    hasTriggeredHaptic = false
                    reachedThreshold = false
                }
            } else if (isSwiping && activeViewHolder == viewHolder) {
                // User just lifted their finger
                isSwiping = false
                if (reachedThreshold) {
                    reachedThreshold = false
                    hasTriggeredHaptic = false
                    onEnqueue(viewHolder)
                }
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, effectiveDx, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (activeViewHolder == viewHolder) {
            if (isSwiping && reachedThreshold) {
                onEnqueue(viewHolder)
            }
            activeViewHolder = null
            isSwiping = false
            reachedThreshold = false
            hasTriggeredHaptic = false
        }
        viewHolder.itemView.translationX = 0f
    }

    private fun getTintedQueueIcon(context: Context): Drawable? {
        if (queueIcon == null || lastContext != context) {
            lastContext = context
            val raw = AppCompatResources.getDrawable(context, R.drawable.ic_queue_add_24)?.mutate()
            if (raw != null) {
                queueIcon = DrawableCompat.wrap(raw)
            }
        }
        val onThemeColor = context.getAttrColorCompat(com.google.android.material.R.attr.colorOnPrimary).defaultColor
        queueIcon?.let { DrawableCompat.setTint(it, onThemeColor) }
        return queueIcon
    }

    companion object {
        const val SWIPE_QUEUE_COLOR = 0xFF5EEAD4.toInt()
        const val THRESHOLD_RATIO = 0.35f
        const val MAX_DRAG_RATIO = 0.50f
        const val DAMPING_FACTOR = 0.25f

        /**
         * Calculate whether a given swipe distance [dX] meets or exceeds the activation threshold
         * for an item of width [itemWidth].
         */
        fun isThresholdReached(dX: Float, itemWidth: Float): Boolean {
            if (itemWidth <= 0f) return false
            return dX >= itemWidth * THRESHOLD_RATIO
        }

        /**
         * Calculate the clamped / resisted translation distance for a given raw [dX] and [itemWidth].
         */
        fun calculateEffectiveDx(dX: Float, itemWidth: Float): Float {
            if (itemWidth <= 0f) return dX
            val maxDrag = itemWidth * MAX_DRAG_RATIO
            return if (dX > maxDrag) {
                maxDrag + (dX - maxDrag) * DAMPING_FACTOR
            } else {
                dX
            }
        }

        /**
         * Reusable helper function to draw the theme-aware queue background and scaled icon on a Canvas.
         * Dynamically resolves the active palette's `?attr/colorPrimary` and `?attr/colorOnPrimary`.
         */
        fun drawBackground(
            canvas: Canvas,
            itemView: View,
            dX: Float,
            threshold: Float,
            icon: Drawable?,
            paint: Paint,
            context: Context,
        ) {
            if (dX <= 0f) return
            val left = itemView.left.toFloat()
            val top = itemView.top.toFloat()
            val right = left + dX
            val bottom = itemView.bottom.toFloat()

            // Adapt dynamically to the user's selected theme and palette
            val themeColor = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
            val onThemeColor = context.getAttrColorCompat(com.google.android.material.R.attr.colorOnPrimary).defaultColor
            paint.color = themeColor

            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.drawRect(left, top, right, bottom, paint)

            if (icon != null && dX > 10f) {
                DrawableCompat.setTint(icon, onThemeColor)
                val density = context.resources.displayMetrics.density
                val iconSize = (28 * density).toInt()
                val margin = (20 * density).toInt()

                val progress = if (threshold > 0f) (dX / threshold).coerceIn(0f, 1f) else 1f
                val scale = 0.5f + 0.5f * progress
                val alpha = (255 * (0.3f + 0.7f * progress)).toInt().coerceIn(0, 255)

                val centerY = top + (itemView.height) / 2f
                val iconTargetCenterX = left + margin + iconSize / 2f
                val availableCenterX = left + dX / 2f
                val centerX = if (availableCenterX < iconTargetCenterX) availableCenterX else iconTargetCenterX

                val iconLeft = (centerX - iconSize / 2f).toInt()
                val iconTop = (centerY - iconSize / 2f).toInt()
                val iconRight = iconLeft + iconSize
                val iconBottom = iconTop + iconSize

                icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                icon.alpha = alpha

                canvas.save()
                canvas.scale(scale, scale, centerX, centerY)
                icon.draw(canvas)
                canvas.restore()
            }

            canvas.restore()
        }
    }
}
