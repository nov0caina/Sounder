/*
 * Copyright (c) 2025 Auxio Project
 * EatInsetsFrameLayout.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.AttrRes

/**
 * A [FrameLayout] that works around the pre-Android 10 behavior of propagating mutated insets to
 * sibling views. Wrap this around views that to isolate mutated window insets.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class EatInsetsFrameLayout
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    FrameLayout(context, attrs, defStyleAttr) {
    var onContentChangedListener: (() -> Unit)? = null

    init {
        clipToPadding = false
    }

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        super.dispatchApplyWindowInsets(insets)
        return insets
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        onContentChangedListener?.invoke()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun invalidateChildInParent(location: IntArray?, dirty: Rect?): ViewParent? {
        onContentChangedListener?.invoke()
        return super.invalidateChildInParent(location, dirty)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onContentChangedListener?.invoke()
    }
}
