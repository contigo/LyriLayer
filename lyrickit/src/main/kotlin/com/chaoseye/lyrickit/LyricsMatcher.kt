package com.chaoseye.lyrickit

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object LyricsMatcher {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    private val versions = Regex("(?i)(?:\\b(live|remix|remaster(?:ed)?|instrumental|acoustic|karaoke|demo|inst)\\b|라이브|리믹스|리마스터|어쿠스틱|반주)")
    private fun version(value: String) = versions.findAll(value).map { it.value.lowercase(Locale.ROOT) }.toSet()
    fun score(query: SongInfo, candidate: SongInfo): Float {
        if(normalize(query.title).isEmpty() || normalize(query.artist).isEmpty()) return 0f
        if(version(query.title) != version(candidate.title)) return 0f
        if(normalize(query.title) != normalize(candidate.title) || normalize(query.artist) != normalize(candidate.artist)) return 0f
        var total = 75f
        val a = query.durationMs?.takeIf { it > 0 }; val b = candidate.durationMs?.takeIf { it > 0 }
        if(a != null && b != null) {
            val difference = abs(a-b)
            if(difference > 8000) return 0f
            total += when { difference <= 2000 -> 20f; difference <= 4000 -> 15f; else -> 5f }
        } else total += 10f
        if(!query.album.isNullOrBlank() && !candidate.album.isNullOrBlank() && normalize(query.album)==normalize(candidate.album)) total += 5f
        return total
    }
}
