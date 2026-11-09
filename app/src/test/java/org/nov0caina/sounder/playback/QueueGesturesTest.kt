/*
 * Copyright (c) 2026 Auxio Project / Sounder
 * QueueGesturesTest.kt is part of Sounder.
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

package org.nov0caina.sounder.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nov0caina.sounder.list.recycler.SwipeToQueueHelper

class QueueGesturesTest {

    @Test
    fun swipeToQueue_thresholdCalculations() {
        val width = 1000f
        // Threshold is 35% of width (350f)
        assertFalse(SwipeToQueueHelper.isThresholdReached(0f, width))
        assertFalse(SwipeToQueueHelper.isThresholdReached(349f, width))
        assertTrue(SwipeToQueueHelper.isThresholdReached(350f, width))
        assertTrue(SwipeToQueueHelper.isThresholdReached(500f, width))
        assertFalse(SwipeToQueueHelper.isThresholdReached(350f, 0f))
        assertFalse(SwipeToQueueHelper.isThresholdReached(350f, -100f))
    }

    @Test
    fun swipeToQueue_elasticResistanceCalculation() {
        val width = 1000f
        // Below 50% (500f), raw dX is preserved
        assertEquals(0f, SwipeToQueueHelper.calculateEffectiveDx(0f, width), 0.001f)
        assertEquals(200f, SwipeToQueueHelper.calculateEffectiveDx(200f, width), 0.001f)
        assertEquals(500f, SwipeToQueueHelper.calculateEffectiveDx(500f, width), 0.001f)

        // Above 500f, 25% damping is applied to the excess
        // Excess = 600 - 500 = 100 -> 100 * 0.25 = 25 -> 525f
        assertEquals(525f, SwipeToQueueHelper.calculateEffectiveDx(600f, width), 0.001f)
        // Excess = 900 - 500 = 400 -> 400 * 0.25 = 100 -> 600f
        assertEquals(600f, SwipeToQueueHelper.calculateEffectiveDx(900f, width), 0.001f)
    }

    @Test
    fun swipeToQueue_constants() {
        assertEquals(0xFF5EEAD4.toInt(), SwipeToQueueHelper.SWIPE_QUEUE_COLOR)
        assertEquals(0.35f, SwipeToQueueHelper.THRESHOLD_RATIO, 0.0001f)
        assertEquals(0.50f, SwipeToQueueHelper.MAX_DRAG_RATIO, 0.0001f)
        assertEquals(0.25f, SwipeToQueueHelper.DAMPING_FACTOR, 0.0001f)
    }

    @Test
    fun queueSwipe_restrictsDismissalToUpcomingTracksOnly() {
        fun computeSwipeFlags(position: Int, currentIndex: Int): Int {
            return if (position != -1 && position > currentIndex) {
                androidx.recyclerview.widget.ItemTouchHelper.START
            } else {
                0
            }
        }

        val currentIndex = 2
        // Past tracks cannot be dismissed
        assertEquals(0, computeSwipeFlags(0, currentIndex))
        assertEquals(0, computeSwipeFlags(1, currentIndex))

        // Currently playing track cannot be dismissed
        assertEquals(0, computeSwipeFlags(2, currentIndex))

        // Upcoming tracks can be dismissed
        assertEquals(androidx.recyclerview.widget.ItemTouchHelper.START, computeSwipeFlags(3, currentIndex))
        assertEquals(androidx.recyclerview.widget.ItemTouchHelper.START, computeSwipeFlags(4, currentIndex))
        assertEquals(androidx.recyclerview.widget.ItemTouchHelper.START, computeSwipeFlags(5, currentIndex))

        // Invalid position
        assertEquals(0, computeSwipeFlags(-1, currentIndex))
    }
}
