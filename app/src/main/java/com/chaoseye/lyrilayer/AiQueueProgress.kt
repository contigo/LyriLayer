package com.chaoseye.lyrilayer

internal data class AiQueueProgress(val done:Int,val total:Int,val percent:Int){
    companion object {
        private val terminal=setOf("review","saved","failed","skipped")
        fun calculate(entries:List<AiQueueEntry>,activeKey:String?,activePercent:Int):AiQueueProgress{
            val total=entries.size
            if(total==0)return AiQueueProgress(0,0,0)
            val done=entries.count{it.state in terminal}
            val partial=if(entries.any{it.key==activeKey&&it.state=="running"})activePercent.coerceIn(0,100) else 0
            return AiQueueProgress(done,total,((done*100L+partial)/total).toInt().coerceIn(0,100))
        }
    }
}
