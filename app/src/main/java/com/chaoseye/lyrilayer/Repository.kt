package com.chaoseye.lyrilayer
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
internal class OnlineSearchException(message:String,val retryable:Boolean):IOException(message)
object Repository {
    private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    private val gate=Mutex()
    private const val MISS="LyricKit-LRCLIB-v1"
    private fun text(j:JSONObject,k:String)=if(j.isNull(k))"" else j.optString(k)
    private suspend fun request(endpoint:String,params:Map<String,String>):String? {
        currentCoroutineContext().ensureActive()
        val url=("https://lrclib.net/api/"+endpoint).toHttpUrl().newBuilder().apply{params.filter{(k,v)->v.isNotBlank()||k=="album_name"}.forEach{(k,v)->addQueryParameter(k,v)}}.build()
        try {
            return client.newCall(Request.Builder().url(url).header("User-Agent","LyriLayer/0.1.5 (https://github.com/contigo/LyriLayer)").header("Accept","application/json").build()).execute().use{r->
                when {
                    r.code==404 -> null
                    r.code==429||r.code==503 -> throw IOException("가사 서버 사용량 제한 (HTTP ${r.code})")
                    !r.isSuccessful -> throw IOException("가사 서버 오류 (HTTP ${r.code})")
                    else -> r.body?.string()?:throw IOException("가사 서버 응답 없음")
                }
            }
        }finally{delay(350)}
    }
    private fun lyric(t:Track,j:JSONObject):Lyric?{
        val plain=text(j,"plainLyrics");val synced=text(j,"syncedLyrics").takeIf{Lrc.parse(it).isNotEmpty()}.orEmpty()
        val instrumental=j.optBoolean("instrumental")
        if(plain.isBlank()&&synced.isBlank()&&!instrumental)return null
        return Lyric(t.key,plain,synced,"LRCLIB",if(plain.isBlank()&&synced.isBlank())"INSTRUMENTAL" else "FOUND")
    }
    private data class Candidate(val value:Lyric,val score:Int,val artist:String,val seconds:Double)
    private fun candidate(t:Track,id:MusicRules.Identity,j:JSONObject):Candidate?{
        val l=lyric(t,j)?:return null
        val a=text(j,"artistName");val seconds=j.optDouble("duration",0.0)
        val score=MusicRules.score(id,t.album,t.duration,text(j,"trackName"),a,text(j,"albumName"),seconds,l.synced.isNotBlank())?:return null
        return Candidate(l,score,a,seconds)
    }
    private suspend fun legacyOnline(t:Track):Lyric?{
        val ids=MusicRules.identities(t.title,t.artist,t.fileName)
        if(ids.isEmpty())return null
        val candidates=linkedMapOf<String,Candidate>()
        var error:IOException?=null
        suspend fun safe(endpoint:String,params:Map<String,String>):String? = try{request(endpoint,params)}catch(e:CancellationException){throw e}catch(e:IOException){error=e;if(e.message?.contains("사용량 제한")==true||e is java.net.UnknownHostException||e is javax.net.ssl.SSLException)throw e;null}
        val id=ids.first()
        var partial:Lyric?=null
        if(id.artist.isNotBlank()&&t.duration>0){
            val exact=safe("get-cached",mapOf("track_name" to id.title,"artist_name" to id.artist,"album_name" to MusicRules.clean(t.album),"duration" to ((t.duration+500)/1000).toString()))
            if(exact!=null){
                val l=candidate(t,id,JSONObject(exact))?.value
                if(l!=null&&l.synced.isNotBlank())return l
                partial=l
            }
        }
        val searches=linkedSetOf<Pair<MusicRules.Identity,Map<String,String>>>()
        for(x in ids){
            searches+=x to mapOf("track_name" to x.title,"artist_name" to x.artist)
            if(x.artist.isNotBlank())searches+=x to mapOf("q" to "${x.title} ${x.artist}")
            searches+=x to mapOf("track_name" to x.title)
        }
        for((x,params) in searches.take(9)){
            val raw=safe("search",params)?:continue
            val a=JSONArray(raw)
            for(i in 0 until minOf(a.length(),50)){
                val j=a.optJSONObject(i)?:continue
                val c=candidate(t,x,j)?:continue
                val key=j.optString("id",j.toString())
                if((candidates[key]?.score?:-1)<c.score)candidates[key]=c
            }
            val best=candidates.values.maxByOrNull{it.score}
            if(best!=null&&best.score>=145&&best.value.synced.isNotBlank()&&candidates.values.none{best.score-it.score<=8&&!MusicRules.artistMatches(best.artist,it.artist)})return best.value
        }
        val sorted=candidates.values.sortedByDescending{it.score};val best=sorted.firstOrNull()
        if(best!=null&&sorted.drop(1).none{best.score-it.score<=8&&(!MusicRules.artistMatches(best.artist,it.artist)||kotlin.math.abs(best.seconds-it.seconds)>4)}){
            if(best.value.synced.isNotBlank()||partial==null)return best.value
        }
        if(partial!=null)return partial
        // Last resort: LRCLIB's exact endpoint may fetch additional sources.
        if(id.artist.isNotBlank()&&t.duration>0){
            val raw=safe("get",mapOf("track_name" to id.title,"artist_name" to id.artist,"album_name" to MusicRules.clean(t.album),"duration" to ((t.duration+500)/1000).toString()))
            if(raw!=null)candidate(t,id,JSONObject(raw))?.value?.let{return it}
        }
        error?.let{throw it}
        return null
    }
    private suspend fun online(c:Context,t:Track):Lyric? {
        val primary = LyricKitBridge.lookup(c,t)
        val candidate = LyricKitBridge.toLyric(t, primary)
        if (candidate?.synced?.isNotBlank() == true) return candidate
        val failures = when(primary) {
            is com.chaoseye.lyrickit.SearchOutcome.Unavailable -> primary.failures
            is com.chaoseye.lyrickit.SearchOutcome.Found -> primary.failures
            else -> emptyMap()
        }
        val details = failures.map { (provider,failure) -> com.chaoseye.lyrickit.SearchFailures.label(provider)+": "+com.chaoseye.lyrickit.SearchFailures.describe(failure) }
        val legacy = try { legacyOnline(MusicInfoRepository.canonical(c,t))?.copy(key=t.key) } catch(e: CancellationException) { throw e } catch(e: Exception) {
            if (candidate != null) return candidate
            val status=Regex("HTTP ([0-9]{3})").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val failure=com.chaoseye.lyrickit.SearchFailures.classify(if(status!=null)com.chaoseye.lyrickit.HttpFailure(status) else e)
            throw OnlineSearchException((details + ("LRCLIB: "+com.chaoseye.lyrickit.SearchFailures.describe(failure))).joinToString(" / "),failure.retryable||failures.values.any{it.retryable})
        }
        if (legacy?.synced?.isNotBlank() == true) return legacy
        if (candidate != null) return candidate
        if (legacy != null) return legacy
        if (primary is com.chaoseye.lyrickit.SearchOutcome.Unavailable) throw OnlineSearchException(
            details.ifEmpty { primary.failedProviders.map { com.chaoseye.lyrickit.SearchFailures.label(it)+": 검색 실패" } }.joinToString(" / "),
            failures.values.any { it.retryable })
        return null
    }
    data class SearchResult(val id:Long,val title:String,val artist:String,val album:String,val seconds:Double,val plain:String,val synced:String,val instrumental:Boolean)
    suspend fun searchCandidates(query:String):List<SearchResult> = gate.withLock {
        withContext(Dispatchers.IO) {
            if(query.isBlank())return@withContext emptyList()
            val body=request("search",mapOf("q" to query.trim()))?:return@withContext emptyList()
            val a=JSONArray(body)
            (0 until minOf(a.length(),20)).mapNotNull { i ->
                val j=a.optJSONObject(i)?:return@mapNotNull null
                val plain=text(j,"plainLyrics");val synced=text(j,"syncedLyrics").takeIf{Lrc.parse(it).isNotEmpty()}.orEmpty()
                if(plain.isBlank()&&synced.isBlank()&&!j.optBoolean("instrumental"))return@mapNotNull null
                SearchResult(j.optLong("id"),text(j,"trackName"),text(j,"artistName"),text(j,"albumName"),j.optDouble("duration",0.0),plain,synced,j.optBoolean("instrumental"))
            }
        }
    }
    suspend fun selectCandidate(c:Context,t:Track,r:SearchResult) {
        val hasLyrics=r.plain.isNotBlank()||r.synced.isNotBlank()
        // A user selection is deliberate. Preserve it from further automatic replacements.
        LyricsDb.get(c).dao().put(Lyric(t.key,r.plain,r.synced,"LRCLIB (USER)",if(hasLyrics)"FOUND" else "INSTRUMENTAL",edited=true))
    }
    suspend fun clearForReset(c:Context):Int=gate.withLock { LyricsDb.get(c).dao().clearAll() }
    suspend fun resolve(c:Context,t:Track,force:Boolean=false):Lyric=gate.withLock{
        withContext(Dispatchers.IO){
            if(t.excluded||MusicRules.excluded("",t.fileName,t.title))throw IOException("녹음 파일은 검색하지 않습니다")
            val dao=LyricsDb.get(c).dao();val old=dao.get(t.key)
            if(old!=null&&(old.edited||(!force&&(old.status=="FOUND"||old.status=="INSTRUMENTAL"||(old.status=="NOT_FOUND"&&old.source==MISS&&System.currentTimeMillis()-old.fetched<86400000)))))return@withContext old
            val result=try{LocalLyrics.find(c,t)?:online(c,t)?:Lyric(t.key,status="NOT_FOUND",source=MISS)}catch(e:IOException){if(old?.status=="FOUND")return@withContext old;throw e}
            ensureActive()
            if(old?.status=="FOUND"&&(result.status!="FOUND"||(old.synced.isNotBlank()&&result.synced.isBlank())))return@withContext old
            if(dao.insert(result)==-1L)dao.updateAuto(result.key,result.plain,result.synced,result.source,result.status,result.fetched)
            dao.get(t.key)?:result
        }
    }
}
