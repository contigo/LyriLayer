package com.chaoseye.ailyrickit.quality

/** V16 weighted vote early termination. Caller owns decoder and remaining probe count. */
object BoundedLanguagePolicy {
    data class Vote(val language: String, val probability: Double?)
    private val preferred = listOf("ko", "en", "ja")
    fun probeOrder(n: Int): List<Int> {
        require(n >= 0)
        if (n == 0) return emptyList()
        return (listOf(n / 2, n / 4, 3 * n / 4, 0, n - 1) + (0 until n)).distinct()
    }
    fun lockedWinner(votes: List<Vote>, remaining: Int): String? {
        require(remaining >= 0)
        val scores = mutableMapOf<String, Double>()
        for (v in votes) {
            val w = v.probability ?: 1.0
            require(w.isFinite() && w in 0.0..1.0)
            scores[v.language] = (scores[v.language] ?: 0.0) + w
        }
        val ranked = preferred.sortedByDescending { scores[it] ?: 0.0 }
        val best = scores[ranked[0]] ?: 0.0
        val second = scores[ranked[1]] ?: 0.0
        return if (best > 0 && best - second > remaining + 1e-9) ranked[0] else null
    }
}
