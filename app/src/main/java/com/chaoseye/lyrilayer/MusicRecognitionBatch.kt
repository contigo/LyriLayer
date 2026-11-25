package com.chaoseye.lyrilayer
import android.content.Context
import android.net.Uri
import com.chaoseye.recognitionkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class FingerprintRow(val state:String,val message:String="",val identity:String="",val lyrics:String="")
internal data class FingerprintLive(val running:Boolean=false,val done:Int=0,val total:Int=0,val current:String="",val message:String="",val rows:Map<String,FingerprintRow> = emptyMap(),val currentKey:String?=null,val stage:String="",val stopping:Boolean=false)
internal object MusicRecognitionBatch{
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private val _live=MutableStateFlow(FingerprintLive())
    val live=_live.asStateFlow()
    fun reset(){if(!_live.value.running)_live.value=FingerprintLive()}
    fun stop(){if(_live.value.running){_live.update{it.copy(stopping=true)};job?.cancel()}}
    fun start(context:Context,tracks:List<Track>,retry:Boolean,consent:Boolean){
        if(job?.isActive==true||_live.value.running||!consent)return
        val c=context.applicationContext
        val input=tracks.filter{!it.excluded&&it.uri.isNotBlank()&&it.duration>=2000&&!MusicRules.excluded("",it.fileName,it.title)}.distinctBy{it.key}
        if(input.isEmpty())return
        _live.value=FingerprintLive(running=true,total=input.size,rows=input.associate{it.key to FingerprintRow("pending")})
        val task=scope.launch(start=CoroutineStart.LAZY){
            val kit=RecognitionKit(c,ShazamProvider(true))
            try{
                val infoDao=MusicInfoDb.get(c).dao()
                val lyricDao=LyricsDb.get(c).dao()
                for(t in input){
                    ensureActive()
                    fun stage(phase:String,identity:String="",lyric:String=""){
                        _live.update{it.copy(current=t.title,currentKey=t.key,stage=phase,rows=it.rows+(t.key to FingerprintRow("running",identity=identity,lyrics=lyric)))}
                    }
                    var identified=false
                    var identityLabel="음원 정보 미확인"
                    var halt=false
                    stage("음원 지문 검색","검색 중","대기")
                    try{
                        if(FingerprintPipelineRules.identified(infoDao.get(t.key))){
                            identified=true;identityLabel="기존 확인 정보 유지"
                        }else when(val result=kit.recognize(Uri.parse(t.uri),t.duration,RecognitionConfig(retryOtherSections=retry))){
                            is RecognitionResult.Success->{
                                val saved=MusicInfoRepository.acceptFingerprint(c,t,result.track)
                                identified=saved||FingerprintPipelineRules.identified(infoDao.get(t.key))
                                identityLabel=if(saved)result.track.title+" · "+result.track.artist else "사용자 확인 정보 유지"
                            }
                            RecognitionResult.NoMatch->identityLabel="일치하는 음원 없음 · 기존 정보로 가사 검색"
                            is RecognitionResult.Failure->{halt=result.haltBatch;identityLabel=result.message}
                        }
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){identityLabel=e.message?:"음원 검색 오류"}
                    if(halt){
                        _live.update{it.copy(message="검색 중단: "+identityLabel,rows=it.rows+(t.key to FingerprintRow("failed",identity=identityLabel,lyrics="미실행 · 재시도 가능")))}
                        break
                    }
                    ensureActive()
                    stage("가사 검색 및 저장",identityLabel,"검색 중")
                    var lyric=lyricDao.get(t.key)
                    var lyricLabel=FingerprintPipelineRules.lyricLabel(lyric)
                    try{
                        if(lyric?.edited!=true&&!FingerprintPipelineRules.lyricReady(lyric)){
                            lyric=withTimeout(65_000){Repository.resolve(c,t,true)}
                            Playback.retry.value++
                        }
                        lyricLabel=FingerprintPipelineRules.lyricLabel(lyric)
                    }catch(e:TimeoutCancellationException){lyricLabel="가사 검색 시간 초과"}
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){lyricLabel="가사 검색 실패: "+(e.message?:"오류").take(180)}
                    ensureActive()
                    val ready=FingerprintPipelineRules.lyricReady(lyricDao.get(t.key))
                    val row=FingerprintRow(FingerprintPipelineRules.outcome(identified,ready),identity=identityLabel,lyrics=lyricLabel)
                    _live.update{it.copy(done=it.done+1,rows=it.rows+(t.key to row))}
                }
            }catch(e:CancellationException){_live.update{it.copy(message="검색 중단")};throw e}
            catch(e:Exception){_live.update{it.copy(message="검색 오류: "+(e.message?:"오류"))}}
            finally{
                kit.close()
                _live.update{v->v.copy(running=false,stopping=false,current="",currentKey=null,stage="",message=v.message.ifBlank{"검색 종료"},rows=v.rows.mapValues{if(it.value.state=="running")it.value.copy(state="pending",message="중단 · 다시 시작 가능")else it.value})}
                job=null
            }
        }
        job=task;task.start()
    }
}
