package com.chaoseye.lyrilayer

internal object AiQueueRules {
    const val EXTRACT = "extract"
    const val ALIGN = "align"
    fun kind(track: Track, lyric: Lyric?): String? {
        if (track.excluded || track.uri.isBlank() || track.duration <= 0 || track.duration > 720000L) return null
        if (MusicRules.excluded("", track.fileName, track.title)) return null
        if (lyric?.status == "INSTRUMENTAL" || Lrc.parse(lyric?.synced.orEmpty()).any { it.text.isNotBlank() }) return null
        return if (!lyric?.plain.isNullOrBlank()) ALIGN else EXTRACT
    }
    fun fingerprint(lyric: Lyric?): String {
        if (lyric == null) return "absent"
        val values = listOf(lyric.key, lyric.plain, lyric.synced, lyric.source, lyric.status, lyric.edited.toString(), lyric.offset.toString())
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
