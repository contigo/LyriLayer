package com.chaoseye.lyrilayer

internal object LyricDisplay {
    const val AI_SOURCE="ON_DEVICE_AI"
    fun registered(status:String?)=status=="FOUND"||status=="INSTRUMENTAL"
    fun aiGenerated(status:String?,source:String?)=status=="FOUND"&&source==AI_SOURCE
    fun visible(filter:String,status:String?,source:String?,active:Boolean)=when(filter){
        "ai" -> active||aiGenerated(status,source)
        "missing" -> !registered(status)
        else -> true
    }
    fun label(status:String?,source:String?,synced:Boolean)=when{
        aiGenerated(status,source) -> "✦ AI 생성 가사"
        status=="INSTRUMENTAL" -> "연주곡"
        status=="FOUND"&&synced -> "싱크 가사"
        status=="FOUND" -> "일반 가사"
        status=="NOT_FOUND" -> "검색 실패"
        else -> "미등록"
    }
    fun editedSource(old:String?,replaced:Boolean)=if(old==AI_SOURCE&&!replaced)AI_SOURCE else "USER"
    fun timestamp(ms:Long):String=java.lang.String.format(java.util.Locale.ROOT,"%02d:%02d.%02d",ms/60000,(ms/1000)%60,(ms/10)%100)
}
