package com.chaoseye.lyrilayer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.withTransaction
import kotlinx.coroutines.*

@Composable private fun SyncIcon(name: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick=onClick,enabled=enabled) { RecognitionGlyph(name,Modifier.size(22.dp).semantics { contentDescription=label }) }
}

@Composable internal fun ColumnScope.ManualSyncList(tracks: List<Track>, lyrics: Map<String,Lyric>, info: Map<String,MusicInfo>, onSelect: (Track) -> Unit) {
    val candidates = remember(tracks,lyrics) { tracks.filter { ManualSyncRules.plainOnly(it,lyrics[it.key]) }.distinctBy { it.key } }
    val registered = remember(tracks,lyrics) { tracks.count { Lrc.parse(lyrics[it.key]?.synced.orEmpty()).any { line -> line.text.isNotBlank() } } }
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Card(Modifier.weight(1f),colors=CardDefaults.cardColors(containerColor=colors.primaryContainer)) {
            Column(Modifier.padding(12.dp)) { Text("편집 대상",fontSize=12.sp); Text(candidates.size.toString()+"곡",style=MaterialTheme.typography.headlineSmall) }
        }
        Card(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("싱크 등록",fontSize=12.sp); Text(registered.toString()+"곡",style=MaterialTheme.typography.headlineSmall) } }
    }
    OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("곡명 / 가수 검색",fontSize=13.sp)},leadingIcon={RecognitionGlyph("music")},trailingIcon={if(query.isNotEmpty())SyncIcon("close","검색어 삭제"){query=""}})
    val visible=candidates.filter { t -> listOf(t.title,t.artist,info[t.key]?.title.orEmpty(),info[t.key]?.artist.orEmpty()).any { it.contains(query,true) } }
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        if(visible.isEmpty()) item { Text(if(candidates.isEmpty())"일반 가사만 있는 곡이 없습니다." else "검색 결과가 없습니다.",Modifier.padding(16.dp),color=colors.onSurfaceVariant) }
        items(visible,key={it.key}) { t ->
            Surface(onClick={onSelect(t)},shape=RoundedCornerShape(12.dp),color=colors.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    RecognitionGlyph("music",Modifier.size(28.dp),colors.primary)
                    Column(Modifier.weight(1f)) { Text(info[t.key]?.title?:t.title,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis); Text((info[t.key]?.artist?:t.artist)+" · "+PlainSync.rows(lyrics[t.key]?.plain.orEmpty()).size+"줄",fontSize=12.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis) }
                    RecognitionGlyph("clock",color=colors.primary); RecognitionGlyph("chevron",Modifier.size(16.dp),colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable internal fun ColumnScope.ManualSyncEditor(context: Context, track: Track, lyric: Lyric, info: MusicInfo?, onSaved: () -> Unit, onClose: () -> Unit) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val player=remember(track.uri){SyncPreviewPlayer(context)}
    val original=rememberSaveable(track.key){ManualSyncRules.original(lyric)}
    val baseline=rememberSaveable(track.key){AiQueueRules.fingerprint(lyric)}
    val rows=remember(original){PlainSync.rows(original)}
    val initial=rememberSaveable(track.key){ManualSyncRules.initial(lyric,track.duration)}
    var times by rememberSaveable(track.key){mutableStateOf(ArrayList(initial))}
    var selected by rememberSaveable(track.key){mutableIntStateOf(times.indexOfFirst{it==ManualSyncRules.UNSET}.coerceAtLeast(0))}
    var undoIndex by rememberSaveable(track.key){mutableIntStateOf(-1)}
    var undoTime by rememberSaveable(track.key){mutableLongStateOf(ManualSyncRules.UNSET)}
    var undoSelection by rememberSaveable(track.key){mutableIntStateOf(0)}
    var saving by remember{mutableStateOf(false)}
    var status by rememberSaveable(track.key){mutableStateOf("")}
    var discard by remember{mutableStateOf(false)}
    var drag by remember{mutableStateOf<Float?>(null)}
    var lastPosition by rememberSaveable(track.key){mutableLongStateOf(0L)}
    val colors=MaterialTheme.colorScheme
    val list=rememberLazyListState()
    val dirty=times!=initial
    val duration=if(player.ready)minOf(player.duration,track.duration) else track.duration
    val valid=ManualSyncRules.valid(times,duration)&&times.size==rows.size
    val marked=times.count{it>=0}
    DisposableEffect(player,lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){player.pause();lastPosition=player.position}}
        lifecycle.addObserver(observer);player.open(track.uri)
        onDispose{lastPosition=player.position;player.close();lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(player.ready){if(player.ready&&lastPosition>0)player.seek(lastPosition)}
    LaunchedEffect(player){while(isActive){player.refresh();delay(100)}}
    LaunchedEffect(selected){if(selected in rows.indices)list.animateScrollToItem((selected-2).coerceAtLeast(0))}
    fun leave(){if(!saving){player.pause();if(dirty)discard=true else onClose()}}
    BackHandler(enabled=true){leave()}
    fun change(value:ArrayList<Long>,index:Int,advance:Boolean){
        undoIndex=index;undoTime=times[index];undoSelection=selected;times=value
        if(advance)selected=ManualSyncRules.nextIndex(times,index)
        status=""
    }
    fun mark(){
        val pos=player.readPosition()?:return
        val updated=ManualSyncRules.mark(times,selected,pos,duration)
        if(updated==null)status="앞뒤 가사 시간과 겹칩니다. 재생 위치를 조정하세요." else change(updated,selected,true)
    }
    fun save(){
        if(!valid||saving)return
        player.pause();saving=true
        val snapshot=ArrayList(times)
        scope.launch{
            try{
                val raw=PlainSync.lrc(rows.mapIndexed{i,text->Line(snapshot[i],text)})
                val db=LyricsDb.get(context)
                db.withTransaction{
                    val old=db.dao().get(track.key)?:error("기존 가사가 삭제되었습니다.")
                    if(AiQueueRules.fingerprint(old)!=baseline)error("편집 중 가사가 변경되었습니다. 기존 데이터를 보호하기 위해 저장하지 않았습니다.")
                    db.dao().put(old.copy(plain=original,synced=raw,source=PlainSync.MANUAL_SOURCE,status="FOUND",edited=true,offset=0,fetched=System.currentTimeMillis()))
                }
                Playback.retry.value++;onSaved()
            }catch(e:CancellationException){throw e}catch(e:Exception){status="저장 실패: "+(e.message?:"오류")}
            finally{saving=false}
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        SyncIcon("back","편집 목록",!saving){leave()}
        Column(Modifier.weight(1f)){
            Text(info?.title?:track.title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(info?.artist?:track.artist,fontSize=12.sp,color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Text(marked.toString()+"/"+rows.size,style=MaterialTheme.typography.titleMedium,color=colors.primary)
    }
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp)){
        Column(Modifier.padding(horizontal=12.dp,vertical=6.dp)){
            val end=player.duration.coerceAtLeast(1L)
            val current=drag?:player.position.toFloat()
            Slider(value=current.coerceIn(0f,end.toFloat()),onValueChange={drag=it},onValueChangeFinished={drag?.let{player.seek(it.toLong())};drag=null},valueRange=0f..end.toFloat(),enabled=player.ready&&!player.seeking&&!saving)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(LyricDisplay.timestamp(current.toLong()),fontSize=12.sp);Text(LyricDisplay.timestamp(player.duration.takeIf{it>0}?:track.duration),fontSize=12.sp,color=colors.onSurfaceVariant)}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
                TextButton(enabled=player.ready&&!player.seeking&&!saving,onClick={player.seek(player.position-5000)}){Text("−5초")}
                FilledIconButton(enabled=player.ready&&!player.seeking&&!saving,onClick={player.toggle()}){
                    if(player.playing)Text("Ⅱ",fontSize=24.sp,modifier=Modifier.semantics{contentDescription="일시정지"}) else RecognitionGlyph("play",Modifier.size(24.dp).semantics{contentDescription="재생"})
                }
                TextButton(enabled=player.ready&&!player.seeking&&!saving,onClick={player.seek(player.position+5000)}){Text("+5초")}
            }
            if(!player.ready&&player.error.isBlank())LinearProgressIndicator(Modifier.fillMaxWidth())
            if(player.error.isNotBlank())Text(player.error,fontSize=12.sp,color=colors.error)
        }
    }
    LinearProgressIndicator(progress={if(rows.isEmpty())0f else marked.toFloat()/rows.size},modifier=Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text("가사 선택 · 시간 기록",Modifier.weight(1f),fontSize=12.sp,color=colors.onSurfaceVariant)
        TextButton(enabled=undoIndex in times.indices&&!saving,onClick={times=ArrayList(times).apply{set(undoIndex,undoTime)};selected=undoSelection;undoIndex=-1;status=""}){Text("되돌리기",fontSize=12.sp)}
        TextButton(enabled=selected in times.indices&&times[selected]>=0&&!saving,onClick={change(ArrayList(times).apply{set(selected,ManualSyncRules.UNSET)},selected,false)}){Text("시간 삭제",fontSize=12.sp)}
    }
    LazyColumn(Modifier.weight(1f),state=list,verticalArrangement=Arrangement.spacedBy(4.dp)){
        items(rows.size){i->
            val chosen=i==selected;val timestamp=times.getOrElse(i){ManualSyncRules.UNSET}
            Surface(shape=RoundedCornerShape(10.dp),color=if(chosen)colors.primaryContainer else colors.surfaceContainerLow,contentColor=if(chosen)colors.onPrimaryContainer else colors.onSurface){
                Row(Modifier.fillMaxWidth().clickable(enabled=!saving){selected=i;status=""}.padding(horizontal=10.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Column(Modifier.width(75.dp)){
                        Text((i+1).toString(),fontSize=10.sp,color=colors.onSurfaceVariant)
                        Text(if(timestamp>=0)LyricDisplay.timestamp(timestamp) else "--:--.--",fontSize=12.sp,color=if(chosen)colors.onPrimaryContainer else colors.primary)
                    }
                    Text(rows[i],Modifier.weight(1f),style=if(chosen)MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                    if(timestamp>=0)SyncIcon("play","이 가사 위치로 이동",player.ready&&!player.seeking&&!saving){selected=i;player.seek(timestamp)} else if(chosen)RecognitionGlyph("clock",color=colors.onPrimaryContainer)
                }
            }
        }
    }
    if(status.isNotBlank())Text(status,color=colors.error,fontSize=12.sp)
    Button(enabled=player.ready&&player.playing&&!player.seeking&&drag==null&&selected in rows.indices&&!saving,onClick={mark()},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){
        RecognitionGlyph("clock",Modifier.size(24.dp));Spacer(Modifier.width(8.dp));Text("싱크 기록  ·  "+(selected+1)+"번째 줄",style=MaterialTheme.typography.titleMedium)
    }
    OutlinedButton(enabled=valid&&!saving,onClick={save()},modifier=Modifier.fillMaxWidth()){
        RecognitionGlyph("check",Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text(if(saving)"저장 중" else "싱크 저장  "+marked+"/"+rows.size)
    }
    if(discard)AlertDialog(onDismissRequest={discard=false},title={Text("편집 내용을 취소할까요?")},text={Text("저장하지 않은 싱크 시간이 삭제됩니다.")},confirmButton={TextButton(onClick={discard=false;onClose()}){Text("취소하고 나가기")}},dismissButton={TextButton(onClick={discard=false}){Text("계속 편집")}})
}
