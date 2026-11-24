package com.chaoseye.lyrilayer

import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

data class NowPlaying(val track: Track? = null, val playing: Boolean = false, val position: Long = 0, val lyric: Lyric? = null, val index: Int = -1, val message: String = "삼성 뮤직 재생 대기", val overlayStatus: String = "삼성 뮤직 재생 대기")
object Playback { val now = MutableStateFlow(NowPlaying()); val connected = MutableStateFlow(false); val retry = MutableStateFlow(0L) }
class MusicListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var fetch: Job? = null
    private var activeKey: String? = null
    private var lyric: Lyric? = null
    private var lines = emptyList<Line>()
    private var error = "가사 검색 중"
    private var overlay: TextView? = null
    private var overlayFailure = ""
    private var overlayFailureAt = 0L
    private val wm get() = getSystemService(WindowManager::class.java)
    override fun onListenerConnected() {
        super.onListenerConnected()
        // This system-bound service is independent of both app Activities.
        // Do not gate overlays on Samsung Music or LyriLayer being foreground.
        Playback.connected.value=true
        monitor?.cancel();fetch?.cancel();activeKey=null
        monitor=scope.launch {
            val manager=getSystemService(MediaSessionManager::class.java)
            val component=ComponentName(this@MusicListener,MusicListener::class.java)
            var controller: MediaController? = null
            var lastCheck=0L
            var lastRetry=Playback.retry.value
            var sessionStatus="삼성 뮤직 재생 대기"
            while(isActive) {
                val now=SystemClock.elapsedRealtime()
                if(now-lastCheck>=750 || controller==null) {
                    controller=try {
                        val samsung=manager.getActiveSessions(component).filter { it.packageName=="com.sec.android.app.music" }.maxByOrNull { if(it.playbackState?.state==PlaybackState.STATE_PLAYING) 1 else 0 }
                        sessionStatus=if(samsung==null)"삼성 뮤직 재생 세션 없음" else "삼성 뮤직 곡 정보 대기"
                        samsung
                    } catch(e: SecurityException) { sessionStatus="알림 접근 권한을 확인하세요";null }
                    lastCheck=now
                }
                val m=controller?.metadata
                val state=controller?.playbackState
                val title=m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val prefs=getSharedPreferences("settings",Context.MODE_PRIVATE)
                val testMode=prefs.getBoolean("overlay_test",false)
                val playing=state?.state==PlaybackState.STATE_PLAYING
                val permission=Settings.canDrawOverlays(this@MusicListener)
                val interactive=getSystemService(PowerManager::class.java).isInteractive
                // Test uses metadata only for the title; missing metadata never blocks the overlay.
                if(testMode){
                    fetch?.cancel();fetch=null;activeKey=null;lyric=null;lines=emptyList()
                    val track=if(title.isBlank())null else Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?:0)
                    val reason=OverlayTest.status(playing,permission,interactive)
                    val status=if(reason==null){
                        if(show(OverlayTest.text(title.ifBlank{m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()})))"테스트 플로팅 표시 중" else overlayFailure.ifBlank{"오버레이 표시 실패"}
                    }else{hide();if(!playing&&controller==null)sessionStatus else reason}
                    Playback.now.value=NowPlaying(track=track,playing=playing,position=state?.position?.coerceAtLeast(0)?:0, message="플로팅 테스트 모드",overlayStatus=status)
                    delay(150);continue
                }
                if(title.isBlank() || state==null) {
                    fetch?.cancel(); activeKey=null; lyric=null; lines=emptyList(); Playback.now.value=NowPlaying(message=sessionStatus,overlayStatus=sessionStatus); hide(); delay(750); continue
                }
                val track=Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
                if(activeKey!=track.key||lastRetry!=Playback.retry.value) {
                    lastRetry=Playback.retry.value
                    fetch?.cancel(); activeKey=track.key; lyric=null; lines=emptyList(); error="가사 검색 중"
                    fetch=scope.launch {
                        try {
                            val match=withContext(Dispatchers.IO) { runCatching { Library.allAudio(this@MusicListener) }.getOrDefault(emptyList()).filter { MusicRules.norm(it.title)==MusicRules.norm(track.title) && (MusicRules.clean(track.artist).isBlank() || MusicRules.artistMatches(it.artist,track.artist)) && (track.duration<=0 || kotlin.math.abs(it.duration-track.duration)<=3000) }.let { matches -> matches.firstOrNull { it.excluded } ?: matches.distinctBy { it.key }.singleOrNull() } }
                            val actual=match ?: run { error="음악 목록에서 곡을 확인할 수 없습니다 · 음악 접근 권한 확인"; return@launch }
                            if(actual.excluded) { error="녹음 및 기타 오디오는 제외됩니다"; return@launch }
                            val dao=LyricsDb.get(this@MusicListener).dao()
                            fun apply(l: Lyric?) {
                                lyric=l;lines=Lrc.parse(l?.synced.orEmpty())
                                error=when { l==null -> "가사 검색 중"; l.status=="NOT_FOUND" -> "가사를 찾지 못했습니다"; l.status=="INSTRUMENTAL" -> "연주곡"; lines.isEmpty()&&l.plain.isNotBlank() -> "일반 가사 · 시간 정보 없음"; else -> "" }
                            }
                            val cached=dao.get(actual.key)
                            apply(cached)
                            coroutineScope {
                                launch {
                                    if(cached?.status!="FOUND"&&cached?.status!="INSTRUMENTAL"){
                                        try { Repository.resolve(this@MusicListener,actual) }
                                        catch(e:CancellationException){throw e}
                                        catch(e:Exception){if(lyric?.status!="FOUND")error="가사 검색 오류 · ${e.message ?: "앱에서 다시 검색하세요"}"}
                                    }
                                }
                                dao.observe().collect { all ->
                                    val current=all.find { it.key==actual.key }
                                    if(current!=lyric)apply(current)
                                }
                            }
                        } catch(e: CancellationException) { throw e } catch(e: Exception) { error="가사 검색 오류 · ${e.message ?: "앱에서 다시 검색하세요"}" }
                    }
                }
                val delta=if(playing && state.lastPositionUpdateTime>0) ((now-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed).toLong() else 0
                val position=(state.position+delta).coerceIn(0,if(track.duration>0) track.duration else Long.MAX_VALUE)
                val index=Lrc.index(lines,position-(lyric?.offset ?: 0))
                val enabled=prefs.getBoolean("overlay",false)
                val reason=when {
                    !enabled -> "플로팅 가사 스위치가 꺼져 있습니다"
                    !permission -> "다른 앱 위에 표시 권한 필요"
                    !playing -> "재생 대기 / 일시 정지"
                    !interactive -> "화면 꺼짐"
                    lines.isEmpty() -> if(lyric?.status=="FOUND")"싱크 가사 없음 · 일반 가사는 앱에서 확인" else error.ifBlank{"가사 없음"}
                    index !in lines.indices -> "첫 가사 시작 대기"
                    lines[index].text.isBlank() -> "가사 없는 구간"
                    else -> ""
                }
                val status=if(reason.isEmpty()){
                    if(show(lines[index].text))"플로팅 가사 표시 중" else overlayFailure.ifBlank{"오버레이 표시 실패"}
                }else{hide();reason}
                Playback.now.value=NowPlaying(track,playing,position,lyric,index,error,status)
                delay(150)
            }
        }
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
    private fun show(value:String):Boolean {
        if(overlayFailure.isNotBlank()&&SystemClock.elapsedRealtime()-overlayFailureAt<2000)return false
        val dp=resources.displayMetrics.density
        val (areaWidth,areaHeight)=overlayArea()
        val width=(areaWidth*0.88f).toInt().coerceAtLeast(1)
        val percent=OverlayPosition.normalize(getSharedPreferences("settings",Context.MODE_PRIVATE).getInt(OverlayPosition.KEY,OverlayPosition.DEFAULT))
        val existing=overlay
        val view=existing?:TextView(this).apply {
            setTextColor(Color.WHITE);textSize=18f;gravity=Gravity.CENTER
            setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt())
            background=GradientDrawable().apply { setColor(0xE6000000.toInt());cornerRadius=16*dp }
        }
        try {
            val changed=view.text.toString()!=value
            if(changed)view.text=value
            val oldParams=existing?.layoutParams as? WindowManager.LayoutParams
            if(view.maxHeight!=areaHeight)view.maxHeight=areaHeight
            // Re-measure even when only the position or display size changed.
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(areaHeight,View.MeasureSpec.AT_MOST))
            val y=OverlayPosition.topOffset(percent,areaHeight,view.measuredHeight,(8*dp).toInt())
            if(oldParams==null){
                val params=WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    this.y=y;alpha=0.78f
                    if(Build.VERSION.SDK_INT>=30){
                        setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                        setFitInsetsIgnoringVisibility(true)
                    }
                }
                wm.addView(view,params);overlay=view
            }else if(oldParams.y!=y||oldParams.width!=width){
                oldParams.y=y;oldParams.width=width;wm.updateViewLayout(view,oldParams)
            }
            overlayFailure=""
            return true
        }catch(e:Exception){
            hide();overlayFailureAt=SystemClock.elapsedRealtime()
            overlayFailure="오버레이 표시 실패: "+e.javaClass.simpleName
            android.util.Log.w("LyriLayer","Overlay layout failed",e);return false
        }
    }
    private fun hide() { overlay?.let { runCatching { wm.removeViewImmediate(it) } }; overlay=null }
    override fun onListenerDisconnected() {
        Playback.connected.value=false;monitor?.cancel();fetch?.cancel();activeKey=null;lyric=null;lines=emptyList()
        hide();Playback.now.value=NowPlaying(message="음악 감지 서비스 연결 대기",overlayStatus="음악 감지 서비스 연결 대기")
        super.onListenerDisconnected()
        // Reconnect after system disconnection only while the user still grants access.
        FloatingPermissions.rebind(this)
    }
    override fun onDestroy() { Playback.connected.value=false;scope.cancel(); hide(); Playback.now.value=NowPlaying(); super.onDestroy() }
}
