package com.chaoseye.lyrilayer

import android.content.Context
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
import kotlinx.coroutines.*

@Composable
internal fun SyncManager(context:Context,track:Track,lyric:Lyric,now:NowPlaying,modelReady:Boolean,onSaved:()->Unit,onClose:()->Unit,onManualEdit:()->Unit){
    val scope=rememberCoroutineScope()
    val original=remember{lyric.plain.ifBlank{Lrc.parse(lyric.synced).joinToString("\n"){it.text}}}
    val rows=remember(original){PlainSync.rows(original)}
    var mode by remember{mutableStateOf("menu")}
    var source by remember{mutableStateOf(PlainSync.MANUAL_SOURCE)}
    var times by remember{mutableStateOf(List(rows.size){""})}
    var uncertain by remember{mutableStateOf(emptySet<Int>())}
    var running by remember{mutableStateOf(false)}
    var saving by remember{mutableStateOf(false)}
    var progress by remember{mutableIntStateOf(0)}
    var status by remember{mutableStateOf("")}
    var task by remember{mutableStateOf<Job?>(null)}
    var language by remember{mutableStateOf("auto")}
    var preview by remember{mutableStateOf(emptyList<Line>())}
    var exportData by remember{mutableStateOf("")}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null)scope.launch{
            try{
                val data=exportData
                withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(data.toByteArray(Charsets.UTF_8))}?:error("파일을 열 수 없습니다")}
                status="LRC 파일 저장 완료"
            }catch(e:CancellationException){throw e}catch(e:Exception){status="내보내기 오류: "+e.message}
        }
    }
    val values=times.map(PlainSync::parseTime)
    val valid=PlainSync.valid(values,track.duration)
    val draft=if(valid)PlainSync.lrc(rows.mapIndexed{i,text->Line(values[i]!!,text)}) else ""
    val matching=now.libraryKey==track.key
    val next=times.indexOfFirst{it.isBlank()}
    fun load(lines:List<Line>,kind:String){
        times=lines.map{LyricDisplay.timestamp(it.ms)};source=kind;uncertain=emptySet();mode="edit"
    }
    fun startAi(){
        running=true;progress=0;preview=emptyList();status="AI 분석 준비";mode="ai"
        task=scope.launch{
            try{
                val result=DeviceLyrics.extract(context,track,language,preview={raw->
                    scope.launch{if(running)preview=Lrc.parse(raw)}
                }){n,stage->scope.launch{if(running){progress=n;status=stage}}}
                status="원본 가사와 인식 시간 매칭 중"
                val marks=withContext(Dispatchers.Default){PlainSync.align(original,Lrc.parse(result),track.duration)}
                if(marks.isEmpty()){status="신뢰할 만한 매칭을 찾지 못했습니다. 수동 싱크를 사용해 주세요.";mode="menu"}
                else{
                    load(marks.map{Line(it.ms,it.text)},PlainSync.AI_SOURCE)
                    uncertain=marks.indices.filter{!marks[it].matched}.toSet()
                    status="원문 유지 · 매칭 "+(marks.size-uncertain.size)+"/"+marks.size+"줄 · 추정 "+uncertain.size+"줄 검토 필요"
                }
            }catch(e:CancellationException){status="분석 중단";mode="menu";throw e}
            catch(e:Exception){status="AI 싱크 오류: "+e.message;mode="menu"}
            catch(e:LinkageError){status="AI 엔진을 사용할 수 없습니다";mode="menu"}
            finally{running=false;task=null}
        }
    }
    fun exportLrc(raw:String){
        exportData=raw
        export.launch(track.title.replace(Regex("""[\\/:*?"<>|]"""),"_").take(100)+".lrc")
    }
    val previewState=rememberLazyListState()
    LaunchedEffect(preview.size){if(preview.isNotEmpty())previewState.scrollToItem(preview.lastIndex)}
    AlertDialog(onDismissRequest={if(!running&&!saving)onClose()},title={Text(when(mode){"edit"->"가사 싱크 편집";"ai"->"AI 자동 싱크 생성";"plain"->"일반 가사";else->"가사 관리"})},text={
        Column(Modifier.fillMaxWidth().heightIn(max=470.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(track.title,style=MaterialTheme.typography.titleMedium)
            if(status.isNotBlank())Text(status,fontSize=13.sp)
            when(mode){
                "menu"->{
                    Text("원본 가사 "+rows.size+"줄 · 원문 유지",fontSize=14.sp)
                    Button(onClick={mode="plain"},modifier=Modifier.fillMaxWidth()){Text("일반 가사 보기")}
                    OutlinedButton(enabled=rows.isNotEmpty()&&track.duration>0,onClick={
                        load(PlainSync.estimated(original,track.duration),PlainSync.ESTIMATE_SOURCE)
                        uncertain=rows.indices.toSet();status="재생 시간 균등 분배 · 실제 싱크가 아닙니다. 검토 후 저장하세요."
                    },modifier=Modifier.fillMaxWidth()){Text("추정 자동 스크롤 / LRC 생성")}
                    OutlinedButton(enabled=rows.isNotEmpty(),onClick=onManualEdit,modifier=Modifier.fillMaxWidth()){Text("수동 싱크 편집")}
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("AI 언어",Modifier.weight(1f))
                        listOf("auto" to "자동","ko" to "한","en" to "영","ja" to "일").forEach{(key,label)->
                            TextButton(onClick={language=key},contentPadding=PaddingValues(2.dp),modifier=Modifier.width(42.dp)){Text(if(language==key)"[$label]" else label)}
                        }
                    }
                    OutlinedButton(enabled=modelReady&&rows.isNotEmpty()&&track.duration>0,onClick={startAi()},modifier=Modifier.fillMaxWidth()){Text("✦ AI 자동 싱크 생성")}
                    if(!modelReady)Text("AI 모델은 설정에서 먼저 다운로드하세요.",fontSize=12.sp)
                    OutlinedButton(enabled=Lrc.parse(lyric.synced).isNotEmpty(),onClick={
                        exportLrc(PlainSync.lrc(Lrc.parse(lyric.synced).map{it.copy(ms=(it.ms+lyric.offset).coerceAtLeast(0))}))
                    },modifier=Modifier.fillMaxWidth()){Text("저장된 LRC 내보내기")}
                }
                "plain"->LazyColumn(Modifier.weight(1f,fill=false)){items(rows){Text(it,Modifier.padding(vertical=4.dp))}}
                "ai"->{
                    Text("진행률 $progress%",fontSize=20.sp)
                    LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
                    Text("실시간 인식 미리보기 · "+preview.size+"줄",fontSize=14.sp)
                    LazyColumn(state=previewState,modifier=Modifier.weight(1f,fill=false)){items(preview){Text("["+LyricDisplay.timestamp(it.ms)+"] "+it.text,Modifier.padding(vertical=4.dp),fontSize=13.sp)}}
                    Text("25초 음원 구간별 갱신 · 인식 결과와 원문을 분석 후 비교합니다.",fontSize=12.sp)
                }
                "edit"->{
                    Text(if(matching)"현재 재생 위치: "+LyricDisplay.timestamp(now.position) else "삼성 뮤직에서 이 곡을 재생하세요. 시간 직접 입력도 가능합니다.",fontSize=13.sp)
                    Button(enabled=matching&&now.playing&&next>=0,onClick={
                        times=times.toMutableList().apply{set(next,LyricDisplay.timestamp(now.position))}
                        uncertain=uncertain-next
                    },modifier=Modifier.fillMaxWidth()){Text("현재 가사 시간 기록")}
                    Text("시간 형식 mm:ss.SS · 모든 줄의 시간을 오름차순으로 입력",fontSize=12.sp)
                    LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        items(rows.size){i->
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
                                OutlinedTextField(times[i],{v->times=times.toMutableList().apply{set(i,v)};uncertain=uncertain-i},modifier=Modifier.width(110.dp),singleLine=true,label={Text((i+1).toString())})
                                Column(Modifier.weight(1f)){
                                    Text(rows[i],fontSize=14.sp)
                                    if(i in uncertain)Text("추정 · 검토 필요",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                                }
                                TextButton(enabled=matching&&now.playing,onClick={times=times.toMutableList().apply{set(i,LyricDisplay.timestamp(now.position))};uncertain=uncertain-i},contentPadding=PaddingValues(0.dp)){Text("기록",fontSize=12.sp)}
                            }
                        }
                    }
                    OutlinedButton(enabled=valid&&!saving,onClick={exportLrc(draft)},modifier=Modifier.fillMaxWidth()){Text("생성 LRC 내보내기")}
                }
            }
        }
    },confirmButton={
        if(mode=="edit")TextButton(enabled=valid&&!saving,onClick={
            saving=true
            scope.launch{
                try{
                    LyricsDb.get(context).dao().put(lyric.copy(plain=original,synced=draft,source=source,status="FOUND",edited=true,offset=0,fetched=System.currentTimeMillis()))
                    Playback.retry.value++;onSaved()
                }catch(e:CancellationException){throw e}catch(e:Exception){status="저장 실패: "+e.message}
                finally{saving=false}
            }
        }){Text("검토 후 저장")}
        else if(mode=="ai"&&running)TextButton(onClick={task?.cancel();status="현재 분석 구간 종료 후 중단"}){Text("분석 중단")}
    },dismissButton={TextButton(enabled=!running&&!saving,onClick={if(mode=="menu")onClose()else{mode="menu";status=""}}){Text(if(mode=="menu")"닫기" else "뒤로")}})
}
