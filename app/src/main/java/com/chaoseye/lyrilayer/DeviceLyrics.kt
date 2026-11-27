package com.chaoseye.lyrilayer

import android.content.Context
import com.chaoseye.ailyrickit.AiLyricKit
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class AiAnalysisProgress(val trackKey:String,val percent:Int)

object DeviceLyrics {
    private val _activeAnalysis=MutableStateFlow<AiAnalysisProgress?>(null)
    internal val activeAnalysis=_activeAnalysis.asStateFlow()
    private var cachedKit:AiLyricKit?=null
    private var cachedStamp:Pair<Long,Long>?=null
    private val verified=mutableMapOf<String,Pair<Long,Long>>()
    private val lock=Mutex()
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(20,TimeUnit.MINUTES).build()
    fun supported()=Build.SUPPORTED_ABIS.any{it=="arm64-v8a"}
    private fun prefs(c:Context)=c.getSharedPreferences("settings",Context.MODE_PRIVATE)
    private fun model(c:Context,spec:WhisperModel)=File(File(c.filesDir,"whisper"),spec.filename)
    fun ready(c:Context)=AiPerformance.small.let{it.sizeValid(model(c,it).length())}
    // A single native context is used under lock; never share it concurrently.
    private fun closeCached(){
        val kit=cachedKit;cachedKit=null;cachedStamp=null
        kit?.close()
    }
    internal suspend fun releaseBatchModel()=withContext(NonCancellable+Dispatchers.IO){lock.withLock{closeCached()}}
    private suspend fun verifyModel(file:File,spec:WhisperModel):Boolean{
        if(!spec.sizeValid(file.length()))return false
        val stamp=file.length() to file.lastModified()
        if(verified[spec.id]==stamp)return true
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->
            val buf=ByteArray(65536)
            while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break;hash.update(buf,0,n)}
        }
        val ok=hash.digest().joinToString(""){"%02x".format(it)}==spec.sha
        if(ok)verified[spec.id]=stamp else verified.remove(spec.id)
        return ok
    }
    suspend fun prepare(c:Context,progress:(Int)->Unit)=lock.withLock {
        withContext(Dispatchers.IO){
            if(!supported())throw IOException("64비트 ARM 기기 필요")
            val spec=AiPerformance.small
            val dst=model(c,spec);dst.parentFile?.mkdirs()
            if(verifyModel(dst,spec)){progress(100);return@withContext}
            closeCached();verified.remove(spec.id)
            val tmp=File(dst.parentFile,spec.filename+".part")
            try{
                client.newCall(Request.Builder().url(spec.url).build()).execute().use{r->
                    if(!r.isSuccessful)throw IOException("모델 다운로드 오류 HTTP ${r.code}")
                    val body=r.body?:throw IOException("모델 응답 없음")
                    val hash=MessageDigest.getInstance("SHA-256")
                    var total=0L
                    body.byteStream().use{input->tmp.outputStream().use{output->
                        val buf=ByteArray(65536)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break
                            total+=n;if(total>spec.maxSize)throw IOException("모델 크기 오류")
                            output.write(buf,0,n);hash.update(buf,0,n);progress((total*100/(body.contentLength().takeIf{it>0}?:spec.maxSize)).toInt().coerceIn(0,99))
                        }
                    }}
                    if(!spec.sizeValid(total)||hash.digest().joinToString(""){"%02x".format(it)}!=spec.sha)throw IOException("모델 무결성 검증 실패")
                }
                if(!tmp.renameTo(dst))throw IOException("모델 저장 실패")
                verified[spec.id]=dst.length() to dst.lastModified()
                progress(100)
            }finally{tmp.delete()}
        }
    }
    suspend fun extract(c:Context,t:Track,language:String,retainForBatch:Boolean=false,preview:(String)->Unit={},progress:(Int,String)->Unit):String=lock.withLock{
        _activeAnalysis.value=AiAnalysisProgress(t.key,0)
        try {
        withContext(Dispatchers.IO){
            fun report(percent:Int,stage:String){
                _activeAnalysis.value=AiAnalysisProgress(t.key,percent.coerceIn(0,100))
                progress(percent,stage)
            }
            if(t.excluded||t.uri.isBlank()||MusicRules.excluded("",t.fileName,t.title))throw IOException("분석할 음악 파일이 없습니다")
            val spec=AiPerformance.small
            val file=model(c,spec)
            if(!supported()||!verifyModel(file,spec))throw IOException("설정에서 Small Q5_1 모델을 다운로드하거나 무결성을 확인하세요")
            val settings=prefs(c)
            val threads=AiPerformance.threads(settings.getInt(AiPerformance.THREADS_KEY,0),Runtime.getRuntime().availableProcessors())
            val reuse=settings.getBoolean(AiPerformance.REUSE_KEY,true)
            val stamp=file.length() to file.lastModified()
            if(!reuse||cachedStamp!=stamp)closeCached()
            if(t.duration>12*60*1000L)throw IOException("현재 AI 분석은 12분 이하 곡을 지원합니다")
            val job=currentCoroutineContext()[Job]
            var success=false
            try{
                report(0,"Small · AI 모델 준비")
                val kit=cachedKit?:AiLyricKit.open(c,file,cancelled={job?.isActive==false}).also{
                    cachedKit=it;cachedStamp=stamp
                }
                val result=kit.extract(Uri.parse(t.uri),language,threads,
                    cancelled={job?.isActive==false},
                    progress={p->
                        val ratio=if(p.total>0)p.completed.toDouble()/p.total else 0.0
                        val fraction=ratio.coerceIn(0.0,1.0)
                        when(p.phase){
                            "audio"->report((fraction*10).toInt(),"음악 디코딩")
                            "language"->report(10+(fraction*15).toInt(),"대표 언어 감지")
                            "decode"->report(25+(fraction*74).toInt(),"Small · 가사 인식")
                        }
                    },preview=preview)
                currentCoroutineContext().ensureActive()
                if(Lrc.parse(result.lrc).isEmpty())throw IOException("인식된 가사가 없습니다")
                report(100,"확인 후 저장하세요")
                success=true
                return@withContext result.lrc
            }finally{if(!retainForBatch||!reuse||!success)closeCached()}
        }
        }finally{_activeAnalysis.value=null;if(!retainForBatch)withContext(NonCancellable+Dispatchers.IO){closeCached()}}
    }
}
