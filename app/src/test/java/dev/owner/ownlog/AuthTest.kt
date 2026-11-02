package dev.owner.ownlog

import dev.owner.ownlog.data.SupabaseApi
import dev.owner.ownlog.data.UploadOutcome
import dev.owner.ownlog.domain.AUTH_SIGNED_OUT
import dev.owner.ownlog.domain.OutgoingEvent
import dev.owner.ownlog.domain.encodeBatch
import dev.owner.ownlog.domain.jwtSubject
import dev.owner.ownlog.domain.parseAuthResponse
import dev.owner.ownlog.domain.shouldRefresh
import java.util.Base64
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    @Test
    fun readsTheUserIdFromTheAccessToken() {
        val userId = "11111111-1111-1111-1111-111111111111"
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"$userId","role":"authenticated"}""".toByteArray(),
        )
        val access = "eyJhbGciOiJIUzI1NiJ9.$payload.sig"
        assertEquals(userId, jwtSubject(access))
        val parsed = parseAuthResponse(
            """{"access_token":"$access","refresh_token":"r1","expires_in":3600,"user":{"id":"$userId","email":"a@b.c"}}""",
            nowMillis = 1_000,
        )
        assertEquals(userId, parsed?.userId)
        assertEquals("r1", parsed?.refreshToken)
        assertEquals(1_000L + 3_600_000L, parsed?.expiresAtMillis)
        assertEquals("a@b.c", parsed?.email)
        assertTrue(shouldRefresh(expiresAtMillis = 30_000, nowMillis = 0))
        assertFalse(shouldRefresh(expiresAtMillis = 120_000, nowMillis = 0))
    }

    @Test
    fun batchCarriesTheSignedInUser() {
        val json = encodeBatch(
            listOf(OutgoingEvent("1", 0, "note", null, null, "记一下", "正文", null, "dev", 0)),
            ownerId = "11111111-1111-1111-1111-111111111111",
        )
        assertTrue(json.contains("\"user_id\":\"11111111-1111-1111-1111-111111111111\""))
        val omitted = encodeBatch(
            listOf(OutgoingEvent("1", 0, "note", null, null, null, null, null, "dev", 0)),
        )
        assertFalse(omitted.contains("user_id"))
    }

    @Test
    fun signInKeepsPublishableKeyOutOfAuthorization() {
        val server = MockWebServer()
        val userId = "22222222-2222-2222-2222-222222222222"
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"$userId"}""".toByteArray(),
        )
        val access = "header.$payload.sig"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"$access","refresh_token":"r2","expires_in":10}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"$access","refresh_token":"r2","expires_in":10}"""))
        server.start()
        try {
            val api = SupabaseApi()
            val root = server.url("/").toString().trimEnd('/')
            val signed = api.signInWithPassword(root, "sb_publishable_example", "a@b.c", "pw", 0)
            assertTrue(signed is dev.owner.ownlog.data.AuthCall.Success)
            val request = server.takeRequest()
            assertEquals("/auth/v1/token?grant_type=password", request.path)
            assertEquals("sb_publishable_example", request.getHeader("apikey"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("a@b.c"))

            val legacy = api.signInWithPassword(root, "eyJhbGciOiJIUzI1NiJ9.payload.sig", "a@b.c", "pw", 0)
            assertTrue(legacy is dev.owner.ownlog.data.AuthCall.Success)
            val jwtKey = server.takeRequest()
            assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", jwtKey.getHeader("Authorization"))

            val paused = api.upload(root, "sb_publishable_example", "", "[]") as UploadOutcome.Failure
            assertEquals(AUTH_SIGNED_OUT, paused.message)
            assertTrue(paused.config)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
