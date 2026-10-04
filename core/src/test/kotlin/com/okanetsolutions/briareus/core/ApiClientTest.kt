package com.okanetsolutions.briareus.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Answers every request from a queue and records what was asked: the client's pluggable transport in tests. */
class FakeServer : Interceptor {
    data class Reply(val status: Int, val body: String, val type: String = "application/json", val headers: Map<String, String> = emptyMap())

    val requests = ArrayList<Request>()
    val bodies = ArrayList<String>()
    val replies = ArrayDeque<Reply>()

    fun reply(status: Int = 200, body: String = "{}", type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        replies += Reply(status, body, type, headers)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        bodies += request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
        val r = replies.removeFirst()
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(r.status).message("x")
            .header("Content-Type", r.type).apply { r.headers.forEach { (k, v) -> header(k, v) } }
            .body(r.body.toResponseBody(r.type.toMediaType())).build()
    }
}

class ApiClientTest {
    private val token = "brm_" + "t".repeat(43)
    private val server = FakeServer()
    private val client = ApiClient(
        ServerAddress.parse("https://b.example")!!, token,
        ApiClient.defaultHttpClient().newBuilder().addInterceptor(server).build(),
    )

    private suspend fun expectError(block: suspend () -> Unit): ApiError {
        try { block() } catch (e: ApiError) { return e }
        fail("expected an ApiError"); throw AssertionError()
    }

    @Test fun refusesABadToken() {
        try { ApiClient(ServerAddress.parse("https://b.example")!!, "nope"); fail() } catch (e: ApiError) { assertEquals(ApiError.Kind.INVALID_TOKEN, e.kind) }
    }

    @Test fun discoverySendsTheTokenAndChecksTheVersion() = runTest {
        server.reply(body = """{"version":1,"client":{"id":"c","permission":"read"},"transcribe":false}""")
        val d = client.discovery()
        assertEquals("c", d.device.id)
        val r = server.requests.single()
        assertEquals("https://b.example/api/v1/", r.url.toString())
        assertEquals("Bearer $token", r.header("Authorization"))
        assertEquals("application/json", r.header("Accept"))
        assertNull(r.header("Cookie"))
        server.reply(body = """{"version":2,"client":{"id":"c","permission":"read"}}""")
        assertEquals(ApiError.Kind.INCOMPATIBLE_VERSION, expectError { client.discovery() }.kind)
    }

    @Test fun getsFillThePathAndQuery() = runTest {
        server.reply(body = """{"session":{"id":"a b"},"events":[]}""")
        client.call("session", args("sessionId" to "a b/c", "since" to 12, "all" to true))
        assertEquals("https://b.example/api/v1/sessions/a%20b%2Fc?since=12&all=1", server.requests.last().url.toString())
        assertEquals("GET", server.requests.last().method)
    }

    @Test fun writesSendJsonBodies() = runTest {
        server.reply(body = """{"session":{"id":"s"}}""")
        client.call("message", args("sessionId" to "s", "text" to "hi", "attachments" to listOf("u1")))
        val r = server.requests.last()
        assertEquals("POST", r.method)
        assertEquals("https://b.example/api/v1/sessions/s/messages", r.url.toString())
        assertEquals("""{"text":"hi","attachments":["u1"]}""", server.bodies.last())
        assertTrue(r.body!!.contentType().toString().startsWith("application/json"))
    }

    @Test fun aMissingPathArgumentIsRefusedBeforeTheNetwork() = runTest {
        val e = expectError { client.call("message", args("text" to "hi")) }
        assertEquals(400, e.status)
        assertEquals("Missing argument: sessionId", e.message)
        assertEquals(ApiError.Kind.HTTP, expectError { client.call("nonexistent") }.kind)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun previewAccessIsAPlainGet() = runTest {
        server.reply(body = """{"clientId":"id","clientSecret":"s","hostSuffix":"preview.example.com"}""")
        val access = PreviewAccess.parse(client.call("preview_access"))
        assertEquals("https://b.example/api/v1/preview/access", server.requests.last().url.toString())
        assertEquals("GET", server.requests.last().method)
        assertEquals("preview.example.com", access?.hostSuffix)
    }

    @Test fun theSessionListIsCutToARepo() = runTest {
        server.reply(body = """{"sessions":[{"id":"1","repo":"o/a"},{"id":"2","repo":"o/b"}]}""")
        val result = client.call("sessions", args("repo" to "o/b"))
        assertEquals("https://b.example/api/v1/sessions", server.requests.last().url.toString())
        assertEquals(listOf("2"), Session.list(result).map { it.id })
    }

    @Test fun errors() = runTest {
        server.reply(401, """{"error":"revoked"}""")
        assertTrue(expectError { client.call("projects") }.unauthorized)
        server.reply(429, """{"error":"slow down"}""", headers = mapOf("Retry-After" to "7"))
        val limited = expectError { client.call("projects") }
        assertEquals(7.0, limited.retryAfter)
        assertTrue(limited.isRefusal)
        server.reply(302, "", type = "text/html", headers = mapOf("Location" to "https://login.example"))
        assertEquals(ApiError.Kind.REDIRECTED, expectError { client.call("projects") }.kind)
        server.reply(200, "<html>", type = "text/html")
        assertEquals(ApiError.Kind.NON_JSON, expectError { client.call("projects") }.kind)
        server.reply(500, "oops", type = "text/plain")
        val e = expectError { client.call("projects") }
        assertEquals("internal server error (HTTP 500)", e.description)
        assertTrue(!e.isRefusal)
    }

    @Test fun oversizedMessagesNeverLeave() = runTest {
        val e = expectError { client.call("message", args("sessionId" to "s", "text" to "x".repeat(1024 * 1024))) }
        assertEquals(ApiError.Kind.OVERSIZED_REQUEST, e.kind)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun uploadAndTranscribeSendRawBytes() = runTest {
        server.reply(201, """{"file":{"id":"up1","name":"a.png","size":3}}""")
        assertEquals("up1", client.upload("a b.png", byteArrayOf(1, 2, 3), "image/png"))
        assertEquals("https://b.example/api/v1/uploads?name=a%20b.png", server.requests.last().url.toString())
        assertEquals("image/png", server.requests.last().body!!.contentType().toString())
        server.reply(body = """{"text":"hello there"}""")
        assertEquals("hello there", client.transcribe(byteArrayOf(9), "audio/mp4"))
        assertEquals("audio/mp4", server.requests.last().body!!.contentType().toString())
    }

    @Test fun streamsEvents() = runTest {
        server.reply(body = "event: session\ndata: {\"id\":\"a\"}\n\n: ping\n\nid: 4\ndata: x\n\n", type = "text/event-stream")
        val events = client.stream("events", args("transcripts" to 0), lastEventId = "3").toList()
        assertEquals(listOf(SseEvent("session", "{\"id\":\"a\"}", null), SseEvent(null, "x", "4")), events)
        assertEquals("3", server.requests.last().header("Last-Event-ID"))
        assertEquals("text/event-stream", server.requests.last().header("Accept"))
        server.reply(body = "{}")
        assertEquals(ApiError.Kind.NON_JSON, expectError { client.stream("events").toList() }.kind)
    }

    @Test fun retryAfterDates() {
        assertEquals(30.0, ApiClient.retryAfter("Wed, 21 Oct 2015 07:28:30 GMT", java.time.Instant.parse("2015-10-21T07:28:00Z").toEpochMilli()))
        assertNull(ApiClient.retryAfter("soon", 0))
        assertNull(ApiClient.retryAfter(null, 0))
    }
}
