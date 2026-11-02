package dev.owner.ownlog.domain

enum class OcrReason(val wire: String) {
    OCR_LIST("ocr_list"),
    NO_A11Y_TEXT("no_a11y_text"),
    INTERACTION("interaction"),
}

object OcrPolicy {
    const val MIN_GAP_MS = 20_000L
    const val LIST_GAP_MS = 30_000L
    const val LIST_HEARTBEAT_MS = 60_000L
    const val NO_TEXT_DELAY_MS = 8_000L
    const val NO_TEXT_GAP_MS = 90_000L
    const val NEW_SCREEN_WINDOW_MS = 3_000L
    const val INTERACTION_DELAY_MS = 1_000L
    const val SCROLL_SETTLE_MS = 800L
    const val DEFAULT_INTERACTION_GAP_MS = 5_000L
    const val MIN_INTERACTION_GAP_MS = 3_000L
    const val MAX_INTERACTION_GAP_MS = 60_000L

    fun clampInteractionGap(ms: Long): Long = ms.coerceIn(MIN_INTERACTION_GAP_MS, MAX_INTERACTION_GAP_MS)

    /**
     * Click and settled-scroll captures for OCR-list packages.
     * [interactionAt] is the click time, or the moment a scroll burst settled
     * (last scroll plus [SCROLL_SETTLE_MS]). The capture is due [INTERACTION_DELAY_MS] later.
     * [MIN_GAP_MS] does not apply. The per-app gap is clamped to 3–60 seconds.
     */
    fun decideInteraction(
        packageName: String,
        ocrPackages: Set<String>,
        lastInteractionOcrAt: Long?,
        interactionAt: Long,
        now: Long,
        gapMs: Long = DEFAULT_INTERACTION_GAP_MS,
    ): OcrReason? {
        if (packageName.isBlank() || packageName !in ocrPackages) return null
        if (now - interactionAt < INTERACTION_DELAY_MS) return null
        val since = if (lastInteractionOcrAt == null) Long.MAX_VALUE else now - lastInteractionOcrAt
        if (since < clampInteractionGap(gapMs)) return null
        return OcrReason.INTERACTION
    }

    fun decide(
        packageName: String,
        ocrPackages: Set<String>,
        lastUsefulTextAt: Long?,
        lastOcrAt: Long?,
        screenChangedAt: Long,
        now: Long,
    ): OcrReason? {
        if (packageName.isBlank()) return null
        val sinceOcr = if (lastOcrAt == null) Long.MAX_VALUE else now - lastOcrAt
        if (sinceOcr < MIN_GAP_MS) return null
        if (packageName in ocrPackages) {
            val sinceScreen = now - screenChangedAt
            val newScreen = sinceScreen in 0..NEW_SCREEN_WINDOW_MS && sinceOcr >= LIST_GAP_MS
            val heartbeat = sinceOcr >= LIST_HEARTBEAT_MS
            if (newScreen || heartbeat) return OcrReason.OCR_LIST
            return null
        }
        val silenceAnchor = maxOf(lastUsefulTextAt ?: Long.MIN_VALUE, screenChangedAt)
        val silentFor = now - silenceAnchor
        if (silentFor >= NO_TEXT_DELAY_MS && sinceOcr >= NO_TEXT_GAP_MS) {
            return OcrReason.NO_A11Y_TEXT
        }
        return null
    }
}
