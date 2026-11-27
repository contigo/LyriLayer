package com.chaoseye.lyrilayer

internal data class WhisperModel(val id:String,val title:String,val filename:String,val minSize:Long,val maxSize:Long,val sha:String,val url:String){
    fun sizeValid(size:Long)=size in minSize..maxSize
}
internal object AiPerformance{
    const val THREADS_KEY="ai_threads"
    const val REUSE_KEY="ai_reuse"
    val small=WhisperModel("small","Small Q5_1 · AI 가사 엔진 (181 MiB)",
        com.chaoseye.ailyrickit.model.ModelVerifier.FILE_NAME,
        com.chaoseye.ailyrickit.model.ModelVerifier.SMALL_Q5_1_BYTES,
        com.chaoseye.ailyrickit.model.ModelVerifier.SMALL_Q5_1_BYTES,
        com.chaoseye.ailyrickit.model.ModelVerifier.MODEL_SHA256,
        com.chaoseye.ailyrickit.model.ModelVerifier.MODEL_URL)
    fun threads(requested:Int,available:Int):Int{
        val max=available.coerceIn(1,8)
        return if(requested<=0)minOf(6,max)else requested.coerceIn(1,max)
    }
    fun remainingMs(elapsed:Long,percent:Int):Long?=if(percent in 1..99&&elapsed>0)elapsed*(100-percent)/percent else null
    fun time(ms:Long):String{
        val seconds=ms.coerceAtLeast(0)/1000
        return java.lang.String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)
    }
}
