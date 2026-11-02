package dev.owner.ownlog

import dev.owner.ownlog.data.SupabaseApi
import dev.owner.ownlog.data.UploadOutcome
import dev.owner.ownlog.domain.FailureAction
import dev.owner.ownlog.domain.OutgoingEvent
import dev.owner.ownlog.domain.SizedItem
import dev.owner.ownlog.domain.encodeBatch
import dev.owner.ownlog.domain.estimateOutgoingBytes
import dev.owner.ownlog.domain.extrasFromMap
import dev.owner.ownlog.domain.failureAction
import dev.owner.ownlog.domain.isSupabaseSecretKey
import dev.owner.ownlog.domain.normalizeSupabaseEndpoint
import dev.owner.ownlog.domain.supabaseAuthorizationHeader
import dev.owner.ownlog.domain.planBatches
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTest {
    @Test
    fun batchesByCountBytesAndKeepsOversizedRow() {
        val items = (1..5).map { SizedItem(it.toString(), 100) }
        val byCount = planBatches(items, maxCount = 2, maxBytes = 10_000)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4"), listOf("5")), byCount.map { batch -> batch.map { it.id } })

        val byBytes = planBatches(
            listOf(SizedItem("a", 40), SizedItem("b", 40), SizedItem("c", 10)),
            maxCount = 10,
            maxBytes = 50,
        )
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), byBytes.map { batch -> batch.map { it.id } })

        val huge = planBatches(listOf(SizedItem("big", 9_000), SizedItem("next", 10)), maxCount = 10, maxBytes = 100)
        assertEquals(listOf(listOf("big"), listOf("next")), huge.map { batch -> batch.map { it.id } })
        assertTrue(planBatches(emptyList(), 10, 100).isEmpty())
    }

    @Test
    fun failureActionSplitsBadRowsAndRetriesOutages() {
        assertEquals(FailureAction.RETRY, failureAction(8, retryable = true))
        assertEquals(FailureAction.SPLIT, failureAction(8, retryable = false))
        assertEquals(FailureAction.ABANDON, failureAction(1, retryable = false))
    }

    @Test
    fun payloadEscapesTextAndEmbedsExtras() {
        val extras = extrasFromMap(
            mapOf(
                "duration_ms" to "5000",
                "session_id" to "s1",
                "interrupted" to "true",
            ),
        )
        assertEquals("""{"duration_ms":5000,"session_id":"s1","interrupted":true}""", extras)
        val json = encodeBatch(
            listOf(
                OutgoingEvent(
                    id = "11111111-1111-1111-1111-111111111111",
                    occurredAtMillis = 0,
                    type = "ui_text",
                    packageName = "com.tencent.mm",
                    appLabel = "微信",
                    title = "他说\"你好\"",
                    content = "第一行\n第二行",
                    extrasJson = extras,
                    deviceId = "dev-1",
                    createdAtMillis = 0,
                ),
            ),
        )
        assertTrue(json.contains("微信"))
        assertTrue(json.contains("他说\\\"你好\\\""))
        assertTrue(json.contains("第一行\\n第二行"))
        assertTrue(json.contains("\"extras\":{\"duration_ms\":5000,\"session_id\":\"s1\",\"interrupted\":true}"))
        assertTrue(json.contains("1970-01-01T00:00:00Z"))
        assertFalse(json.contains("hunter2"))
        val bytes = estimateOutgoingBytes(
            OutgoingEvent("1", 0, "tap", null, null, null, null, null, "dev", 0),
        )
        assertTrue(bytes > 0)
    }

    @Test
    fun endpointNormalization() {
        assertEquals(
            "https://abc.supabase.co/rest/v1/events",
            normalizeSupabaseEndpoint("https://abc.supabase.co"),
        )
        assertEquals(
            "https://abc.supabase.co/rest/v1/events",
            normalizeSupabaseEndpoint("https://abc.supabase.co/"),
        )
        assertEquals(
            "https://abc.supabase.co/rest/v1/events",
            normalizeSupabaseEndpoint("https://abc.supabase.co/rest/v1"),
        )
        assertEquals(
            "http://127.0.0.1:54321/rest/v1/events",
            normalizeSupabaseEndpoint("http://127.0.0.1:54321/rest/v1/events"),
        )
    }

    @Test
    fun uploadUsesAnonKeyAndClassifiesStatus() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid api key"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("relation missing"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad row"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        server.start()
        try {
            val api = SupabaseApi()
            val root = server.url("/").toString().trimEnd('/')
            val ok = api.upload(root, "anon-key", "user-access-token", """[{"id":"1"}]""")
            assertTrue(ok is UploadOutcome.Success)
            val recorded = server.takeRequest()
            assertEquals("/rest/v1/events", recorded.path)
            assertEquals("anon-key", recorded.getHeader("apikey"))
            assertEquals("Bearer user-access-token", recorded.getHeader("Authorization"))
            assertEquals("""[{"id":"1"}]""", recorded.body.readUtf8())
            assertTrue(recorded.getHeader("Prefer")!!.contains("resolution=ignore-duplicates"))

            val denied = api.upload(root, "anon-key", "user-access-token", "[]") as UploadOutcome.Failure
            assertFalse(denied.retryable)
            assertTrue(denied.config)

            val missing = api.upload(root, "anon-key", "user-access-token", "[]") as UploadOutcome.Failure
            assertTrue(missing.config)
            assertEquals(404, missing.code)

            val bad = api.upload(root, "anon-key", "user-access-token", "[]") as UploadOutcome.Failure
            assertFalse(bad.retryable)
            assertFalse(bad.config)

            val down = api.upload(root, "anon-key", "user-access-token", "[]") as UploadOutcome.Failure
            assertTrue(down.retryable)
            assertEquals(503, down.code)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun publishableKeyStaysInApikeyAndUserTokenIsBearer() {
        assertNull(supabaseAuthorizationHeader("sb_publishable_example"))
        assertNull(supabaseAuthorizationHeader("  sb_publishable_example  "))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.start()
        try {
            val api = SupabaseApi()
            val root = server.url("/").toString().trimEnd('/')
            val key = "sb_publishable_example"
            val access = "user-access-token"
            assertTrue(api.upload(root, "  $key  ", access, "[]") is UploadOutcome.Success)
            val posted = server.takeRequest()
            assertEquals("/rest/v1/events", posted.path)
            assertEquals(key, posted.getHeader("apikey"))
            assertEquals("Bearer $access", posted.getHeader("Authorization"))
            assertFalse(posted.getHeader("Authorization")!!.contains(key))

            assertTrue(api.ping(root, key, access) is UploadOutcome.Success)
            val ping = server.takeRequest()
            assertEquals("/rest/v1/events?select=id&limit=1", ping.path)
            assertEquals("GET", ping.method)
            assertEquals(key, ping.getHeader("apikey"))
            assertEquals("Bearer $access", ping.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun legacyJwtKeyIsNotSentAsTheUserToken() {
        val key = "eyJhbGciOiJIUzI1NiJ9.payload.sig"
        assertEquals("Bearer $key", supabaseAuthorizationHeader(" $key "))
        assertEquals("Bearer anon-key", supabaseAuthorizationHeader("anon-key"))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.start()
        try {
            val api = SupabaseApi()
            val root = server.url("/").toString().trimEnd('/')
            val access = "user-access-token"
            assertTrue(api.upload(root, key, access, "[]") is UploadOutcome.Success)
            val posted = server.takeRequest()
            assertEquals(key, posted.getHeader("apikey"))
            assertEquals("Bearer $access", posted.getHeader("Authorization"))

            assertTrue(api.ping(root, key, access) is UploadOutcome.Success)
            val ping = server.takeRequest()
            assertEquals(key, ping.getHeader("apikey"))
            assertEquals("Bearer $access", ping.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun secretKeyIsRefusedBeforeAnyRequest() {
        assertTrue(isSupabaseSecretKey(" sb_secret_live_example "))
        assertFalse(isSupabaseSecretKey("sb_publishable_example"))
        assertFalse(isSupabaseSecretKey("eyJhbGciOiJIUzI1NiJ9.x.y"))
        val server = MockWebServer()
        server.start()
        try {
            val api = SupabaseApi()
            val root = server.url("/").toString().trimEnd('/')
            val upload = api.upload(root, "sb_secret_live_example", "user-access-token", "[]") as UploadOutcome.Failure
            val ping = api.ping(root, " sb_secret_live_example ", "user-access-token") as UploadOutcome.Failure
            assertTrue(upload.config)
            assertFalse(upload.retryable)
            assertTrue(ping.config)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
