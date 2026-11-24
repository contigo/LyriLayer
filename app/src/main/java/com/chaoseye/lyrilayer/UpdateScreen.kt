package com.chaoseye.lyrilayer
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun UpdateScreen(context:Context){
    val prefs=remember{context.getSharedPreferences("settings",Context.MODE_PRIVATE)}
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    var autoCheck by remember{mutableStateOf(prefs.getBoolean(AppUpdates.AUTO_CHECK,true))}
    var autoDownload by remember{mutableStateOf(prefs.getBoolean(AppUpdates.AUTO_DOWNLOAD,true))}
    Text("현재 버전: "+AppUpdates.current(context),fontSize=18.sp)
    Text("배포 채널: GitHub 개발 버전",fontSize=14.sp)
    Row(verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text("앱 시작 시 업데이트 확인");Text("최신 개발 버전 자동 확인",fontSize=12.sp)}
        Switch(autoCheck,{autoCheck=it;prefs.edit().putBoolean(AppUpdates.AUTO_CHECK,it).apply()})
    }
    Row(verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text("자동 다운로드");Text("비종량제 네트워크에서만",fontSize=12.sp)}
        Switch(autoDownload,{autoDownload=it;prefs.edit().putBoolean(AppUpdates.AUTO_DOWNLOAD,it).apply()})
    }
    HorizontalDivider()
    val release=state.release
    if(release!=null){
        Text("최신 버전: "+release.tag,fontSize=20.sp)
        Text("APK 용량: "+String.format(java.util.Locale.ROOT,"%.1f MB",release.size/1_000_000.0),fontSize=14.sp)
    }
    if(state.message.isNotBlank())Text(state.message,color=if(state.stage=="error")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if(state.stage=="downloading"){
        LinearProgressIndicator(progress={state.percent/100f},modifier=Modifier.fillMaxWidth())
        Text("다운로드 "+state.percent+"%")
    }else if(state.stage=="checking")LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(onClick={AppUpdates.check(context)},enabled=state.stage!="checking"&&state.stage!="downloading",modifier=Modifier.fillMaxWidth()){Text("업데이트 확인")}
    if(state.stage=="available"||(state.stage=="error"&&release!=null))Button(onClick={AppUpdates.download(context)},modifier=Modifier.fillMaxWidth()){Text("업데이트 다운로드")}
    if(state.stage=="ready")Button(onClick={AppUpdates.install(context)},modifier=Modifier.fillMaxWidth()){Text("업데이트 설치")}
    Text("APK 패키지, 버전, SHA-256 및 고정 서명 인증서를 확인합니다. 설치 시 Android 사용자 확인이 필요합니다.",fontSize=13.sp)
    if(release!=null){HorizontalDivider();Text("릴리즈 정보",style=MaterialTheme.typography.titleMedium);Text(release.notes,fontSize=13.sp)}
}
