package com.example.andvibe

import android.view.View

/**
 * Blocks taps while work runs without Material's disabled fade
 * (that look was too flashy next to the busy bar).
 */
object BusyUi {
    fun setEnabled(view: View, enabled: Boolean) {
        view.isClickable = enabled
        view.isLongClickable = enabled
        view.isFocusable = enabled
        view.isEnabled = true
        view.alpha = 1f
    }
}
