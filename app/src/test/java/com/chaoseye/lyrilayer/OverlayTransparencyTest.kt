package com.chaoseye.lyrilayer

import org.junit.Assert.*
import org.junit.Test

class OverlayTransparencyTest {
    @Test fun defaultPreservesPreviousWindowAlpha(){
        assertEquals(0.78f,OverlayTransparency.windowAlpha(OverlayTransparency.DEFAULT),0.0001f)
    }
    @Test fun clampsInvalidStoredValues(){
        assertEquals(20,OverlayTransparency.normalize(-1))
        assertEquals(90,OverlayTransparency.normalize(101))
    }
    @Test fun alphaDecreasesWithTransparencyAndPreservesTouchLimit(){
        assertEquals(0.8f,OverlayTransparency.windowAlpha(20),0.0001f)
        assertEquals(0.5f,OverlayTransparency.windowAlpha(50),0.0001f)
        assertEquals(0.1f,OverlayTransparency.windowAlpha(90),0.0001f)
        assertTrue((0..100).all{OverlayTransparency.windowAlpha(it)<=0.8f})
    }
}
