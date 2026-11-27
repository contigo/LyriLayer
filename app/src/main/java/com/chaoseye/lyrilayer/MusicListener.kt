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
    private var error = "저장된 가사 확인 중"
    private var overlay: LinearLayout? = null
    private var displayedContent: FloatingContent? = null
    private var resolvedKey:String?=null
    private var excludedTrack=false
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
        fetch?.cancel();fetch=null;activeKey=null;resolvedKey=null;excludedTrack=false
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
                "overlay",PlainSync.MODE_KEY,OverlayPosition.KEY,OverlayTransparency.KEY,null->signal()
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
            retryJob.cancel()
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
        val playing=state?.state==PlaybackState.STATE_PLAYING
        val permission=Settings.canDrawOverlays(this@MusicListener)
        val interactive=getSystemService(PowerManager::class.java).isInteractive
        val enabled=prefs.getBoolean("overlay",false)
        if(!playing||!interactive||!enabled||!permission){
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
        if(title.isBlank() || state==null) {
            clearTrack();hide()
            Playback.now.value=NowPlaying(playing=playing,message=sessionStatus,overlayStatus=sessionStatus)
            return null
        }
        val track=Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
        if(activeKey!=track.key||force) {
            clearTrack();hide();activeKey=track.key
            excludedTrack=MusicRules.excluded("", "",track.title)
            error=if(excludedTrack)"녹음 및 기타 오디오는 제외됩니다" else "저장된 가사 확인 중"
            fetch=scope.launch {
                try {
                    if(excludedTrack)return@launch
                    val match=matchingTrack(track)
                    val actual=match ?: run { error="음악 목록에서 곡을 확인할 수 없습니다 · 음악 접근 권한 확인";return@launch }
                    resolvedKey=actual.key
                    if(actual.excluded){excludedTrack=true;error="녹음 및 기타 오디오는 제외됩니다";return@launch}
                    // Playback is read-only: observe saved lyrics, never start a search or AI job.
                    LyricsDb.get(this@MusicListener).dao().observeKey(actual.key).collect { current ->
                        val saved=current?.takeIf{it.status=="FOUND"}
                        if(saved!=lyric||error=="저장된 가사 확인 중"){
                            lyric=saved
                            lines=Lrc.parse(saved?.synced.orEmpty()).takeIf{rows->rows.any{it.text.isNotBlank()}}?:emptyList()
                            estimatedLines=if(lines.isEmpty())PlainSync.estimated(saved?.plain.orEmpty(),actual.duration) else emptyList()
                            plainSnippet=PlainSync.static(saved?.plain.orEmpty())
                            error=if(lines.isEmpty()&&plainSnippet.isBlank())"저장된 가사 없음" else ""
                            onChanged()
                        }
                    }
                } catch(e:CancellationException){throw e}
                catch(e:Exception){error="저장된 가사 조회 오류"}
                finally{if(isActive)onChanged()}
            }
        }
        val delta=if(playing && state.lastPositionUpdateTime>0) ((now-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed).toLong() else 0
        val position=(state.position+delta).coerceIn(0,if(track.duration>0) track.duration else Long.MAX_VALUE)
        val index=Lrc.index(lines,position-(lyric?.offset ?: 0))
        val plainMode=prefs.getString(PlainSync.MODE_KEY,PlainSync.ESTIMATE)
        val content=OverlayContent.saved(
            enabled,playing,permission,interactive,excludedTrack,
            lines.isNotEmpty(),lines.getOrNull(index)?.text,
            plainMode,estimatedLines.getOrNull(Lrc.index(estimatedLines,position))?.text,plainSnippet
        )
        val reason=when{
            excludedTrack->"녹음 및 기타 오디오는 제외됩니다"
            lines.isNotEmpty()&&content==null->"가사 없는 구간"
            plainSnippet.isNotBlank()&&plainMode==PlainSync.OFF->"일반 가사 표시 꺼짐"
            else->error.ifBlank{"저장된 가사 없음"}
        }
        val status=if(content!=null){
            if(show(content))"플로팅 가사 표시 중" else overlayFailure.ifBlank{"오버레이 표시 실패"}
        }else{hide();reason}
        Playback.now.value=NowPlaying(track,playing,position,lyric,index,error,status,resolvedKey)
        val timedLines=when{
            excludedTrack->emptyList()
            lines.isNotEmpty()->lines
            plainMode==PlainSync.ESTIMATE->estimatedLines
            else->emptyList()
        }
        // No periodic timer when no lyrics exist; media/DB/settings events wake the listener.
        if(timedLines.isEmpty()&&(content==null||overlayFailure.isBlank()))return null
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
        }
            if(contentChanged){
                val primary=view.getChildAt(0) as TextView
                primary.text=content.text
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
