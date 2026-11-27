package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class AiPerformanceTest{
    @Test fun threadLimits(){
        assertEquals(6,AiPerformance.threads(0,8))
        assertEquals(2,AiPerformance.threads(0,2))
        assertEquals(4,AiPerformance.threads(8,4))
        assertEquals(8,AiPerformance.threads(8,12))
        assertEquals(1,AiPerformance.threads(0,0))
    }
    @Test fun modelSelection(){
        assertEquals("small",AiPerformance.model(null).id)
        assertEquals("base",AiPerformance.model("base").id)
        assertEquals("small",AiPerformance.model("small").id)
        assertTrue(AiPerformance.small.sizeValid(190085487L))
        assertFalse(AiPerformance.small.sizeValid(190085486L))
        assertEquals(64,AiPerformance.small.sha.length)
        assertEquals("tiny",AiPerformance.model("tiny").id)
        assertTrue(AiPerformance.base.sizeValid(59707625))
        assertFalse(AiPerformance.tiny.sizeValid(59707625))
        assertEquals(64,AiPerformance.tiny.sha.length)
    }
    @Test fun timeEstimate(){
        assertEquals(60000L,AiPerformance.remainingMs(60000,50)!!)
        assertNull(AiPerformance.remainingMs(0,0))
        assertNull(AiPerformance.remainingMs(60000,100))
        assertEquals("01:00",AiPerformance.time(60000))
    }
}
