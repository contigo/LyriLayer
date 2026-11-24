package com.chaoseye.lyrilayer

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class QueueLive(val running:Boolean=false, val stopping:Boolean=false, val key:String?=null, val percent:Int=0, val stage:String="", val preview:String="",val startedAt:Long=0L,val completedCount:Int=0,val elapsedTotal:Long=0L)
internal object AiQueue {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var task: Job? = null
    private var initialized = false
    private val _live = MutableStateFlow(QueueLive())
    val live = _live.asStateFlow()
    private val _notice = MutableStateFlow("")
    val notice = _notice.asStateFlow()
    private suspend fun init(c:Context) {
        if (!initialized) { AiQueueDb.get(c).dao().recover(); initialized=true }
    }
    fun initialize(context:Context) { scope.launch { mutex.withLock { init(context.applicationContext) } } }
    fun enqueue(context:Context, tracks:List<Track>, language:String) {
        val c=context.applicationContext
        scope.launch {
            try { mutex.withLock {
                init(c)
                val q=AiQueueDb.get(c).dao();val db=LyricsDb.get(c).dao()
                val existing=q.all().map{it.key}.toSet()
                val now=System.currentTimeMillis()
                val additions=tracks.distinctBy{it.key}.mapIndexedNotNull{i,t->
                    if(t.key in existing)return@mapIndexedNotNull null
                    val kind=AiQueueRules.kind(t,db.get(t.key))?:return@mapIndexedNotNull null
                    AiQueueEntry(t.key,t.title,t.artist,kind,now+i,language=language)
                }
                q.insert(additions);_notice.value="대기열에 "+additions.size+"곡 추가"
            }}catch(e:CancellationException){throw e}catch(e:Exception){_notice.value="추가 실패: "+e.message}
        }
    }
    fun start(context:Context) {
        if(task?.isActive==true||_live.value.running)return
        val c=context.applicationContext
        if(!DeviceLyrics.supported()||!DeviceLyrics.ready(c)){_notice.value="AI 모델을 먼저 다운로드하세요.";return}
        if(!FloatingPermissions.audioGranted(c)){_notice.value="음악 접근 권한을 허용하세요.";return}
        _live.value=QueueLive(running=true,stage="대기열 준비")
        task=scope.launch {
            var active:AiQueueEntry?=null
            val dao=AiQueueDb.get(c).dao()
            try {
                mutex.withLock {init(c)}
                val tracks=withContext(Dispatchers.IO){Library.scan(c).associateBy{it.key}}
                var completedCount=0
                var elapsedTotal=0L
                while(isActive) {
                    val item=mutex.withLock {
                        val next=dao.nextPending()?:return@withLock null
                        next.copy(state="running",message="분석 준비").also{active=it;dao.update(it)}
                    }?:break
                    val t=tracks[item.key]
                    val db=LyricsDb.get(c).dao()
                    try {
                        val before=db.get(item.key)
                        val kind=t?.let{AiQueueRules.kind(it,before)}
                        if(t==null||kind==null) {
                            dao.update(item.copy(state="skipped",message="음원 없음 또는 이미 싱크 등록됨"));active=null;continue
                        }
                        val started=android.os.SystemClock.elapsedRealtime()
                        _live.value=QueueLive(running=true,key=item.key,stage="AI 분석 준비",startedAt=started,completedCount=completedCount,elapsedTotal=elapsedTotal)
                        val raw=DeviceLyrics.extract(c,t,item.language,preview={text->
                            _live.update{old->if(old.key==item.key&&!old.stopping)old.copy(preview=text)else old}
                        }){n,stage->
                            _live.update{old->if(old.key==item.key&&!old.stopping)old.copy(percent=n.coerceIn(0,100),stage=stage)else old}
                        }
                        currentCoroutineContext().ensureActive()
                        val heard=Lrc.parse(raw).filter{it.text.isNotBlank()&&it.ms<t.duration}
                        if(heard.isEmpty())error("인식된 가사 없음")
                        val original=before?.plain.orEmpty()
                        val marks=if(kind==AiQueueRules.ALIGN)withContext(Dispatchers.Default){PlainSync.align(original,heard,t.duration)}else emptyList()
                        if(kind==AiQueueRules.ALIGN&&marks.isEmpty())error("기존 가사와 일치하는 인식 구간 없음")
                        val draft=if(kind==AiQueueRules.ALIGN)PlainSync.lrc(marks.map{Line(it.ms,it.text)})else PlainSync.lrc(heard)
                        val plain=if(kind==AiQueueRules.ALIGN)original else heard.joinToString("\n"){it.text}
                        val uncertain=marks.count{!it.matched}
                        val elapsed=android.os.SystemClock.elapsedRealtime()-started
                        completedCount++;elapsedTotal+=elapsed
                        val resultMessage=if(kind==AiQueueRules.ALIGN)"매칭 "+(marks.size-uncertain)+"/"+marks.size+"줄 · 검토 필요" else "AI 생성 가사 · 검토 필요"
                        val result=item.copy(kind=kind,state="review",draft=draft,plain=plain,baseline=AiQueueRules.fingerprint(before),uncertain=uncertain,message=resultMessage+" · "+AiPerformance.time(elapsed))
                        // Persist the review draft before attempting automatic registration.
                        mutex.withLock {
                            dao.update(result)
                            val prefs=c.getSharedPreferences("settings",Context.MODE_PRIVATE)
                            val allowed=AiAutoSave.allowed(kind,
                                prefs.getBoolean(AiAutoSave.EXTRACT_KEY,AiAutoSave.DEFAULT_ENABLED),
                                prefs.getBoolean(AiAutoSave.ALIGN_KEY,AiAutoSave.DEFAULT_ENABLED),
                                prefs.getBoolean(AiAutoSave.REVIEW_UNCERTAIN_KEY,AiAutoSave.DEFAULT_ENABLED),
                                if(kind==AiQueueRules.ALIGN)marks.size else heard.size,uncertain)
                            if(allowed){
                                try{saveResult(c,dao,result,draft,true)}
                                catch(e:CancellationException){throw e}
                                catch(e:Exception){
                                    // Keep a successful analysis available for manual review on save failure.
                                    dao.update(result.copy(message=("자동 저장 보류: "+(e.message?:"저장 오류")).take(300)))
                                }
                            }
                        }
                        _live.update{old->old.copy(completedCount=completedCount,elapsedTotal=elapsedTotal)}
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){dao.update(item.copy(state="failed",message=(e.message?:"분석 실패").take(300)))}
                    catch(e:LinkageError){dao.update(item.copy(state="failed",message="AI 엔진 오류"))}
                    active=null
                }
                _notice.value="대기열 처리 종료 · 저장 및 검토 결과를 확인하세요."
            }catch(e:CancellationException){_notice.value="분석 중지 · 남은 곡은 다시 시작할 수 있습니다.";throw e}
            catch(e:Exception){_notice.value="대기열 오류: "+e.message}
            finally {
                try{withContext(NonCancellable){active?.let{item->
                    if(dao.get(item.key)?.state=="running")dao.update(item.copy(state="pending",message="분석 중단 · 다시 시작 가능"))
                }}}finally{_live.value=QueueLive();task=null}
            }
        }
    }
    fun pause(){if(_live.value.running){_live.value=_live.value.copy(stopping=true,stage="현재 구간 종료 후 중지");task?.cancel()}}
    fun retry(context:Context)=mutate(context){it.retryFailed()}
    fun remove(context:Context,key:String)=mutate(context){it.remove(key)}
    fun clearFinished(context:Context)=mutate(context){it.clearFinished()}
    private fun mutate(context:Context, action:suspend(AiQueueDao)->Unit){
        scope.launch { try{mutex.withLock{val c=context.applicationContext;init(c);action(AiQueueDb.get(c).dao())}}catch(e:CancellationException){throw e}catch(e:Exception){_notice.value="대기열 변경 실패: "+e.message} }
    }
    // Called under queue mutex; use the existing lyrics DB transaction to prevent races with edits/searches.
    private suspend fun saveResult(c:Context,queue:AiQueueDao,item:AiQueueEntry,raw:String,automatic:Boolean){
        val parsed=Lrc.parse(raw)
        if(parsed.none{it.text.isNotBlank()})error("유효한 LRC 가사가 없습니다.")
        if(item.kind==AiQueueRules.ALIGN&&parsed.map{it.text}!=PlainSync.rows(item.plain))error("자동 싱크는 원본 가사 순서와 문장을 유지해야 합니다.")
        val result=Lyric(item.key,if(item.kind==AiQueueRules.ALIGN)item.plain else parsed.joinToString("\n"){it.text},raw,if(item.kind==AiQueueRules.ALIGN)PlainSync.AI_SOURCE else LyricDisplay.AI_SOURCE,"FOUND",edited=true)
        val db=LyricsDb.get(c)
        db.withTransaction {
            val old=db.dao().get(item.key)
            // Idempotent if the lyrics write succeeded before a process interruption in the separate queue DB.
            if(AiQueueRules.fingerprint(old)!=AiQueueRules.fingerprint(result)){
                if(AiQueueRules.fingerprint(old)!=item.baseline)error("분석 후 기존 가사가 변경되어 저장하지 않았습니다.")
                db.dao().put(result)
            }
        }
        queue.update(item.copy(state="saved",draft="",message=if(automatic)"자동 저장 완료" else "검토 후 저장 완료"))
        Playback.retry.value++
    }
    fun approve(context:Context,key:String,editedDraft:String){
        val c=context.applicationContext
        scope.launch {
            try { mutex.withLock {
                init(c)
                val queue=AiQueueDb.get(c).dao(); val item=queue.get(key)?:return@withLock
                if(item.state!="review")return@withLock
                saveResult(c,queue,item,editedDraft,false)
                _notice.value="가사 저장 완료"
            }}catch(e:CancellationException){throw e}catch(e:Exception){_notice.value="저장 실패: "+e.message}
        }
    }
}
