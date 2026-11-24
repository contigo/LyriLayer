package com.chaoseye.lyrilayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
        val updateSupported=remember{AppUpdates.supported(this)}
        val updateState by AppUpdates.state.collectAsStateWithLifecycle()
        LaunchedEffect(Unit){if(updateSupported)AppUpdates.check(this@MainActivity,automatic=true)}
        val audioPermission=FloatingPermissions.audioName()
        val allowed=FloatingPermissions.audioGranted(this)
        val db=remember { LyricsDb.get(this) }
        val stored by remember { db.dao().observe() }.collectAsStateWithLifecycle(initialValue=emptyList())
        val map=remember(stored) { stored.associateBy { it.key } }
        var tracks by remember { mutableStateOf(emptyList<Track>()) }
        var message by remember { mutableStateOf("") }
        var messageStatus by remember { mutableStateOf("IDLE") }
        val successColor=if(androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF1B5E20)
        val failureColor=MaterialTheme.colorScheme.error
        val neutralColor=MaterialTheme.colorScheme.onSurfaceVariant
        val runningColor=MaterialTheme.colorScheme.primary
        val aiColor=if(androidx.compose.foundation.isSystemInDarkTheme())Color(0xFFCEB5FF) else Color(0xFF6942AD)
        fun resultColor(status:String):Color=when(status){"FOUND","INSTRUMENTAL"->successColor;"NOT_FOUND","ERROR"->failureColor;"RUNNING"->runningColor;else->neutralColor}
        var query by remember { mutableStateOf("") }
        var lyricFilter by rememberSaveable { mutableStateOf("all") }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var settingsScreen by rememberSaveable { mutableStateOf("main") }
        var queueScreen by rememberSaveable { mutableStateOf(false) }
        BackHandler(enabled=page==0&&queueScreen){queueScreen=false}
        val queuedLive by AiQueue.live.collectAsStateWithLifecycle()
        BackHandler(enabled=page==2&&settingsScreen!="main"){settingsScreen="main"}
        var edit by remember { mutableStateOf<Track?>(null) }
        var syncTrack by remember { mutableStateOf<Track?>(null) }
        var manualSearch by remember { mutableStateOf<Track?>(null) }
        var aiTrack by remember { mutableStateOf<Track?>(null) }
        var aiTarget by remember { mutableStateOf<Track?>(null) }
        var aiLanguage by remember { mutableStateOf("auto") }
        var aiResult by remember { mutableStateOf("") }
        var aiPreview by remember { mutableStateOf("") }
        var aiStatus by remember { mutableStateOf("") }
        var aiProgress by remember { mutableIntStateOf(0) }
        var aiRunning by remember { mutableStateOf(false) }
        var aiFailed by remember { mutableStateOf(false) }
        var aiSaving by remember { mutableStateOf(false) }
        var aiTask by remember { mutableStateOf<Job?>(null) }
        var aiRunId by remember { mutableIntStateOf(0) }
        fun clearAi(){
            aiTrack=null;aiTarget=null;aiResult="";aiPreview="";aiStatus="";aiProgress=0;aiFailed=false
        }
        fun openAi(t:Track){
            if(aiTarget?.key!=t.key){
                if(aiTarget!=null){aiTrack=aiTarget;return}
                aiTarget=t;aiLanguage="auto";aiProgress=0;aiResult="";aiPreview="";aiFailed=false
                aiStatus="AI가 음악에서 가사를 추정합니다. 보컬 분리 없이 분석하므로 검토가 필요합니다."
            }
            aiTrack=t
        }
        var modelReady by remember { mutableStateOf(DeviceLyrics.ready(this)) }
        var modelBusy by remember { mutableStateOf(false) }
        var modelProgress by remember { mutableIntStateOf(0) }
        var modelMessage by remember { mutableStateOf("") }
        val scope=rememberCoroutineScope()
        val now by Playback.now.collectAsStateWithLifecycle()
        val listenerConnected by Playback.connected.collectAsStateWithLifecycle()
        val listenerComponent=remember { FloatingPermissions.component(this) }
        val listenerAllowed=remember(tick) { FloatingPermissions.listenerGranted(this) }
        val overlayAllowed=remember(tick) { Settings.canDrawOverlays(this) }
        LaunchedEffect(tick,allowed,listenerAllowed) {
            if(listenerAllowed&&!Playback.connected.value) FloatingPermissions.rebind(this@MainActivity)
            if(allowed&&listenerAllowed) Playback.retry.value++
        }
        val work=remember { WorkManager.getInstance(this) }
        val jobs by remember { work.getWorkInfosForUniqueWorkFlow(BatchWorker.NAME) }.collectAsStateWithLifecycle(initialValue=emptyList())
        val job=jobs.firstOrNull { !it.state.isFinished } ?: jobs.firstOrNull()
        val prefs=remember { getSharedPreferences("settings",MODE_PRIVATE) }
        var overlay by remember { mutableStateOf(prefs.getBoolean("overlay",false)) }
        var showMissingOverlay by remember { mutableStateOf(prefs.getBoolean(OverlayContent.SHOW_MISSING,true)) }
        var plainMode by remember { mutableStateOf(prefs.getString(PlainSync.MODE_KEY,PlainSync.ESTIMATE)?:PlainSync.ESTIMATE) }
        var testMode by remember { mutableStateOf(prefs.getBoolean("overlay_test",false)) }
        var overlayPosition by remember { mutableIntStateOf(OverlayPosition.normalize(prefs.getInt(OverlayPosition.KEY,OverlayPosition.DEFAULT))) }
        fun saveOverlayPosition(value:Int){
            overlayPosition=OverlayPosition.normalize(value)
            prefs.edit().putInt(OverlayPosition.KEY,overlayPosition).apply()
        }
        // Save the pass across rotations; each permission is offered only once per launch/pass.
        var permissionPass by rememberSaveable { mutableStateOf(true) }
        var permissionAttempted by rememberSaveable { mutableIntStateOf(0) }
        var permissionPending by rememberSaveable { mutableStateOf(false) }
        var permissionError by rememberSaveable { mutableStateOf("") }
        val accessResult=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
            permissionPending=false;refresh.intValue++
        }
        val audioResult=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
            permissionPending=false;refresh.intValue++
        }
        val missing=if(permissionPass&&!permissionPending)PermissionPlan.next(allowed,listenerAllowed,overlayAllowed,permissionAttempted) else null
        LaunchedEffect(permissionPass,permissionPending,missing) {
            if(permissionPass&&!permissionPending&&missing==null)permissionPass=false
        }
        fun startAccessPass(){
            permissionAttempted=0;permissionPending=false;permissionError="";permissionPass=true;refresh.intValue++
        }
        fun requestAccess(access:RequiredAccess){
            permissionAttempted=permissionAttempted or access.bit
            permissionPending=true;permissionError=""
            try {
                when(access){
                    RequiredAccess.AUDIO -> {
                        val asked=prefs.getBoolean("audio_permission_asked",false)
                        if(asked&&!shouldShowRequestPermissionRationale(audioPermission)){
                            accessResult.launch(FloatingPermissions.appSettings(this@MainActivity))
                        }else{
                            prefs.edit().putBoolean("audio_permission_asked",true).apply()
                            audioResult.launch(audioPermission)
                        }
                    }
                    RequiredAccess.LISTENER -> accessResult.launch(FloatingPermissions.listenerSettings(this@MainActivity))
                    RequiredAccess.OVERLAY -> accessResult.launch(FloatingPermissions.overlaySettings(this@MainActivity))
                }
            }catch(e:Exception){
                permissionPending=false
                permissionError="권한 설정 화면을 열지 못했습니다. 휴대전화 설정에서 직접 허용해 주세요."
                refresh.intValue++
            }
        }
        LaunchedEffect(tick,allowed) { tracks=if(allowed) withContext(Dispatchers.IO) { runCatching { Library.scan(this@MainActivity) }.getOrDefault(emptyList()) } else emptyList() }
        Scaffold(bottomBar={ if(!(page==2&&settingsScreen!="main")&&!(page==0&&queueScreen)) NavigationBar { listOf("라이브러리","실시간 가사","설정").forEachIndexed { i,s -> NavigationBarItem(selected=page==i,onClick={page=i;settingsScreen="main";queueScreen=false},icon={Text(listOf("♫","≋","⚙")[i])},label={Text(s)}) } } }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).then(if(page==2)Modifier.verticalScroll(rememberScrollState()) else Modifier),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(page==0&&queueScreen){
                    AiQueueScreen(this@MainActivity,tracks,map,modelReady,onBack={queueScreen=false})
                }else if(page==2&&settingsScreen=="position"){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={settingsScreen="main"}){Text("‹ 뒤로")}
                        Text("가사 표시 위치",style=MaterialTheme.typography.titleLarge,fontSize=22.sp)
                    }
                        Text("화면의 위아래 위치를 조절하세요. 실제 가사와 테스트 모드에 함께 적용됩니다.",fontSize=14.sp)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                            Text("위치: $overlayPosition%",fontSize=16.sp)
                            TextButton(onClick={saveOverlayPosition(OverlayPosition.DEFAULT)}){Text("기본 위치")}
                        }
                        Slider(value=overlayPosition.toFloat(),onValueChange={saveOverlayPosition(it.roundToInt())},valueRange=0f..100f)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            TextButton(onClick={saveOverlayPosition(0)}){Text("상단")}
                            TextButton(onClick={saveOverlayPosition(50)}){Text("중앙")}
                            TextButton(onClick={saveOverlayPosition(100)}){Text("하단")}
                        }
                        BoxWithConstraints(Modifier.width(150.dp).height(230.dp).align(Alignment.CenterHorizontally).border(1.dp,MaterialTheme.colorScheme.outline,RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(18.dp)).padding(8.dp)){
                            Text("화면 미리보기",modifier=Modifier.align(Alignment.Center),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.align(Alignment.TopCenter).offset(y=(maxHeight-40.dp)*(overlayPosition/100f)).fillMaxWidth().height(40.dp).background(Color(0xDD000000),RoundedCornerShape(8.dp)).padding(4.dp),contentAlignment=Alignment.Center){
                                Text("가사 표시 위치",fontSize=12.sp,color=Color.White)
                            }
                        }

                    Text("변경한 위치는 자동 저장되며, 음악 재생 중인 플로팅에도 바로 반영됩니다.",fontSize=14.sp,color=neutralColor)
                }else if(page==2&&settingsScreen=="update"&&updateSupported){
                    Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={settingsScreen="main"}){Text("‹ 뒤로")};Text("앱 업데이트",fontSize=22.sp)}
                    UpdateScreen(this@MainActivity)
                }else if(page==2&&settingsScreen=="test"){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={settingsScreen="main"}){Text("‹ 뒤로")}
                        Text("플로팅 실행 테스트",style=MaterialTheme.typography.titleLarge,fontSize=22.sp)
                    }
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text("테스트 모드",Modifier.weight(1f),fontSize=16.sp)
                            Switch(testMode,{testMode=it;prefs.edit().putBoolean("overlay_test",it).apply();if(it){if(!listenerAllowed||!overlayAllowed)startAccessPass() else FloatingPermissions.rebind(this@MainActivity)}})
                        }
                        Text("ON: 삼성 뮤직 재생 시 가사 검색 없이 현재 재생 중인 곡 제목을 테스트 플로팅에 표시합니다. 일시 정지/정지 또는 화면 꺼짐 시 숨깁니다. 기존 플로팅 가사 스위치와 독립적으로 동작합니다.",fontSize=14.sp)
                        Text("알림 접근: "+if(listenerAllowed)"허용됨" else "권한 필요",color=if(listenerAllowed)successColor else failureColor,fontSize=14.sp)
                        Text("음악 감지 서비스: "+if(listenerConnected)"연결됨" else "연결 대기",color=if(listenerConnected)successColor else neutralColor,fontSize=14.sp)
                        Text("다른 앱 위에 표시: "+if(overlayAllowed)"허용됨" else "권한 필요",color=if(overlayAllowed)successColor else failureColor,fontSize=14.sp)
                        Text("재생 감지: "+if(now.playing)"재생 중" else "재생 대기",color=if(now.playing)successColor else neutralColor,fontSize=14.sp)
                        Text("현재 곡: "+(now.track?.title?.takeIf{it.isNotBlank()} ?: "곡 정보 대기"),fontSize=16.sp)
                        Text("플로팅 상태: "+if(!listenerAllowed)"알림 접근 권한 필요" else if(!listenerConnected)"서비스 연결 대기" else now.overlayStatus,fontSize=14.sp,color=if(listenerConnected&&now.overlayStatus.endsWith("표시 중"))successColor else neutralColor)
                        OutlinedButton(onClick={if(listenerAllowed)NotificationListenerService.requestRebind(listenerComponent);Playback.retry.value++}){Text("음악 감지 다시 연결")}

                    if(!listenerAllowed||!overlayAllowed)Button(onClick={startAccessPass()}){Text("필수 권한 설정")}
                }else{
                Text("LyriLayer",style=MaterialTheme.typography.headlineMedium,fontSize=30.sp)
                when(page) {
                    0 -> {
                        if(updateSupported&&(updateState.stage=="available"||updateState.stage=="ready")){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text("새 업데이트 "+updateState.release?.tag.orEmpty(),modifier=Modifier.weight(1f),fontSize=14.sp)
                                TextButton(onClick={page=2;settingsScreen="update"}){Text(if(updateState.stage=="ready")"설치" else "확인")}
                            }
                        }
                        val found=tracks.count { LyricDisplay.registered(map[it.key]?.status) }
                        val aiCount=tracks.count { LyricDisplay.aiGenerated(map[it.key]?.status,map[it.key]?.source) }
                        Text(buildAnnotatedString {
                            append("전체 ${tracks.size}곡 · ")
                            withStyle(SpanStyle(color=successColor)){append("등록 $found")}
                            append(" · ")
                            withStyle(SpanStyle(color=aiColor)){append("AI 생성 $aiCount")}
                            append(" · ")
                            withStyle(SpanStyle(color=neutralColor)){append("미등록 ${tracks.size-found}")}
                        },fontSize=18.sp)
                        Text("통화·음성 녹음 및 벨소리 제외",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)
                        if(!allowed) Button(onClick={startAccessPass()}) { Text("음악 파일 접근 허용") }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={BatchWorker.start(this@MainActivity)},enabled=allowed && job?.state?.isFinished!=false) { Text("가사 일괄 수집",fontSize=16.sp) }
                            OutlinedButton(onClick={refresh.intValue++}) { Text("새로고침",fontSize=16.sp) }
                        }
                        OutlinedButton(onClick={queueScreen=true},modifier=Modifier.fillMaxWidth()){
                            Text(if(queuedLive.running)"✦ AI 분석 목록 · 처리 중 "+queuedLive.percent+"%" else "✦ AI 분석 목록 / 일괄 분석",fontSize=16.sp)
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
                                        Text("$percent%",style=MaterialTheme.typography.headlineSmall,fontSize=26.sp,color=when(job.state){WorkInfo.State.SUCCEEDED->successColor;WorkInfo.State.FAILED->failureColor;WorkInfo.State.CANCELLED->neutralColor;else->runningColor})
                                    }
                                    LinearProgressIndicator(progress={percent/100f},modifier=Modifier.fillMaxWidth().height(8.dp))
                                    Text("처리 $done / $total 곡 · 남은 곡 ${(total-done).coerceAtLeast(0)}",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)
                                    Text(buildAnnotatedString {
                                        withStyle(SpanStyle(color=successColor)){append("성공 ${data.getInt("found",0)}")}
                                        append(" · ")
                                        withStyle(SpanStyle(color=failureColor)){append("없음 ${data.getInt("missing",0)}")}
                                        append(" · ")
                                        withStyle(SpanStyle(color=failureColor)){append("오류 ${data.getInt("errors",0)}")}
                                    },style=MaterialTheme.typography.bodySmall,fontSize=14.sp)
                                    data.getString("current")?.takeIf{it.isNotBlank()&&!job.state.isFinished}?.let{Text("검색 중: $it",style=MaterialTheme.typography.bodySmall,fontSize=14.sp)}
                                    data.getString("lastError")?.takeIf{it.isNotBlank()}?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,fontSize=14.sp)}
                                    if(!job.state.isFinished) TextButton(onClick={work.cancelUniqueWork(BatchWorker.NAME)}){Text("수집 중단",fontSize=16.sp)}
                                }
                            }
                        }
                        OutlinedTextField(query,{query=it},label={Text("곡 제목 / 가수 검색")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            listOf("all" to "전체","ai" to "AI 가사","missing" to "미등록").forEach{(value,label)->
                                FilterChip(selected=lyricFilter==value,onClick={lyricFilter=value},label={Text(label)},modifier=Modifier.weight(1f))
                            }
                        }
                        if(aiTarget!=null)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Text("✦ "+aiTarget?.title.orEmpty()+" · "+when{aiRunning->"AI 분석 중 $aiProgress%";aiFailed->"분석 실패 / 중단";aiResult.isNotBlank()->"검토 대기";else->"분석 준비"},color=aiColor,modifier=Modifier.weight(1f),fontSize=14.sp)
                            TextButton(onClick={aiTrack=aiTarget}){Text("열기")}
                        }
                        Text(message,color=if(messageStatus=="AI")aiColor else resultColor(messageStatus))
                        val visibleTracks=tracks.filter{t->
                            val l=map[t.key]
                            (t.title.contains(query,true)||t.artist.contains(query,true))&&LyricDisplay.visible(lyricFilter,l?.status,l?.source,aiTarget?.key==t.key)
                        }
                        if(visibleTracks.isEmpty()&&allowed)Text("해당 조건의 곡이 없습니다.",color=neutralColor,fontSize=14.sp)
                        LazyColumn(Modifier.weight(1f)) { items(visibleTracks) { t ->
                            val l=map[t.key]
                            val activeAi=aiTarget?.key==t.key
                            val generated=LyricDisplay.aiGenerated(l?.status,l?.source)||l?.source==PlainSync.AI_SOURCE
                            val rowLabel=when{
                                activeAi&&aiRunning -> "✦ AI 분석 중 · $aiProgress%"
                                activeAi&&aiFailed -> "AI 분석 실패 / 중단"
                                activeAi&&aiResult.isNotBlank() -> "✦ AI 분석 완료 · 검토 대기"
                                else -> LyricDisplay.label(l?.status,l?.source,!l?.synced.isNullOrBlank())
                            }
                            val rowColor=when{activeAi&&aiFailed->failureColor;activeAi||generated->aiColor;else->resultColor(l?.status.orEmpty())}
                            ListItem(headlineContent={Text(t.title)},supportingContent={
                                Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
                                    Text(buildAnnotatedString{append(t.artist);append(" · ");withStyle(SpanStyle(color=rowColor)){append(rowLabel)}})
                                    if(l?.status=="FOUND"&&(!l.plain.isBlank()||l.synced.isNotBlank()))TextButton(onClick={syncTrack=t},contentPadding=PaddingValues(0.dp)){Text("가사 관리 / 싱크",fontSize=13.sp)}
                                    if(activeAi&&aiRunning)LinearProgressIndicator(progress={aiProgress/100f},modifier=Modifier.fillMaxWidth(),color=aiColor)
                                    if(!LyricDisplay.registered(l?.status)||activeAi)TextButton(onClick={openAi(t)},contentPadding=PaddingValues(0.dp)){Text(if(activeAi)"AI 분석 열기" else "✦ AI 가사 분석",color=aiColor,fontSize=13.sp)}
                                }
                            },trailingContent={Row { TextButton(onClick={manualSearch=t}) { Text("직접") }; TextButton(onClick={edit=t}) { Text("편집") }; TextButton(onClick={scope.launch { messageStatus="RUNNING"; message="검색 중: ${t.title}"; try { val result=Repository.resolve(this@MainActivity,t,true); messageStatus=result.status; message="${t.title}: ${when { result.status=="INSTRUMENTAL" -> "연주곡"; result.synced.isNotBlank() -> "싱크 가사 저장 완료"; result.status=="FOUND" -> "일반 가사 저장 완료"; else -> "가사 없음 · 편집에서 Google 검색 가능" }}" } catch(e: CancellationException){throw e} catch(e: Exception){messageStatus="ERROR";message="검색 오류: ${e.message}"} }}) { Text("검색") } }})
                            HorizontalDivider()
                        } }
                    }
                    1 -> {
                        Text(now.track?.title ?: "재생 중인 음악 없음",style=MaterialTheme.typography.titleLarge)
                        Text(now.track?.artist.orEmpty())
                        if(LyricDisplay.aiGenerated(now.lyric?.status,now.lyric?.source))Text("✦ AI 생성 가사",color=aiColor,fontSize=14.sp)
                        Text(if(now.playing) "Samsung Music 재생 중" else "재생 대기 / 일시 정지")
                        Text(if(!listenerAllowed)"알림 접근 권한 필요" else if(!listenerConnected)"음악 감지 서비스 연결 대기" else now.overlayStatus, fontSize=14.sp,color=if(listenerConnected&&now.overlayStatus.endsWith("표시 중"))successColor else neutralColor)
                        TextButton(onClick={if(listenerAllowed)NotificationListenerService.requestRebind(listenerComponent);Playback.retry.value++}){Text("삼성 뮤직 다시 연결 / 가사 확인")}
                        val lines=remember(now.lyric?.synced) { Lrc.parse(now.lyric?.synced.orEmpty()) }
                        if(lines.isNotEmpty()) {
                            val list=rememberLazyListState()
                            LaunchedEffect(now.index,lines) { if(now.index>=0) list.animateScrollToItem((now.index-2).coerceAtLeast(0)) }
                            LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)) { items(lines.size) { i -> Text(lines[i].text,style=if(i==now.index) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,color=if(i==now.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
                        } else if(!now.lyric?.plain.isNullOrBlank()){
                            val plain=now.lyric?.plain.orEmpty()
                            val rows=remember(plain){PlainSync.rows(plain)}
                            val estimates=remember(plain,now.track?.duration){PlainSync.estimated(plain,now.track?.duration?:0)}
                            val idx=Lrc.index(estimates,now.position)
                            val list=rememberLazyListState()
                            LaunchedEffect(idx,plainMode){if(plainMode==PlainSync.ESTIMATE&&idx>=0)list.animateScrollToItem((idx-1).coerceAtLeast(0))}
                            Text(if(plainMode==PlainSync.ESTIMATE)"일반 가사 · 추정 싱크" else "일반 가사 · 시간 정보 없음")
                            LazyColumn(state=list,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                items(rows.size){i->Text(rows[i],color=if(plainMode==PlainSync.ESTIMATE&&i==idx)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}
                            }
                        } else Text(now.message)
                    }
                    else -> {
                        Text("삼성 뮤직 연결",style=MaterialTheme.typography.titleLarge)
                        Text("알림 접근 권한은 삼성 뮤직 MediaSession을 읽기 위해 사용합니다. 알림 본문은 수집하지 않습니다.")
                        Text("필수 권한 · ${listOf(allowed,listenerAllowed,overlayAllowed).count{it}} / 3 허용",fontSize=16.sp,color=if(allowed&&listenerAllowed&&overlayAllowed)successColor else failureColor)
                        Text("음악 파일 접근: "+if(allowed)"허용됨" else "권한 필요",color=if(allowed)successColor else failureColor,fontSize=14.sp)
                        if(!allowed||!listenerAllowed||!overlayAllowed)Button(onClick={startAccessPass()}){Text("필수 권한 다시 설정")}
                        if(permissionError.isNotBlank())Text(permissionError,color=failureColor,fontSize=14.sp)
                        Button(onClick={requestAccess(RequiredAccess.LISTENER)}) { Text("알림 접근 설정") }
                        Button(onClick={requestAccess(RequiredAccess.OVERLAY)}) { Text("다른 앱 위에 표시 권한") }
                        Row(verticalAlignment=Alignment.CenterVertically) { Text("플로팅 가사",Modifier.weight(1f)); Switch(overlay,{overlay=it;prefs.edit().putBoolean("overlay",it).apply();if(it){if(!allowed||!listenerAllowed||!overlayAllowed)startAccessPass() else FloatingPermissions.rebind(this@MainActivity)}}) }
                        Text("음악 재생 중 홈 화면이나 다른 앱에서도 플로팅 가사를 표시합니다. 화면이 꺼지거나 재생이 멈추면 숨깁니다.")
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text("가사가 없는 음악도 표시",fontSize=16.sp)
                                Text("곡 제목, 검색 상태 및 AI 분석 진행률",fontSize=12.sp,color=neutralColor)
                            }
                            Switch(showMissingOverlay,{showMissingOverlay=it;prefs.edit().putBoolean(OverlayContent.SHOW_MISSING,it).apply()})
                        }
                        HorizontalDivider()
                        Text("일반 가사 플로팅 방식",fontSize=16.sp)
                        listOf(PlainSync.ESTIMATE to "자동 스크롤 (추정)",PlainSync.STATIC to "일반 가사 고정 표시",PlainSync.OFF to "곡 정보만 표시").forEach{(key,label)->
                            Row(verticalAlignment=Alignment.CenterVertically){
                                RadioButton(selected=plainMode==key,onClick={plainMode=key;prefs.edit().putString(PlainSync.MODE_KEY,key).apply()})
                                Text(label,fontSize=14.sp)
                            }
                        }
                        OutlinedButton(onClick={settingsScreen="position"},modifier=Modifier.fillMaxWidth()){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){
                                    Text("가사 표시 위치",fontSize=16.sp)
                                    Text("현재 위치: $overlayPosition%",fontSize=12.sp,color=neutralColor)
                                }
                                Text("›",fontSize=24.sp)
                            }
                        }
                        HorizontalDivider()
                        OutlinedButton(onClick={settingsScreen="test"},modifier=Modifier.fillMaxWidth()){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){
                                    Text("플로팅 실행 테스트",fontSize=16.sp)
                                    Text(if(testMode)"테스트 모드 ON" else "테스트 모드 OFF",fontSize=12.sp,color=if(testMode)successColor else neutralColor)
                                }
                                Text("›",fontSize=24.sp)
                            }
                        }
                        HorizontalDivider()
                        Text("온라인 검색 시 곡 제목, 가수, 앨범, 길이를 LRCLIB에 전송합니다. 음악 파일이나 알림은 전송하지 않습니다.")
                        HorizontalDivider()
                        if(updateSupported){
                            OutlinedButton(onClick={settingsScreen="update"},modifier=Modifier.fillMaxWidth()){
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                    Column(Modifier.weight(1f)){
                                        Text("앱 업데이트",fontSize=16.sp)
                                        Text("현재 "+AppUpdates.current(this@MainActivity)+(if(updateState.release!=null)" · "+updateState.release?.tag.orEmpty() else ""),fontSize=12.sp,color=neutralColor)
                                    }
                                    Text("›",fontSize=24.sp)
                                }
                            }
                            HorizontalDivider()
                        }
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
        }
        missing?.let { access ->
            AlertDialog(
                onDismissRequest={permissionPass=false},
                title={Text("플로팅 가사 권한 설정")},
                text={
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("${access.ordinal+1} / 3 · ${access.label}",fontSize=18.sp)
                        Text(access.explanation,fontSize=14.sp)
                        if(access==RequiredAccess.LISTENER)Text("설정에서 현재 설치한 LyriLayer 또는 LyriLayer Dev의 음악 감지 서비스를 허용하세요. Android 13 이상에서 제한된 설정 안내가 나오면 앱 정보에서 사용자 직접 허용이 필요할 수 있습니다.",fontSize=14.sp)
                        if(access==RequiredAccess.OVERLAY)Text("설정 목록에서 현재 앱을 찾아 다른 앱 위에 표시를 허용하세요.",fontSize=14.sp)
                        Text("권한은 직접 허용해야 합니다. 나중에 설정 탭에서 다시 요청할 수 있습니다.",fontSize=12.sp)
                    }
                },
                confirmButton={TextButton(onClick={requestAccess(access)}){Text(if(access==RequiredAccess.AUDIO)"권한 요청" else "설정 열기")}},
                dismissButton={TextButton(onClick={permissionPass=false}){Text("나중에")}}
            )
        }
        manualSearch?.let { t ->
            var term by remember(t){mutableStateOf(listOf(MusicRules.clean(t.artist),MusicRules.title(t.title)).filter{it.isNotBlank()}.joinToString(" "))}
            var results by remember(t){mutableStateOf(emptyList<Repository.SearchResult>())}
            var status by remember(t){mutableStateOf("제목과 가수를 수정해 검색할 수 있습니다.")}
            var searchStatus by remember(t){mutableStateOf("IDLE")}
            var busy by remember(t){mutableStateOf(false)}
            val searchScope=rememberCoroutineScope()
            AlertDialog(onDismissRequest={manualSearch=null},title={Text("가사 직접 검색")},text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedTextField(term,{term=it},label={Text("제목 / 가수")},singleLine=true)
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        Button(enabled=!busy&&term.isNotBlank(),onClick={searchScope.launch{
                            busy=true;searchStatus="RUNNING";status="LRCLIB 검색 중";results=emptyList()
                            try{results=Repository.searchCandidates(term);searchStatus=if(results.isEmpty())"NOT_FOUND" else "FOUND";status=if(results.isEmpty())"검색 결과 없음" else "곡과 재생 시간을 확인해 선택하세요."}
                            catch(e:CancellationException){throw e}catch(e:Exception){searchStatus="ERROR";status="검색 오류: ${e.message}"}
                            finally{busy=false}
                        }}){Text("LRCLIB 검색")}
                        OutlinedButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode(term+" 가사 lyrics"))))}){Text("Google")}
                    }
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status,color=resultColor(searchStatus))
                    Text("내 음원: ${t.duration/1000}초 · 선택한 가사는 자동으로 덮어쓰지 않습니다.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={manualSearch=null;openAi(t)}){Text("기기에서 AI 가사 추출")}
                    OutlinedButton(onClick={manualSearch=null;edit=t}){Text("내장 가사 / LRC 가져오기") }
                    results.forEach{r->
                        ListItem(headlineContent={Text(r.title)},supportingContent={Text("${r.artist} · ${r.album} · ${r.seconds.toInt()}초 · ${if(r.synced.isNotBlank())"싱크" else "일반"}")},trailingContent={TextButton(onClick={scope.launch{Repository.selectCandidate(this@MainActivity,t,r);messageStatus="FOUND";message="선택한 가사 저장 완료";manualSearch=null}}){Text("저장")}})
                        HorizontalDivider()
                    }
                }
            },confirmButton={TextButton(onClick={manualSearch=null;edit=t}){Text("가사 붙여넣기 / 편집")}},dismissButton={TextButton(onClick={manualSearch=null}){Text("닫기")}})
        }
        aiTrack?.let { t ->
            val previewLines=remember(aiPreview){Lrc.parse(aiPreview)}
            val previewState=rememberLazyListState()
            LaunchedEffect(previewLines.size){
                if(previewLines.isNotEmpty())previewState.scrollToItem(previewLines.lastIndex)
            }
            AlertDialog(onDismissRequest={if(!aiSaving)aiTrack=null},title={Text("AI 가사 분석")},text={
                Column(Modifier.heightIn(max=490.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(t.title,style=MaterialTheme.typography.titleMedium)
                    Text(aiStatus,color=if(aiFailed)failureColor else aiColor)
                    if(!aiRunning&&aiResult.isBlank()){
                        Text("가사 언어")
                        listOf("auto" to "자동 감지","ko" to "한국어","en" to "영어","ja" to "일본어").forEach{(code,label)->
                            Row(verticalAlignment=Alignment.CenterVertically){RadioButton(selected=aiLanguage==code,onClick={aiLanguage=code});Text(label)}
                        }
                    }
                    if(!modelReady)Text("설정에서 AI 모델을 먼저 다운로드하세요.")
                    Button(enabled=modelReady&&!aiRunning&&!aiSaving,onClick={
                        aiRunning=true;aiResult="";aiPreview="";aiProgress=0;aiFailed=false;aiStatus="AI 분석 준비"
                        val language=aiLanguage
                        val runId=++aiRunId
                        aiTask=scope.launch{
                            try{
                                aiResult=DeviceLyrics.extract(this@MainActivity,t,language,
                                    preview={raw->runOnUiThread{if(aiRunning&&aiRunId==runId)aiPreview=raw}}
                                ){n,stage->runOnUiThread{if(aiRunning&&aiRunId==runId){aiProgress=n.coerceIn(0,100);aiStatus=stage}}}
                                aiPreview=aiResult;aiProgress=100;aiStatus="AI 가사를 검토한 뒤 저장하세요."
                            }catch(e:CancellationException){aiFailed=true;aiStatus="분석 중단 · 미리보기는 저장되지 않습니다.";throw e}
                            catch(e:Exception){aiFailed=true;aiStatus="분석 오류: ${e.message}"}
                            catch(e:LinkageError){aiFailed=true;aiStatus="이 기기에서 AI 엔진을 불러올 수 없습니다."}
                            finally{aiRunning=false;aiTask=null}
                        }
                    }){Text(if(aiResult.isNotBlank())"다시 분석" else "AI 분석 시작")}
                    if(aiRunning||aiPreview.isNotBlank()||aiFailed){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Text(if(aiRunning)"분석 진행률" else if(aiFailed)"분석 중단 / 실패" else "분석 완료",color=aiColor)
                            Text("$aiProgress%",color=aiColor,fontSize=20.sp)
                        }
                        LinearProgressIndicator(progress={aiProgress/100f},modifier=Modifier.fillMaxWidth(),color=aiColor)
                        Text("실시간 가사 미리보기 · ${previewLines.size}줄",style=MaterialTheme.typography.titleSmall)
                        Text("25초 단위 음원 구간의 인식이 끝나면 자동 갱신됩니다.",fontSize=12.sp)
                        Card(Modifier.fillMaxWidth()){
                            if(previewLines.isEmpty())Text("첫 구간 인식 대기 중…",modifier=Modifier.padding(12.dp),fontSize=14.sp)
                            else LazyColumn(state=previewState,modifier=Modifier.fillMaxWidth().height(180.dp).padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                items(previewLines.size){i->
                                    val line=previewLines[i]
                                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                        Text(LyricDisplay.timestamp(line.ms),color=neutralColor,fontSize=12.sp)
                                        Text(line.text,Modifier.weight(1f),color=if(i==previewLines.lastIndex)aiColor else MaterialTheme.colorScheme.onSurface,fontSize=14.sp)
                                    }
                                }
                            }
                        }
                    }
                    if(aiRunning)Text("앱을 열어 두세요. 창을 닫아도 현재 화면에서 진행률을 확인할 수 있습니다.",fontSize=12.sp)
                    if(!aiRunning&&!aiFailed&&aiResult.isNotBlank())OutlinedTextField(aiResult,{aiResult=it},label={Text("AI 생성 LRC · 검토 및 수정")},modifier=Modifier.fillMaxWidth().heightIn(min=160.dp,max=240.dp))
                }
            },confirmButton={TextButton(enabled=!aiRunning&&!aiFailed&&!aiSaving&&Lrc.parse(aiResult).isNotEmpty(),onClick={
                aiSaving=true
                scope.launch{
                    try{
                        val raw=aiResult
                        db.dao().put(Lyric(t.key,Lrc.parse(raw).joinToString("\n"){it.text},raw,LyricDisplay.AI_SOURCE,"FOUND",edited=true))
                        messageStatus="AI";message="AI 가사 저장 완료";clearAi()
                    }catch(e:CancellationException){throw e}
                    catch(e:Exception){aiStatus="저장 오류: ${e.message}"}
                    finally{aiSaving=false}
                }
            }){Text("확인 후 저장")}},dismissButton={
                Row{
                    if(aiRunning)TextButton(onClick={aiTask?.cancel();aiStatus="현재 분석 구간 종료 후 중단합니다."}){Text("분석 중단")}
                    TextButton(enabled=!aiSaving,onClick={if(aiRunning)aiTrack=null else clearAi()}){Text(if(aiRunning)"목록 보기" else "닫기")}
                }
            })
        }
        syncTrack?.let{t->
            val lyric=map[t.key]
            if(lyric!=null)SyncManager(this@MainActivity,t,lyric,now,modelReady,onSaved={syncTrack=null;message="싱크 가사 저장 완료";messageStatus="FOUND"},onClose={syncTrack=null})
        }
        edit?.let { t ->
            val old=map[t.key]
            val clipboard=LocalClipboardManager.current
            var raw by remember(t) { mutableStateOf(old?.synced?.takeIf { it.isNotBlank() } ?: old?.plain.orEmpty()) }
            var offset by remember(t) { mutableStateOf((old?.offset ?: 0).toString()) }
            var localStatus by remember(t){mutableStateOf("")}
            var replacedSource by remember(t){mutableStateOf(false)}
            val pickLrc=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{raw=withContext(Dispatchers.IO){LocalLyrics.readText(this@MainActivity,uri)};replacedSource=true;localStatus="파일 가져오기 완료"}catch(e:Exception){localStatus="파일 읽기 오류: ${e.message}"}}}
            AlertDialog(onDismissRequest={edit=null},title={Text(t.title)},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())) { OutlinedTextField(raw,{raw=it},label={Text("LRC 또는 일반 가사")},modifier=Modifier.heightIn(max=240.dp)); OutlinedTextField(offset,{offset=it},label={Text("싱크 보정 (ms, +는 늦게)")}); TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com/search?q="+Uri.encode("${t.artist} ${t.title} 가사"))))}) { Text("Google에서 직접 검색") }; TextButton(onClick={clipboard.getText()?.text?.let { raw=it;replacedSource=true }}) { Text("복사한 가사 붙여넣기") }; TextButton(onClick={scope.launch{val l=withContext(Dispatchers.IO){LocalLyrics.find(this@MainActivity,t)};if(l!=null){raw=l.synced.ifBlank{l.plain};replacedSource=true;localStatus="내장 또는 로컬 가사 찾음"}else localStatus="내장 가사 없음 · LRC 파일을 직접 선택하세요"}}){Text("내장 가사 / 같은 이름 LRC 읽기")}; TextButton(onClick={pickLrc.launch(arrayOf("*/*"))}){Text("LRC 또는 TXT 파일 선택")}; if(localStatus.isNotBlank())Text(localStatus,style=MaterialTheme.typography.bodySmall) }},confirmButton={TextButton(onClick={scope.launch { val synced=Lrc.parse(raw).isNotEmpty(); db.dao().put(Lyric(t.key,if(synced) "" else raw,if(synced) raw else "",LyricDisplay.editedSource(old?.source,replacedSource),if(raw.isBlank()) "NOT_FOUND" else "FOUND",edited=raw.isNotBlank(),offset=offset.toLongOrNull() ?: 0));edit=null }}) { Text("저장") }},dismissButton={TextButton(onClick={edit=null}) { Text("취소") }})
        }
    }
}
