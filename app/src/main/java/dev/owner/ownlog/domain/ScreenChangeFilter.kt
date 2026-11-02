package dev.owner.ownlog.domain

/**
 * Keeps one record per meaningful screen, not one record per accessibility event.
 * Identical text on the same window is dropped. Same-window edits are throttled.
 * Coming back to the same text in a new window is kept once the gap has passed,
 * so a second visit to the same article is not lost.
 */
class ScreenChangeFilter(
    private val sameWindowMinGapMs: Long = 1_200L,
) {
    private var lastPackage: String? = null
    private var lastWindow: String? = null
    private var lastSignature: String? = null
    private var lastEmitAt: Long = 0L

    fun shouldRecord(packageName: String, windowKey: String, signature: String, now: Long): Boolean {
        if (signature.isBlank()) return false
        val sameWindow = packageName == lastPackage && windowKey == lastWindow
        val sameSignature = packageName == lastPackage && signature == lastSignature
        if (sameWindow && sameSignature) return false
        if (sameWindow && now - lastEmitAt < sameWindowMinGapMs) return false
        if (!sameWindow && sameSignature && now - lastEmitAt < sameWindowMinGapMs) return false
        lastPackage = packageName
        lastWindow = windowKey
        lastSignature = signature
        lastEmitAt = now
        return true
    }
}
