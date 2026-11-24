package com.chaoseye.lyrilayer

internal object PlainSync {
    const val MODE_KEY="plain_overlay_mode"
    const val STATIC="static"
    const val ESTIMATE="estimate"
    const val OFF="off"
    const val AI_SOURCE="AI_SYNC"
    const val MANUAL_SOURCE="MANUAL_SYNC"
    const val ESTIMATE_SOURCE="ESTIMATED_SYNC"
    data class Mark(val text:String,val ms:Long,val matched:Boolean)
    fun rows(raw:String)=raw.lineSequence().map{it.trim()}.filter{it.isNotBlank()}.toList()
    fun estimated(raw:String,duration:Long):List<Line>{
        val lines=rows(raw)
        if(duration<=0||lines.isEmpty())return emptyList()
        return lines.mapIndexed{i,text->Line(i.toLong()*duration/lines.size,text)}
    }
    fun static(raw:String)=rows(raw).take(3).joinToString("\n")
    fun lrc(lines:List<Line>)=lines.joinToString("\n"){"["+LyricDisplay.timestamp(it.ms)+"]"+it.text}
    fun parseTime(value:String):Long? {
        val m=Regex("""^(\d{1,5}):([0-5]\d)(?:[.:](\d{1,3}))?$""").matchEntire(value.trim())?:return null
        return m.groupValues[1].toLong()*60000+m.groupValues[2].toLong()*1000+(m.groupValues[3].padEnd(3,'0').toLongOrNull()?:0)
    }
    fun valid(times:List<Long?>,duration:Long)=times.isNotEmpty()&&times.all{it!=null&&it>=0&&(duration<=0||it<duration)}&&times.zipWithNext().all{(a,b)->a!=null&&b!=null&&a<b}
    private fun similarity(x:String,y:String):Double{
        if(x.isEmpty()||y.isEmpty())return 0.0
        if(x==y)return 1.0
        if(minOf(x.length,y.length)>=4&&(x.contains(y)||y.contains(x)))return 0.65+0.35*minOf(x.length,y.length)/maxOf(x.length,y.length)
        if(minOf(x.length,y.length)*2<maxOf(x.length,y.length))return 0.0
        var prev=IntArray(y.length+1){it}
        for(i in x.indices){
            val cur=IntArray(y.length+1);cur[0]=i+1
            for(j in y.indices)cur[j+1]=minOf(cur[j]+1,prev[j+1]+1,prev[j]+if(x[i]==y[j])0 else 1)
            prev=cur
        }
        return 1.0-prev.last().toDouble()/maxOf(x.length,y.length)
    }
    fun align(raw:String,recognized:List<Line>,duration:Long):List<Mark>{
        val original=rows(raw)
        val heard=recognized.filter{it.text.isNotBlank()&&it.ms>=0&&it.ms<duration}.sortedBy{it.ms}
        if(original.isEmpty()||heard.isEmpty()||duration<original.size*10L||original.size>500||heard.size>800)return emptyList()
        val n=original.size;val m=heard.size
        val x=original.map{MusicRules.norm(it).take(120)};val y=heard.map{MusicRules.norm(it.text).take(120)}
        val dp=Array(n+1){DoubleArray(m+1)}
        val path=Array(n+1){ByteArray(m+1)}
        for(i in 1..n)for(j in 1..m){
            var score=dp[i-1][j];var action:Byte=1
            if(dp[i][j-1]>score){score=dp[i][j-1];action=2}
            val sim=similarity(x[i-1],y[j-1])
            if(sim>=0.58&&dp[i-1][j-1]+sim>score){score=dp[i-1][j-1]+sim;action=3}
            dp[i][j]=score;path[i][j]=action
        }
        val anchors=sortedMapOf<Int,Long>();var i=n;var j=m
        while(i>0&&j>0){
            when(path[i][j].toInt()){
                3->{anchors[i-1]=heard[j-1].ms;i--;j--}
                2->j--
                else->i--
            }
        }
        if(anchors.isEmpty())return emptyList()
        val result=original.mapIndexed{index,text->
            val exact=anchors[index]
            if(exact!=null)Mark(text,exact,true)
            else{
                val left=anchors.keys.lastOrNull{it<index}?:-1
                val right=anchors.keys.firstOrNull{it>index}?:n
                val lo=anchors[left]?:0L;val hi=anchors[right]?:duration
                Mark(text,(lo+(hi-lo)*(index-left)/(right-left)).coerceIn(0,duration-1),false)
            }
        }
        var last=-10L
        return result.mapIndexed{index,mark->
            val upper=((duration-1)/10*10-(n-index-1)*10L).coerceAtLeast(0)
            val time=(mark.ms/10*10).coerceAtLeast(last+10).coerceAtMost(upper)
            last=time
            mark.copy(ms=time,matched=mark.matched&&time==mark.ms/10*10)
        }
    }
}
