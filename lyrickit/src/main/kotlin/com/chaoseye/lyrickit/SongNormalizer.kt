package com.chaoseye.lyrickit

object SongNormalizer {
    private val decorated = Regex("^(.+?)(?:\\s+[-–—]\\s+)(.+?)\\s+by\\s+(.+)$",RegexOption.IGNORE_CASE)
    private val soundtrack = Regex("\\b(?:soundtrack|ost|original soundtrack)\\b",RegexOption.IGNORE_CASE)
    private val credit = Regex("\\s*(?:&|,|;)\\s*")
    fun normalize(song: SongInfo): SongInfo {
        val m=decorated.matchEntire(song.title.trim()) ?: return song
        if(!soundtrack.containsMatchIn(m.groupValues[1])) return song
        val title=m.groupValues[2].trim()
        val embedded=m.groupValues[3].trim()
        val parts=embedded.split(credit).filter{it.isNotBlank()}
        // A short final credit may be a filename truncation; do not invent the missing name.
        val artist=if(song.artist.isNotBlank()) song.artist else embedded
        return song.copy(title=title,artist=artist)
    }
    internal fun searchVariants(song: SongInfo): List<SongInfo> {
        val clean=normalize(song)
        if(clean.title==song.title || song.artist.isNotBlank()) return listOf(clean)
        val parts=clean.artist.split(credit).filter{it.isNotBlank()}
        return if(parts.size>1 && parts.last().length<=3) listOf(clean,clean.copy(artist=parts.first())) else listOf(clean)
    }
    internal fun truncatedCreditMatches(raw: SongInfo,candidate: SongInfo): Boolean {
        val clean=normalize(raw)
        if(clean.title==raw.title || raw.artist.isNotBlank()) return false
        val parts=clean.artist.split(credit).map{it.trim()}.filter{it.isNotBlank()}
        if(parts.size!=2 || parts.last().length !in 2..3) return false
        val actual=candidate.artist.replace(Regex("\\s*\\((?=[^()]*\\p{IsHangul})[^()]*\\)"),"")
            .split(credit).map{it.trim()}.filter{it.isNotBlank()}
        return actual.size==2 && actual[0].equals(parts[0],true) && actual[1].startsWith(parts[1],true)
    }
}
