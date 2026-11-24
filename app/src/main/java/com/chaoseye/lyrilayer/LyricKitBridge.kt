package com.chaoseye.lyrilayer

import com.chaoseye.lyrickit.SongInfo
import com.chaoseye.lyrickit.SearchOutcome
import com.chaoseye.lyrickit.createLyricKit

internal object LyricKitBridge {
    private val engine by lazy { createLyricKit() }
    suspend fun lookup(track: Track): SearchOutcome {
        if (track.excluded || MusicRules.excluded("", track.fileName, track.title)) return SearchOutcome.NotFound
        val identity = MusicRules.identities(track.title, track.artist, track.fileName)
            .firstOrNull { it.title.isNotBlank() && it.artist.isNotBlank() } ?: return SearchOutcome.NotFound
        return engine.searchDetailed(SongInfo(identity.title, identity.artist,
            MusicRules.clean(track.album).takeIf { it.isNotBlank() }, track.duration.takeIf { it > 0 }))
    }
    fun toLyric(track: Track, outcome: SearchOutcome): Lyric? {
        val result = (outcome as? SearchOutcome.Found)?.result ?: return null
        val synced = result.syncedLyrics.orEmpty().takeIf { raw ->
            val lines = Lrc.parse(raw).filter { it.text.isNotBlank() }
            lines.isNotEmpty() && (track.duration <= 0 || lines.all { it.ms < track.duration })
        }.orEmpty()
        val plain = result.plainLyrics.orEmpty().ifBlank { Lrc.parse(synced).joinToString("\n") { it.text } }
        if (plain.isBlank() && synced.isBlank()) return null
        return Lyric(track.key, plain, synced, "LyricKit/${result.provider.name}", "FOUND")
    }
}
