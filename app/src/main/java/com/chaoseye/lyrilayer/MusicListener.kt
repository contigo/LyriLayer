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
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

data class NowPlaying(val track: Track? = null, val playing: Boolean = false, val position: Long = 0, val lyric: Lyric? = null, val index: Int = -1, val message: String = "삼성 뮤직 재생 대기")
object Playback { val now = MutableStateFlow(NowPlaying()) }
class MusicListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var fetch: Job? = null
    private var activeKey: String? = null
    private var lyric: Lyric? = null
    private var lines = emptyList<Line>()
    private var error = "가사 검색 중"
    private var overlay: TextView? = null
    private val wm get() = getSystemService(WindowManager::class.java)
    override fun onListenerConnected() {
        super.onListenerConnected()
        monitor?.cancel()
        monitor=scope.launch {
            val manager=getSystemService(MediaSessionManager::class.java)
            val component=ComponentName(this@MusicListener,MusicListener::class.java)
            var controller: MediaController? = null
            var lastCheck=0L
            while(isActive) {
                val now=SystemClock.elapsedRealtime()
                if(now-lastCheck>=750 || controller==null) {
                    controller=try { manager.getActiveSessions(component).filter { it.packageName=="com.sec.android.app.music" }.maxByOrNull { if(it.playbackState?.state==PlaybackState.STATE_PLAYING) 1 else 0 } } catch(e: SecurityException) { null }
                    lastCheck=now
                }
                val m=controller?.metadata
                val state=controller?.playbackState
                val title=m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                if(title.isBlank() || state==null) {
                    fetch?.cancel(); activeKey=null; lyric=null; lines=emptyList(); Playback.now.value=NowPlaying(); hide(); delay(750); continue
                }
                val track=Track(title,m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
                if(activeKey!=track.key) {
                    fetch?.cancel(); activeKey=track.key; lyric=null; lines=emptyList(); error="가사 검색 중"
                    fetch=scope.launch {
                        try {
                            val match=withContext(Dispatchers.IO) { runCatching { Library.allAudio(this@MusicListener) }.getOrDefault(emptyList()).filter { MusicRules.norm(it.title)==MusicRules.norm(track.title) && (MusicRules.clean(track.artist).isBlank() || MusicRules.artistMatches(it.artist,track.artist)) && (track.duration<=0 || kotlin.math.abs(it.duration-track.duration)<=3000) }.let { matches -> matches.firstOrNull { it.excluded } ?: matches.distinctBy { it.key }.singleOrNull() } }
                            val actual=match ?: run { error="음악 목록에서 곡을 확인할 수 없습니다 · 음악 접근 권한 확인"; return@launch }
                            if(actual.excluded) { error="녹음 및 기타 오디오는 제외됩니다"; return@launch }
                            val initial=Repository.resolve(this@MusicListener,actual)
                            fun apply(l: Lyric?) { lyric=l; lines=Lrc.parse(l?.synced.orEmpty()); error=if(l?.status=="NOT_FOUND") "가사를 찾지 못했습니다" else if(l?.status=="INSTRUMENTAL") "연주곡" else "" }
                            apply(initial)
                            LyricsDb.get(this@MusicListener).dao().observe().collect { all -> apply(all.find { it.key==actual.key }) }
                        } catch(e: CancellationException) { throw e } catch(e: Exception) { error="가사 검색 오류 · ${e.message ?: "앱에서 다시 검색하세요"}" }
                    }
                }
                val playing=state.state==PlaybackState.STATE_PLAYING
                val delta=if(playing && state.lastPositionUpdateTime>0) ((now-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed).toLong() else 0
                val position=(state.position+delta).coerceIn(0,if(track.duration>0) track.duration else Long.MAX_VALUE)
                val index=Lrc.index(lines,position-(lyric?.offset ?: 0))
                Playback.now.value=NowPlaying(track,playing,position,lyric,index,error)
                val enabled=getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("overlay",false)
                if(enabled && playing && Settings.canDrawOverlays(this@MusicListener) && getSystemService(PowerManager::class.java).isInteractive && index>=0 && lines[index].text.isNotBlank()) show(lines[index].text) else hide()
                delay(150)
            }
        }
    }
    private fun show(value: String) {
        if(overlay?.text?.toString()==value) return
        if(overlay==null) {
            val dp=resources.displayMetrics.density
            val view=TextView(this).apply { setTextColor(Color.WHITE); textSize=18f; gravity=Gravity.CENTER; setPadding((16*dp).toInt(),(10*dp).toInt(),(16*dp).toInt(),(10*dp).toInt()); background=GradientDrawable().apply { setColor(0xE6000000.toInt()); cornerRadius=16*dp } }
            val p=WindowManager.LayoutParams((resources.displayMetrics.widthPixels*0.88).toInt(),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y=(100*dp).toInt(); alpha=0.78f }
            try { wm.addView(view,p); overlay=view } catch(e: Exception) { return }
        }
        overlay?.text=value
    }
    private fun hide() { overlay?.let { runCatching { wm.removeViewImmediate(it) } }; overlay=null }
    override fun onListenerDisconnected() { monitor?.cancel(); fetch?.cancel(); activeKey=null; lyric=null; lines=emptyList(); hide(); Playback.now.value=NowPlaying(); super.onListenerDisconnected() }
    override fun onDestroy() { scope.cancel(); hide(); Playback.now.value=NowPlaying(); super.onDestroy() }
}
