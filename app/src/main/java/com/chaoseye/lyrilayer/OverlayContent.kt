package com.chaoseye.lyrilayer

internal data class FloatingContent(val text:String,val detail:String="",val tone:String="",val progress:Int?=null)
internal object OverlayContent {
    const val SHOW_MISSING="overlay_show_missing"
    fun decide(
        enabled:Boolean,playing:Boolean,permission:Boolean,interactive:Boolean,excluded:Boolean,
        showMissing:Boolean,title:String,artist:String,line:String?,hasSync:Boolean,
        status:String?,searching:Boolean,error:String,aiProgress:Int?=null
    ):FloatingContent? {
        if(!enabled||!playing||!permission||!interactive||excluded)return null
        if(!line.isNullOrBlank())return FloatingContent(line)
        if(!showMissing)return null
        val name=listOf(MusicRules.clean(artist),MusicRules.clean(title)).filter{it.isNotBlank()}.joinToString(" - ").ifBlank{"삼성 뮤직"}
        val pct=aiProgress?.coerceIn(0,100)
        return when{
            !hasSync&&pct!=null -> FloatingContent(name,"✦ AI 가사 분석 중 · $pct%","ai",pct)
            hasSync -> FloatingContent(name,error.ifBlank{"가사 없는 구간"},"neutral")
            status=="INSTRUMENTAL" -> FloatingContent(name,"연주곡 · 가사 없음","neutral")
            searching -> FloatingContent(name,"가사 검색 중…","neutral")
            status=="FOUND" -> FloatingContent(name,"일반 가사만 등록됨 · 싱크 가사 없음","neutral")
            error.contains("오류") -> FloatingContent(name,"가사 검색 오류","error")
            error.contains("권한") -> FloatingContent(name,"음악 접근 권한 또는 곡 정보 확인 필요","error")
            status=="NOT_FOUND" -> FloatingContent(name,"등록된 가사가 없습니다.","error")
            else -> FloatingContent(name,error.ifBlank{"등록된 가사가 없습니다."},"neutral")
        }
    }
}
