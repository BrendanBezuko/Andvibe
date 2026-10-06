package com.example.andvibe

import android.view.View

/** Dim and disable controls while long-running work is in progress. */
object BusyUi {
    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }
}
