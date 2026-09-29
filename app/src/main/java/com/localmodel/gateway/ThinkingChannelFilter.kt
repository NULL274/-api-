package com.localmodel.gateway

class ThinkingChannelFilter {

    private companion object {
        const val START = "<|channel>"
        const val END = "<channel|>"
    }

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
                val end = buffer.indexOf(END)
                if (end >= 0) {
                    thinking.append(buffer, 0, end)
                    buffer.delete(0, end + END.length)
                    inThought = false
                    changed = true
                } else {
                    val keep = partialSuffix(END)
                    thinking.append(buffer, 0, buffer.length - keep)
                    buffer.delete(0, buffer.length - keep)
                }
            } else {
                val start = buffer.indexOf(START)
                if (start >= 0) {
                    val lineEnd = buffer.indexOf("\n", start)
                    if (lineEnd < 0) break
                    answer.append(buffer, 0, start)
                    val channelName = buffer.substring(start + START.length, lineEnd).trim()
                    buffer.delete(0, lineEnd + 1)
                    inThought = channelName.contains("thought")
                    changed = true
                } else {
                    val keep = partialSuffix(START)
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

    private fun partialSuffix(marker: String): Int {
        val max = minOf(marker.length - 1, buffer.length)
        for (len in max downTo 1) {
            if (buffer.substring(buffer.length - len) == marker.substring(0, len)) return len
        }
        return 0
    }
}
