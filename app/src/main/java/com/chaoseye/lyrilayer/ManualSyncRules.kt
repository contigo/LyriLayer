package com.chaoseye.lyrilayer

internal object ManualSyncRules {
    const val UNSET = -1L
    const val ADJUST_STEP_MS = 100L
    fun eligible(t: Track, l: Lyric?): Boolean = !t.excluded && t.uri.isNotBlank() && t.duration > 0 && l?.status == "FOUND" && (l.plain.isNotBlank() || Lrc.parse(l.synced).any { it.text.isNotBlank() })
    fun plainOnly(t: Track, l: Lyric?): Boolean = eligible(t,l) && !l?.plain.isNullOrBlank() && Lrc.parse(l?.synced.orEmpty()).none { it.text.isNotBlank() }
    fun original(l: Lyric): String = l.plain.ifBlank { Lrc.parse(l.synced).joinToString("\n") { it.text } }
    fun initial(l: Lyric, duration: Long): ArrayList<Long> {
        val rows = PlainSync.rows(original(l))
        val existing = Lrc.parse(l.synced)
        val times = existing.map { ((it.ms + l.offset).coerceAtLeast(0) / 10) * 10 }
        return if (existing.map { it.text } == rows && PlainSync.valid(times,duration)) ArrayList(times) else ArrayList(List(rows.size) { UNSET })
    }
    fun valid(times: List<Long>, duration: Long): Boolean = duration > 0 && PlainSync.valid(times.map { it.takeIf { x -> x >= 0 } },duration)
    fun mark(times: List<Long>, index: Int, position: Long, duration: Long): ArrayList<Long>? {
        if (index !in times.indices || position !in 0 until duration) return null
        val time = position / 10 * 10
        val prev = times.subList(0,index).lastOrNull { it >= 0 }
        val next = times.subList(index+1,times.size).firstOrNull { it >= 0 }
        if ((prev != null && time <= prev) || (next != null && time >= next)) return null
        return ArrayList(times).apply { set(index,time) }
    }
    fun canAdjust(times: List<Long>, index: Int, delta: Long, duration: Long): Boolean {
        val current = times.getOrNull(index) ?: return false
        if (current < 0 || duration <= 0 || current >= duration || (delta != ADJUST_STEP_MS && delta != -ADJUST_STEP_MS)) return false
        if (delta > 0 && current >= duration - delta) return false
        val target = current + delta
        if (target !in 0 until duration) return false
        val prev = times.subList(0,index).lastOrNull { it >= 0 }
        val next = times.subList(index+1,times.size).firstOrNull { it >= 0 }
        return (prev == null || target > prev) && (next == null || target < next)
    }
    fun adjust(times: List<Long>, index: Int, delta: Long, duration: Long): ArrayList<Long>? {
        if (!canAdjust(times,index,delta,duration)) return null
        return ArrayList(times).apply { set(index,times[index]+delta) }
    }
    fun nextIndex(times: List<Long>, current: Int): Int = if (current < times.lastIndex) current + 1 else times.indexOfFirst { it == UNSET }.takeIf { it >= 0 } ?: current
}
