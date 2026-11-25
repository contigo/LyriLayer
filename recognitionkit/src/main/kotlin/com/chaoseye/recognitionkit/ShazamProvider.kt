package com.chaoseye.recognitionkit

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Experimental unofficial provider: explicit caller opt-in required. */
class ShazamProvider(private val enabled:Boolean=false):RecognitionProvider {
    companion object {
        private val gate=Mutex()
        private val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build()
        private var lastRequest=0L
    }
    override suspend fun recognize(sample:AudioSample):RecognitionResult{
        if(!enabled)return RecognitionResult.Failure("비공식 음원 검색에 동의해야 합니다",true)
        val signature=withContext(Dispatchers.Default){SongRecSignature.fromPcm16Mono16kHz(sample.pcm)}
        return gate.withLock{
            delay((1500-(android.os.SystemClock.elapsedRealtime()-lastRequest)).coerceAtLeast(0))
            val now=System.currentTimeMillis()/1000
            val payload=JSONObject().put("signature",JSONObject().put("samplems",sample.durationMs).put("timestamp",now).put("uri",signature)).put("timestamp",now).put("timezone",TimeZone.getDefault().id)
            val url="https://amp.shazam.com/discovery/v5/en/US/android/-/tag/"+UUID.randomUUID()+"/"+UUID.randomUUID()+"?sync=true&webv3=true&sampling=true&shazamapiversion=v3"
            val request=Request.Builder().url(url).header("User-Agent","LyriLayer RecognitionKit/1.0 (experimental)").header("Accept","application/json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            lastRequest=android.os.SystemClock.elapsedRealtime()
            suspendCancellableCoroutine<RecognitionResult>{continuation->
                val call=client.newCall(request)
                continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback{
                    override fun onFailure(call:Call,e:IOException){continuation.resume(RecognitionResult.Failure("음원 검색 연결 실패",true))}
                    override fun onResponse(call:Call,response:Response){
                        val result=try{response.use{r->
                            if(!r.isSuccessful)RecognitionResult.Failure("음원 검색 서버 HTTP "+r.code,true)
                            else{
                                val source=(r.body?:throw IOException("응답 없음")).source()
                                if(source.request(1048577L))throw IOException("응답 크기 초과")
                                parse(source.readUtf8())
                            }
                        }}catch(e:Exception){RecognitionResult.Failure("음원 검색 응답 오류",true)}
                        continuation.resume(result)
                    }
                })
            }
        }
    }
    internal fun parse(raw:String):RecognitionResult{
        val root=JSONObject(raw)
        val t=root.optJSONObject("track")?:return RecognitionResult.NoMatch
        fun value(o:JSONObject?,key:String)=if(o==null||o.isNull(key))"" else o.optString(key)
        val id=value(t,"key");val title=value(t,"title");val artist=value(t,"subtitle")
        if(id.isBlank()||title.isBlank()||artist.isBlank()||(root.optJSONArray("matches")?.length()?:0)==0)return RecognitionResult.NoMatch
        var album="";var released=""
        val sections=t.optJSONArray("sections")
        if(sections!=null)for(i in 0 until sections.length()){
            val section=sections.optJSONObject(i)?:continue
            if(!value(section,"type").equals("SONG",true))continue
            val metadata=section.optJSONArray("metadata")?:continue
            for(j in 0 until metadata.length()){
                val item=metadata.optJSONObject(j)?:continue
                when(value(item,"title").uppercase(java.util.Locale.ROOT)){"ALBUM"->album=value(item,"text");"RELEASED"->released=value(item,"text")}
            }
        }
        val images=t.optJSONObject("images")
        val cover=value(images,"coverarthq").ifBlank{value(images,"coverart")}.takeIf{it.startsWith("https://")}.orEmpty()
        return RecognitionResult.Success(RecognizedTrack(id,title,artist,album,cover,released,value(t,"isrc")))
    }
}
