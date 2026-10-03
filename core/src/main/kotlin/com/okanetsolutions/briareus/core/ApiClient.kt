package com.okanetsolutions.briareus.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The client API (`/api/v1`) over HTTPS: one token, no cookies, no cache, no redirects, no automatic retries. Read backoff
 * belongs to the screens; a write is never sent twice by this layer.
 */
class ApiClient(
    val address: ServerAddress,
    private val token: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    init {
        if (!Token.valid(token)) throw ApiError(ApiError.Kind.INVALID_TOKEN)
    }

    /** `GET /`: the token's own record and what the server can do. */
    suspend fun discovery(): Discovery {
        val json = request("GET", url(""), null)
        val discovery = Discovery.parse(json) ?: throw ApiError(ApiError.Kind.NON_JSON)
        if (discovery.version != 1) throw ApiError(ApiError.Kind.INCOMPATIBLE_VERSION)
        return discovery
    }

    /** `GET /openapi.json`, read into the routes the server has and who may call each. */
    suspend fun catalog(): RouteCatalog = RouteCatalog.fromOpenApi(request("GET", url("openapi.json"), null))
        ?: throw ApiError(ApiError.Kind.NON_JSON)

    /**
     * One call by name, on its route. A name not in the table, or a missing path argument, is refused with a 400 before
     * any network call. [timeoutMs] is for a call that answers only once its work is done.
     */
    suspend fun call(name: String, arguments: JsonObject = JsonObject(emptyMap()), timeoutMs: Long? = null): JsonObject {
        val route = Routes[name] ?: throw ApiError(ApiError.Kind.HTTP, 400, "Unknown call")
        val rest = arguments.toMutableMap()
        val path = fillPath(route, rest)
        val kept = route.filter?.let { (rest.remove(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val reads = route.method == "GET" || route.method == "DELETE"
        val result = if (reads) {
            request(route.method, url(path, JsonObject(rest)), null, timeoutMs)
        } else {
            route.set?.let { rest[it] = JsonPrimitive(true) }
            val body = BriareusJson.encodeToString(JsonObject.serializer(), JsonObject(rest))
            if (body.toByteArray().size > MAX_REQUEST_BYTES) throw ApiError(ApiError.Kind.OVERSIZED_REQUEST)
            request(route.method, url(path), body.toRequestBody(JSON), timeoutMs)
        }
        val filter = route.filter ?: return result
        val list = route.list ?: return result
        kept ?: return result
        return JsonObject(result + (list to JsonArray(result.objects(list).filter { it.str(filter) == kept })))
    }

    /** Stores a file to attach to a message; the answer is the id a message takes in `attachments`. */
    suspend fun upload(name: String, bytes: ByteArray, contentType: String = "application/octet-stream"): String {
        if (bytes.size > UPLOAD_LIMIT) throw ApiError(ApiError.Kind.HTTP, 413, "Files up to 25 MiB can be attached.")
        val json = request("POST", url("uploads", args("name" to name)), bytes.toRequestBody(rawType(contentType)), UPLOAD_TIMEOUT_MS)
        return json.obj("file")?.nonEmpty("id") ?: throw ApiError(ApiError.Kind.NON_JSON)
    }

    /** The text of a recorded voice note, sent with the content type it was recorded in. */
    suspend fun transcribe(audio: ByteArray, contentType: String): String {
        if (audio.size > UPLOAD_LIMIT) throw ApiError(ApiError.Kind.HTTP, 413, "Voice notes up to 25 MiB can be transcribed.")
        val json = request("POST", url("transcribe"), audio.toRequestBody(rawType(contentType)), UPLOAD_TIMEOUT_MS)
        return json.str("text") ?: throw ApiError(ApiError.Kind.NON_JSON)
    }

    /**
     * A GET call's server-sent event stream, as a cold flow: collecting opens it, cancelling the collector closes it, and
     * it completes when the server ends it. Fails with [ApiError] like a call.
     */
    fun stream(name: String, arguments: JsonObject = JsonObject(emptyMap()), lastEventId: String? = null): Flow<SseEvent> = flow {
        val route = Routes[name]?.takeIf { it.method == "GET" } ?: throw ApiError(ApiError.Kind.HTTP, 400, "Unknown call")
        val rest = arguments.toMutableMap()
        val path = fillPath(route, rest)
        val request = baseRequest(url(path, JsonObject(rest)), "text/event-stream").get()
            .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
            .build()
        val call = streamHttp.newCall(request)
        val response = call.await()
        response.use {
            check(it)
            val type = it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            if (type != "text/event-stream") throw ApiError(ApiError.Kind.NON_JSON, it.code)
            val source = it.body.source()
            val events = ArrayList<SseEvent>()
            val parser = SseParser { event -> events += event }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    parser.line(line)
                    for (event in events) emit(event)
                    events.clear()
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ApiError(ApiError.Kind.NETWORK, message = e.message ?: "The connection to the server was lost.")
            } finally {
                call.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private val streamHttp: OkHttpClient by lazy {
        // The server pings every 25 seconds; a minute of silence is a dead connection.
        http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun fillPath(route: ApiRoute, rest: MutableMap<String, JsonElement>): String {
        val out = StringBuilder()
        var i = 0
        val p = route.path
        while (i < p.length) {
            if (p[i] != '{') { out.append(p[i++]); continue }
            val end = p.indexOf('}', i)
            val arg = p.substring(i + 1, end)
            val value = (rest[arg] as? JsonPrimitive)?.urlValue(inPath = true)
                ?: throw ApiError(ApiError.Kind.HTTP, 400, "Missing argument: $arg")
            out.append(encodeSegment(value))
            rest.remove(arg)
            i = end + 1
        }
        return out.toString()
    }

    internal fun url(path: String, query: JsonObject = JsonObject(emptyMap())): HttpUrl {
        val builder = (address.baseUrl + path).toHttpUrl().newBuilder()
        for ((key, value) in query) {
            // An array is a repeatable parameter: `project=a&project=b`.
            val values = (value as? JsonArray)?.toList() ?: listOf(value)
            for (v in values) (v as? JsonPrimitive)?.urlValue(inPath = false)?.let { builder.addQueryParameter(key, it) }
        }
        return builder.build()
    }

    private fun baseRequest(url: HttpUrl, accept: String = "application/json"): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", accept).header("User-Agent", USER_AGENT)

    private suspend fun request(method: String, url: HttpUrl, body: RequestBody?, timeoutMs: Long? = null): JsonObject {
        val client = if (timeoutMs != null) http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http
        val request = baseRequest(url).method(method, body ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(JSON) else null).build()
        return client.newCall(request).await().use { response ->
            check(response)
            val text = response.body.string()
            if (!isJson(response)) throw ApiError(ApiError.Kind.NON_JSON, response.code)
            runCatching { BriareusJson.parseToJsonElement(text) as JsonObject }.getOrElse { throw ApiError(ApiError.Kind.NON_JSON, response.code) }
        }
    }

    /** Throws for a redirect or an error status; returns for a 2xx. */
    private fun check(response: Response) {
        val status = response.code
        if (status in 300..399) throw ApiError(ApiError.Kind.REDIRECTED, status)
        if (status in 200..299) return
        val payload = if (isJson(response)) runCatching { BriareusJson.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull() else null
        throw ApiError(
            ApiError.Kind.HTTP, status, payload?.nonEmpty("error") ?: statusText(status),
            retryAfter(response.header("Retry-After"), System.currentTimeMillis()),
        )
    }

    companion object {
        const val UPLOAD_LIMIT = 25 * 1024 * 1024
        private const val MAX_REQUEST_BYTES = 1024 * 1024
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val UPLOAD_TIMEOUT_MS = 180_000L
        private const val USER_AGENT = "Briareus-Quest/1.0"
        private val JSON = "application/json".toMediaType()

        /** TLS 1.2 or later, no redirects of any kind, no cookies or cache (OkHttp keeps neither unless given one). */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()))
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** Seconds to wait from a Retry-After header (seconds or an HTTP date), or null when it cannot be read. */
        fun retryAfter(value: String?, nowMs: Long): Double? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v.toDoubleOrNull()?.let { return if (it >= 0) it else null }
            val date = runCatching {
                java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: return null
            return maxOf(0.0, (date - nowMs) / 1000.0)
        }

        private fun isJson(response: Response): Boolean =
            response.header("Content-Type").orEmpty().substringBefore(';').trim().equals("application/json", ignoreCase = true)

        private fun rawType(contentType: String) = runCatching { contentType.toMediaType() }.getOrDefault("application/octet-stream".toMediaType())

        private fun encodeSegment(value: String): String = buildString {
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-_.~") append(c.toChar()) else append("%%%02X".format(c))
            }
        }

        private fun statusText(status: Int) = when (status) {
            400 -> "bad request"; 401 -> "unauthorized"; 403 -> "forbidden"; 404 -> "not found"
            405 -> "method not allowed"; 408 -> "request timeout"; 409 -> "conflict"; 413 -> "request too large"
            422 -> "unprocessable entity"; 429 -> "too many requests"; 500 -> "internal server error"
            502 -> "bad gateway"; 503 -> "service unavailable"; 504 -> "gateway timeout"
            else -> "request failed"
        }
    }
}

/** Runs the call on OkHttp's threads; cancelling the coroutine cancels the call. Transport failures become [ApiError]. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, r, _ -> r.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(
                if (call.isCanceled()) ApiError(ApiError.Kind.CANCELLED)
                else ApiError(ApiError.Kind.NETWORK, message = e.message?.let { "The server could not be reached: $it" } ?: "The server could not be reached."),
            )
        }
    })
}
