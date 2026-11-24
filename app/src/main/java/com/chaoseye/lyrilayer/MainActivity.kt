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
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
        var manualSearch by remember { mutableStateOf<Track?>(null) }
        var aiTrack by remember { mutableStateOf<Track?>(null) }
        var modelReady by remember { mutableStateOf(DeviceLyrics.ready(this)) }
        var modelBusy by remember { mutableStateOf(false) }
        var modelProgress by remember { mutableIntStateOf(0) }
        var modelMessage by remember { mutableStateOf("") }
        val scope=rememberCoroutineScope()
        val now by Playback.now.collectAsStateWithLifecycle()
        val work=remember { WorkManager.getInstance(this) }
        val jobs by remember { work.getWorkInfosForUniqueWorkFlow(BatchWorker.NAME) }.collectAsStateWithLifecycle(initialValue=emptyList())
        val job=jobs.firstOrNull { !it.state.isFinished } ?: jobs.firstOrNull()
        val prefs=remember { getSharedPreferences("settings",MODE_PRIVATE) }
        var overlay by remember { mutableStateOf(prefs.getBoolean("overlay",false)) }
        LaunchedEffect(tick,allowed) { tracks=if(allowed) withContext(Dispatchers.IO) { runCatching { Library.scan(this@MainActivity) }.getOrDefault(emptyList()) } else emptyList() }
        Scaffold(bottomBar={ NavigationBar { listOf("라이브러리","실시간 가사","설정").forEachIndexed { i,s -> NavigationBarItem(selected=page==i,onClick={page=i},icon={Text(listOf("♫","≋","⚙")[i])},label={Text(s)}) } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).then(if(page==2)Modifier.verticalScroll(rememberScrollState()) else Modifier),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("LyriLayer",style=MaterialTheme.typography.headlineMedium,fontSize=30.sp)
                when(page) {
                    0 -> {
                        val found=tracks.count { map[it.key]?.status=="FOUND" || map[it.key]?.status=="INSTRUMENTAL" }
                        Text("전체 ${tracks.size}곡 · 등록 $found · 미등록 ${tracks.size-found}",fontSize=18.sp)
                        Text("통화·음성 녹음 및 벨소리 제외",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)
                        if(!allowed) Button(onClick={permission.launch(audioPermission)}) { Text("음악 파일 접근 허용") }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={BatchWorker.start(this@MainActivity)},enabled=allowed && job?.state?.isFinished!=false) { Text("가사 일괄 수집",fontSize=16.sp) }
                            OutlinedButton(onClick={refresh.intValue++}) { Text("새로고침",fontSize=16.sp) }
                        }
                        if(job!=null) {
                            val data=when{
                                job.state==WorkInfo.State.SUCCEEDED || job.state==WorkInfo.State.FAILED -> job.outputData
                                job.progress.getInt("total",-1)>=0 -> job.progress
                                else -> BatchWorker.savedProgress(this@MainActivity,job.id.toString())
                            }
                            val done=data.getInt("done",0);val total=data.getInt("total",0)
                            val percent=CollectionProgress.percent(done,total,job.state==WorkInfo.State.SUCCEEDED)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                                        Text(when(job.state){WorkInfo.State.SUCCEEDED->"수집 완료";WorkInfo.State.CANCELLED->"수집 중단";WorkInfo.State.FAILED->"수집 실패";WorkInfo.State.RUNNING->"가사 수집 중";else->"수집 대기 / 이어서 처리"},style=MaterialTheme.typography.titleMedium,fontSize=18.sp)
                                        Text("$percent%",style=MaterialTheme.typography.headlineSmall,fontSize=26.sp,color=MaterialTheme.colorScheme.primary)
                                    }
                                    LinearProgressIndicator(progress={percent/100f},modifier=Modifier.fillMaxWidth().height(8.dp))
                                    Text("처리 $done / $total 곡 · 남은 곡 ${(total-done).coerceAtLeast(0)}",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)
                                    Text("성공 ${data.getInt("found",0)} · 없음 ${data.getInt("missing",0)} · 오류 ${data.getInt("errors",0)}",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)
                                    data.getString("current")?.takeIf{it.isNotBlank()&&!job.state.isFinished}?.let{Text("검색 중: $it",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)}
                                    data.getString("lastError")?.takeIf{it.isNotBlank()}?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,fontSize=14.sp)}
                                    if(!job.state.isFinished) TextButton(onClick={work.cancelUniqueWork(BatchWorker.NAME)}){Text("수집 중단",fontSize=16.sp)}
                                }
                            }
                        }
                        OutlinedTextField(query,{query=it},label={Text("곡 제목 / 가수 검색")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Text(message)
                        LazyColumn(Modifier.weight(1f)) { items(tracks.filter { it.title.contains(query,true) || it.artist.contains(query,true) }) { t ->
                            val l=map[t.key]
                            ListItem(headlineContent={Text(t.title)},supportingContent={Text("${t.artist} · ${when { l?.status=="INSTRUMENTAL" -> "연주곡"; !l?.synced.isNullOrBlank() -> "싱크 가사"; l?.status=="FOUND" -> "일반 가사"; l?.status=="NOT_FOUND" -> "검색 실패"; else -> "미등록" }}")},trailingContent={Row { TextButton(onClick={manualSearch=t}) { Text("직접") }; TextButton(onClick={edit=t}) { Text("편집") }; TextButton(onClick={scope.launch { message="검색 중: ${t.title}"; try { val result=Repository.resolve(this@MainActivity,t,true); message="${t.title}: ${when { result.status=="INSTRUMENTAL" -> "연주곡"; result.synced.isNotBlank() -> "싱크 가사 저장 완료"; result.status=="FOUND" -> "일반 가사 저장 완료"; else -> "가사 없음 · 편집에서 Google 검색 가능" }}" } catch(e: CancellationException){throw e} catch(e: Exception){message="검색 오류: ${e.message}"} }}) { Text("검색") } }})
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
                        HorizontalDivider()
                        Text("온디바이스 AI 가사",style=MaterialTheme.typography.titleLarge)
                        Text(if(modelReady)"Whisper Base 모델 준비됨" else "모델 최초 1회 다운로드 (약 57 MiB). 이후 음원은 기기에서만 분석합니다.")
                        Button(enabled=!modelBusy&&DeviceLyrics.supported(),onClick={scope.launch {
                            modelBusy=true;modelProgress=0;modelMessage="모델 다운로드 중"
                            try{DeviceLyrics.prepare(this@MainActivity){n->runOnUiThread{modelProgress=n}};modelReady=true;modelMessage="모델 준비 완료"}
                            catch(e:CancellationException){throw e}catch(e:Exception){modelMessage="다운로드 오류: ${e.message}"}
                            finally{modelBusy=false}
                        }}){Text(if(modelReady)"모델 무결성 확인" else "AI 모델 다운로드")}
                        if(modelBusy)LinearProgressIndicator(progress={modelProgress/100f},modifier=Modifier.fillMaxWidth())
                        if(modelMessage.isNotBlank())Text(modelMessage,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        manualSearch?.let { t ->
            var term by remember(t){mutableStateOf(listOf(MusicRules.clean(t.artist),MusicRules.title(t.title)).filter{it.isNotBlank()}.joinToString(" "))}
            var results by remember(t){mutableStateOf(emptyList<Repository.SearchResult>())}
            var status by remember(t){mutableStateOf("제목과 가수를 수정해 검색할 수 있습니다.")}
            var busy by remember(t){mutableStateOf(false)}
            val searchScope=rememberCoroutineScope()
            AlertDialog(onDismissRequest={manualSearch=null},title={Text("가사 직접 검색")},text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedTextField(term,{term=it},label={Text("제목 / 가수")},singleLine=true)
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        Button(enabled=!busy&&term.isNotBlank(),onClick={searchScope.launch{
                            busy=true;status="LRCLIB 검색 중";results=emptyList()
                            try{results=Repository.searchCandidates(term);status=if(results.isEmpty())"검색 결과 없음" else "곡과 재생 시간을 확인해 선택하세요."}
                            catch(e:CancellationException){throw e}catch(e:Exception){status="검색 오류: ${e.message}"}
                            finally{busy=false}
                        }}){Text("LRCLIB 검색")}
                        OutlinedButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(term+" 가사 lyrics"))))}){Text("Google")}
                    }
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status)
                    Text("내 음원: ${t.duration/1000}초 · 선택한 가사는 자동으로 덮어쓰지 않습니다.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={manualSearch=null;aiTrack=t}){Text("기기에서 AI 가사 추출")}
                    OutlinedButton(onClick={manualSearch=null;edit=t}){Text("내장 가사 / LRC 가져오기") }
                    results.forEach{r->
                        ListItem(headlineContent={Text(r.title)},supportingContent={Text("${r.artist} · ${r.album} · ${r.seconds.toInt()}초 · ${if(r.synced.isNotBlank())"싱크" else "일반"}")},trailingContent={TextButton(onClick={scope.launch{Repository.selectCandidate(this@MainActivity,t,r);message="선택한 가사 저장 완료";manualSearch=null}}){Text("저장")}})
                        HorizontalDivider()
                    }
                }
            },confirmButton={TextButton(onClick={manualSearch=null;edit=t}){Text("가사 붙여넣기 / 편집")}},dismissButton={TextButton(onClick={manualSearch=null}){Text("닫기")}})
        }
        aiTrack?.let { t ->
            var language by remember(t){mutableStateOf("auto")}
            var result by remember(t){mutableStateOf("")}
            var status by remember(t){mutableStateOf("원본 음악에서 AI가 가사를 추정합니다. 보컬 분리는 적용하지 않으며 오인식될 수 있습니다.")}
            var progress by remember(t){mutableIntStateOf(0)}
            var running by remember(t){mutableStateOf(false)}
            var task by remember(t){mutableStateOf<Job?>(null)}
            val aiScope=rememberCoroutineScope()
            AlertDialog(onDismissRequest={if(!running)aiTrack=null},title={Text("기기에서 AI 가사 추출")},text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(t.title);Text(status)
                    Text("가사 언어")
                    listOf("auto" to "자동 감지","ko" to "한국어","en" to "영어","ja" to "일본어").forEach{(code,label)->
                        Row(verticalAlignment=Alignment.CenterVertically){RadioButton(selected=language==code,onClick={language=code},enabled=!running);Text(label)}
                    }
                    if(!modelReady)Text("설정에서 AI 모델을 먼저 다운로드하세요.")
                    Button(enabled=modelReady&&!running,onClick={
                        running=true;result="";progress=0
                        task=aiScope.launch {
                            try{
                                result=DeviceLyrics.extract(this@MainActivity,t,language){n,stage->runOnUiThread{progress=n;status=stage+" · "+n+"%"}}
                                status="AI 가사를 검토한 뒤 저장하세요."
                            }catch(e:CancellationException){status="분석 중단";throw e}
                            catch(e:Exception){status="분석 오류: ${e.message}"}
                            catch(e:LinkageError){status="이 기기에서 AI 엔진을 불러올 수 없습니다."}
                            finally{running=false}
                        }
                    }){Text("AI 분석 시작")}
                    if(running){LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth());Text("진행률 $progress% · 앱을 열어 두세요.")}
                    if(result.isNotBlank())OutlinedTextField(result,{result=it},label={Text("AI 생성 LRC · 검토 및 수정")},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=300.dp))
                }
            },confirmButton={TextButton(enabled=!running&&Lrc.parse(result).isNotEmpty(),onClick={scope.launch{
                db.dao().put(Lyric(t.key,Lrc.parse(result).joinToString("\n"){it.text},result,"ON_DEVICE_AI","FOUND",edited=true));message="AI 가사 저장 완료";aiTrack=null
            }}){Text("확인 후 저장")}},dismissButton={TextButton(onClick={if(running){task?.cancel();status="현재 분석 구간 종료 후 중단합니다."}else aiTrack=null}){Text(if(running)"분석 중단" else "닫기")}})
        }
        edit?.let { t ->
            val old=map[t.key]
            val clipboard=LocalClipboardManager.current
            var raw by remember(t) { mutableStateOf(old?.synced?.takeIf { it.isNotBlank() } ?: old?.plain.orEmpty()) }
            var offset by remember(t) { mutableStateOf((old?.offset ?: 0).toString()) }
            var localStatus by remember(t){mutableStateOf("")}
            val pickLrc=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{raw=withContext(Dispatchers.IO){LocalLyrics.readText(this@MainActivity,uri)};localStatus="파일 가져오기 완료"}catch(e:Exception){localStatus="파일 읽기 오류: ${e.message}"}}}
            AlertDialog(onDismissRequest={edit=null},title={Text(t.title)},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) { OutlinedTextField(raw,{raw=it},label={Text("LRC 또는 일반 가사")},modifier=Modifier.heightIn(max=240.dp)); OutlinedTextField(offset,{offset=it},label={Text("싱크 보정 (ms, +는 늦게)")}); TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode("${t.artist} ${t.title} 가사"))))}) { Text("Google에서 직접 검색") }; TextButton(onClick={clipboard.getText()?.text?.let { raw=it }}) { Text("복사한 가사 붙여넣기") }; TextButton(onClick={scope.launch{val l=withContext(Dispatchers.IO){LocalLyrics.find(this@MainActivity,t)};if(l!=null){raw=l.synced.ifBlank{l.plain};localStatus="내장 또는 로컬 가사 찾음"}else localStatus="내장 가사 없음 · LRC 파일을 직접 선택하세요"}}){Text("내장 가사 / 같은 이름 LRC 읽기")}; TextButton(onClick={pickLrc.launch(arrayOf("*/*"))}){Text("LRC 또는 TXT 파일 선택")}; if(localStatus.isNotBlank())Text(localStatus,style=MaterialTheme.typography.bodySmall) }},confirmButton={TextButton(onClick={scope.launch { val synced=Lrc.parse(raw).isNotEmpty(); db.dao().put(Lyric(t.key,if(synced) "" else raw,if(synced) raw else "","USER",if(raw.isBlank()) "NOT_FOUND" else "FOUND",edited=raw.isNotBlank(),offset=offset.toLongOrNull() ?: 0));edit=null }}) { Text("저장") }},dismissButton={TextButton(onClick={edit=null}) { Text("취소") }})
        }
    }
}
