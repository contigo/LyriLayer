package com.chaoseye.lyrilayer
import org.junit.Test
import org.junit.Assert.*
class CollectionProgressTest {
 @Test fun progress(){assertEquals(0,CollectionProgress.percent(0,500,false));assertEquals(68,CollectionProgress.percent(340,500,false));assertEquals(99,CollectionProgress.percent(999,1000,false));assertEquals(100,CollectionProgress.percent(500,500,true));assertEquals(100,CollectionProgress.percent(0,0,true));assertEquals(0,CollectionProgress.percent(0,0,false))}
}
