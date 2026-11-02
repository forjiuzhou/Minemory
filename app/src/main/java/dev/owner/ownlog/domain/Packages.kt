package dev.owner.ownlog.domain

import java.time.Instant
import java.time.ZoneId

const val DEFAULT_OCR_PACKAGES =
    "com.ss.android.ugc.aweme\ncom.ss.android.ugc.aweme.lite\ncom.xingin.xhs\ncom.tencent.mm"

/** The list shipped before WeChat Channels was added. Unedited copies upgrade. */
const val LEGACY_OCR_PACKAGES =
    "com.ss.android.ugc.aweme\ncom.ss.android.ugc.aweme.lite\ncom.xingin.xhs"

fun upgradeDefaultOcrPackages(stored: String?): String {
    if (stored.isNullOrBlank()) return DEFAULT_OCR_PACKAGES
    if (parsePackageList(stored) == parsePackageList(LEGACY_OCR_PACKAGES)) return DEFAULT_OCR_PACKAGES
    return stored
}

fun parsePackageList(raw: String): Set<String> {
    return raw.split('\n', ',', ' ', '\t', '，', '、', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '.' in it }
        .toCollection(linkedSetOf())
}

fun startOfDayMillis(nowMillis: Long, zone: ZoneId): Long {
    return Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
