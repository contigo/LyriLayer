package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class AiAutoSaveTest {
    @Test fun defaultsAndSwitches(){
        assertTrue(AiAutoSave.DEFAULT_ENABLED)
        assertTrue(AiAutoSave.allowed(AiQueueRules.EXTRACT,true,true,true,10,0))
        assertFalse(AiAutoSave.allowed(AiQueueRules.EXTRACT,false,true,true,10,0))
        assertFalse(AiAutoSave.allowed(AiQueueRules.ALIGN,true,false,true,10,0))
    }
    @Test fun alignmentThreshold(){
        assertTrue(AiAutoSave.allowed(AiQueueRules.ALIGN,true,true,true,10,2))
        assertFalse(AiAutoSave.allowed(AiQueueRules.ALIGN,true,true,true,10,3))
        assertTrue(AiAutoSave.allowed(AiQueueRules.ALIGN,true,true,false,10,3))
        assertFalse(AiAutoSave.allowed(AiQueueRules.ALIGN,true,true,false,0,0))
        assertFalse(AiAutoSave.allowed(AiQueueRules.ALIGN,true,true,false,10,11))
    }
}
