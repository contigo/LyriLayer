package com.chaoseye.lyrilayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen-owned preview. All calls and callbacks execute on the main thread. */
internal class SyncPreviewPlayer(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, Handler(Looper.getMainLooper())).build()
    private var player: MediaPlayer? = null
    private var focused = false
    var ready by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var seeking by mutableStateOf(false); private set
    var duration by mutableStateOf(0L); private set
    var position by mutableStateOf(0L); private set
    var error by mutableStateOf(""); private set
    fun open(uri: String) {
        close(); error = ""; duration = 0; position = 0
        val p = MediaPlayer(); player = p
        try {
            p.setAudioAttributes(attributes)
            p.setOnPreparedListener { if (player === it) { duration = it.duration.toLong().coerceAtLeast(0); ready = duration > 0; if (!ready) error = "재생 시간을 확인할 수 없습니다." } }
            p.setOnCompletionListener { if (player === it) { playing = false; position = duration; abandon() } }
            p.setOnSeekCompleteListener { if (player === it) { seeking = false; position = readPosition() ?: position } }
            p.setOnErrorListener { m, _, _ -> if (player === m) fail("음원을 재생할 수 없습니다."); true }
            p.setDataSource(app,Uri.parse(uri)); p.prepareAsync()
        } catch (e: Exception) { fail("음원 열기 실패: " + (e.message ?: "오류")) }
    }
    private fun abandon() { if (focused) { focused = false; audio.abandonAudioFocusRequest(focus) } }
    private fun fail(message: String) { error = message; close() }
    fun readPosition(): Long? = if (!ready || seeking) null else runCatching { player?.currentPosition?.toLong()?.coerceIn(0,duration) }.getOrNull()
    fun refresh() { readPosition()?.let { position = it } }
    fun toggle() {
        if (!ready || seeking) return
        if (playing) { pause(); return }
        val p = player ?: return
        try {
            focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focused) { error = "오디오 재생 권한을 확보하지 못했습니다."; return }
            error = ""; p.start(); playing = true
        } catch (e: Exception) { fail("재생 실패: " + (e.message ?: "오류")) }
    }
    fun pause() {
        if (ready && playing) runCatching { player?.pause() }
        playing = false; refresh(); abandon()
    }
    fun seek(ms: Long) {
        if (!ready || seeking) return
        try { seeking = true; position = ms.coerceIn(0,duration); player?.seekTo(position,MediaPlayer.SEEK_CLOSEST) }
        catch (e: Exception) { fail("재생 위치 이동 실패") }
    }
    fun close() {
        val p = player; player = null
        ready = false; playing = false; seeking = false; abandon()
        runCatching { p?.release() }
    }
}
