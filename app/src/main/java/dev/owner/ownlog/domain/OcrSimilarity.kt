package dev.owner.ownlog.domain

object OcrSimilarity {
    /** Skip a capture only when it is strictly more similar than this. */
    const val DUPLICATE_ABOVE = 0.90

    fun isNearDuplicate(previous: String?, next: String, above: Double = DUPLICATE_ABOVE): Boolean {
        if (previous.isNullOrBlank() || next.isBlank()) return false
        return similarity(previous, next) > above
    }

    fun similarity(leftRaw: String, rightRaw: String): Double {
        val left = normalize(leftRaw)
        val right = normalize(rightRaw)
        if (left == right) return if (left.isEmpty()) 0.0 else 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val maxLen = maxOf(left.length, right.length)
        return 1.0 - levenshtein(left, right).toDouble() / maxLen
    }

    fun normalize(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
