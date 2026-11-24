package com.chaoseye.lyrilayer

internal object AiQueueRules {
    const val EXTRACT = "extract"
    const val ALIGN = "align"
    val tabs=listOf("all" to "전체","pending" to "대기","review" to "검토","failed" to "실패","saved" to "완료")
    fun countKinds(entries:List<AiQueueEntry>):Pair<Int,Int> =
        entries.count{it.kind==EXTRACT} to entries.count{it.kind==ALIGN}
    fun visible(filter:String,state:String)=when(filter){
        "all"->true
        "review"->state=="running"||state=="review"
        "saved"->state=="saved"||state=="skipped"
        else->state==filter
    }
    fun filtered(entries:List<AiQueueEntry>,filter:String):List<AiQueueEntry>{
        val result=entries.filter{visible(filter,it.state)}
        return if(filter=="review")result.sortedBy{if(it.state=="running")0 else 1}else result
    }
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
