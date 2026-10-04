package com.okanetsolutions.briareus.quest

import android.content.Context
import androidx.core.content.edit
import com.okanetsolutions.briareus.core.ApiClient
import com.okanetsolutions.briareus.core.ApiError
import com.okanetsolutions.briareus.core.BriareusJson
import com.okanetsolutions.briareus.core.Connection
import com.okanetsolutions.briareus.core.Discovery
import com.okanetsolutions.briareus.core.PreviewAccess
import com.okanetsolutions.briareus.core.Project
import com.okanetsolutions.briareus.core.RuntimeCatalog
import com.okanetsolutions.briareus.core.ServerAddress
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.Token
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.core.str
import com.okanetsolutions.briareus.core.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.min

/**
 * The connection and everything read through it, shared by every window and the background service. One `GET /events`
 * stream feeds the projects' conversations while anything watches ([watch]); each open conversation streams its own
 * transcript ([conversation]).
 */
class Store(context: Context) {
    val vault = Vault(context)
    val cache = ResponseCache(context, vault)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connection = MutableStateFlow<Connection?>(null)
    val connection: StateFlow<Connection?> = _connection.asStateFlow()
    var client: ApiClient? = null
        private set

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()
    private val _sessions = MutableStateFlow<Map<String, Session>>(emptyMap())
    val sessions: StateFlow<Map<String, Session>> = _sessions.asStateFlow()

    enum class Link { OFFLINE, CONNECTING, LIVE }
    private val _link = MutableStateFlow(Link.OFFLINE)
    val link: StateFlow<Link> = _link.asStateFlow()

    /** Every session record as it arrives from the stream, for the notification rules. */
    private val _updates = MutableSharedFlow<Session>(extraBufferCapacity = 64)
    val updates: SharedFlow<Session> = _updates.asSharedFlow()

    /** Something the user should read: a failed action, a lost connection. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Why the app went back to pairing, shown there once. */
    var signedOutReason: String? = null

    /** The conversations on screen right now; their alerts are not notified. */
    val visibleSessions = MutableStateFlow<Set<String>>(emptySet())

    var backgroundAlerts: Boolean
        get() = prefs.getBoolean("background_alerts", true)
        set(value) = prefs.edit { putBoolean("background_alerts", value) }

    init {
        cache.prune()
        restore()
    }

    // MARK: - Connection

    private fun restore() {
        val saved = vault.getString("connection")?.let { runCatching { BriareusJson.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return
        val connection = Connection.parse(saved) ?: return
        val token = vault.getString("token:${connection.address.origin}") ?: return
        client = runCatching { ApiClient(connection.address, token) }.getOrNull() ?: return
        _connection.value = connection
        (cache.read("projects") as? JsonObject)?.let { _projects.value = Project.list(it) }
        (cache.read("sessions") as? JsonObject)?.let { saved -> _sessions.value = Session.list(saved).associateBy { it.id } }
        // The route catalog is read again on every launch, so a server update shows up without pairing again.
        scope.launch { runCatching { refreshCatalog() } }
    }

    /** Pairs with a server: checks the token with `GET /`, reads the route catalog, and saves both sealed. Null on success, else what to tell the user. */
    suspend fun pair(addressText: String, tokenText: String): String? {
        val address = ServerAddress.parse(addressText) ?: return ApiError(ApiError.Kind.INVALID_ADDRESS).description
        val token = tokenText.trim()
        if (!Token.valid(token)) return ApiError(ApiError.Kind.INVALID_TOKEN).description
        return try {
            val candidate = ApiClient(address, token)
            val discovery = candidate.discovery()
            val catalog = candidate.catalog()
            val previous = _connection.value
            if (previous != null && (previous.address != address || previous.device.id != discovery.device.id)) cache.clear()
            vault.putString("token:${address.origin}", token)
            val connection = Connection(address, discovery.device, catalog, discovery.transcribe)
            vault.putString("connection", connection.toJson().toString())
            client = candidate
            _connection.value = connection
            signedOutReason = null
            startEvents()
            null
        } catch (e: ApiError) {
            e.description
        }
    }

    private suspend fun refreshCatalog() {
        val c = client ?: return
        val discovery = c.discovery()
        val catalog = c.catalog()
        val connection = Connection(c.address, discovery.device, catalog, discovery.transcribe)
        vault.putString("connection", connection.toJson().toString())
        _connection.value = connection
    }

    /**
     * Forgets the connection on this headset: the token, the saved responses and the key that sealed them. With [revoke]
     * the token is also revoked on the server first (`DELETE /token`); running agents are never stopped.
     */
    fun forget(revoke: Boolean = false, reason: String? = null) {
        val c = client
        scope.launch {
            if (revoke && c != null) runCatching { c.call("revoke_token") }
            stopEvents()
            client = null
            _connection.value = null
            _projects.value = emptyList()
            _sessions.value = emptyMap()
            conversations.values.forEach { it.close() }
            conversations.clear()
            previewAccess = null
            cache.clear()
            vault.destroy()
            signedOutReason = reason
        }
    }

    /** A 401 means the token expired or was revoked: back to pairing. */
    fun failed(e: Throwable, silent: Boolean = false) {
        if (e is CancellationException) return
        val error = e as? ApiError ?: ApiError(ApiError.Kind.NETWORK, message = e.message)
        if (error.unauthorized) { forget(reason = error.description); return }
        if (!silent) _messages.tryEmit(error.description)
    }

    fun can(call: String): Boolean = _connection.value?.can(call) == true

    /** Tells the user something, as a toast in the window they are in. */
    fun say(text: String) {
        _messages.tryEmit(text)
    }

    /** Why voice notes cannot be recorded here, or null when they can. */
    fun voiceNotesOff(): String? =
        if (!can("transcribe")) "This server cannot transcribe voice notes, or this token may not ask it to."
        else Discovery.voiceNotesOff(_connection.value?.transcribe)

    // MARK: - Projects and conversations

    suspend fun refresh() {
        val c = client ?: return
        val projects = c.call("projects")
        val sessions = c.call("sessions")
        cache.write("projects", projects)
        cache.write("sessions", sessions)
        _projects.value = Project.list(projects)
        _sessions.value = Session.list(sessions).associateBy { it.id }
    }

    fun upsert(session: Session) {
        _sessions.update { it + (session.id to session) }
        _updates.tryEmit(session)
        saveSessions()
    }

    private fun remove(id: String) {
        _sessions.update { it - id }
        saveSessions()
    }

    private var saveJob: Job? = null
    private fun saveSessions() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2_000)
            cache.write("sessions", JsonObject(mapOf("sessions" to JsonArray(_sessions.value.values.map { it.raw }))))
        }
    }

    fun projectTitle(repo: String): String = _projects.value.firstOrNull { it.repo == repo }?.title ?: repo.substringAfter('/')

    // MARK: - The events stream

    private var watchers = 0
    private var eventsJob: Job? = null

    /** Keeps `GET /events` open until the returned release runs. Windows hold one while shown; the service while it runs. */
    fun watch(): () -> Unit {
        watchers++
        startEvents()
        var released = false
        return {
            if (!released) {
                released = true
                watchers--
                if (watchers == 0) scope.launch { delay(5_000); if (watchers == 0) stopEvents() }
            }
        }
    }

    private fun startEvents() {
        if (eventsJob != null || watchers == 0 || client == null) return
        eventsJob = scope.launch {
            runEvents()
            eventsJob = null
        }
    }

    private fun stopEvents() {
        eventsJob?.cancel()
        eventsJob = null
        _link.value = Link.OFFLINE
    }

    private suspend fun runEvents() {
        var backoff = 1_000L
        while (scope.isActive) {
            val c = client ?: break
            _link.value = Link.CONNECTING
            try {
                // Nothing is replayed on reconnect, so the lists are read first and the stream keeps them current.
                refresh()
                c.stream("events").collect { event ->
                    _link.value = Link.LIVE
                    backoff = 1_000L
                    when (event.event) {
                        "session" -> Session.parse(BriareusJson.parseToJsonElement(event.data))?.let(::upsert)
                        "session.deleted" -> (BriareusJson.parseToJsonElement(event.data) as? JsonObject)?.str("id")?.let(::remove)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.unauthorized) { failed(e); return }
                e.retryAfter?.let { backoff = maxOf(backoff, (it * 1000).toLong()) }
            } catch (_: Exception) {
            }
            _link.value = Link.CONNECTING
            delay(backoff)
            backoff = min(backoff * 2, 60_000L)
        }
        _link.value = Link.OFFLINE
    }

    // MARK: - Conversations

    private val conversations = HashMap<String, Conversation>()

    fun conversation(id: String): Conversation = conversations.getOrPut(id) { Conversation(this, id) }

    // MARK: - Actions

    /** Runs a write, reporting a failure to the user; returns the answer or null. */
    suspend fun mutate(name: String, arguments: JsonObject): JsonObject? = try {
        val c = client ?: return null
        val result = c.call(name, arguments)
        Session.parse(result["session"])?.let(::upsert)
        result
    } catch (e: Exception) {
        failed(e)
        null
    }

    suspend fun send(sessionId: String, text: String, attachments: List<String> = emptyList()): Boolean =
        mutate("message", args("sessionId" to sessionId, "text" to text, "attachments" to attachments.ifEmpty { null })) != null

    /** The service token ▶ Run's previews take past Cloudflare Access, read once; null when the server has none for this token. */
    private var previewAccess: PreviewAccess? = null

    suspend fun previewAccess(): PreviewAccess? {
        previewAccess?.let { return it }
        if (!can("preview_access")) return null
        return runCatching { PreviewAccess.parse(client!!.call("preview_access")) }
            .onFailure { failed(it, silent = true) }.getOrNull()?.also { previewAccess = it }
    }

    suspend fun runtimes(repo: String): RuntimeCatalog? = runCatching { RuntimeCatalog.parse(client!!.call("runtimes", args("repo" to repo))) }
        .onFailure { failed(it) }.getOrNull()

    suspend fun branches(repo: String): Pair<String?, List<String>> = runCatching {
        val r = client!!.call("branches", args("repo" to repo))
        r.str("defaultBranch") to r.strings("branches")
    }.onFailure { failed(it, silent = true) }.getOrDefault(null to emptyList())
}
