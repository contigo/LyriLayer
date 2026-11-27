package com.chaoseye.lyrilayer

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.provider.MediaStore
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.Build
import android.view.WindowInsets
import android.view.View
import android.util.DisplayMetrics
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.content.res.ColorStateList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel

data class NowPlaying(val track: Track? = null, val playing: Boolean = false, val position: Long = 0, val lyric: Lyric? = null, val index: Int = -1, val message: String = "삼성 뮤직 재생 대기", val overlayStatus: String = "삼성 뮤직 재생 대기", val libraryKey:String?=null)
internal data class SessionDiagnostics(val packages:List<String> = emptyList(),val selectedPackage:String="",val state:Int?=null,val ticking:Boolean=false,val error:String="")
object Playback { val now = MutableStateFlow(NowPlaying()); val connected = MutableStateFlow(false); val retry = MutableStateFlow(0L); internal val diagnostics=MutableStateFlow(SessionDiagnostics()) }
private const val SAMSUNG_PACKAGE="com.sec.android.app.music"
class MusicListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var fetch: Job? = null
    private var activeKey: String? = null
    private var lyric: Lyric? = null
    private var lines = emptyList<Line>()
    private var error = "가사 검색 중"
    private var overlay: LinearLayout? = null
    private var displayedContent: FloatingContent? = null
    private var resolvedKey:String?=null
    private var excludedTrack=false
    private var searching=false
    private var estimatedLines=emptyList<Line>()
    private var plainSnippet=""
    private var overlayFailure = ""
    private var overlayFailureAt = 0L
    private var windowContext:Context?=null
    private data class LayoutKey(val width:Int,val height:Int,val density:Float,val fontScale:Float,val position:Int,val alpha:Float)
    private var displayedLayout:LayoutKey?=null
    // Accessed on Main; the expensive scan and indexing run on IO.
    private var audioIndex:Map<String,List<Track>>?=null
    private var libraryGeneration=0L
    private var libraryObserverActive=false
    private val wm get() = overlayContext().getSystemService(WindowManager::class.java)
    private fun overlayContext():Context {
        windowContext?.let{return it}
        val c=if(Build.VERSION.SDK_INT>=30){
            val display=getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?:error("디스플레이를 찾을 수 없습니다")
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
        }else this
        windowContext=c
        return c
    }
    private suspend fun matchingTrack(track:Track):Track? {
        if(!FloatingPermissions.audioGranted(this)){
            audioIndex=null
            return null
        }
        val generation=libraryGeneration
        val index=audioIndex?:withContext(Dispatchers.IO){
            Library.allAudio(this@MusicListener).groupBy{MusicRules.norm(it.title)}
        }.also{if(libraryObserverActive&&generation==libraryGeneration)audioIndex=it}
        val matches=index[MusicRules.norm(track.title)].orEmpty().filter{
            (MusicRules.clean(track.artist).isBlank()||MusicRules.artistMatches(it.artist,track.artist))&&
                (track.duration<=0||kotlin.math.abs(it.duration-track.duration)<=3000)
        }
        return matches.firstOrNull{it.excluded}?:matches.distinctBy{it.key}.singleOrNull()
    }
    private fun clearTrack(){
        fetch?.cancel();fetch=null;activeKey=null;resolvedKey=null;excludedTrack=false;searching=false
        lyric=null;lines=emptyList();estimatedLines=emptyList();plainSnippet=""
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        monitor?.cancel();clearTrack();hide()
        Playback.connected.value=true
        monitor=scope.launch {
            var failures=0
            while(isActive){
                try{
                    observeSessions()
                    break
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    clearTrack();hide()
                    val message="음악 감지 오류: "+e.javaClass.simpleName
                    Playback.now.value=NowPlaying(message=message,overlayStatus=message)
                    Playback.diagnostics.value=SessionDiagnostics(error=message)
                    android.util.Log.w("LyriLayer","Session observation failed",e)
                    failures++
                    if(failures<=3)delay(1000L*failures)
                    else { val previous=Playback.retry.value;Playback.retry.first{it!=previous};failures=0 }
                }
            }
        }
    }
    private suspend fun observeSessions():Unit=coroutineScope {
        val manager=getSystemService(MediaSessionManager::class.java)
        val component=ComponentName(this@MusicListener,MusicListener::class.java)
        val handler=android.os.Handler(android.os.Looper.getMainLooper())
        val wake=Channel<Unit>(Channel.CONFLATED)
        val registered=linkedMapOf<android.media.session.MediaSession.Token,MediaController>()
        var sessions:List<MediaController> = emptyList()
        var refreshRequested=true
        var lastRetry=Playback.retry.value
        var force=true
        var sessionListenerAdded=false
        var receiverAdded=false
        var prefsAdded=false
        var opsAdded=false
        var observerAdded=false
        val prefs=getSharedPreferences("settings",Context.MODE_PRIVATE)
        val ops=getSystemService(android.app.AppOpsManager::class.java)
        fun signal(){wake.trySend(Unit)}
        val callback=object:MediaController.Callback(){
            override fun onPlaybackStateChanged(state:PlaybackState?){signal()}
            override fun onMetadataChanged(metadata:MediaMetadata?){signal()}
            override fun onSessionDestroyed(){refreshRequested=true;signal()}
        }
        fun updateSessions(next:List<MediaController>){
            sessions=next
            val samsung=next.filter{it.packageName==SAMSUNG_PACKAGE}.associateBy{it.sessionToken}
            registered.keys.filter{it !in samsung}.toList().forEach{key->registered.remove(key)?.unregisterCallback(callback)}
            samsung.forEach{(token,c)->if(token !in registered){c.registerCallback(callback,handler);registered[token]=c}}
        }
        val listener=MediaSessionManager.OnActiveSessionsChangedListener {
            refreshRequested=true;signal()
        }
        val settingsListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key->
            when(key){
                OverlayTiming.AUTO_SEARCH_KEY->{force=true;signal()}
                "overlay","overlay_test",OverlayContent.SHOW_MISSING,PlainSync.MODE_KEY,OverlayPosition.KEY,OverlayTransparency.KEY,null->signal()
                else->Unit
            }
        }
        val libraryObserver=object:ContentObserver(handler){
            override fun onChange(selfChange:Boolean){
                audioIndex=null;libraryGeneration++
                if(activeKey!=null){force=true;signal()}
            }
        }
        val receiver=object:android.content.BroadcastReceiver(){
            override fun onReceive(context:Context?,intent:android.content.Intent?){signal()}
        }
        val opListener=android.app.AppOpsManager.OnOpChangedListener { _,_->signal() }
        val retryJob=launch{
            Playback.retry.collect{value->
                if(value!=lastRetry){lastRetry=value;refreshRequested=true;force=true;signal()}
            }
        }
        val analysisJob=launch{
            var previousRelevant:AiAnalysisProgress?=null
            DeviceLyrics.activeAnalysis.collect{value->
                val relevant=value?.takeIf{it.trackKey==(resolvedKey?:activeKey)}
                if(relevant!=previousRelevant){previousRelevant=relevant;signal()}
            }
        }
        try{
            audioIndex=null;libraryGeneration++
            observerAdded=runCatching{
                contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,true,libraryObserver)
            }.isSuccess
            libraryObserverActive=observerAdded
            manager.addOnActiveSessionsChangedListener(listener,component,handler);sessionListenerAdded=true
            prefs.registerOnSharedPreferenceChangeListener(settingsListener);prefsAdded=true
            val filter=android.content.IntentFilter().apply{
                addAction(android.content.Intent.ACTION_SCREEN_ON);addAction(android.content.Intent.ACTION_SCREEN_OFF);addAction(android.content.Intent.ACTION_USER_PRESENT);addAction(android.content.Intent.ACTION_CONFIGURATION_CHANGED)
            }
            if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter)
            receiverAdded=true
            opsAdded=runCatching{ops.startWatchingMode(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,packageName,opListener)}.isSuccess
            var previousToken:android.media.session.MediaSession.Token?=null
            var frameErrors=0
            while(isActive){
                if(refreshRequested){
                    refreshRequested=false
                    updateSessions(manager.getActiveSessions(component))
                }
                val controller=registered.values.maxByOrNull{if(it.playbackState?.state==PlaybackState.STATE_PLAYING)1 else 0}
                val token=controller?.sessionToken
                if(token!=previousToken){previousToken=token;clearTrack();force=true}
                val sessionStatus=if(controller==null)"삼성 뮤직 재생 세션 없음" else "삼성 뮤직 곡 정보 대기"
                val state=controller?.playbackState?.state
                val frameStart=SystemClock.elapsedRealtime()
                val nextDelay=try{
                    val result=renderFrame(controller,sessionStatus,frameStart,force,::signal)
                    force=false;frameErrors=0
                    Playback.diagnostics.value=SessionDiagnostics(sessions.map{it.packageName}.distinct(),controller?.packageName.orEmpty(),state,result!=null)
                    result
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    frameErrors++;clearTrack();hide();force=true
                    val message="음악 상태 처리 오류: "+e.javaClass.simpleName
                    Playback.now.value=NowPlaying(message=message,overlayStatus=message)
                    Playback.diagnostics.value=SessionDiagnostics(sessions.map{it.packageName}.distinct(),controller?.packageName.orEmpty(),state,false,message)
                    android.util.Log.w("LyriLayer","Playback frame failed",e)
                    if(frameErrors<=3){withTimeoutOrNull(2000L){wake.receive()};continue}
                    null
                }
                // Sleep until the next lyric boundary or an event. No timer while inactive.
                if(nextDelay!=null){
                    val remaining=(nextDelay-(SystemClock.elapsedRealtime()-frameStart)).coerceAtLeast(1L)
                    withTimeoutOrNull(remaining){wake.receive()}
                }else wake.receive()
            }
        }finally{
            retryJob.cancel();analysisJob.cancel()
            if(observerAdded)runCatching{contentResolver.unregisterContentObserver(libraryObserver)}
            libraryObserverActive=false;audioIndex=null;libraryGeneration++
            if(sessionListenerAdded)runCatching{manager.removeOnActiveSessionsChangedListener(listener)}
            registered.values.forEach{runCatching{it.unregisterCallback(callback)}}
            if(receiverAdded)runCatching{unregisterReceiver(receiver)}
            if(prefsAdded)prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            if(opsAdded)runCatching{ops.stopWatchingMode(opListener)}
            wake.close()
        }
    }
    private fun renderFrame(controller:MediaController?,sessionStatus:String,now:Long,force:Boolean,onChanged:()->Unit):Long? {
        val m=controller?.metadata
        val state=controller?.playbackState
        val title=m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank{m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()}
        val prefs=getSharedPreferences("settings",Context.MODE_PRIVATE)
        val testMode=prefs.getBoolean("overlay_test",false)
        val playing=state?.state==PlaybackState.STATE_PLAYING
        val permission=Settings.canDrawOverlays(this@MusicListener)
        val interactive=getSystemService(PowerManager::class.java).isInteractive
        val enabled=prefs.getBoolean("overlay",false)
        if(!playing||!interactive||(!enabled&&!testMode)||!permission){
            clearTrack();hide()
            val track=if(title.isBlank())null else Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?:0)
            val reason=when{
                !permission->"다른 앱 위에 표시 권한 필요"
                !interactive->"화면 꺼짐"
                !playing->if(controller==null)sessionStatus else "재생 대기 / 일시 정지"
                else->"플로팅 가사 스위치가 꺼져 있습니다"
            }
            Playback.now.value=NowPlaying(track=track,playing=playing,position=state?.position?.coerceAtLeast(0)?:0,message=reason,overlayStatus=reason)
            return null
        }
        // Test uses metadata only for the title; missing metadata never blocks the overlay.
        if(testMode){
            clearTrack()
            val track=if(title.isBlank())null else Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?:0)
            val reason=OverlayTest.status(playing,permission,interactive)
            val status=if(reason==null){
                if(show(OverlayTest.text(title.ifBlank{m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()})))"테스트 플로팅 표시 중" else overlayFailure.ifBlank{"오버레이 표시 실패"}
            }else{hide();if(!playing&&controller==null)sessionStatus else reason}
            Playback.now.value=NowPlaying(track=track,playing=playing,position=state?.position?.coerceAtLeast(0)?:0, message="플로팅 테스트 모드",overlayStatus=status)
            return OverlayTiming.SAFETY_MS
        }
        if(title.isBlank() || state==null) {
            clearTrack()
            val fallback=OverlayContent.decide(prefs.getBoolean("overlay",false),playing,permission,interactive,false,prefs.getBoolean(OverlayContent.SHOW_MISSING,true),m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),"",null,false,null,false,"곡 정보 대기")
            val status=if(fallback!=null){if(show(fallback))"곡 정보 플로팅 표시 중" else overlayFailure.ifBlank{"오버레이 표시 실패"}}else{hide();sessionStatus}
            Playback.now.value=NowPlaying(playing=playing,message=sessionStatus,overlayStatus=status)
            return OverlayTiming.SAFETY_MS
        }
        val track=Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
        if(activeKey!=track.key||force) {
            fetch?.cancel();activeKey=track.key;resolvedKey=null;lyric=null;lines=emptyList();estimatedLines=emptyList();plainSnippet=""
            excludedTrack=MusicRules.excluded("", "",track.title);searching=!excludedTrack
            error=if(excludedTrack)"녹음 및 기타 오디오는 제외됩니다" else "가사 검색 중"
            val allowOnline=prefs.getBoolean(OverlayTiming.AUTO_SEARCH_KEY,true)
            fetch=scope.launch {
                try {
                    if(excludedTrack)return@launch
                    val match=matchingTrack(track)
                    val actual=match ?: run { searching=false;error="음악 목록에서 곡을 확인할 수 없습니다 · 음악 접근 권한 확인";return@launch }
                    resolvedKey=actual.key
                    if(actual.excluded){excludedTrack=true;searching=false;error="녹음 및 기타 오디오는 제외됩니다";return@launch}
                    val dao=LyricsDb.get(this@MusicListener).dao()
                    fun apply(l: Lyric?) {
                        lyric=l;lines=Lrc.parse(l?.synced.orEmpty());estimatedLines=if(lines.isEmpty())PlainSync.estimated(l?.plain.orEmpty(),actual.duration) else emptyList();plainSnippet=PlainSync.static(l?.plain.orEmpty())
                        error=when { l==null -> "가사 검색 중"; l.status=="NOT_FOUND" -> "가사를 찾지 못했습니다"; l.status=="INSTRUMENTAL" -> "연주곡"; lines.isEmpty()&&l.plain.isNotBlank() -> "일반 가사 · 시간 정보 없음"; else -> "" }
                    }
                    val cached=dao.get(actual.key)
                    apply(cached)
                    searching=cached?.status!="FOUND"&&cached?.status!="INSTRUMENTAL"
                    onChanged()
                    coroutineScope {
                        launch {
                            if(cached?.status!="FOUND"&&cached?.status!="INSTRUMENTAL"){
                                try { Repository.resolve(this@MusicListener,actual,allowOnline=allowOnline) }
                                catch(e:CancellationException){throw e}
                                catch(e:Exception){if(lyric?.status!="FOUND")error="가사 검색 오류 · ${e.message ?: "앱에서 다시 검색하세요"}"}
                                finally{if(isActive){searching=false;if(!allowOnline&&lyric==null)error="자동 온라인 검색 꺼짐";onChanged()}}
                            }
                        }
                        dao.observeKey(actual.key).collect { current ->
                            if(current!=lyric){apply(current);onChanged()}
                        }
                    }
                } catch(e: CancellationException) { throw e } catch(e: Exception) { searching=false;error="가사 검색 오류 · ${e.message ?: "앱에서 다시 검색하세요"}" }
                finally{if(isActive)onChanged()}
            }
        }
        val delta=if(playing && state.lastPositionUpdateTime>0) ((now-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed).toLong() else 0
        val position=(state.position+delta).coerceIn(0,if(track.duration>0) track.duration else Long.MAX_VALUE)
        val index=Lrc.index(lines,position-(lyric?.offset ?: 0))
        val reason=when {
            !enabled -> "플로팅 가사 스위치가 꺼져 있습니다"
            !permission -> "다른 앱 위에 표시 권한 필요"
            !playing -> "재생 대기 / 일시 정지"
            !interactive -> "화면 꺼짐"
            excludedTrack -> "녹음 및 기타 오디오는 제외됩니다"
            lines.isEmpty() -> error.ifBlank{"가사 없음"}
            index !in lines.indices -> "첫 가사 시작 대기"
            lines[index].text.isBlank() -> "가사 없는 구간"
            else -> ""
        }
        val analysis=DeviceLyrics.activeAnalysis.value
        val aiPercent=analysis?.takeIf{it.trackKey==(resolvedKey?:track.key)}?.percent
        val baseContent=OverlayContent.decide(enabled,playing,permission,interactive,excludedTrack,
            prefs.getBoolean(OverlayContent.SHOW_MISSING,true),track.title,track.artist,
            lines.getOrNull(index)?.text,lines.isNotEmpty(),lyric?.status,searching,reason,aiPercent)
        val plainMode=prefs.getString(PlainSync.MODE_KEY,PlainSync.ESTIMATE)
        val estimated=estimatedLines.getOrNull(Lrc.index(estimatedLines,position))
        val content=if(enabled&&playing&&permission&&interactive&&!excludedTrack&&lines.isEmpty()&&lyric?.status=="FOUND"&&aiPercent==null&&plainSnippet.isNotBlank()){
            when(plainMode){
                PlainSync.ESTIMATE -> estimated?.let{FloatingContent(it.text,"일반 가사 · 추정 싱크","neutral")}?:FloatingContent(plainSnippet,"일반 가사 · 시간 정보 없음","neutral")
                PlainSync.STATIC -> FloatingContent(plainSnippet,"일반 가사 · 시간 정보 없음","neutral")
                else -> baseContent
            }
        }else baseContent
        val status=if(content!=null){
            if(show(content))if(content.detail.isEmpty())"플로팅 가사 표시 중" else "곡 정보 플로팅 표시 중"
            else overlayFailure.ifBlank{"오버레이 표시 실패"}
        }else{hide();reason}
        Playback.now.value=NowPlaying(track,playing,position,lyric,index,error,status,resolvedKey)
        val timedLines=when{
            lines.isNotEmpty()->lines
            plainMode==PlainSync.ESTIMATE&&aiPercent==null->estimatedLines
            else->emptyList()
        }
        val offset=if(lines.isNotEmpty())lyric?.offset?:0L else 0L
        return OverlayTiming.nextDelay(timedLines,position,offset,state.playbackSpeed,state.lastPositionUpdateTime>0,track.duration)
    }
    // Use the current display area so rotation and screen size changes are reflected.
    @Suppress("DEPRECATION")
    private fun overlayArea():Pair<Int,Int> {
        if(Build.VERSION.SDK_INT>=30){
            val metrics=wm.currentWindowMetrics
            val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return (metrics.bounds.width()-insets.left-insets.right).coerceAtLeast(1) to
                (metrics.bounds.height()-insets.top-insets.bottom).coerceAtLeast(1)
        }
        val metrics=DisplayMetrics()
        wm.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }
    private fun show(value:String):Boolean=show(FloatingContent(value))
    private fun show(content:FloatingContent):Boolean {
        if(overlayFailure.isNotBlank()&&SystemClock.elapsedRealtime()-overlayFailureAt<2000)return false
        try {
        val ctx=overlayContext()
        val dp=ctx.resources.displayMetrics.density
        val (areaWidth,areaHeight)=overlayArea()
        val width=(areaWidth*0.88f).toInt().coerceAtLeast(1)
        val prefs=getSharedPreferences("settings",Context.MODE_PRIVATE)
        val percent=OverlayPosition.normalize(prefs.getInt(OverlayPosition.KEY,OverlayPosition.DEFAULT))
        val windowAlpha=OverlayTransparency.windowAlpha(prefs.getInt(OverlayTransparency.KEY,OverlayTransparency.DEFAULT))
        val existing=overlay
        val key=LayoutKey(width,areaHeight,dp,ctx.resources.configuration.fontScale,percent,windowAlpha)
        val previous=displayedLayout
        val contentChanged=existing==null||displayedContent!=content
        if(!contentChanged&&previous==key)return true
        val view=existing?:LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            background=GradientDrawable().apply { setColor(0xE6000000.toInt());cornerRadius=16*dp }
            addView(TextView(ctx).apply{setTextColor(Color.WHITE);textSize=18f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2))
            addView(TextView(ctx).apply{textSize=14f;gravity=Gravity.CENTER;visibility=View.GONE},LinearLayout.LayoutParams(-1,-2))
            addView(ProgressBar(ctx,null,android.R.attr.progressBarStyleHorizontal).apply{
                max=100;progressTintList=ColorStateList.valueOf(0xFFCEB5FF.toInt());visibility=View.GONE
            },LinearLayout.LayoutParams(-1,(5*dp).toInt()).apply{topMargin=(6*dp).toInt()})
        }
            if(contentChanged){
                val primary=view.getChildAt(0) as TextView
                val detail=view.getChildAt(1) as TextView
                val bar=view.getChildAt(2) as ProgressBar
                primary.text=content.text
                detail.text=content.detail
                detail.setTextColor(when(content.tone){"ai"->0xFFCEB5FF.toInt();"error"->0xFFFFAAAA.toInt();else->0xFFDDDDDD.toInt()})
                detail.visibility=if(content.detail.isBlank())View.GONE else View.VISIBLE
                bar.visibility=if(content.progress==null)View.GONE else View.VISIBLE
                bar.progress=content.progress?:0
                displayedContent=content
            }
            val oldParams=existing?.layoutParams as? WindowManager.LayoutParams
            // A position or transparency change does not require text remeasurement.
            if(contentChanged||previous==null||previous.width!=width||previous.height!=areaHeight||previous.density!=dp||previous.fontScale!=key.fontScale){
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(areaHeight,View.MeasureSpec.AT_MOST))
            }
            val y=OverlayPosition.topOffset(percent,areaHeight,view.measuredHeight,(8*dp).toInt())
            if(oldParams==null){
                val params=WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    this.y=y;alpha=windowAlpha
                    if(Build.VERSION.SDK_INT>=30){
                        setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        setFitInsetsIgnoringVisibility(true)
                    }
                }
                wm.addView(view,params);overlay=view
            }else if(oldParams.y!=y||oldParams.width!=width||oldParams.alpha!=windowAlpha){
                oldParams.y=y;oldParams.width=width;oldParams.alpha=windowAlpha;wm.updateViewLayout(view,oldParams)
            }
            displayedLayout=key
            overlayFailure=""
            return true
        }catch(e:Exception){
            hide();overlayFailureAt=SystemClock.elapsedRealtime()
            overlayFailure="오버레이 표시 실패: "+e.javaClass.simpleName
            android.util.Log.w("LyriLayer","Overlay layout failed",e);return false
        }
    }
    private fun hide() { overlay?.let { runCatching { wm.removeViewImmediate(it) } }; overlay=null;displayedContent=null;displayedLayout=null }
    override fun onListenerDisconnected() {
        Playback.connected.value=false;monitor?.cancel();monitor=null;clearTrack();Playback.diagnostics.value=SessionDiagnostics()
        hide();Playback.now.value=NowPlaying(message="음악 감지 서비스 연결 대기",overlayStatus="음악 감지 서비스 연결 대기")
        super.onListenerDisconnected()
        // Reconnect after system disconnection only while the user still grants access.
        FloatingPermissions.rebind(this)
    }
    override fun onDestroy() { Playback.connected.value=false;scope.cancel(); hide();windowContext=null;Playback.diagnostics.value=SessionDiagnostics(); Playback.now.value=NowPlaying(); super.onDestroy() }
}
