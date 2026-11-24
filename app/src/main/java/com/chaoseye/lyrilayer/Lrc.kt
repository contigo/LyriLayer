package com.chaoseye.lyrilayer

data class Line(val ms: Long, val text: String)
object Lrc {
    private val stamp = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    fun parse(raw: String): List<Line> {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return raw.lineSequence().flatMap { row ->
            val text = stamp.replace(row, "").trim()
            stamp.findAll(row).mapNotNull { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val sec = m.groupValues[2].toLong()
                if (sec >= 60 || min > 10000) return@mapNotNull null
                val fraction = m.groupValues[3].padEnd(3,'0').toLongOrNull() ?: 0
                Line((min*60000 + sec*1000 + fraction + offset).coerceAtLeast(0), text)
            }
        }.sortedBy { it.ms }.toList()
    }
    fun index(lines: List<Line>, ms: Long): Int {
        var lo=0; var hi=lines.size
        while(lo<hi) { val mid=(lo+hi)/2; if(lines[mid].ms<=ms) lo=mid+1 else hi=mid }
        return lo-1
    }
}
