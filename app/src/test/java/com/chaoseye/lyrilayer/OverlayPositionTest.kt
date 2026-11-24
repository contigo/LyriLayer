package com.chaoseye.lyrilayer

import org.junit.Assert.*
import org.junit.Test

class OverlayPositionTest {
    @Test fun topMiddleBottom(){
        assertEquals(8,OverlayPosition.topOffset(0,1000,100,8))
        assertEquals(450,OverlayPosition.topOffset(50,1000,100,8))
        assertEquals(892,OverlayPosition.topOffset(100,1000,100,8))
    }
    @Test fun keepsLargeOverlayInsideAvailableArea(){
        assertEquals(0,OverlayPosition.topOffset(100,50,100,8))
        assertEquals(5,OverlayPosition.topOffset(0,100,90,8))
        assertEquals(5,OverlayPosition.topOffset(100,100,90,8))
    }
    @Test fun clampsSavedValues(){
        assertEquals(0,OverlayPosition.normalize(-10))
        assertEquals(100,OverlayPosition.normalize(110))
        assertEquals(85,OverlayPosition.DEFAULT)
    }
}
