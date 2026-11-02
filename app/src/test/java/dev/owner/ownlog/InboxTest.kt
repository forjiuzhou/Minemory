package dev.owner.ownlog

import dev.owner.ownlog.data.SupabaseApi
import dev.owner.ownlog.data.UploadOutcome
import dev.owner.ownlog.domain.OpenSession
import dev.owner.ownlog.domain.UsageKind
import dev.owner.ownlog.domain.UsageTick
import dev.owner.ownlog.domain.extrasFromMap
import dev.owner.ownlog.domain.extrasWithPageMeta
import dev.owner.ownlog.domain.extractUrl
import dev.owner.ownlog.domain.inboxObjectPath
import dev.owner.ownlog.domain.inferSourcePackage
import dev.owner.ownlog.domain.mapNote
import dev.owner.ownlog.domain.mapPhoto
import dev.owner.ownlog.domain.mapShare
import dev.owner.ownlog.domain.mapVoice
import dev.owner.ownlog.domain.mergeJson
import dev.owner.ownlog.domain.parsePageMeta
import dev.owner.ownlog.domain.planUsage
import dev.owner.ownlog.domain.storageObjectUrl
import java.io.File
import java.time.Instant
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxTest {
    @Test
    fun extractsUrlAndInfersSourceFromDomain() {
        val douyin = "7.64 复制打开抖音，看看【周末去哪】https://v.douyin.com/iRnAbCd/ 怎么拍的"
        assertEquals("https://v.douyin.com/iRnAbCd/", extractUrl(douyin))
        assertEquals("com.ss.android.ugc.aweme", inferSourcePackage(null, extractUrl(douyin)))
        assertEquals(
            "com.tencent.mm",
            inferSourcePackage(null, "https://mp.weixin.qq.com/s/abc"),
        )
        assertEquals(
            "com.xingin.xhs",
            inferSourcePackage(null, extractUrl("笔记 https://xhslink.com/a/xyz).")),
        )
        assertEquals(
            "https://xhslink.com/a/xyz",
            extractUrl("笔记 https://xhslink.com/a/xyz)."),
        )
        assertEquals(
            "com.tencent.mm",
            inferSourcePackage("com.tencent.mm", "https://www.douyin.com/video/1"),
        )
        assertNull(inferSourcePackage("android", null))
    }

    @Test
    fun mapsShareNotePhotoAndVoice() {
        val share = mapShare(
            subject = null,
            text = "7.64 复制打开抖音，看看【周末去哪】https://v.douyin.com/iRnAbCd/ 怎么拍的",
            note = "想去",
            explicitPackage = null,
            image = false,
        )
        assertEquals("share", share.type)
        assertTrue(share.content!!.contains("周末去哪"))
        assertTrue(share.content!!.contains("https://v.douyin.com/iRnAbCd/"))
        assertTrue(share.extrasJson.contains("\"url\":\"https://v.douyin.com/iRnAbCd/\""))
        assertTrue(share.extrasJson.contains("\"note\":\"想去\""))
        assertTrue(share.extrasJson.contains("\"source_package\":\"com.ss.android.ugc.aweme\""))

        val note = mapNote("买牛奶\n记得低脂", "明天")
        assertEquals("note", note.type)
        assertEquals("买牛奶", note.title)
        assertEquals("买牛奶\n记得低脂", note.content)
        assertTrue(note.extrasJson.contains("\"note\":\"明天\""))

        val photo = mapPhoto("超市小票\n12.5", "报销", "dev/2026/10/id.jpg", "image/jpeg", 1200)
        assertEquals("photo", photo.type)
        assertEquals("超市小票\n12.5", photo.content)
        assertTrue(photo.extrasJson.contains("\"file_path\":\"dev/2026/10/id.jpg\""))
        assertTrue(photo.extrasJson.contains("\"mime\":\"image/jpeg\""))
        assertTrue(photo.extrasJson.contains("\"bytes\":1200"))
        assertTrue(photo.extrasJson.contains("\"image\":true"))

        val silent = mapVoice(null, "开会", "dev/2026/10/id.m4a", "audio/mp4", 50)
        assertEquals("voice", silent.type)
        assertNull(silent.content)
        assertTrue(silent.extrasJson.contains("\"transcribed\":false"))
        assertTrue(silent.extrasJson.contains("\"note\":\"开会\""))

        val spoken = mapVoice("明天下雨", null, "dev/2026/10/id.m4a", "audio/mp4", 50)
        assertEquals("明天下雨", spoken.content)
        assertTrue(spoken.extrasJson.contains("\"transcribed\":true"))
    }

    @Test
    fun storagePathAndPageMetaRoundTrip() {
        val at = Instant.parse("2026-10-22T00:30:00Z").toEpochMilli()
        assertEquals("device-1/2026/10/abc.jpg", inboxObjectPath("device-1", at, "abc", "jpg"))
        assertEquals("uid/2026/10/abc.jpg", dev.owner.ownlog.domain.objectKey("uid", "pending/2026/10/abc.jpg"))
        assertEquals("uid/2026/10/abc.jpg", dev.owner.ownlog.domain.objectKey("uid", "uid/2026/10/abc.jpg"))
        assertEquals(
            "https://abc.supabase.co/storage/v1/object/inbox/device-1/2026/10/abc.jpg",
            storageObjectUrl("https://abc.supabase.co/", "device-1/2026/10/abc.jpg"),
        )
        val merged = mergeJson(
            """{"bytes":1200,"url":"https://a.example"}""",
            mapOf("og_title" to "标题", "og_description" to "说明"),
        )
        assertTrue(merged.contains("\"bytes\":1200"))
        assertTrue(merged.contains("\"og_title\":\"标题\""))
        assertTrue(merged.contains("\"url\":\"https://a.example\""))

        val html = """
            <html><head>
            <title>Hello &amp; 世界</title>
            <meta content="OG 标题" property="og:title">
            <meta property="og:description" content="一段 &quot;说明&quot;">
            </head></html>
        """.trimIndent()
        val meta = parsePageMeta(html)
        assertEquals("Hello & 世界", meta.title)
        assertEquals("OG 标题", meta.ogTitle)
        assertEquals("一段 \"说明\"", meta.ogDescription)
        val withOg = extrasWithPageMeta("""{"url":"https://a.example"}""", meta)
        assertTrue(withOg.contains("\"og_title\":\"OG 标题\""))
        assertTrue(withOg.contains("\"og_description\":\"一段 \\\"说明\\\"\""))
    }

    @Test
    fun usageSessionsKeepDurationAndCollapseFlicker() {
        var next = 0
        val plan = planUsage(
            ticks = listOf(
                UsageTick("com.tencent.mm", UsageKind.FOREGROUND, 1_000),
                UsageTick("com.tencent.mm", UsageKind.FOREGROUND, 1_200),
                UsageTick("com.ss.android.ugc.aweme", UsageKind.FOREGROUND, 10_000),
            ),
            open = null,
            skipPackage = "dev.owner.ownlog",
            newSessionId = { "s${++next}" },
        )
        assertEquals(listOf("app_fg", "app_bg", "app_fg"), plan.events.map { it.type })
        assertEquals("com.tencent.mm", plan.events[0].packageName)
        assertEquals("com.ss.android.ugc.aweme", plan.open?.packageName)
        val background = extrasFromMap(plan.events[1].extras)
        assertTrue(background.contains("\"duration_ms\":9000"))
        assertTrue(background.contains("\"source\":\"usage_stats\""))

        val flicker = planUsage(
            ticks = listOf(
                UsageTick("com.tencent.mm", UsageKind.FOREGROUND, 0),
                UsageTick("com.tencent.mm", UsageKind.BACKGROUND, 1_000),
                UsageTick("com.tencent.mm", UsageKind.FOREGROUND, 1_400),
            ),
            open = null,
            skipPackage = "dev.owner.ownlog",
            newSessionId = { "same" },
        )
        assertEquals(listOf("app_fg"), flicker.events.map { it.type })
        assertEquals("com.tencent.mm", flicker.open?.packageName)

        val skipped = planUsage(
            ticks = listOf(
                UsageTick("com.tencent.mm", UsageKind.FOREGROUND, 0),
                UsageTick("dev.owner.ownlog", UsageKind.FOREGROUND, 5_000),
            ),
            open = null,
            skipPackage = "dev.owner.ownlog",
            newSessionId = { "own" },
        )
        assertEquals(listOf("app_fg", "app_bg"), skipped.events.map { it.type })
        assertTrue(skipped.events.none { it.packageName == "dev.owner.ownlog" })
        assertNull(skipped.open)
        assertEquals(5_000L, skipped.events[1].occurredAt)
    }

    @Test
    fun storageUploadUsesTheSameKeyHeaders() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val file = File.createTempFile("ownlog", ".jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val api = SupabaseApi()
            val root = server.url("/").toString().trimEnd('/')
            val path = "device-1/2026/10/abc.jpg"
            assertTrue(api.uploadObject(root, "sb_publishable_example", "user-access-token", path, "image/jpeg", file) is UploadOutcome.Success)
            val published = server.takeRequest()
            assertEquals("POST", published.method)
            assertEquals("/storage/v1/object/inbox/device-1/2026/10/abc.jpg", published.path)
            assertEquals("sb_publishable_example", published.getHeader("apikey"))
            assertEquals("Bearer user-access-token", published.getHeader("Authorization"))
            assertFalse(published.getHeader("Authorization")!!.contains("sb_publishable"))

            val jwt = "eyJhbGciOiJIUzI1NiJ9.payload.sig"
            assertTrue(api.uploadObject(root, jwt, "user-access-token", path, "image/jpeg", file) is UploadOutcome.Success)
            val legacy = server.takeRequest()
            assertEquals(jwt, legacy.getHeader("apikey"))
            assertEquals("Bearer user-access-token", legacy.getHeader("Authorization"))

            val secret = api.uploadObject(root, "sb_secret_live", "user-access-token", path, "image/jpeg", file) as UploadOutcome.Failure
            assertTrue(secret.config)
            assertEquals(2, server.requestCount)
        } finally {
            file.delete()
            server.shutdown()
        }
    }

    @Test
    fun resumedSessionStaysOpenAcrossPolls() {
        val open = OpenSession("com.tencent.mm", "微信", null, 1_000, "keep")
        val plan = planUsage(
            ticks = emptyList(),
            open = open,
            skipPackage = "dev.owner.ownlog",
            newSessionId = { "new" },
        )
        assertTrue(plan.events.isEmpty())
        assertEquals(open, plan.open)
    }
}
