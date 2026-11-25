package com.chaoseye.recognitionkit

import android.content.Context
import android.media.*
import android.net.Uri
import kotlinx.coroutines.*
import java.io.IOException
import java.nio.ByteOrder

internal object AudioSampleExtractor {
    private const val TARGET=16000
    suspend fun read(c:Context,uri:Uri,startMs:Long,lengthMs:Long):AudioSample=withContext(Dispatchers.IO){
        require(startMs>=0&&lengthMs in 2000..12000)
        val startUs=startMs*1000;val endUs=(startMs+lengthMs)*1000
        val result=ShortArray((lengthMs*TARGET/1000).toInt())
        var count=0
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try{
            extractor.setDataSource(c,uri,null)
            val index=(0 until extractor.trackCount).firstOrNull{extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true}?:throw IOException("오디오 트랙 없음")
            val format=extractor.getTrackFormat(index)
            extractor.selectTrack(index);extractor.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec=decoder;decoder.configure(format,null,null,0);decoder.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            var phase=0L;var sum=0.0;var samples=0
            var inputEnd=false;var outputEnd=false;var idle=0
            val info=MediaCodec.BufferInfo()
            while(!outputEnd&&count<result.size){
                currentCoroutineContext().ensureActive()
                var moved=false
                if(!inputEnd){
                    val i=decoder.dequeueInputBuffer(10000)
                    if(i>=0){
                        val buffer=decoder.getInputBuffer(i)?:throw IOException("디코더 입력 없음")
                        buffer.clear();val pts=extractor.sampleTime
                        val n=if(pts<0||pts>endUs+500000)-1 else extractor.readSampleData(buffer,0)
                        if(n<0){decoder.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnd=true}
                        else{decoder.queueInputBuffer(i,0,n,pts,0);extractor.advance()}
                        moved=true
                    }
                }
                val i=decoder.dequeueOutputBuffer(info,10000)
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    val out=decoder.outputFormat
                    val newRate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if(count>0&&newRate!=rate)throw IOException("샘플레이트 변경")
                    rate=newRate;channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding=if(out.containsKey(MediaFormat.KEY_PCM_ENCODING))out.getInteger(MediaFormat.KEY_PCM_ENCODING)else AudioFormat.ENCODING_PCM_16BIT
                    moved=true
                }else if(i>=0){
                    try{
                        val buffer=decoder.getOutputBuffer(i)?.order(ByteOrder.nativeOrder())
                        if(buffer!=null&&info.size>0&&info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0){
                            require(rate in 8000..384000&&channels in 1..32)
                            val bytes=when(encoding){AudioFormat.ENCODING_PCM_16BIT->2;AudioFormat.ENCODING_PCM_FLOAT->4;else->throw IOException("지원하지 않는 PCM 형식")}
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            var frame=0L
                            while(buffer.remaining()>=bytes*channels&&count<result.size){
                                val pts=info.presentationTimeUs+frame*1000000/rate;frame++
                                if(pts>=endUs){outputEnd=true;break}
                                var v=0.0
                                repeat(channels){v+=if(bytes==2)buffer.short.toDouble()/32768 else buffer.float.toDouble()}
                                if(pts<startUs)continue
                                sum+=(v/channels).coerceIn(-1.0,1.0);samples++;phase+=TARGET
                                if(phase>=rate){
                                    val value=(sum/samples*32767).toInt().coerceIn(-32768,32767).toShort()
                                    while(phase>=rate&&count<result.size){result[count++]=value;phase-=rate}
                                    sum=0.0;samples=0
                                }
                            }
                        }
                        outputEnd=outputEnd||info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    }finally{decoder.releaseOutputBuffer(i,false)}
                    moved=true
                }
                idle=if(moved)0 else idle+1
                if(idle>200)throw IOException("음원 디코딩 시간 초과")
            }
            if(count<TARGET*2)throw IOException("음원 샘플이 너무 짧습니다")
            AudioSample(result.copyOf(count),count*1000L/TARGET)
        }finally{runCatching{codec?.stop()};runCatching{codec?.release()};extractor.release()}
    }
}
