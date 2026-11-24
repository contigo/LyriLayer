package com.chaoseye.lyrilayer
import org.junit.Test
import org.junit.Assert.*
class LrcTest {
 @Test fun boundaries() { val l=Lrc.parse("[00:05.00]A\n[00:10.50]B"); assertEquals(-1,Lrc.index(l,4999)); assertEquals(0,Lrc.index(l,5000)); assertEquals(1,Lrc.index(l,10500)) }
 @Test fun multipleTags() { assertEquals(listOf(1250L,2500L),Lrc.parse("[00:01.25][00:02.5]A").map{it.ms}) }
 @Test fun offset() { assertEquals(1200L,Lrc.parse("[offset:200]\n[00:01]A").first().ms) }
}
