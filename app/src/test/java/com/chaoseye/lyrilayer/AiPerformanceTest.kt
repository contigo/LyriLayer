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
    @Test fun fixedSmallModel(){
        val model=AiPerformance.small
        assertEquals("small",model.id)
        assertEquals("ggml-small-q5_1.bin",model.filename)
        assertTrue(model.sizeValid(190085487L))
        assertFalse(model.sizeValid(190085486L))
        assertFalse(model.sizeValid(59707625L))
        assertFalse(model.sizeValid(32000000L))
        assertEquals(64,model.sha.length)
    }
    @Test fun timeEstimate(){
        assertEquals(60000L,AiPerformance.remainingMs(60000,50)!!)
        assertNull(AiPerformance.remainingMs(0,0))
        assertNull(AiPerformance.remainingMs(60000,100))
        assertEquals("01:00",AiPerformance.time(60000))
    }
}
