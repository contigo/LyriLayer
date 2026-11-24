package com.chaoseye.lyrickit

enum class Provider { GENIE, BUGS, MUSIXMATCH }
data class SongInfo(val title: String, val artist: String, val album: String? = null, val durationMs: Long? = null)
data class LyricLine(val startTimeMs: Long, val endTimeMs: Long? = null, val text: String)
data class LyricsResult(val song: SongInfo, val provider: Provider, val matchScore: Float, val plainLyrics: String?, val syncedLyrics: String?, val syncedLines: List<LyricLine>)
