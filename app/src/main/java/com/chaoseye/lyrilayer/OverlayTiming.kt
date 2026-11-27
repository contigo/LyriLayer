package com.chaoseye.lyrilayer

import kotlin.math.ceil

internal object OverlayTiming {
    const val AUTO_SEARCH_KEY="overlay_auto_online_search"
    const val SAFETY_MS=5000L
    const val FALLBACK_MS=500L

    // A slow safety check covers missed media callbacks, display changes and static lyrics.
    // Timed lyrics wake at the exact next boundary, adjusted for offset and playback speed.
    fun nextDelay(lines:List<Line>,position:Long,offset:Long,speed:Float,hasClock:Boolean,duration:Long):Long {
        if(lines.isEmpty())return SAFETY_MS
        if(!hasClock||!speed.isFinite()||speed<=0f)return FALLBACK_MS
        val adjusted=position.toDouble()-offset.toDouble()
        val next=lines.getOrNull(Lrc.index(lines,adjusted.toLong())+1)?:return SAFETY_MS
        val target=next.ms.toDouble()+offset.toDouble()
        if(duration>0&&target>duration)return SAFETY_MS
        return ceil((target-position.toDouble())/speed.toDouble()).toLong().coerceIn(1L,SAFETY_MS)
    }
}
