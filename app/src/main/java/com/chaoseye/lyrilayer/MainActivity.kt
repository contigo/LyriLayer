package com.chaoseye.lyrilayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh.intValue++ }
    override fun onResume() { super.onResume(); refresh.intValue++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=if(androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Screen() } }
    }
    @Composable private fun Screen() {
        val tick=refresh.intValue
        val audioPermission=if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val allowed=checkSelfPermission(audioPermission)==PackageManager.PERMISSION_GRANTED
        val db=remember { LyricsDb.get(this) }
        val stored by remember { db.dao().observe() }.collectAsStateWithLifecycle(initialValue=emptyList())
        val map=remember(stored) { stored.associateBy { it.key } }
        var tracks by remember { mutableStateOf(emptyList<Track>()) }
        var message by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var page by remember { mutableIntStateOf(0) }
        var edit by remember { mutableStateOf<Track?>(null) }
        val scope=rememberCoroutineScope()
        val now by Playback.now.collectAsStateWithLifecycle()
        val work=remember { WorkManager.getInstance(this) }
        val jobs by remember { work.getWorkInfosForUniqueWorkFlow(BatchWorker.NAME) }.collectAsStateWithLifecycle(initialValue=emptyList())
        val job=jobs.firstOrNull { !it.state.isFinished } ?: jobs.firstOrNull()
        val prefs=remember { getSharedPreferences("settings",MODE_PRIVATE) }
        var overlay by remember { mutableStateOf(prefs.getBoolean("overlay",false)) }
        LaunchedEffect(tick,allowed) { tracks=if(allowed) withContext(Dispatchers.IO) { runCatching { Library.scan(this@MainActivity) }.getOrDefault(emptyList()) } else emptyList() }
        Scaffold(bottomBar={ NavigationBar { listOf("라이브러리","실시간 가사","설정").forEachIndexed { i,s -> NavigationBarItem(selected=page==i,onClick={page=i},icon={Text(listOf("♫","≋","⚙")[i])},label={Text(s)}) } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("LyriLayer",style=MaterialTheme.typography.headlineMedium)
                when(page) {
                    0 -> {
                        val found=tracks.count { map[it.key]?.status=="FOUND" || map[it.key]?.status=="INSTRUMENTAL" }
                        Text("전체 ${tracks.size}곡 · 등록 $found · 미등록 ${tracks.size-found}")
                        Text("통화·음성 녹음 및 벨소리 제외",style=MaterialTheme.typography.bodySmall)
                        if(!allowed) Button(onClick={permission.launch(audioPermission)}) { Text("음악 파일 접근 허용") }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={BatchWorker.start(this@MainActivity)},enabled=allowed && job?.state?.isFinished!=false) { Text("가사 일괄 수집") }
                            OutlinedButton(onClick={refresh.intValue++}) { Text("새로고침") }
                        }
                        if(job!=null) {
                            val data=if(job.state.isFinished) job.outputData else job.progress
                            Text("수집: ${job.state} · ${data.getInt("done",0)}/${data.getInt("total",0)}")
                            Text("성공 ${data.getInt("found",0)} · 없음 ${data.getInt("missing",0)} · 오류 ${data.getInt("errors",0)}")
                            data.getString("lastError")?.takeIf { it.isNotBlank() }?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                            if(!job.state.isFinished) TextButton(onClick={work.cancelUniqueWork(BatchWorker.NAME)}) { Text("수집 중단") }
                        }
                        OutlinedTextField(query,{query=it},label={Text("곡 제목 / 가수 검색")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Text(message)
                        LazyColumn(Modifier.weight(1f)) { items(tracks.filter { it.title.contains(query,true) || it.artist.contains(query,true) }) { t ->
                            val l=map[t.key]
                            ListItem(headlineContent={Text(t.title)},supportingContent={Text("${t.artist} · ${when { l?.status=="INSTRUMENTAL" -> "연주곡"; !l?.synced.isNullOrBlank() -> "싱크 가사"; l?.status=="FOUND" -> "일반 가사"; l?.status=="NOT_FOUND" -> "검색 실패"; else -> "미등록" }}")},trailingContent={Row { TextButton(onClick={edit=t}) { Text("편집") }; TextButton(onClick={scope.launch { message="검색 중: ${t.title}"; try { val result=Repository.resolve(this@MainActivity,t,true); message="${t.title}: ${when { result.status=="INSTRUMENTAL" -> "연주곡"; result.synced.isNotBlank() -> "싱크 가사 저장 완료"; result.status=="FOUND" -> "일반 가사 저장 완료"; else -> "가사 없음 · 편집에서 Google 검색 가능" }}" } catch(e: CancellationException){throw e} catch(e: Exception){message="검색 오류: ${e.message}"} }}) { Text("검색") } }})
                            HorizontalDivider()
                        } }
                    }
                    1 -> {
                        Text(now.track?.title ?: "재생 중인 음악 없음",style=MaterialTheme.typography.titleLarge)
                        Text(now.track?.artist.orEmpty())
                        Text(if(now.playing) "Samsung Music 재생 중" else "재생 대기 / 일시 정지")
                        val lines=remember(now.lyric?.synced) { Lrc.parse(now.lyric?.synced.orEmpty()) }
                        if(lines.isNotEmpty()) {
                            val list=rememberLazyListState()
                            LaunchedEffect(now.index,lines) { if(now.index>=0) list.animateScrollToItem((now.index-2).coerceAtLeast(0)) }
                            LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)) { items(lines.size) { i -> Text(lines[i].text,style=if(i==now.index) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,color=if(i==now.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                        } else if(!now.lyric?.plain.isNullOrBlank()) LazyColumn(Modifier.weight(1f)) { item { Text("일반 가사 · 시간 정보 없음"); Text(now.lyric?.plain.orEmpty()) } } else Text(now.message)
                    }
                    else -> {
                        Text("삼성 뮤직 연결",style=MaterialTheme.typography.titleLarge)
                        Text("알림 접근 권한은 삼성 뮤직 MediaSession을 읽기 위해 사용합니다. 알림 본문은 수집하지 않습니다.")
                        Button(onClick={startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}) { Text("알림 접근 설정") }
                        Button(onClick={startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}) { Text("다른 앱 위에 표시 권한") }
                        Row(verticalAlignment=Alignment.CenterVertically) { Text("플로팅 가사",Modifier.weight(1f)); Switch(overlay,{overlay=it;prefs.edit().putBoolean("overlay",it).apply()}) }
                        Text("싱크 가사가 있는 곡을 재생할 때만 오버레이를 표시합니다.")
                        Text("온라인 검색 시 곡 제목, 가수, 앨범, 길이를 LRCLIB에 전송합니다. 음악 파일이나 알림은 전송하지 않습니다.")
                    }
                }
            }
        }
        edit?.let { t ->
            val old=map[t.key]
            var raw by remember(t) { mutableStateOf(old?.synced?.takeIf { it.isNotBlank() } ?: old?.plain.orEmpty()) }
            var offset by remember(t) { mutableStateOf((old?.offset ?: 0).toString()) }
            AlertDialog(onDismissRequest={edit=null},title={Text(t.title)},text={Column { OutlinedTextField(raw,{raw=it},label={Text("LRC 또는 일반 가사")},modifier=Modifier.heightIn(max=240.dp)); OutlinedTextField(offset,{offset=it},label={Text("싱크 보정 (ms, +는 늦게)")}); TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode("${t.artist} ${t.title} 가사"))))}) { Text("Google에서 직접 검색") } }},confirmButton={TextButton(onClick={scope.launch { val synced=Lrc.parse(raw).isNotEmpty(); db.dao().put(Lyric(t.key,if(synced) "" else raw,if(synced) raw else "","USER",if(raw.isBlank()) "NOT_FOUND" else "FOUND",edited=raw.isNotBlank(),offset=offset.toLongOrNull() ?: 0));edit=null }}) { Text("저장") }},dismissButton={TextButton(onClick={edit=null}) { Text("취소") }})
        }
    }
}
