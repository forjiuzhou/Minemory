package dev.owner.ownlog

import dev.owner.ownlog.domain.ClickSnap
import dev.owner.ownlog.domain.DEFAULT_OCR_PACKAGES
import dev.owner.ownlog.domain.LEGACY_OCR_PACKAGES
import dev.owner.ownlog.domain.GeoFix
import dev.owner.ownlog.domain.LocationPolicy
import dev.owner.ownlog.domain.NotificationDedupe
import dev.owner.ownlog.domain.OcrPolicy
import dev.owner.ownlog.domain.OcrReason
import dev.owner.ownlog.domain.PasswordSignals
import dev.owner.ownlog.domain.ScreenChangeFilter
import dev.owner.ownlog.domain.ScreenText
import dev.owner.ownlog.domain.SessionTracker
import dev.owner.ownlog.domain.TextNode
import dev.owner.ownlog.domain.decideClick
import dev.owner.ownlog.domain.formatDuration
import dev.owner.ownlog.domain.parsePackageList
import dev.owner.ownlog.domain.startOfDayMillis
import dev.owner.ownlog.domain.upgradeDefaultOcrPackages
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    @Test
    fun passwordFlagAndInputTypes() {
        assertTrue(PasswordSignals.isPassword(true, 0))
        val textPassword = PasswordSignals.TYPE_CLASS_TEXT or
            PasswordSignals.TYPE_TEXT_VARIATION_PASSWORD or
            PasswordSignals.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(PasswordSignals.isPassword(false, textPassword))
        val visible = PasswordSignals.TYPE_CLASS_TEXT or PasswordSignals.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertTrue(PasswordSignals.isPassword(false, visible))
        val web = PasswordSignals.TYPE_CLASS_TEXT or PasswordSignals.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertTrue(PasswordSignals.isPassword(false, web))
        val numberPassword = PasswordSignals.TYPE_CLASS_NUMBER or PasswordSignals.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(PasswordSignals.isPassword(false, numberPassword))
        val uri = PasswordSignals.TYPE_CLASS_TEXT or PasswordSignals.TYPE_TEXT_VARIATION_URI
        assertFalse(PasswordSignals.isPassword(false, uri))
        val plain = PasswordSignals.TYPE_CLASS_TEXT
        assertFalse(PasswordSignals.isPassword(false, plain))
    }

    @Test
    fun screenTextDropsPasswordKeepsExpenseAndDedupes() {
        val nodes = listOf(
            TextNode("微信", null, "com.tencent.mm:id/title", false, 0),
            TextNode("微信", "微信", null, false, 0),
            TextNode("星巴克", null, "com.tencent.mm:id/merchant", false, 0),
            TextNode("¥36.00", null, "com.tencent.mm:id/amount", false, 0),
            TextNode("hunter2", null, "com.bank:id/password", true, 0),
            TextNode(
                "123456",
                "支付密码",
                "com.bank:id/pin",
                false,
                PasswordSignals.TYPE_CLASS_NUMBER or PasswordSignals.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
            TextNode("...", null, null, false, 0),
            TextNode("a", null, null, false, 0),
            TextNode("买", null, "com.shop:id/buy", false, 0),
        )
        val extracted = ScreenText.extract(nodes, "账单")
        assertTrue(extracted.content.contains("星巴克"))
        assertTrue(extracted.content.contains("¥36.00"))
        assertTrue(extracted.content.contains("买"))
        assertFalse(extracted.content.contains("hunter2"))
        assertFalse(extracted.content.contains("123456"))
        assertFalse(extracted.content.contains("支付密码"))
        assertEquals(2, extracted.droppedPasswordFields)
        assertEquals(listOf("账单", "微信", "星巴克", "¥36.00", "买"), extracted.content.split("\n"))
        assertEquals("账单", extracted.title)
        assertTrue(extracted.signature.isNotBlank())
        assertFalse(extracted.viewIds.contains("com.bank:id/password"))
        assertTrue(extracted.viewIds.contains("com.tencent.mm:id/amount"))
        val again = ScreenText.extract(nodes, "账单")
        assertEquals(extracted.signature, again.signature)
    }

    @Test
    fun screenChangeFilterThrottlesSameWindowAndKeepsALaterVisit() {
        val filter = ScreenChangeFilter(sameWindowMinGapMs = 1_200)
        assertFalse(filter.shouldRecord("com.tencent.mm", "1", "", 0))
        assertTrue(filter.shouldRecord("com.tencent.mm", "1", "sig-a", 0))
        assertFalse(filter.shouldRecord("com.tencent.mm", "1", "sig-a", 100))
        assertFalse(filter.shouldRecord("com.tencent.mm", "1", "sig-b", 200))
        assertTrue(filter.shouldRecord("com.tencent.mm", "1", "sig-b", 2_000))
        assertTrue(filter.shouldRecord("com.tencent.mm", "2", "sig-c", 2_100))
        assertFalse(filter.shouldRecord("com.tencent.mm", "3", "sig-c", 2_200))
        assertTrue(filter.shouldRecord("com.tencent.mm", "3", "sig-c", 4_000))
    }

    @Test
    fun clickSkipsPasswordTextAndBlankControls() {
        val password = decideClick(
            ClickSnap("hunter2", "密码", "id/password", "EditText", true, 0),
        )
        assertTrue(password.record)
        assertTrue(password.passwordSkipped)
        assertNull(password.content)

        val numberPassword = decideClick(
            ClickSnap(
                "123456",
                null,
                "id/pin",
                "EditText",
                false,
                PasswordSignals.TYPE_CLASS_NUMBER or PasswordSignals.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertTrue(numberPassword.passwordSkipped)
        assertNull(numberPassword.content)

        val amount = decideClick(ClickSnap("确认支付", "¥128.00", "id/pay", "Button", false, 0))
        assertTrue(amount.record)
        assertFalse(amount.passwordSkipped)
        assertEquals("确认支付 · ¥128.00", amount.content)

        val empty = decideClick(ClickSnap("  ", null, "id/icon", "ImageView", false, 0))
        assertFalse(empty.record)
    }

    @Test
    fun notificationDedupe() {
        val dedupe = NotificationDedupe(sameTextWindowMs = 120_000, minIntervalMs = 10_000)
        assertTrue(dedupe.shouldRecord("mm:1", "支付\n¥10", 0))
        assertFalse(dedupe.shouldRecord("mm:1", "支付\n¥10", 30_000))
        assertFalse(dedupe.shouldRecord("mm:1", "支付\n¥12", 5_000))
        assertTrue(dedupe.shouldRecord("mm:1", "支付\n¥12", 20_000))
        assertTrue(dedupe.shouldRecord("mm:2", "支付\n¥10", 20_000))
        assertFalse(dedupe.shouldRecord("mm:1", "", 30_000))
    }

    @Test
    fun sessionDurationsAndScreenOff() {
        val tracker = SessionTracker()
        val opened = tracker.onForeground("com.tencent.mm", "微信", "订阅号", 1_000) { "s1" }
        assertEquals(listOf("app_fg"), opened.map { it.type })
        assertTrue(tracker.onForeground("com.tencent.mm", "微信", "一篇文章", 2_000) { "s2" }.isEmpty())
        assertEquals("一篇文章", tracker.open?.title)
        val switched = tracker.onForeground("com.ss.android.ugc.aweme", "抖音", null, 11_000) { "s3" }
        assertEquals(listOf("app_bg", "app_fg"), switched.map { it.type })
        assertEquals("10000", switched[0].extras["duration_ms"])
        assertEquals("前台 10秒", switched[0].content)
        assertEquals("s1", switched[0].extras["session_id"])
        assertEquals("s3", switched[1].extras["session_id"])
        val closed = tracker.close(71_000, "screen_off")
        assertEquals("screen_off", closed[0].extras["reason"])
        assertEquals("前台 1分0秒", closed[0].content)
        assertNull(tracker.open)
        assertTrue(tracker.close(72_000, "screen_off").isEmpty())
        assertEquals("1小时1分", formatDuration(3_660_000))
    }

    @Test
    fun locationMovementAndStationaryHeartbeat() {
        val start = GeoFix(39.9042, 116.4074, 10.0, 0)
        assertTrue(LocationPolicy.shouldStore(null, start))
        assertFalse(LocationPolicy.shouldStore(null, start.copy(lat = 120.0)))
        val tooSoon = start.copy(lat = 39.9100, timeMillis = 10_000)
        assertFalse(LocationPolicy.shouldStore(start, tooSoon))
        val moved = start.copy(lat = 39.9052, timeMillis = 60_000)
        val meters = LocationPolicy.distanceMeters(start.lat, start.lng, moved.lat, moved.lng)
        assertTrue(meters in 100.0..130.0)
        assertTrue(LocationPolicy.shouldStore(start, moved))
        val jitter = moved.copy(accuracyMeters = 500.0, timeMillis = 120_000)
        assertFalse(LocationPolicy.shouldStore(moved, jitter))
        val still = start.copy(lat = 39.90421, timeMillis = 4 * 60 * 1000)
        assertFalse(LocationPolicy.shouldStore(start, still))
        val heartbeat = still.copy(timeMillis = LocationPolicy.STATIONARY_MS)
        assertTrue(LocationPolicy.shouldStore(start, heartbeat))
    }

    @Test
    fun ocrListAndMissingText() {
        val douyin = setOf("com.ss.android.ugc.aweme")
        assertEquals(
            OcrReason.OCR_LIST,
            OcrPolicy.decide("com.ss.android.ugc.aweme", douyin, null, null, 1_000, 1_500),
        )
        assertNull(
            OcrPolicy.decide("com.ss.android.ugc.aweme", douyin, null, 0, 10_000, 10_000),
        )
        assertEquals(
            OcrReason.OCR_LIST,
            OcrPolicy.decide(
                "com.ss.android.ugc.aweme",
                douyin,
                0,
                0,
                60_000,
                OcrPolicy.LIST_HEARTBEAT_MS,
            ),
        )
        assertNull(
            OcrPolicy.decide("com.tencent.mm", douyin, null, null, 10_000, 11_000),
        )
        assertEquals(
            OcrReason.NO_A11Y_TEXT,
            OcrPolicy.decide("com.tencent.mm", douyin, null, null, 0, OcrPolicy.NO_TEXT_DELAY_MS),
        )
        assertNull(
            OcrPolicy.decide("com.tencent.mm", douyin, 59_000, 0, 0, 60_000),
        )
    }

    @Test
    fun packageListAndStartOfDay() {
        assertEquals(
            linkedSetOf("com.ss.android.ugc.aweme", "com.xingin.xhs"),
            parsePackageList("com.ss.android.ugc.aweme, com.xingin.xhs\n# skip\nnot-a-package"),
        )
        assertTrue(parsePackageList(DEFAULT_OCR_PACKAGES).contains("com.ss.android.ugc.aweme"))
        assertTrue(parsePackageList(DEFAULT_OCR_PACKAGES).contains("com.xingin.xhs"))
        assertTrue(parsePackageList(DEFAULT_OCR_PACKAGES).contains("com.tencent.mm"))
        assertEquals(DEFAULT_OCR_PACKAGES, upgradeDefaultOcrPackages(null))
        assertEquals(DEFAULT_OCR_PACKAGES, upgradeDefaultOcrPackages(LEGACY_OCR_PACKAGES))
        assertEquals(
            "com.example.app",
            upgradeDefaultOcrPackages("com.example.app"),
        )
        val defaults = parsePackageList(DEFAULT_OCR_PACKAGES)
        assertNull(
            OcrPolicy.decide("com.tencent.mm", defaults, null, 0, 0, OcrPolicy.MIN_GAP_MS - 1),
        )
        assertEquals(
            OcrReason.OCR_LIST,
            OcrPolicy.decide("com.tencent.mm", defaults, null, null, 1_000, 1_500),
        )
        val now = Instant.parse("2026-10-22T13:00:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-10-22T00:00:00Z").toEpochMilli(),
            startOfDayMillis(now, ZoneOffset.UTC),
        )
    }

    @Test
    fun interactionCapturesIgnoreTheGlobalMinimum() {
        val packages = setOf("com.tencent.mm", "com.ss.android.ugc.aweme")
        assertEquals("interaction", OcrReason.INTERACTION.wire)
        assertEquals(5_000L, OcrPolicy.DEFAULT_INTERACTION_GAP_MS)
        assertEquals(3_000L, OcrPolicy.clampInteractionGap(0))
        assertEquals(3_000L, OcrPolicy.clampInteractionGap(3_000L))
        assertEquals(5_000L, OcrPolicy.clampInteractionGap(5_000L))
        assertEquals(60_000L, OcrPolicy.clampInteractionGap(60_000L))
        assertEquals(60_000L, OcrPolicy.clampInteractionGap(90_000L))

        val now = 2_000L
        assertNull(OcrPolicy.decide("com.tencent.mm", packages, null, 0, 0, now))
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = null,
                interactionAt = 1_000L,
                now = now,
            ),
        )
        assertNull(
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = null,
                interactionAt = 1_001L,
                now = now,
            ),
        )

        val click = 10_000L
        assertNull(
            OcrPolicy.decideInteraction("com.tencent.mm", packages, null, click, click + 999L),
        )
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction("com.tencent.mm", packages, null, click, click + OcrPolicy.INTERACTION_DELAY_MS),
        )

        val lastScroll = 20_000L
        val settled = lastScroll + OcrPolicy.SCROLL_SETTLE_MS
        assertNull(
            OcrPolicy.decideInteraction(
                "com.ss.android.ugc.aweme",
                packages,
                null,
                settled,
                settled + OcrPolicy.INTERACTION_DELAY_MS - 1,
            ),
        )
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction(
                "com.ss.android.ugc.aweme",
                packages,
                null,
                settled,
                settled + OcrPolicy.INTERACTION_DELAY_MS,
            ),
        )

        assertNull(
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 20_000L,
                interactionAt = 23_999L,
                now = 24_999L,
            ),
        )
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 20_000L,
                interactionAt = 24_000L,
                now = 25_000L,
                gapMs = 5_000L,
            ),
        )
        assertNull(
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 0L,
                interactionAt = 8_999L,
                now = 9_999L,
                gapMs = 10_000L,
            ),
        )
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 0L,
                interactionAt = 9_000L,
                now = 10_000L,
                gapMs = 10_000L,
            ),
        )
        assertNull(
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 0L,
                interactionAt = 1_999L,
                now = 2_999L,
                gapMs = 1_000L,
            ),
        )
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 0L,
                interactionAt = 2_000L,
                now = 3_000L,
                gapMs = 1_000L,
            ),
        )
        assertNull(
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 0L,
                interactionAt = 58_999L,
                now = 59_999L,
                gapMs = 120_000L,
            ),
        )
        assertEquals(
            OcrReason.INTERACTION,
            OcrPolicy.decideInteraction(
                "com.tencent.mm",
                packages,
                lastInteractionOcrAt = 0L,
                interactionAt = 59_000L,
                now = 60_000L,
                gapMs = 120_000L,
            ),
        )

        assertNull(OcrPolicy.decideInteraction("", packages, null, 0L, 5_000L))
        assertNull(OcrPolicy.decideInteraction("com.android.chrome", packages, null, 0L, 5_000L))
        assertEquals(
            OcrReason.NO_A11Y_TEXT,
            OcrPolicy.decide("com.android.chrome", packages, null, null, 0L, OcrPolicy.NO_TEXT_DELAY_MS),
        )
        assertNull(
            OcrPolicy.decideInteraction(
                "com.android.chrome",
                packages,
                null,
                0L,
                OcrPolicy.NO_TEXT_DELAY_MS,
            ),
        )
    }
}
