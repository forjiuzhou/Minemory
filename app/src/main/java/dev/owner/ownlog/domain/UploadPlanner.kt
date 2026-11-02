package dev.owner.ownlog.domain

data class SizedItem(val id: String, val bytes: Int)

enum class FailureAction { RETRY, ABANDON, SPLIT }

fun planBatches(items: List<SizedItem>, maxCount: Int, maxBytes: Int): List<List<SizedItem>> {
    require(maxCount >= 1)
    require(maxBytes >= 1)
    val batches = mutableListOf<List<SizedItem>>()
    var current = mutableListOf<SizedItem>()
    var bytes = 2
    fun flush() {
        if (current.isNotEmpty()) {
            batches += current.toList()
            current = mutableListOf()
            bytes = 2
        }
    }
    for (item in items) {
        val need = item.bytes.coerceAtLeast(2) + 1
        if (current.isNotEmpty() && (current.size >= maxCount || bytes + need > maxBytes)) flush()
        current += item
        bytes += need
    }
    flush()
    return batches
}

fun failureAction(batchSize: Int, retryable: Boolean): FailureAction {
    if (retryable) return FailureAction.RETRY
    if (batchSize <= 1) return FailureAction.ABANDON
    return FailureAction.SPLIT
}

fun estimateOutgoingBytes(event: OutgoingEvent): Int {
    fun weight(value: String?) = (value?.length ?: 0) * 3
    return 320 +
        weight(event.content) +
        weight(event.title) +
        weight(event.extrasJson) +
        weight(event.packageName) +
        weight(event.appLabel) +
        weight(event.deviceId)
}
