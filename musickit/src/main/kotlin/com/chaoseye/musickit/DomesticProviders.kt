package com.chaoseye.musickit

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

internal fun parseDuration(value:String):Long {
    val parts=value.trim().split(':').map{it.toLongOrNull()?:return 0}
    if(parts.size !in 2..3||parts.any{it<0}||parts.last()>=60)return 0
    return parts.fold(0L){a,b->a*60+b}*1000
}
private fun JSONObject.str(key:String):String=if(isNull(key))"" else optString(key).trim()
internal fun parseBugsMusic(body:JSONObject):List<MusicCandidate>{
    val rows=body.optJSONArray("list")?:return emptyList()
    return (0 until rows.length()).mapNotNull{i->
        val row=rows.optJSONObject(i)?:return@mapNotNull null
        val id=row.str("track_id");val title=row.str("track_title")
        val artists=row.optJSONArray("artists")?:return@mapNotNull null
        val artist=(0 until artists.length()).mapNotNull{artists.optJSONObject(it)?.str("artist_nm")}.filter{it.isNotBlank()}.joinToString(", ")
        val album=row.str("album_title").ifBlank{row.optJSONObject("album")?.str("album_title").orEmpty()}
        if(id.isBlank()||title.isBlank()||artist.isBlank())null else MusicCandidate(MusicSource.BUGS,id,title,artist,album,parseDuration(row.str("len")))
    }.distinctBy{it.id}
}
internal fun parseGenieMusic(body:String):List<MusicCandidate> =
    Jsoup.parse(body).select("tr.list[songid]").mapNotNull{row->
        fun display(selector:String):String{
            val el=row.selectFirst(selector)?:return ""
            el.select("span.icon,span[class^=icon-]").remove()
            return el.attr("title").ifBlank{el.text()}.trim()
        }
        val id=row.attr("songid");val title=display("td.info a.title.ellipsis");val artist=display("td.info a.artist.ellipsis")
        if(id.isBlank()||!id.all(Char::isDigit)||title.isBlank()||artist.isBlank())null
        else MusicCandidate(MusicSource.GENIE,id,title,artist,display("td.info a.albumtitle"),parseDuration(row.select("td.info span.duration").text()))
    }.distinctBy{it.id}
internal class BugsMusic(private val http:PublicHttp):MusicProvider{
    override val source=MusicSource.BUGS
    override suspend fun search(query:MusicQuery):List<MusicCandidate> = gate.withLock{
        try{parseBugsMusic(http.get("https://m.bugs.co.kr/api/getSearchList",mapOf("type" to "track","query" to listOf(query.title,query.artist).filter{it.isNotBlank()}.joinToString(" "),"page" to "1","size" to "30")))}
        finally{withContext(NonCancellable){delay(1000)}}
    }
    companion object{private val gate=Mutex()}
}
internal class GenieMusic(private val http:PublicHttp):MusicProvider{
    override val source=MusicSource.GENIE
    override suspend fun search(query:MusicQuery):List<MusicCandidate> = gate.withLock{
        try{
            suspend fun find(term:String)=parseGenieMusic(http.text("https://www.genie.co.kr/search/searchMain",mapOf("query" to term),"text/html"))
            val first=find(listOf(query.title,query.artist).filter{it.isNotBlank()}.joinToString(" "))
            if(query.artist.isBlank()||first.any{MusicMatching.score(query,it)>=70})first else {delay(1000);(first+find(query.title)).distinctBy{it.id}}
        }finally{withContext(NonCancellable){delay(1000)}}
    }
    companion object{private val gate=Mutex()}
}
