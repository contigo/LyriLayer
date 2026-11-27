package com.chaoseye.lyrilayer

import org.junit.Assert.*
import org.junit.Test

class OverlayTimingTest {
    private val lines=listOf(Line(1000,"one"),Line(3500,"two"),Line(3500,"duplicate"),Line(8000,"three"))
    @Test fun schedulesNextBoundary(){
        assertEquals(1000L,OverlayTiming.nextDelay(lines,0,0,1f,true,10000))
        assertEquals(2500L,OverlayTiming.nextDelay(lines,1000,0,1f,true,10000))
        assertEquals(4500L,OverlayTiming.nextDelay(lines,3500,0,1f,true,10000))
        assertEquals(1L,OverlayTiming.nextDelay(lines,3499,0,1f,true,10000))
    }
    @Test fun offsetsAndSpeed(){
        assertEquals(750L,OverlayTiming.nextDelay(lines,0,500,2f,true,10000))
        assertEquals(500L,OverlayTiming.nextDelay(lines,0,-500,1f,true,10000))
        assertEquals(3L,OverlayTiming.nextDelay(lines,995,0,2f,true,10000))
    }
    @Test fun safetyAndInvalidClock(){
        assertEquals(5000L,OverlayTiming.nextDelay(emptyList(),0,0,1f,true,10000))
        assertEquals(5000L,OverlayTiming.nextDelay(lines,8000,0,1f,true,10000))
        assertEquals(5000L,OverlayTiming.nextDelay(listOf(Line(30000,"late")),0,0,1f,true,40000))
        assertEquals(5000L,OverlayTiming.nextDelay(lines,3500,0,1f,true,4000))
        assertEquals(500L,OverlayTiming.nextDelay(lines,0,0,1f,false,10000))
        assertEquals(500L,OverlayTiming.nextDelay(lines,0,0,0f,true,10000))
    }
}
