package com.chaoseye.lyrilayer

internal data class FloatingContent(val text:String)
internal object OverlayContent {
    fun saved(
        enabled:Boolean,playing:Boolean,permission:Boolean,interactive:Boolean,excluded:Boolean,
        hasSync:Boolean,line:String?,plainMode:String?,estimated:String?,plain:String
    ):FloatingContent? {
        if(!enabled||!playing||!permission||!interactive||excluded)return null
        val text=if(hasSync)line else when(plainMode){
            PlainSync.ESTIMATE->estimated?.takeIf{it.isNotBlank()}?:plain
            PlainSync.STATIC->plain
            else->null
        }
        return text?.takeIf{it.isNotBlank()}?.let{FloatingContent(it)}
    }
}
