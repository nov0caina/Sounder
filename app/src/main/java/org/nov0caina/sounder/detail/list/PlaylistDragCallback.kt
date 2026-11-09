/*
 * Copyright (c) 2023 Auxio Project
 * PlaylistDragCallback.kt is part of Auxio.
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

package org.nov0caina.sounder.detail.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.R
import org.nov0caina.sounder.detail.DetailViewModel
import org.nov0caina.sounder.list.recycler.MaterialDragCallback
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper
import org.nov0caina.sounder.playback.PlaybackViewModel
import org.nov0caina.sounder.util.getAttrColorCompat
import org.nov0caina.sounder.util.showToast
import org.oxycblt.musikr.Song

/**
 * A [MaterialDragCallback] extension for playlist-specific item editing and playback queue gestures.
 * When editing, enables vertical drag reordering and leftward swipe-to-delete.
 * When in normal view, enables modern swipe-to-queue (swipe right).
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class PlaylistDragCallback(
    private val detailModel: DetailViewModel,
    private val playbackModel: PlaybackViewModel,
    private val getSongAt: (Int) -> Song?,
) : MaterialDragCallback() {

    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SwipeToQueueHelper.SWIPE_QUEUE_COLOR
    }
    private var queueIcon: Drawable? = null
    private var lastContext: Context? = null

    private var activeViewHolder: RecyclerView.ViewHolder? = null
    private var isSwipingRight = false
    private var reachedThreshold = false
    private var hasTriggeredHaptic = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val isEditing = detailModel.editedPlaylist.value != null
        return if (isEditing) {
            if (viewHolder is ViewHolder && viewHolder.enabled) {
                makeFlag(
                    ItemTouchHelper.ACTION_STATE_DRAG,
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ) or makeFlag(ItemTouchHelper.ACTION_STATE_SWIPE, ItemTouchHelper.START)
            } else {
                0
            }
        } else {
            val song = getSongAt(viewHolder.bindingAdapterPosition)
            if (song != null) {
                makeMovementFlags(0, ItemTouchHelper.RIGHT)
            } else {
                0
            }
        }
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        val isEditing = detailModel.editedPlaylist.value != null
        return if (isEditing) super.getSwipeThreshold(viewHolder) else Float.MAX_VALUE
    }

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
        val isEditing = detailModel.editedPlaylist.value != null
        return if (isEditing) super.getSwipeEscapeVelocity(defaultValue) else Float.MAX_VALUE
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val isEditing = detailModel.editedPlaylist.value != null
        if (!isEditing && actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX > 0f) {
            val itemView = viewHolder.itemView
            val width = itemView.width.toFloat()
            val threshold = width * SwipeToQueueHelper.THRESHOLD_RATIO
            val effectiveDx = SwipeToQueueHelper.calculateEffectiveDx(dX, width)
            val icon = getTintedQueueIcon(recyclerView.context)

            SwipeToQueueHelper.drawBackground(
                canvas = c,
                itemView = itemView,
                dX = effectiveDx,
                threshold = threshold,
                icon = icon,
                paint = greenPaint,
                context = recyclerView.context,
            )

            if (isCurrentlyActive) {
                activeViewHolder = viewHolder
                isSwipingRight = true
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
            } else if (isSwipingRight && activeViewHolder == viewHolder) {
                isSwipingRight = false
                if (reachedThreshold) {
                    reachedThreshold = false
                    hasTriggeredHaptic = false
                    val song = getSongAt(viewHolder.bindingAdapterPosition)
                    if (song != null) {
                        playbackModel.addToQueue(song)
                        recyclerView.context.showToast(R.string.lng_queue_added)
                    }
                }
            }

            if (viewHolder is ViewHolder) {
                viewHolder.body.translationX = effectiveDx
                viewHolder.root.translationY = dY
            } else {
                super.onChildDraw(c, recyclerView, viewHolder, effectiveDx, dY, actionState, isCurrentlyActive)
            }
            return
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (activeViewHolder == viewHolder) {
            if (isSwipingRight && reachedThreshold) {
                val song = getSongAt(viewHolder.bindingAdapterPosition)
                if (song != null) {
                    playbackModel.addToQueue(song)
                    recyclerView.context.showToast(R.string.lng_queue_added)
                }
            }
            activeViewHolder = null
            isSwipingRight = false
            reachedThreshold = false
            hasTriggeredHaptic = false
        }
        if (viewHolder is ViewHolder) {
            viewHolder.body.translationX = 0f
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ) =
        detailModel.movePlaylistSongs(
            viewHolder.bindingAdapterPosition,
            target.bindingAdapterPosition,
        )

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        if (direction == ItemTouchHelper.RIGHT) {
            val song = getSongAt(viewHolder.bindingAdapterPosition)
            if (song != null) {
                playbackModel.addToQueue(song)
                viewHolder.itemView.context.showToast(R.string.lng_queue_added)
            }
            if (viewHolder is ViewHolder) {
                viewHolder.body.translationX = 0f
            }
        } else {
            detailModel.removePlaylistSong(viewHolder.bindingAdapterPosition)
        }
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
}
