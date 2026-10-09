package com.example.andvibe

import android.view.View
import android.widget.TextView

/**
 * Blocks taps while work runs without Material's disabled fade
 * (that look was too flashy next to the busy bar).
 *
 * Keep [View.isEnabled] true so controls do not grey out. For text editors,
 * also toggle [View.isFocusableInTouchMode] — clearing focusable alone leaves
 * EditTexts untappable after a busy cycle (console input after Build Tools / gh).
 */
object BusyUi {
    fun setEnabled(view: View, enabled: Boolean) {
        view.isClickable = enabled
        view.isLongClickable = enabled
        view.isFocusable = enabled
        view.isEnabled = true
        view.alpha = 1f
        if (view is TextView && view.onCheckIsTextEditor()) {
            view.isFocusableInTouchMode = enabled
            view.isCursorVisible = enabled
            if (!enabled) view.clearFocus()
        }
    }
}
