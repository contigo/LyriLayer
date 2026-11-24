package com.chaoseye.lyrilayer

import org.junit.Assert.assertEquals
import org.junit.Test

class AiQueueProgressTest {
    private fun entry(key:String,state:String)=AiQueueEntry(key,key,"Artist",AiQueueRules.EXTRACT,0L,state=state)
    @Test fun wholeQueueNotIndividualTrack(){
        val items=(1..100).map{entry(it.toString(),when(it){in 1..20->"review";21->"running";else->"pending"})}
        assertEquals(AiQueueProgress(20,100,20),AiQueueProgress.calculate(items,"21",69))
        assertEquals(AiQueueProgress(20,100,21),AiQueueProgress.calculate(items,"21",100))
    }
    @Test fun completedCountAndNoDoubleCounting(){
        val items=listOf(entry("a","review"),entry("b","saved"),entry("c","failed"),entry("d","skipped"),entry("e","running"),entry("f","pending"))
        assertEquals(AiQueueProgress(4,6,75),AiQueueProgress.calculate(items,"e",50))
        assertEquals(AiQueueProgress(4,6,66),AiQueueProgress.calculate(items,"a",100))
    }
    @Test fun emptyAndQueueChanges(){
        assertEquals(AiQueueProgress(0,0,0),AiQueueProgress.calculate(emptyList(),null,69))
        val items=listOf(entry("a","saved"),entry("b","running"))
        assertEquals(84,AiQueueProgress.calculate(items,"b",69).percent)
        assertEquals(56,AiQueueProgress.calculate(items+entry("c","pending"),"b",69).percent)
        assertEquals(100,AiQueueProgress.calculate(listOf(entry("a","saved")),"a",69).percent)
    }
}
