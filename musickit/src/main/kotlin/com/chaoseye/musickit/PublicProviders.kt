package com.chaoseye.musickit

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class PublicHttp {
    private val client=OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).build()
    suspend fun get(url:String,params:Map<String,String>):JSONObject=suspendCancellableCoroutine{c->
        val target=url.toHttpUrl().newBuilder().apply{params.forEach{(k,v)->addQueryParameter(k,v)}}.build()
        val call=client.newCall(Request.Builder().url(target).header("User-Agent","LyriLayer-MusicKit/1.0 (https://github.com/contigo/LyriLayer)").header("Accept","application/json").build())
        c.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response){
                try{val json=response.use{if(!it.isSuccessful)throw IOException("HTTP "+it.code);JSONObject(it.body?.string()?:throw IOException("empty"))};if(c.isActive)c.resume(json)}
                catch(e:Exception){if(c.isActive)c.resumeWithException(e)}
            }
        })
    }
}
private class ITunes(private val http:PublicHttp):MusicProvider {
    override val source=MusicSource.ITUNES_KR
    override suspend fun search(query:MusicQuery):List<MusicCandidate> = gate.withLock{
        try{
            val a=http.get("https://itunes.apple.com/search",mapOf("term" to listOf(query.title,query.artist).filter{it.isNotBlank()}.joinToString(" "),"country" to "KR","entity" to "song","limit" to "20")).getJSONArray("results")
            (0 until a.length()).mapNotNull{i->val j=a.optJSONObject(i)?:return@mapNotNull null
                val id=j.optLong("trackId",0);val t=j.optString("trackName");val artist=j.optString("artistName")
                if(id<=0||t.isBlank()||artist.isBlank())null else MusicCandidate(source,id.toString(),t,artist,j.optString("collectionName"),j.optLong("trackTimeMillis"),j.optString("artworkUrl100"),j.optString("releaseDate"))
            }
        }finally{withContext(NonCancellable){delay(3100)}}
    }
    companion object{private val gate=Mutex()}
}
private class Brainz(private val http:PublicHttp):MusicProvider {
    override val source=MusicSource.MUSICBRAINZ
    private fun quoted(s:String)="\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\""
    override suspend fun search(query:MusicQuery):List<MusicCandidate> = gate.withLock{
        try{
            val term="recording:"+quoted(query.title)+(if(query.artist.isNotBlank())" AND artist:"+quoted(query.artist) else "")
            val a=http.get("https://musicbrainz.org/ws/2/recording/",mapOf("query" to term,"fmt" to "json","limit" to "15")).getJSONArray("recordings")
            (0 until a.length()).flatMap{i->
                val j=a.getJSONObject(i);val credits=j.optJSONArray("artist-credit")
                val artist=if(credits==null)"" else (0 until credits.length()).joinToString(""){k->val x=credits.getJSONObject(k);x.optString("name")+x.optString("joinphrase")}
                val releases=j.optJSONArray("releases")
                val base=MusicCandidate(source,j.optString("id"),j.optString("title"),artist,durationMs=j.optLong("length"),releaseDate=j.optString("first-release-date"))
                if(base.id.isBlank()||base.title.isBlank()||artist.isBlank())emptyList()
                else if(releases==null||releases.length()==0)listOf(base)
                else (0 until minOf(releases.length(),5)).map{k->val rel=releases.getJSONObject(k);base.copy(id=base.id+"/"+rel.optString("id"),album=rel.optString("title"),releaseDate=rel.optString("date",base.releaseDate))}
            }
        }finally{withContext(NonCancellable){delay(1100)}}
    }
    companion object{private val gate=Mutex()}
}
fun createMusicKit():MusicKit {val http=PublicHttp();return MusicKit(listOf(ITunes(http),Brainz(http)))}
