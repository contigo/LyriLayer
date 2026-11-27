package com.chaoseye.lyrilayer

internal object OverlayTransparency {
    const val KEY = "overlay_transparency_percent"
    // Keep WindowManager alpha <= 0.8 for Android 12+ touch pass-through.
    const val MIN = 20
    const val MAX = 90
    const val DEFAULT = 22
    fun normalize(percent:Int)=percent.coerceIn(MIN,MAX)
    fun windowAlpha(percent:Int)=(100-normalize(percent))/100f
}
