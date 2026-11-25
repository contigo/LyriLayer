package com.chaoseye.lyrilayer

import android.content.Context
import android.net.Uri
import com.chaoseye.recognitionkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class FingerprintRow(val state:String,val message:String="")
internal data class FingerprintLive(val running:Boolean=false,val done:Int=0,val total:Int=0,val current:String="",val message:String="",val rows:Map<String,FingerprintRow> = emptyMap())
internal object MusicRecognitionBatch{
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private val _live=MutableStateFlow(FingerprintLive())
    val live=_live.asStateFlow()
    fun reset(){if(!_live.value.running)_live.value=FingerprintLive()}
    fun stop(){job?.cancel()}
    fun start(context:Context,tracks:List<Track>,retry:Boolean,lyrics:Boolean,consent:Boolean){
        if(job?.isActive==true||_live.value.running||!consent)return
        val c=context.applicationContext
        val input=tracks.filter{!it.excluded&&it.uri.isNotBlank()&&it.duration>=2000}.distinctBy{it.key}
        if(input.isEmpty())return
        _live.value=FingerprintLive(running=true,total=input.size,rows=input.associate{it.key to FingerprintRow("pending")})
        job=scope.launch(start=CoroutineStart.LAZY){
            val kit=RecognitionKit(c,ShazamProvider(true))
            try{
                for(t in input){
                    ensureActive()
                    _live.update{it.copy(current=t.title,rows=it.rows+(t.key to FingerprintRow("running")))}
                    var halt=false
                    val row=try{
                        val old=MusicInfoDb.get(c).dao().get(t.key)
                        if(old?.userConfirmed==true||old?.provider=="SHAZAM_FINGERPRINT")FingerprintRow("done","기존 확인 정보 유지")
                        else when(val result=kit.recognize(Uri.parse(t.uri),t.duration,RecognitionConfig(retryOtherSections=retry))){
                            is RecognitionResult.Success->{
                                val match=result.track
                                val saved=MusicInfoRepository.acceptFingerprint(c,t,match)
                                var message=if(saved)match.title+" · "+match.artist else "사용자 확인 정보 유지"
                                if(saved&&lyrics){
                                    try{Repository.resolve(c,t,true);Playback.retry.value++}
                                    catch(e:CancellationException){throw e}
                                    catch(e:Exception){message+=" · 가사 검색 실패"}
                                }
                                FingerprintRow("done",message)
                            }
                            RecognitionResult.NoMatch->FingerprintRow("failed","일치하는 음원 없음")
                            is RecognitionResult.Failure->{halt=result.haltBatch;FingerprintRow("failed",result.message)}
                        }
                    }catch(e:CancellationException){throw e}catch(e:Exception){FingerprintRow("failed",e.message?:"검색 오류")}
                    _live.update{it.copy(done=it.done+1,rows=it.rows+(t.key to row),message=if(halt)row.message else it.message)}
                    if(halt)break
                }
            }catch(e:CancellationException){_live.update{it.copy(message="검색 중단")};throw e}
            finally{
                kit.close()
                _live.update{v->v.copy(running=false,current="",message=v.message.ifBlank{"검색 종료"},rows=v.rows.mapValues{if(it.value.state=="running")FingerprintRow("pending")else it.value})}
                job=null
            }
        }.also{it.start()}
    }
}
