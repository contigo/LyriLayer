package com.chaoseye.lyrilayer
import org.junit.Assert.*
import org.junit.Test
class AiQueueCountsTest {
    private val missing=AiQueueEntry("a","A","Artist",AiQueueRules.EXTRACT,1L)
    private val plain=AiQueueEntry("b","B","Artist",AiQueueRules.ALIGN,2L)
    @Test fun countsFollowQueue(){
        assertEquals(0 to 0,AiQueueRules.countKinds(emptyList()))
        assertEquals(1 to 0,AiQueueRules.countKinds(listOf(missing)))
        assertEquals(1 to 1,AiQueueRules.countKinds(listOf(missing,plain)))
        assertEquals(1 to 1,AiQueueRules.countKinds(listOf(missing.copy(state="saved"),plain.copy(state="review"))))
        assertEquals(0 to 1,AiQueueRules.countKinds(listOf(plain)))
    }
    @Test fun reviewIncludesRunningFirst(){
        val review=missing.copy(state="review")
        val running=plain.copy(state="running")
        val pending=missing.copy(key="c",state="pending")
        val failed=plain.copy(key="d",state="failed")
        val saved=plain.copy(key="e",state="saved")
        val skipped=plain.copy(key="f",state="skipped")
        val all=listOf(review,pending,failed,saved,skipped,running)
        assertEquals(listOf(running,review),AiQueueRules.filtered(all,"review"))
        assertEquals(listOf(pending),AiQueueRules.filtered(all,"pending"))
        assertEquals(listOf(failed),AiQueueRules.filtered(all,"failed"))
        assertEquals(listOf(saved,skipped),AiQueueRules.filtered(all,"saved"))
        assertEquals(all,AiQueueRules.filtered(all,"all"))
    }
}
