package com.chaoseye.lyrickit

import java.util.Locale
import kotlin.math.roundToLong

object LrcParser {
    private val stamp = Regex("\\[(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]")
    fun parse(lrc: String): List<LyricLine> {
        val offset = Regex("(?im)^\\[offset:([+-]?\\d+)\\]\\s*$").find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return lrc.lineSequence().flatMap { row ->
            val matches = stamp.findAll(row).toList()
            val text = matches.lastOrNull()?.let { row.substring(it.range.last + 1).trim() }.orEmpty()
            if (text.isBlank() || !row.startsWith("[")) emptySequence() else matches.asSequence().map { m ->
                val ms = m.groupValues[1].toLong()*60000 + m.groupValues[2].toLong()*1000 + m.groupValues[3].padEnd(3,'0').toLong()
                LyricLine((ms.toDouble()+offset).coerceIn(0.0,Long.MAX_VALUE.toDouble()).toLong(), text=text)
            }
        }.toList().sortedBy { it.startTimeMs }.distinct()
    }
    fun fromBugs(value: String): List<LyricLine> = value.split('＃').mapNotNull { entry ->
        val parts = entry.split('|', limit=2)
        if(parts.size != 2) return@mapNotNull null
        val seconds = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
        val text = parts[1].trim()
        if(!seconds.isFinite() || seconds < 0 || seconds > 86400 || text.isBlank()) null
        else LyricLine((seconds*1000).roundToLong(), text=text)
    }.sortedBy { it.startTimeMs }.distinct()
    fun format(lines: List<LyricLine>): String = lines.sortedBy { it.startTimeMs }.joinToString("\n") {
        require(it.startTimeMs >= 0)
        val ms = it.startTimeMs
        String.format(Locale.ROOT,"[%02d:%02d.%03d]%s", ms/60000, ms/1000%60, ms%1000,it.text)
    }
}
