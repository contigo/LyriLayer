package com.chaoseye.recognitionkit

import android.content.Context
import android.net.Uri
import android.os.Build
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.*

internal object SongRecSignature {
    init { System.loadLibrary("songrecfp") }
    @JvmStatic external fun fromPcm16Mono16kHz(samples:ShortArray):String
}
data class RecognizedTrack(val id:String,val title:String,val artist:String,val album:String="",val artworkUrl:String="",val releaseDate:String="",val isrc:String="",val provider:String="SHAZAM_FINGERPRINT")
sealed class RecognitionResult {
    data class Success(val track:RecognizedTrack):RecognitionResult()
    data object NoMatch:RecognitionResult()
    data class Failure(val message:String,val haltBatch:Boolean=false):RecognitionResult()
    fun isSuccess()=this is Success
}
data class RecognitionConfig(val sampleDurationMs:Long=12000,val retryOtherSections:Boolean=true){
    init{require(sampleDurationMs in 6000..12000)}
}
data class AudioSample(val pcm:ShortArray,val durationMs:Long)
fun interface RecognitionProvider { suspend fun recognize(sample:AudioSample):RecognitionResult }
internal object SampleWindows {
    fun starts(duration:Long,length:Long,retry:Boolean):List<Long>{
        require(duration>0&&length>0)
        val end=(duration-length).coerceAtLeast(0)
        return (if(retry)listOf(0.5,0.25,0.75)else listOf(0.5)).map{(duration*it-length/2).toLong().coerceIn(0,end)}.distinct()
    }
}
class RecognitionKit @JvmOverloads constructor(context:Context,private val provider:RecognitionProvider=ShazamProvider()){
    private val app=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    fun supported()=Build.SUPPORTED_ABIS.contains("arm64-v8a")
    suspend fun recognize(uri:Uri,durationMs:Long=0,config:RecognitionConfig=RecognitionConfig()):RecognitionResult{
        if(!supported())return RecognitionResult.Failure("ARM64 기기 필요",true)
        return try{
            val duration=if(durationMs>0)durationMs else withContext(Dispatchers.IO){
                val retriever=MediaMetadataRetriever()
                try{retriever.setDataSource(app,uri);retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L}finally{retriever.release()}
            }
            if(duration<2000)return RecognitionResult.Failure("음원 길이 부족")
            val length=minOf(config.sampleDurationMs,duration)
            for(start in SampleWindows.starts(duration,length,config.retryOtherSections)){
                currentCoroutineContext().ensureActive()
                val sample=AudioSampleExtractor.read(app,uri,start,length)
                when(val result=provider.recognize(sample)){
                    RecognitionResult.NoMatch->continue
                    else->return result
                }
            }
            RecognitionResult.NoMatch
        }catch(e:CancellationException){throw e}
        catch(e:LinkageError){RecognitionResult.Failure("음원 지문 엔진 로딩 실패",true)}
        catch(e:Exception){RecognitionResult.Failure(e.message?:"음원 분석 오류")}
    }
    fun interface Callback {fun onResult(result:RecognitionResult)}
    @JvmOverloads fun recognizeAsync(uri:Uri,callback:Callback,durationMs:Long=0,config:RecognitionConfig=RecognitionConfig()):Job =
        scope.launch{callback.onResult(recognize(uri,durationMs,config))}
    fun close(){scope.cancel()}
}
