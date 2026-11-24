package com.chaoseye.lyrilayer

import android.content.Context

internal object OnlineLyricsSettings {
    const val ENABLED = "lyrickit_before_ai"
    const val DEFAULT_ENABLED = true
    fun enabled(context: Context): Boolean = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(ENABLED, DEFAULT_ENABLED)
}
