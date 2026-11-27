package com.chaoseye.lyrilayer

internal data class WhisperModel(val id:String,val title:String,val filename:String,val minSize:Long,val maxSize:Long,val sha:String,val url:String){
    fun sizeValid(size:Long)=size in minSize..maxSize
}
internal object AiPerformance{
    const val MODEL_KEY="ai_model"
    const val THREADS_KEY="ai_threads"
    const val REUSE_KEY="ai_reuse"
    const val LANGUAGE_KEY="ai_language_once"
    val base=WhisperModel("base","Base · 균형 (57 MiB)","ggml-base-q5_1.bin",59707625L,59707625L,
        "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-base-q5_1.bin")
    val tiny=WhisperModel("tiny","Tiny · 빠른 분석 (약 31 MiB)","ggml-tiny-q5_1.bin",31000000L,33000000L,
        "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-tiny-q5_1.bin")
    val small=WhisperModel("small","Small Q5_1 · AI 가사 엔진 (181 MiB)",
        com.chaoseye.ailyrickit.model.ModelVerifier.FILE_NAME,
        com.chaoseye.ailyrickit.model.ModelVerifier.SMALL_Q5_1_BYTES,
        com.chaoseye.ailyrickit.model.ModelVerifier.SMALL_Q5_1_BYTES,
        com.chaoseye.ailyrickit.model.ModelVerifier.MODEL_SHA256,
        com.chaoseye.ailyrickit.model.ModelVerifier.MODEL_URL)
    val models=listOf(small,base,tiny)
    fun model(id:String?)=models.firstOrNull{it.id==id}?:small
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
