package com.chaoseye.musickit

import kotlinx.coroutines.*
import java.text.Normalizer
import java.util.Locale

data class MusicQuery(val title:String,val artist:String="",val album:String="",val durationMs:Long=0,val fileName:String="")
enum class MusicSource { BUGS, GENIE, ITUNES_KR, MUSICBRAINZ }
data class MusicCandidate(val source:MusicSource,val id:String,val title:String,val artist:String,val album:String="",val durationMs:Long=0,val artworkUrl:String="",val releaseDate:String="")
data class MusicMatch(val candidate:MusicCandidate,val score:Int)
data class MusicSearch(val matches:List<MusicMatch>,val verified:MusicMatch?,val errors:Map<MusicSource,String>)
interface MusicProvider {
    val source:MusicSource
    suspend fun search(query:MusicQuery):List<MusicCandidate>
}
object MusicMatching {
    fun clean(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).trim().replace(Regex("\\s+")," ").takeUnless{it.equals("<unknown>",true)||it.equals("unknown",true)||it=="알 수 없음"}.orEmpty()
    fun norm(s:String)=clean(s).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"),"")
    private fun title(s:String)=clean(s).replace(Regex("\\.(mp3|flac|m4a|wav|ogg|aac|opus)$",RegexOption.IGNORE_CASE),"").replace(Regex("^\\d{1,3}[. _-]+"),"").trim()
    private val aliases=listOf(setOf("iu","아이유"),setOf("bts","방탄소년단"),setOf("melomance","멜로망스"),setOf("taeyeon","태연"),setOf("akmu","악뮤","악동뮤지션"),setOf("seventeen","세븐틴"),setOf("blackpink","블랙핑크"),setOf("newjeans","뉴진스"),setOf("day6","데이식스"),setOf("twice","트와이스"),setOf("redvelvet","레드벨벳"),setOf("aespa","에스파"),setOf("ive","아이브"),setOf("limyoungwoong","임영웅"))
    private fun artistKeys(s:String):Set<String> {
        val raw=clean(s)
        val keys=(listOf(raw)+Regex("\\(([^)]+)\\)").findAll(raw).map{it.groupValues[1]}.toList()+raw.replace(Regex("\\([^)]*\\)"),"")).map(::norm).filter{it.isNotBlank()}.toSet()
        return keys+aliases.filter{a->a.any{it in keys}}.flatten()
    }
    fun identities(q:MusicQuery):List<MusicQuery> {
        val a=clean(q.artist);val t=title(q.title);val f=title(q.fileName)
        val out=linkedSetOf(q.copy(title=t,artist=a,album=clean(q.album)))
        for(s in listOf(t,f).distinct()){
            val p=s.split(Regex("\\s+[-–—]\\s+"),limit=2)
            if(p.size==2&&(a.isBlank()||artistKeys(a).intersect(artistKeys(p[0])).isNotEmpty()))out+=q.copy(title=p[1],artist=p[0])
        }
        return out.filter{it.title.isNotBlank()}.take(3)
    }
    fun score(q:MusicQuery,c:MusicCandidate):Int {
        if(norm(q.title).isBlank()||norm(q.title)!=norm(c.title))return 0
        val known=norm(q.artist).isNotBlank()
        if(known&&artistKeys(q.artist).intersect(artistKeys(c.artist)).isEmpty())return 0
        val gap=if(q.durationMs>0&&c.durationMs>0)kotlin.math.abs(q.durationMs-c.durationMs) else null
        if(gap!=null&&gap>5000)return 0
        if(!known&&(gap==null||gap>2000))return 0
        val album=norm(q.album).isNotBlank()&&norm(q.album)==norm(c.album)
        return 40+(if(known)30 else 0)+(if(gap!=null&&gap<=2000)20 else if(gap!=null)10 else 0)+(if(album)10 else 0)
    }
}
class MusicKit(private val providers:List<MusicProvider>,private val timeoutMs:Long=20000) {
    suspend fun identify(query:MusicQuery):MusicSearch=supervisorScope {
        val identities=MusicMatching.identities(query)
        val reports=providers.map{p->async{
            try{
                val out=mutableListOf<MusicCandidate>()
                val values=withTimeout(timeoutMs){
                    for(q in identities){out+=p.search(q);if(out.any{MusicMatching.score(q,it)>=90})break}
                    out
                }
                Triple(p.source,values,null)
            }catch(e:TimeoutCancellationException){Triple(p.source,emptyList<MusicCandidate>(),"시간 초과")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){Triple(p.source,emptyList<MusicCandidate>(),e.javaClass.simpleName)}
        }}.awaitAll()
        val ranked=reports.flatMap{it.second}.distinctBy{it.source to it.id}.map{c->MusicMatch(c,identities.maxOfOrNull{MusicMatching.score(it,c)}?:0)}.filter{it.score>=60}.sortedWith(compareByDescending<MusicMatch>{it.score}.thenBy{it.candidate.source.ordinal}.thenByDescending{it.candidate.album.isNotBlank()})
        val best=ranked.firstOrNull()
        // A different album/edition near the best score requires review, not automatic replacement.
        val ambiguous=best!=null&&ranked.drop(1).any{it.score>=best.score-5&&
            ((it.candidate.album.isNotBlank()&&best.candidate.album.isNotBlank()&&MusicMatching.norm(it.candidate.album)!=MusicMatching.norm(best.candidate.album))||
             (it.candidate.durationMs>0&&best.candidate.durationMs>0&&kotlin.math.abs(it.candidate.durationMs-best.candidate.durationMs)>2000))}
        val verified=best?.takeIf{it.score>=90&&!ambiguous&&it.candidate.album.isNotBlank()&&it.candidate.durationMs>0}
        MusicSearch(ranked.take(20),verified,reports.mapNotNull{r->r.third?.let{r.first to it}}.toMap())
    }
}
