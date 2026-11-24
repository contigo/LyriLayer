package com.chaoseye.lyrilayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object NativeWhisper {
    init { System.loadLibrary("lyrilayer_whisper") }
    external fun open(path:String):Long
    external fun decode(handle:Long,pcm:FloatArray,threads:Int,language:String,offsetMs:Long):ByteArray?
    external fun close(handle:Long)
}

internal data class AiAnalysisProgress(val trackKey:String,val percent:Int)

object DeviceLyrics {
    private val _activeAnalysis=MutableStateFlow<AiAnalysisProgress?>(null)
    internal val activeAnalysis=_activeAnalysis.asStateFlow()
    private const val MODEL="ggml-base-q5_1.bin"
    private const val SIZE=59707625L
    private const val SHA="422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    private const val URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-base-q5_1.bin"
    private val lock=Mutex()
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(20,TimeUnit.MINUTES).build()
    fun supported()=Build.SUPPORTED_ABIS.any{it=="arm64-v8a"}
    private fun model(c:Context)=File(File(c.filesDir,"whisper"),MODEL)
    fun ready(c:Context)=model(c).length()==SIZE
    suspend fun prepare(c:Context,progress:(Int)->Unit)=lock.withLock {
        withContext(Dispatchers.IO){
            if(!supported())throw IOException("64비트 ARM 기기 필요")
            val dst=model(c);dst.parentFile?.mkdirs()
            fun hex(d:ByteArray)=d.joinToString(""){"%02x".format(it)}
            if(dst.length()==SIZE){
                val hash=MessageDigest.getInstance("SHA-256")
                dst.inputStream().use{input->val buf=ByteArray(65536);while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break;hash.update(buf,0,n)}}
                if(hex(hash.digest())==SHA){progress(100);return@withContext}
            }
            val tmp=File(dst.parentFile,MODEL+".part")
            try{
                client.newCall(Request.Builder().url(URL).build()).execute().use{r->
                    if(!r.isSuccessful)throw IOException("모델 다운로드 오류 HTTP ${r.code}")
                    val body=r.body?:throw IOException("모델 응답 없음")
                    val hash=MessageDigest.getInstance("SHA-256")
                    var total=0L
                    body.byteStream().use{input->tmp.outputStream().use{output->
                        val buf=ByteArray(65536)
                        while(true){currentCoroutineContext().ensureActive();val n=input.read(buf);if(n<0)break
                            total+=n;if(total>SIZE)throw IOException("모델 크기 오류")
                            output.write(buf,0,n);hash.update(buf,0,n);progress((total*100/SIZE).toInt())
                        }
                    }}
                    if(total!=SIZE||hex(hash.digest())!=SHA)throw IOException("모델 무결성 검증 실패")
                }
                if(!tmp.renameTo(dst))throw IOException("모델 저장 실패")
                progress(100)
            }finally{tmp.delete()}
        }
    }
    suspend fun extract(c:Context,t:Track,language:String,preview:(String)->Unit={},progress:(Int,String)->Unit):String=lock.withLock{
        _activeAnalysis.value=AiAnalysisProgress(t.key,0)
        try {
        withContext(Dispatchers.IO){
            fun report(percent:Int,stage:String){
                _activeAnalysis.value=AiAnalysisProgress(t.key,percent.coerceIn(0,100))
                progress(percent,stage)
            }
            if(t.excluded||t.uri.isBlank()||MusicRules.excluded("",t.fileName,t.title))throw IOException("분석할 음악 파일이 없습니다")
            if(!supported()||!ready(c))throw IOException("설정에서 AI 모델을 먼저 다운로드하세요")
            if(t.duration>12*60*1000L)throw IOException("현재 AI 분석은 12분 이하 곡을 지원합니다")
            report(0,"음악 디코딩")
            val pcm=AudioPcm.read(c,Uri.parse(t.uri))
            currentCoroutineContext().ensureActive()
            if(pcm.isEmpty())throw IOException("음원 디코딩 결과 없음")
            val handle=NativeWhisper.open(model(c).absolutePath)
            if(handle==0L)throw IOException("AI 모델 열기 실패 · 모델을 다시 확인하세요")
            try{
                val output=StringBuilder()
                var start=0
                val chunk=25*16000
                while(start<pcm.size){
                    currentCoroutineContext().ensureActive()
                    val end=minOf(start+chunk,pcm.size)
                    report(start*100/pcm.size,"보컬 음성 인식")
                    val part=NativeWhisper.decode(handle,pcm.copyOfRange(start,end),minOf(4,Runtime.getRuntime().availableProcessors()),language,start.toLong()*1000/16000)
                        ?:throw IOException("AI 음성 인식 실패")
                    currentCoroutineContext().ensureActive()
                    val text=part.toString(Charsets.UTF_8).trim()
                    if(text.isNotBlank()){
                        if(output.isNotEmpty())output.append('\n')
                        output.append(text).append('\n')
                    }
                    start=end
                    preview(output.toString())
                    report(start*100/pcm.size,"분석 구간 완료")
                }
                currentCoroutineContext().ensureActive()
                if(Lrc.parse(output.toString()).isEmpty())throw IOException("인식된 가사가 없습니다")
                report(100,"확인 후 저장하세요")
                output.toString()
            }finally{NativeWhisper.close(handle)}
        }
        }finally{_activeAnalysis.value=null}
    }
}

internal object AudioPcm {
    private const val LIMIT=12*60*16000
    private class Resampler(var rate:Int) {
        var output=FloatArray(16000*30)
        var size=0
        private var phase=0L
        private var sum=0f
        private var count=0
        fun add(v:Float) {
            sum+=v;count++;phase+=16000
            while(phase>=rate){
                if(size>=LIMIT)throw IOException("음원 길이 제한 초과")
                if(size==output.size)output=output.copyOf(minOf(LIMIT,output.size*2))
                output[size++]=sum/count
                phase-=rate
            }
            if(phase<16000){sum=0f;count=0}
        }
        fun finish()=output.copyOf(size)
    }
    suspend fun read(c:Context,uri:Uri):FloatArray {
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try{
            extractor.setDataSource(c,uri,null)
            val index=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
                ?:throw IOException("오디오 트랙 없음")
            val format=extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec=decoder
            decoder.configure(format,null,null,0);decoder.start()
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            var resampler=Resampler(rate)
            var inputEnd=false;var outputEnd=false
            val info=MediaCodec.BufferInfo()
            var idle=0
            while(!outputEnd){
                currentCoroutineContext().ensureActive()
                var moved=false
                if(!inputEnd){
                    val i=decoder.dequeueInputBuffer(10000)
                    if(i>=0){
                        val b=decoder.getInputBuffer(i)?:throw IOException("디코더 입력 오류")
                        b.clear();val n=extractor.readSampleData(b,0)
                        if(n<0){decoder.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true}
                        else{decoder.queueInputBuffer(i,0,n,extractor.sampleTime,0);extractor.advance()}
                        moved=true
                    }
                }
                val i=decoder.dequeueOutputBuffer(info,10000)
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    val f=decoder.outputFormat
                    channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val newRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if(newRate!=rate){if(resampler.size!=0)throw IOException("음원 샘플레이트 변경");rate=newRate;resampler=Resampler(rate)}
                    encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING))f.getInteger(MediaFormat.KEY_PCM_ENCODING)else AudioFormat.ENCODING_PCM_16BIT
                    moved=true
                }else if(i>=0){
                    try{
                        val b=decoder.getOutputBuffer(i)?.order(ByteOrder.nativeOrder())
                        if(b!=null&&info.size>0&&info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){
                            b.position(info.offset);b.limit(info.offset+info.size)
                            val bytes=when(encoding){AudioFormat.ENCODING_PCM_16BIT->2;AudioFormat.ENCODING_PCM_FLOAT->4;else->throw IOException("지원하지 않는 PCM 형식")}
                            if(channels<=0||rate<=0)throw IOException("잘못된 오디오 형식")
                            while(b.remaining()>=bytes*channels){
                                var v=0f
                                repeat(channels){v+=if(bytes==2)b.short/32768f else b.float}
                                resampler.add((v/channels).coerceIn(-1f,1f))
                            }
                        }
                        outputEnd=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    }finally{decoder.releaseOutputBuffer(i,false)}
                    moved=true
                }
                idle=if(moved)0 else idle+1
                if(idle>500)throw IOException("음원 디코딩 시간 초과")
            }
            return resampler.finish()
        }finally{runCatching{codec?.stop()};runCatching{codec?.release()};extractor.release()}
    }
}
