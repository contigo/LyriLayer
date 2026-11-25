package com.chaoseye.lyrilayer
internal object FingerprintPipelineRules {
    fun identified(info:MusicInfo?)=info?.let{it.userConfirmed||it.provider=="SHAZAM_FINGERPRINT"}==true
    fun lyricReady(lyric:Lyric?)=lyric?.let{it.status=="INSTRUMENTAL"||(it.status=="FOUND"&&(it.plain.isNotBlank()||it.synced.isNotBlank()))}==true
    fun needsWork(info:MusicInfo?,lyric:Lyric?)=!identified(info)||!lyricReady(lyric)
    fun lyricLabel(lyric:Lyric?)=when{
        lyric?.status=="INSTRUMENTAL"->"연주곡"
        lyric?.status=="FOUND"&&lyric.synced.isNotBlank()->"싱크 가사 저장"
        lyric?.status=="FOUND"&&lyric.plain.isNotBlank()->"일반 가사 저장"
        else->"가사 없음"
    }
    fun outcome(identified:Boolean,lyricsReady:Boolean)=when{
        identified&&lyricsReady->"done"
        identified||lyricsReady->"partial"
        else->"failed"
    }
    fun percent(done:Int,total:Int)=if(total<=0)0 else (done.coerceIn(0,total)*100L/total).toInt()
}
