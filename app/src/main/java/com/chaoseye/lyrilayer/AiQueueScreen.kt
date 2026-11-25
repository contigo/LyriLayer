package com.chaoseye.lyrilayer

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
internal fun ColumnScope.AiQueueScreen(context:Context, tracks:List<Track>, lyrics:Map<String,Lyric>, modelReady:Boolean, onBack:()->Unit){
    val dao=remember{AiQueueDb.get(context).dao()}
    val entries by remember{dao.observe()}.collectAsStateWithLifecycle(initialValue=emptyList())
    val live by AiQueue.live.collectAsStateWithLifecycle()
    val notice by AiQueue.notice.collectAsStateWithLifecycle()
    var clock by remember{mutableLongStateOf(android.os.SystemClock.elapsedRealtime())}
    LaunchedEffect(live.running){while(live.running){clock=android.os.SystemClock.elapsedRealtime();delay(1000)}}
    LaunchedEffect(Unit){AiQueue.initialize(context)}
    var filter by remember{mutableStateOf("all")}
    var language by remember{mutableStateOf("auto")}
    var choose by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf(emptySet<String>())}
    var review by remember{mutableStateOf<AiQueueEntry?>(null)}
    val keys=remember(entries){entries.map{it.key}.toSet()}
    val candidates=remember(tracks,lyrics,keys){tracks.distinctBy{it.key}.mapNotNull{t->if(t.key in keys)null else AiQueueRules.kind(t,lyrics[t.key])?.let{t to it}}}
    val (missing,plain)=remember(entries){AiQueueRules.countKinds(entries)}
    val availableMissing=candidates.count{it.second==AiQueueRules.EXTRACT}
    val availablePlain=candidates.count{it.second==AiQueueRules.ALIGN}
    val visibleEntries=remember(entries,filter){AiQueueRules.filtered(entries,filter)}
    val pending=entries.count{it.state=="pending"}
    val progress=remember(entries,live.key,live.percent){AiQueueProgress.calculate(entries,live.key,live.percent)}
    val done=progress.done
    val percent=progress.percent
    Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick=onBack){Text("‹ 뒤로")};Text("AI 분석 목록",fontSize=24.sp)}
    Text("대상: 가사 없음 "+missing+" · 일반 "+plain+" / 추가 가능: "+availableMissing+" · "+availablePlain,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment=Alignment.CenterVertically){
        Text("분석 언어",Modifier.weight(1f),fontSize=13.sp)
        listOf("auto" to "자동","ko" to "한","en" to "영","ja" to "일").forEach{(key,label)->TextButton(onClick={language=key},contentPadding=PaddingValues(2.dp),modifier=Modifier.width(40.dp)){Text(if(language==key)"[$label]" else label)}}
    }
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Button(enabled=candidates.isNotEmpty(),onClick={AiQueue.enqueue(context,candidates.map{it.first},language)},modifier=Modifier.weight(1f)){Text("전체 추가")}
        OutlinedButton(enabled=candidates.isNotEmpty(),onClick={selected=emptySet();choose=true},modifier=Modifier.weight(1f)){Text("곡 선택")}
    }
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("전체 진행률 $percent% · $done/"+entries.size+"곡",fontSize=18.sp)
            LinearProgressIndicator(progress={percent/100f},modifier=Modifier.fillMaxWidth())
            Text("대기 $pending · 검토 "+entries.count{it.state=="review"}+" · 저장 "+entries.count{it.state=="saved"}+" · 실패 "+entries.count{it.state=="failed"},fontSize=13.sp)
            if(live.running){
                Text(live.stage+" · "+live.percent+"%",fontSize=13.sp)
                LinearProgressIndicator(progress={live.percent/100f},modifier=Modifier.fillMaxWidth())
                if(live.startedAt>0){
                    val elapsed=(clock-live.startedAt).coerceAtLeast(0)
                    val remaining=AiPerformance.remainingMs(elapsed,live.percent)
                    Text("현재 곡 "+AiPerformance.time(elapsed)+(remaining?.let{" · 남은 시간 약 "+AiPerformance.time(it)}?:""),fontSize=12.sp)
                    if(live.completedCount>0){
                        val average=live.elapsedTotal/live.completedCount
                        Text("평균 "+AiPerformance.time(average)+" / 곡 · 대기 곡 약 "+AiPerformance.time(average*pending),fontSize=12.sp)
                    }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                if(live.running)Button(enabled=!live.stopping,onClick={AiQueue.pause()},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)){Text(if(live.stopping)"중단 중…" else "분석 중단")}
                else Button(enabled=pending>0&&(modelReady||OnlineLyricsSettings.enabled(context)),onClick={AiQueue.start(context)},modifier=Modifier.fillMaxWidth()){Text("분석 시작 / 재개")}
            }
            if(!modelReady)Text("온라인 가사를 먼저 검색합니다. 검색 실패 시 AI 모델이 필요합니다.",fontSize=12.sp)
        }
    }
    if(notice.isNotBlank())Text(notice,fontSize=12.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        AiQueueRules.tabs.forEach{(id,label)->
            val count=entries.count{AiQueueRules.visible(id,it.state)}
            FilterChip(selected=filter==id,onClick={filter=id},label={Text("$label $count",maxLines=1)})
        }
    }
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
        if(visibleEntries.isEmpty())item{Text("해당 상태의 곡이 없습니다.",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(visibleEntries,key={it.key}){item->
            Column(Modifier.fillMaxWidth()){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(item.title,style=MaterialTheme.typography.titleSmall)
                        Text(item.artist+" · "+if(item.kind==AiQueueRules.ALIGN)"AI 자동 싱크" else "AI 가사 추출",fontSize=12.sp)
                        Text(when(item.state){"pending"->"대기";"running"->"분석 중 "+live.percent+"%";"review"->"검토 필요";"saved"->"저장 완료";"failed"->"실패";else->"건너뜀"},color=if(item.state=="failed")MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,fontSize=13.sp)
                        if(item.message.isNotBlank())Text(item.message,fontSize=12.sp)
                    }
                    if(item.state!="running"){
                        var menu by remember(item.key){mutableStateOf(false)}
                        Box{IconButton(onClick={menu=true}){Text("⋮",fontSize=24.sp)};DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                            if(item.state=="review")DropdownMenuItem(text={Text("검토")},onClick={menu=false;review=item})
                            DropdownMenuItem(text={Text("제거")},onClick={menu=false;AiQueue.remove(context,item.key)})
                        }}
                    }
                }
                if(item.state=="running"&&live.key==item.key){
                    LinearProgressIndicator(progress={live.percent/100f},modifier=Modifier.fillMaxWidth())
                    Text("실시간 가사 분석 미리보기",fontSize=12.sp)
                    Lrc.parse(live.preview).takeLast(3).forEach{line->Text("["+LyricDisplay.timestamp(line.ms)+"] "+line.text,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                HorizontalDivider()
            }
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={AiQueue.retry(context)},enabled=entries.any{it.state=="failed"}){Text("실패 곡 재시도")};TextButton(onClick={AiQueue.clearFinished(context)}){Text("완료 목록 정리")}}
    if(choose)AlertDialog(onDismissRequest={choose=false},title={Text("분석 대상 선택")},text={
        Column(Modifier.heightIn(max=400.dp)){
            Row{TextButton(onClick={selected=candidates.filter{it.second==AiQueueRules.EXTRACT}.map{it.first.key}.toSet()}){Text("가사 없음")};TextButton(onClick={selected=candidates.filter{it.second==AiQueueRules.ALIGN}.map{it.first.key}.toSet()}){Text("일반 가사")};TextButton(onClick={selected=emptySet()}){Text("해제")}}
            LazyColumn(Modifier.weight(1f,fill=false)){items(candidates,key={it.first.key}){(t,kind)->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(t.key in selected,{checked->selected=if(checked)selected+t.key else selected-t.key});Column{Text(t.title);Text(if(kind==AiQueueRules.ALIGN)"일반 가사 → 싱크" else "가사 없음 → AI 생성",fontSize=12.sp)}}}}
        }
    },confirmButton={TextButton(enabled=selected.isNotEmpty(),onClick={AiQueue.enqueue(context,candidates.filter{it.first.key in selected}.map{it.first},language);choose=false}){Text(""+selected.size+"곡 추가")}},dismissButton={TextButton(onClick={choose=false}){Text("취소")}})
    review?.let{item->
        var draft by remember(item.key){mutableStateOf(item.draft)}
        val current=entries.find{it.key==item.key}
        LaunchedEffect(current?.state){if(current?.state!="review")review=null}
        AlertDialog(onDismissRequest={review=null},title={Text("AI 가사 검토")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){Text(item.title);Text(item.message,fontSize=13.sp);if(item.kind==AiQueueRules.ALIGN)Text("원문은 유지하고 시간만 수정하세요.",fontSize=12.sp);OutlinedTextField(draft,{draft=it},label={Text("LRC")},modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=300.dp));if(notice.isNotBlank())Text(notice,fontSize=12.sp)}},confirmButton={TextButton(enabled=Lrc.parse(draft).any{it.text.isNotBlank()},onClick={AiQueue.approve(context,item.key,draft)}){Text("검토 후 저장")}},dismissButton={TextButton(onClick={review=null}){Text("닫기")}})
    }
}
