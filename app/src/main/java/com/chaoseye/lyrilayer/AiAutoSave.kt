package com.chaoseye.lyrilayer

internal object AiAutoSave {
    const val EXTRACT_KEY="ai_autosave_extract"
    const val ALIGN_KEY="ai_autosave_align"
    const val REVIEW_UNCERTAIN_KEY="ai_autosave_review_uncertain"
    const val DEFAULT_ENABLED=true
    fun allowed(kind:String,extract:Boolean,align:Boolean,reviewUncertain:Boolean,total:Int,uncertain:Int):Boolean {
        if(total<=0||uncertain !in 0..total)return false
        return when(kind){
            AiQueueRules.EXTRACT -> extract
            AiQueueRules.ALIGN -> align&&(!reviewUncertain||(total-uncertain)*100L>=total*80L)
            else -> false
        }
    }
}
