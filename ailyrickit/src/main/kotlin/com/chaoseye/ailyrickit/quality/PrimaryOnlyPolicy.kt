package com.chaoseye.ailyrickit.quality

import com.chaoseye.ailyrickit.quality.LyricQualityPolicy as P

/** V15 fast profile: run raw-context review before core ownership, retain held
 * lines for inspection and do not schedule automatic recovery. This is a policy
 * component, not an Android decoder or full inference pipeline. */
object PrimaryOnlyPolicy {
    const val VERSION = 15
    const val MAX_RECOVERY_WINDOWS = 0
    data class Result(val clean: List<P.Segment>, val review: List<P.Segment>)
    fun partition(raw: List<P.Segment>, windowLengthMs: Long, coreStartMs: Long, coreEndMs: Long): Result {
        require(windowLengthMs > 0 && coreStartMs >= 0 && coreEndMs > coreStartMs && coreEndMs <= windowLengthMs)
        val valid = raw.filter { it.startMs < windowLengthMs && it.endMs <= windowLengthMs + 350 }
        val held = RawContextPolicy.reviewIndices(valid)
        val clean = mutableListOf<P.Segment>()
        val review = mutableListOf<P.Segment>()
        for ((i, original) in valid.withIndex()) {
            val end = minOf(original.endMs, windowLengthMs)
            val middle = original.startMs + (end - original.startMs) / 2.0
            if (middle < coreStartMs || middle >= coreEndMs || P.normalize(original.text).isEmpty()) continue
            val line = original.copy(endMs = end)
            if (i in held) review.add(line) else clean.add(line)
        }
        return Result(clean.sortedBy { it.startMs }, review.sortedBy { it.startMs })
    }
}
