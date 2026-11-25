package com.chaoseye.lyrilayer

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ColumnScope.MusicRecognitionScreen(context:Context,tracks:List<Track>,info:Map<String,MusicInfo>,enabled:Boolean,onBack:()->Unit){
    val live by MusicRecognitionBatch.live.collectAsStateWithLifecycle()
    val prefs=remember{context.getSharedPreferences("settings",Context.MODE_PRIVATE)}
    var retry by remember{mutableStateOf(prefs.getBoolean("fingerprint_retry",true))}
    var lyrics by remember{mutableStateOf(prefs.getBoolean("fingerprint_lyrics",true))}
    var consent by remember{mutableStateOf(false)}
    var filter by remember{mutableStateOf("all")}
    var selection by remember{mutableStateOf(emptySet<String>())}
    val eligible=remember(tracks){tracks.filter{!it.excluded&&it.uri.isNotBlank()&&it.duration>=2000}.distinctBy{it.key}}
    val pending=eligible.filter{info[it.key]?.let{m->m.userConfirmed||m.provider=="SHAZAM_FINGERPRINT"}!=true}
    val target=pending.filter{selection.isEmpty()||it.key in selection}
    fun status(t:Track):String=if(info[t.key]?.let{it.userConfirmed||it.provider=="SHAZAM_FINGERPRINT"}==true)"done" else live.rows[t.key]?.state?:"pending"
    Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick=onBack){Text("‹ 뒤로")};Text("음원 지문 검색",fontSize=22.sp)}
    Text("최대 12초 분석 · 원본 파일 변경 없음",fontSize=12.sp)
    Text("실험적 비공식 Shazam 연동. 서비스 허용 범위에서 사용하세요. 음원 지문과 요청 메타데이터만 전송합니다.",fontSize=12.sp)
    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it},enabled=!live.running);Text("비공식 서버 검색에 동의",fontSize=13.sp)}
    Row(verticalAlignment=Alignment.CenterVertically){Text("다른 구간 재시도",Modifier.weight(1f),fontSize=13.sp);Switch(retry,{retry=it;prefs.edit().putBoolean("fingerprint_retry",it).apply()},enabled=!live.running)}
    Row(verticalAlignment=Alignment.CenterVertically){Text("검색 후 가사 수집",Modifier.weight(1f),fontSize=13.sp);Switch(lyrics,{lyrics=it;prefs.edit().putBoolean("fingerprint_lyrics",it).apply()},enabled=!live.running)}
    Text("전체 "+eligible.size+"곡 · 미식별 "+pending.size+"곡 · 대상 "+target.size+"곡",fontSize=13.sp)
    if(live.running)Button(onClick={MusicRecognitionBatch.stop()},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("검색 중단")}
    else Button(enabled=enabled&&consent&&target.isNotEmpty(),onClick={MusicRecognitionBatch.start(context,target,retry,lyrics,consent)},modifier=Modifier.fillMaxWidth()){Text("음원 정보 검색 시작")}
    if(live.total>0){
        val progress=(live.done.toFloat()/live.total).coerceIn(0f,1f)
        LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
        Text(live.done.toString()+"/"+live.total+" · "+(progress*100).toInt()+"% "+live.current+" "+live.message,fontSize=12.sp)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
        listOf("all" to "전체","pending" to "대기","running" to "검색 중","done" to "완료","failed" to "실패").forEach{(id,label)->
            FilterChip(selected=filter==id,onClick={filter=id},label={Text(label+" "+eligible.count{id=="all"||status(it)==id})})
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically){Text("선택 "+selection.size+"곡 (0 = 전체 대상)",Modifier.weight(1f),fontSize=12.sp);TextButton(enabled=!live.running,onClick={selection=emptySet()}){Text("선택 해제")}}
    LazyColumn(Modifier.weight(1f)){
        items(eligible.filter{filter=="all"||status(it)==filter},key={it.key}){t->
            Row(verticalAlignment=Alignment.CenterVertically){
                Checkbox(t.key in selection,{selection=if(it)selection+t.key else selection-t.key},enabled=!live.running&&status(t)!="done")
                Column(Modifier.weight(1f)){
                    Text(info[t.key]?.title?:t.title,maxLines=1)
                    Text((info[t.key]?.artist?:t.artist)+" · "+t.fileName,fontSize=12.sp,maxLines=2)
                    live.rows[t.key]?.message?.takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp)}
                }
                Text(when(status(t)){"done"->"완료";"failed"->"실패";"running"->"검색 중";else->"대기"},fontSize=12.sp)
            }
            HorizontalDivider()
        }
    }
}
