package com.example.andvibe

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.bottomnavigation.BottomNavigationView

/** BottomNavigationView capped at 6 by Material; we need Board…Git (7). */
class MaxBottomNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle,
) : BottomNavigationView(context, attrs, defStyleAttr) {
    override fun getMaxItemCount(): Int = 8
}
