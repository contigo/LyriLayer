package com.chaoseye.lyrilayer

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable private fun GlyphButton(name:String,label:String,onClick:()->Unit,enabled:Boolean=true){
    IconButton(onClick=onClick,enabled=enabled){RecognitionGlyph(name,Modifier.size(22.dp).semantics{contentDescription=label})}
}
@Composable private fun StagePill(icon:String,label:String,kind:String,modifier:Modifier=Modifier){
    val c=MaterialTheme.colorScheme
    val bg=when(kind){"active"->c.primaryContainer;"done"->c.secondaryContainer;"error"->c.errorContainer;else->c.surfaceVariant}
    val fg=when(kind){"active"->c.onPrimaryContainer;"done"->c.onSecondaryContainer;"error"->c.onErrorContainer;else->c.onSurfaceVariant}
    Surface(modifier=modifier,shape=RoundedCornerShape(8.dp),color=bg,contentColor=fg){
        Row(Modifier.padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
            if(kind=="active")CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=1.7.dp,color=fg)
            else RecognitionGlyph(when(kind){"done"->"check";"error"->"alert";else->icon},Modifier.size(15.dp),fg)
            Text(label,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun RowScope.StatCell(label:String,count:Int,icon:String,selected:Boolean,onClick:()->Unit){
    val c=MaterialTheme.colorScheme
    Surface(onClick=onClick,modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp),color=if(selected)c.primaryContainer else c.surfaceContainerHigh,contentColor=if(selected)c.onPrimaryContainer else c.onSurface){
        Column(Modifier.padding(horizontal=8.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                RecognitionGlyph(icon,Modifier.size(15.dp));Text(label,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Text(count.toString(),style=MaterialTheme.typography.titleLarge)
        }
    }
}
@Composable internal fun ColumnScope.MusicRecognitionScreen(context:Context,tracks:List<Track>,info:Map<String,MusicInfo>,lyrics:Map<String,Lyric>,enabled:Boolean,onBack:()->Unit){
    val live by MusicRecognitionBatch.live.collectAsStateWithLifecycle()
    val prefs=remember{context.getSharedPreferences("settings",Context.MODE_PRIVATE)}
    var retry by remember{mutableStateOf(prefs.getBoolean("fingerprint_retry",true))}
    var consent by rememberSaveable{mutableStateOf(false)}
    var filter by rememberSaveable{mutableStateOf("all")}
    var selection by remember{mutableStateOf(emptySet<String>())}
    var settingsOpen by rememberSaveable{mutableStateOf(false)}
    var infoOpen by rememberSaveable{mutableStateOf(false)}
    var detailKey by rememberSaveable{mutableStateOf<String?>(null)}
    val colors=MaterialTheme.colorScheme
    val eligible=remember(tracks){tracks.filter{!it.excluded&&it.uri.isNotBlank()&&it.duration>=2000&&!MusicRules.excluded("",it.fileName,it.title)}.distinctBy{it.key}}
    val pending=remember(eligible,info,lyrics){eligible.filter{FingerprintPipelineRules.needsWork(info[it.key],lyrics[it.key])}}
    val pendingKeys=remember(pending){pending.map{it.key}.toSet()}
    val selected=selection.intersect(pendingKeys)
    val target=if(selection.isEmpty())pending else pending.filter{it.key in selected}
    fun status(t:Track):String{
        val row=live.rows[t.key]
        if(row?.state=="running")return "running"
        if(!FingerprintPipelineRules.needsWork(info[t.key],lyrics[t.key]))return "done"
        if(row?.state in setOf("failed","partial","pending"))return row!!.state
        return "pending"
    }
    val counts=eligible.groupingBy{status(it)}.eachCount()
    val visible=eligible.filter{filter=="all"||if(filter=="todo")status(it)!="done" else status(it)==filter}.sortedBy{if(status(it)=="running")0 else 1}
    val percent=FingerprintPipelineRules.percent(live.done,live.total)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        GlyphButton("back","뒤로",onBack)
        Text("음원 · 가사 검색",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
        GlyphButton("info","검색 및 개인정보 안내",{infoOpen=true})
        GlyphButton("tune","검색 설정",{settingsOpen=true})
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        StatCell("전체",eligible.size,"music",filter=="all"){filter="all"}
        StatCell("미완료",pending.size,"clock",filter=="todo"){filter="todo"}
        StatCell("완료",counts["done"]?:0,"check",filter=="done"){filter="done"}
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Checkbox(checked=consent,onCheckedChange={if(it)infoOpen=true else consent=false},enabled=!live.running)
        Text("비공식 서버 검색 동의",fontSize=12.sp,modifier=Modifier.weight(1f).clickable(enabled=!live.running){infoOpen=true})
        Text("대상 "+target.size+"곡",fontSize=12.sp,color=colors.onSurfaceVariant)
    }
    if(live.running){
        Button(onClick={MusicRecognitionBatch.stop()},enabled=!live.stopping,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=colors.error,contentColor=colors.onError)){
            RecognitionGlyph("stop",Modifier.size(17.dp));Spacer(Modifier.width(8.dp));Text(if(live.stopping)"중단 중" else "검색 중단")
        }
    }else Button(enabled=enabled&&consent&&target.isNotEmpty(),onClick={MusicRecognitionBatch.start(context,target,retry,consent)},modifier=Modifier.fillMaxWidth()){
        RecognitionGlyph("play",Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("통합 검색 시작")
    }
    if(live.total>0){
        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=colors.surfaceContainer)){
            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("전체 진행",Modifier.weight(1f),fontSize=12.sp,color=colors.onSurfaceVariant)
                    Text(live.done.toString()+"/"+live.total+"곡",fontSize=12.sp);Spacer(Modifier.width(8.dp));Text("$percent%",style=MaterialTheme.typography.titleMedium,color=colors.primary)
                }
                LinearProgressIndicator(progress={percent/100f},modifier=Modifier.fillMaxWidth())
                val currentKey=live.currentKey
                if(live.running&&currentKey!=null){
                    val findingLyrics=live.stage=="가사 검색 및 저장"
                    val identified=FingerprintPipelineRules.identified(info[currentKey])
                    Text(info[currentKey]?.title?:live.current,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        StagePill("scan",if(findingLyrics)if(identified)"음원 확인" else "기존 정보" else "음원 식별",if(!findingLyrics)"active" else if(identified)"done" else "idle",Modifier.weight(1f))
                        StagePill("lyrics",if(findingLyrics)"가사 검색·저장" else "가사 대기",if(findingLyrics)"active" else "idle",Modifier.weight(1f))
                    }
                }
                if(live.message.isNotBlank())Text(live.message,fontSize=12.sp,color=colors.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.clickable{infoOpen=true})
            }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
        listOf("all" to "전체","pending" to "대기","running" to "진행","done" to "완료","partial" to "부분","failed" to "실패").forEach{(id,label)->
            FilterChip(selected=filter==id,onClick={filter=id},label={Text(label+" "+(if(id=="all")eligible.size else counts[id]?:0),fontSize=12.sp)})
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(if(selection.isEmpty())"미완료 전체 대상" else selected.size.toString()+"곡 선택",fontSize=11.sp,color=colors.onSurfaceVariant,modifier=Modifier.weight(1f))
        GlyphButton("select","미완료 전체 선택",{selection=pendingKeys},enabled=!live.running&&pendingKeys.isNotEmpty())
        GlyphButton("close","선택 해제",{selection=emptySet()},enabled=!live.running&&selection.isNotEmpty())
    }
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=12.dp)){
        if(visible.isEmpty())item{Text("표시할 곡 없음",Modifier.padding(12.dp),color=colors.onSurfaceVariant,fontSize=13.sp)}
        items(visible,key={it.key}){t->
            val state=status(t)
            val identified=FingerprintPipelineRules.identified(info[t.key]);val ready=FingerprintPipelineRules.lyricReady(lyrics[t.key])
            val identityActive=state=="running"&&live.currentKey==t.key&&live.stage=="음원 지문 검색"
            val lyricActive=state=="running"&&live.currentKey==t.key&&live.stage=="가사 검색 및 저장"
            Surface(shape=RoundedCornerShape(12.dp),color=if(state=="running")colors.primaryContainer.copy(alpha=.45f) else colors.surfaceContainerLow){
                Row(Modifier.fillMaxWidth().padding(end=4.dp),verticalAlignment=Alignment.CenterVertically){
                    Checkbox(checked=t.key in selected,onCheckedChange={selection=if(it)selection+t.key else selection-t.key},enabled=!live.running&&state!="done")
                    Column(Modifier.weight(1f).clickable{detailKey=t.key}.padding(vertical=9.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Text(info[t.key]?.title?:t.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text((info[t.key]?.artist?:t.artist).ifBlank{"가수 미확인"},fontSize=12.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                            StagePill("scan",if(identityActive)"식별 중" else if(identified)"음원 확인" else "음원 미확인",if(identityActive)"active" else if(identified)"done" else if(state in setOf("partial","failed"))"error" else "idle",Modifier.weight(1f))
                            StagePill("lyrics",if(lyricActive)"가사 검색" else if(ready)when{lyrics[t.key]?.status=="INSTRUMENTAL"->"연주곡";lyrics[t.key]?.synced?.isNotBlank()==true->"싱크 가사";else->"일반 가사"}else "가사 미수집",if(lyricActive)"active" else if(ready)"done" else if(state in setOf("partial","failed"))"error" else "idle",Modifier.weight(1f))
                        }
                    }
                    GlyphButton("chevron","곡 상세 정보",{detailKey=t.key})
                }
            }
        }
    }
    if(infoOpen)AlertDialog(onDismissRequest={infoOpen=false},title={Text("음원 · 가사 검색 안내")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("음원 지문 식별 → 온라인 가사 검색 → 저장 → 다음 곡")
        Text("비공식 Shazam 서버에 음원 지문과 요청 메타데이터를 전송합니다. 가사 제공처에는 곡 정보가 전송됩니다. 원본 음원은 전송하지 않으며 파일을 변경하지 않습니다.",fontSize=13.sp)
        Text("서비스 허용 범위에서 사용하세요. 서버 오류나 호출 제한 시 중단합니다.",fontSize=12.sp)
        if(live.message.isNotBlank())Text(live.message,fontSize=12.sp)
    }},confirmButton={TextButton(onClick={if(!live.running)consent=true;infoOpen=false}){Text(if(live.running)"확인" else "동의")}},dismissButton={TextButton(onClick={infoOpen=false}){Text("닫기")}})
    if(settingsOpen)AlertDialog(onDismissRequest={settingsOpen=false},title={Text("검색 설정")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){RecognitionGlyph("scan");Spacer(Modifier.width(8.dp));Text("다른 구간 재시도",Modifier.weight(1f));Switch(retry,{retry=it;prefs.edit().putBoolean("fingerprint_retry",it).apply()},enabled=!live.running)}
        Row(verticalAlignment=Alignment.CenterVertically){RecognitionGlyph("lyrics");Spacer(Modifier.width(8.dp));Text("자동 가사 수집",Modifier.weight(1f));RecognitionGlyph("check",color=colors.primary)}
        Text("가사 수집은 필수 단계입니다. 이미 저장된 가사는 유지합니다.",fontSize=12.sp,color=colors.onSurfaceVariant)
    }},confirmButton={TextButton(onClick={settingsOpen=false}){Text("닫기")}})
    detailKey?.let{key->
        val t=eligible.firstOrNull{it.key==key}
        if(t!=null){
            val row=live.rows[key];val m=info[key];val l=lyrics[key]
            AlertDialog(onDismissRequest={detailKey=null},title={Text(m?.title?:t.title)},text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(m?.artist?:t.artist);Text(t.fileName,fontSize=12.sp,color=colors.onSurfaceVariant)
                HorizontalDivider();Text("음원 식별",style=MaterialTheme.typography.titleSmall);Text(row?.identity?.takeIf{it.isNotBlank()}?:if(FingerprintPipelineRules.identified(m))"확인 완료" else "미확인",fontSize=13.sp)
                Text("가사 수집",style=MaterialTheme.typography.titleSmall);Text(row?.lyrics?.takeIf{it.isNotBlank()}?:FingerprintPipelineRules.lyricLabel(l),fontSize=13.sp)
                row?.message?.takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp)}
            }},confirmButton={TextButton(onClick={detailKey=null}){Text("닫기")}})
        }
    }
}
