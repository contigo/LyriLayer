package com.chaoseye.ailyrickit.quality

import com.chaoseye.ailyrickit.quality.LyricQualityPolicy as P

/** Run on complete decoder output BEFORE core-overlap filtering.
 * Coordinates must use one time origin. Returns indices for review, not deletion.
 */
object RawContextPolicy {
    const val VERSION = 14
    private val extra = Regex("thumbsupifyoulikethisvideo|seeyouinthenextvideo|thanksforwatching")
    private val tail = Regex("^(?:됩니다|됩니다감사합니다|필수|부탁드려요|부탁드립니다|부탁합니다|저에게아주큰힘이(?:됩니다)?|큰힘이(?:됩니다)?|youinthenext(?:video)?|video|watching|andillsee(?:you)?|illsee(?:you)?|and(?:please)?subscribe|よろしくお願いいたします|お願いいたします|お願いします)$")
    private fun promo(text: String) = P.promotional(text) || extra.containsMatchIn(P.normalize(text))
    fun reviewIndices(segments: List<P.Segment>): Set<Int> {
        val ordered = segments.withIndex().sortedWith(compareBy<IndexedValue<P.Segment>>{it.value.startMs}.thenBy{it.index})
        val out = mutableSetOf<Int>()
        for (i in ordered.indices) {
            var joined = ordered[i].value.text
            var standalone = promo(joined)
            if (standalone) out.add(i)
            for (j in i+1 until minOf(i+3, ordered.size)) {
                if (ordered[j].value.startMs - ordered[j-1].value.endMs > 700) break
                joined += " " + ordered[j].value.text
                standalone = standalone || promo(ordered[j].value.text)
                if (!standalone && promo(joined)) out.addAll(i..j)
            }
        }
        for (i in out.sorted()) {
            for (j in i+1 until minOf(i+3, ordered.size)) {
                if (ordered[j].value.startMs - ordered[j-1].value.endMs > 1000 ||
                    !tail.matches(P.normalize(ordered[j].value.text))) break
                out.add(j)
            }
        }
        return out.mapTo(mutableSetOf()){ordered[it].index}
    }
}
