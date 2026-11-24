package com.chaoseye.lyrilayer

internal object OverlayPosition {
    const val KEY = "overlay_position_percent"
    const val DEFAULT = 85
    fun normalize(percent:Int)=percent.coerceIn(0,100)
    // The whole overlay, not just its top edge, stays within the available area.
    fun topOffset(percent:Int,availableHeight:Int,overlayHeight:Int,margin:Int):Int {
        val remaining=(availableHeight.coerceAtLeast(0)-overlayHeight.coerceAtLeast(0)).coerceAtLeast(0)
        val safeMargin=margin.coerceIn(0,remaining/2)
        return safeMargin+((remaining-2L*safeMargin)*normalize(percent)/100L).toInt()
    }
}
