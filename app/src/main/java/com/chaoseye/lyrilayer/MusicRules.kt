package com.chaoseye.lyrilayer
import java.text.Normalizer
import java.util.Locale
object MusicRules {
    private val folders=setOf("call","calls","recordings","tphonecallrecords","tphone call records","tphonecallrecord","voice recorder","voicerecorder","call recordings","call recording","callrecordings","callrecording","통화 녹음","통화녹음","음성 녹음","음성녹음","ringtones","alarms","notifications")
    private val recording=Regex("^(?:통화\\s*녹음|음성\\s*녹음|call[ _-]*(?:recording|record)|voice[ _-]*(?:recording|record))(?=\\s|_|-|\\d|\\.|$)",RegexOption.IGNORE_CASE)
    // TPhone uses [contact_]phone_yyyyMMddHHmmss.m4a. Never store or log the filename.
    private val tphone=Regex("^(?:.+_)?\\+?\\d{3,15}_20\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])(?:[01]\\d|2[0-3])[0-5]\\d[0-5]\\d(?:\\.m4a)?$",RegexOption.IGNORE_CASE)
    private fun recordingName(s:String):Boolean {
        val v=Normalizer.normalize(s,Normalizer.Form.NFKC).trim()
        return recording.containsMatchIn(v) || tphone.matches(v)
    }
    fun excluded(path:String,name:String,title:String="",flag:Boolean=false):Boolean =
        flag || path.contains("Recording", ignoreCase = true) || path.replace('\\','/').split('/').any { it.trim().lowercase(Locale.ROOT) in folders } ||
            recordingName(name) || recordingName(title)
    fun clean(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).trim().replace(Regex("\\s+")," ").takeUnless { it.equals("<unknown>",true)||it.equals("unknown",true)||it=="알 수 없음" }.orEmpty()
    fun norm(s:String)=clean(s).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"),"")
    fun title(s:String)=clean(s).replace(Regex("\\.(mp3|flac|m4a|wav|ogg|aac|opus|wma|ape)$",RegexOption.IGNORE_CASE),"").replace(Regex("^\\d{1,3}[. _-]+"),"").trim()
    fun artistMatches(a:String,b:String):Boolean {
        if(norm(a).isEmpty()||norm(b).isEmpty())return false
        if(norm(a)==norm(b))return true
        fun parts(s:String)=clean(s).split(Regex("\\s*(?:\\(|\\)|/|;|,|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+)\\s*",RegexOption.IGNORE_CASE)).map(::norm).filter{it.length>=2}
        return parts(a).any{it==norm(b)}||parts(b).any{it==norm(a)}||parts(a).intersect(parts(b).toSet()).isNotEmpty()
    }
    data class Identity(val title:String,val artist:String)
    fun identities(raw:String,artist:String,fileName:String):List<Identity>{
        val t=title(raw); val a=clean(artist);val f=title(fileName)
        val result=linkedSetOf<Identity>()
        if(t.isNotBlank())result+=Identity(t,a)
        for(s in listOf(t,f).distinct()){
            if(s.isBlank())continue
            val parts=s.split(Regex("\\s+[-–—]\\s+"),limit=2)
            if(parts.size==2){
                if(a.isBlank()||artistMatches(a,parts[0]))result+=Identity(parts[1].trim(),parts[0].trim())
                if(a.isNotBlank()&&artistMatches(a,parts[1]))result+=Identity(parts[0].trim(),parts[1].trim())
            }
            if(s==f&&a.isNotBlank())result+=Identity(s,a)
        }
        return result.filter{it.title.isNotBlank()}.take(4)
    }
    fun score(id:Identity,album:String,duration:Long,t:String,a:String,al:String,seconds:Double,synced:Boolean):Int?{
        if(norm(id.title).isEmpty()||norm(id.title)!=norm(t))return null
        val known=norm(id.artist).isNotEmpty()
        if(known&&!artistMatches(id.artist,a))return null
        val gap=if(duration>0&&seconds>0)kotlin.math.abs(duration/1000.0-seconds) else null
        if(gap!=null&&gap>8)return null
        if(!known&&(gap==null||gap>2))return null
        return (if(known)100 else 50)+(if(gap!=null)(30-gap*3).toInt() else 0)+(if(norm(album).isNotEmpty()&&norm(album)==norm(al))20 else 0)+(if(synced)6 else 0)
    }
}
