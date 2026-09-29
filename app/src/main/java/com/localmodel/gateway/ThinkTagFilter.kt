package com.localmodel.gateway

class ThinkTagFilter(
    private val startTags: List<String> = listOf("<think>", "\u003c\uff5cthink\uff5c\u003e"),
    private val endTags: List<String> = listOf("</think>", "\u003c\uff5c/think\uff5c\u003e", "\u003c\uff5cend\u2581of\u2581thinking\uff5c\u003e"),
) {

    private val buffer = StringBuilder()
    private var inThought = false

    fun feed(token: String): Pair<String, String> {
        buffer.append(token)
        val answer = StringBuilder()
        val thinking = StringBuilder()

        var changed = true
        while (changed) {
            changed = false
            if (inThought) {
                val hit = findEarliest(buffer, endTags)
                if (hit != null) {
                    thinking.append(buffer, 0, hit.first)
                    buffer.delete(0, hit.first + hit.second.length)
                    inThought = false
                    changed = true
                } else {
                    val keep = partialSuffix(endTags)
                    thinking.append(buffer, 0, buffer.length - keep)
                    buffer.delete(0, buffer.length - keep)
                }
            } else {
                val hit = findEarliest(buffer, startTags)
                if (hit != null) {
                    answer.append(buffer, 0, hit.first)
                    buffer.delete(0, hit.first + hit.second.length)
                    inThought = true
                    changed = true
                } else {
                    val keep = partialSuffix(startTags)
                    answer.append(buffer, 0, buffer.length - keep)
                    buffer.delete(0, buffer.length - keep)
                }
            }
        }
        return answer.toString() to thinking.toString()
    }

    fun flush(): Pair<String, String> {
        val rest = buffer.toString()
        buffer.setLength(0)
        return if (inThought) "" to rest else rest to ""
    }

    private fun findEarliest(text: StringBuilder, markers: List<String>): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (marker in markers) {
            val index = text.indexOf(marker)
            if (index >= 0 && (best == null || index < best.first)) best = index to marker
        }
        return best
    }

    private fun partialSuffix(markers: List<String>): Int {
        var best = 0
        for (marker in markers) {
            val max = minOf(marker.length - 1, buffer.length)
            for (len in max downTo 1) {
                if (buffer.substring(buffer.length - len) == marker.substring(0, len) && len > best) {
                    best = len
                }
            }
        }
        return best
    }
}
