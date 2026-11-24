package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class PlainSyncTest {
    @Test fun estimatedAndManual(){
        assertEquals(listOf(0L,10000L,20000L),PlainSync.estimated("첫째\n둘째\n셋째",30000).map{it.ms})
        assertEquals(62340L,PlainSync.parseTime("01:02.34")!!)
        assertNull(PlainSync.parseTime("01:99"))
        assertFalse(PlainSync.valid(listOf(100L,100L),30000))
        assertTrue(PlainSync.valid(listOf(100L,200L),30000))
        assertEquals("[00:01.00]가사",PlainSync.lrc(listOf(Line(1000,"가사"))))
    }
    @Test fun alignmentKeepsOriginalAndRepeatedOrder(){
        val raw="첫 번째 노래\n반복되는 후렴\n중간 가사\n반복되는 후렴"
        val aligned=PlainSync.align(raw,listOf(Line(1000,"첫 번째 노래"),Line(5000,"반복되는 후렴"),Line(15000,"반복되는 후렴")),20000)
        assertEquals(PlainSync.rows(raw),aligned.map{it.text})
        assertEquals(listOf(1000L,5000L,10000L,15000L),aligned.map{it.ms})
        assertFalse(aligned[2].matched)
        assertTrue(aligned[3].matched)
        assertTrue(PlainSync.align("완전히 다른 가사",listOf(Line(1000,"abc")),3000).isEmpty())
    }
}
