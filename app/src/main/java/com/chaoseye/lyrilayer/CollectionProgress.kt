package com.chaoseye.lyrilayer
object CollectionProgress {
    fun percent(done:Int,total:Int,finished:Boolean):Int {
        if(total<=0)return if(finished)100 else 0
        return ((done.coerceIn(0,total).toLong()*100)/total).toInt()
    }
}
