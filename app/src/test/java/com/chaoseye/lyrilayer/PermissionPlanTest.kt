package com.chaoseye.lyrilayer

import org.junit.Assert.*
import org.junit.Test

class PermissionPlanTest {
    @Test fun skipsGrantedPermissions(){
        assertNull(PermissionPlan.next(true,true,true,0))
        assertEquals(RequiredAccess.LISTENER,PermissionPlan.next(true,false,true,0))
        assertEquals(RequiredAccess.OVERLAY,PermissionPlan.next(true,true,false,0))
    }
    @Test fun eachMissingPermissionIsRequestedAtMostOncePerPass(){
        var attempted=0
        for(expected in RequiredAccess.entries){
            val next=PermissionPlan.next(false,false,false,attempted)
            assertEquals(expected,next)
            attempted=attempted or expected.bit
        }
        assertNull(PermissionPlan.next(false,false,false,attempted))
    }
    @Test fun changesInPermissionAreRechecked(){
        assertEquals(RequiredAccess.OVERLAY,PermissionPlan.next(true,true,false,RequiredAccess.AUDIO.bit))
        assertEquals(RequiredAccess.AUDIO,PermissionPlan.next(false,true,true,0))
    }
}
