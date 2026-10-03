package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime

// MARK: - Connection

/** The token's own record (`client` in what `GET /` answers): this device's token. */
data class Device(
    val id: String,
    val label: String,
    val permission: String,
    /** Empty for an admin token, which is held to no project list. */
    val repos: List<String>,
    /** Milliseconds since 1970; 0 when the server sent none. */
    val expiresAt: Long,
) {
    val rank: Permission? get() = Permission.of(permission)
    val canManage: Boolean get() = (rank?.rank ?: -1) >= Permission.MANAGE.rank

    fun toJson(): JsonObject = args("id" to id, "label" to label, "permission" to permission, "repos" to repos, "expiresAt" to expiresAt)

    companion object {
        fun parse(o: JsonObject?): Device? {
            o ?: return null
            return Device(
                id = o.str("id") ?: return null,
                label = o.str("label").orEmpty(),
                permission = o.str("permission") ?: return null,
                repos = o.strings("repos"),
                expiresAt = o.long("expiresAt") ?: 0,
            )
        }
    }
}

/** What `GET /` answers. [transcribe] is null when the server left it out (a server from before voice notes). */
data class Discovery(val version: Int, val device: Device, val transcribe: Boolean?) {
    companion object {
        fun parse(o: JsonObject): Discovery? {
            val version = o.int("version") ?: return null
            return Discovery(version, Device.parse(o.obj("client")) ?: return null, o.bool("transcribe"))
        }

        /** What the server's owner has to do before voice notes work, or null when they do. */
        fun voiceNotesOff(transcribe: Boolean?): String? = when (transcribe) {
            true -> null
            false -> "Voice notes are off: the server needs OPENAI_TRANSCRIBE_API_KEY and OPENAI_TRANSCRIBE_MODEL, and a restart once they are set."
            null -> "This server cannot transcribe voice notes yet. Update Briareus on the server to a version whose client API transcribes."
        }
    }
}

/** What pairing learned about this device, saved so the next launch opens without asking again first. */
data class Connection(val address: ServerAddress, val device: Device, val catalog: RouteCatalog, val transcribe: Boolean?) {
    fun can(call: String): Boolean = catalog.allows(call, device.rank)

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "origin" to jsonOf(address.origin),
            "device" to device.toJson(),
            "routes" to catalog.toJson(),
            "transcribe" to jsonOf(transcribe),
        ),
    )

    companion object {
        fun parse(o: JsonObject): Connection? {
            val address = ServerAddress.parse(o.str("origin") ?: return null) ?: return null
            val device = Device.parse(o.obj("device")) ?: return null
            val catalog = RouteCatalog.fromSaved(o.array("routes") ?: return null) ?: return null
            return Connection(address, device, catalog, o.bool("transcribe"))
        }
    }
}

// MARK: - Projects

data class Project(val repo: String, val label: String, val hasLocal: Boolean, val runProfiles: List<String>) {
    /** The label when it has one, else the repository name. */
    val title: String get() = label.ifBlank { repo.substringAfter('/') }

    companion object {
        fun parse(o: JsonObject): Project? {
            val repo = o.str("repo") ?: return null
            return Project(repo, o.str("label").orEmpty(), o.bool("hasLocal") ?: false, o.strings("runProfiles"))
        }

        fun list(response: JsonObject): List<Project> = response.objects("projects").mapNotNull(::parse)
    }
}

// MARK: - Sessions

/**
 * A conversation as the server sent it. The record is pushed whole on every change, so a copy is replaced rather than
 * merged, and the object is kept whole so fields this app does not read survive a save.
 */
data class Session(val raw: JsonObject) {
    val id: String get() = raw.str("id").orEmpty()
    val repo: String get() = raw.str("repo").orEmpty()
    val status: String get() = raw.str("status").orEmpty()
    val provider: String get() = raw.str("provider").orEmpty()
    val model: String get() = raw.str("model").orEmpty()
    val effort: String get() = raw.str("effort").orEmpty()
    val branch: String? get() = raw.nonEmpty("branch")
    val error: String? get() = raw.nonEmpty("error")
    val lastText: String? get() = raw.nonEmpty("lastText")
    val lastTool: String? get() = raw.nonEmpty("lastTool")
    val costUsd: Double? get() = raw.double("costUsd")
    val awaitingAnswer: Boolean get() = raw.bool("awaitingAnswer") == true
    val liveInput: Boolean get() = raw.bool("liveInput") == true
    val queued: List<String> get() = raw.objects("queued").map { it.str("text").orEmpty() }
    val parentId: String? get() = raw.nonEmpty("parentId")
    val createdAt: Instant? get() = parseTime(raw.str("createdAt"))
    val endedAt: Instant? get() = parseTime(raw.str("endedAt"))
    val reviewTriage: JsonObject? get() = raw.obj("reviewTriage")
    val reviewLoopOn: Boolean get() = raw.obj("reviewLoop") != null
    val pullNumber: Int? get() = raw.obj("prStatus")?.int("number")
    val pullUrl: String? get() = raw.obj("prStatus")?.nonEmpty("url")
    val serveUrl: String? get() = raw.array("serveLinks")?.filterIsInstance<JsonObject>()?.firstNotNullOfOrNull { it.nonEmpty("url") }
    val browserOn: Boolean get() = raw.obj("browser") != null

    val title: String
        get() = raw.nonEmpty("title") ?: "Untitled conversation"

    /** Working: a turn is queued, preparing or running. */
    val isActive: Boolean get() = status in ACTIVE

    /** Closed, failed and interrupted sessions can be reopened. */
    val isClosed: Boolean get() = status == "closed" || status == "failed" || status == "interrupted"

    /** What the list shows: `waiting` when an idle session's agent asked something and waits for the answer. */
    val state: State
        get() = when {
            status == "idle" && awaitingAnswer -> State.WAITING
            status == "idle" && reviewTriage != null -> State.FINDINGS
            isActive -> State.WORKING
            status == "idle" -> State.IDLE
            status == "failed" -> State.FAILED
            else -> State.CLOSED
        }

    enum class State(val label: String) {
        WAITING("Needs your answer"), FINDINGS("Findings to decide"), WORKING("Working"), IDLE("Ready"), FAILED("Failed"), CLOSED("Closed");

        /** Whether the session waits for its user: an answer, a decision, or a message after a finished turn. */
        val needsYou: Boolean get() = this == WAITING || this == FINDINGS
    }

    companion object {
        private val ACTIVE = setOf("queued", "preparing", "running")

        fun parse(e: JsonElement?): Session? = (e as? JsonObject)?.takeIf { it.nonEmpty("id") != null }?.let(::Session)
        fun list(response: JsonObject): List<Session> = response.objects("sessions").mapNotNull(::parse)
    }
}

internal fun parseTime(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

// MARK: - Transcript

/** One line of a session's transcript. `kind` says how to read the rest; unknown kinds are kept and not shown. */
data class TranscriptEvent(val raw: JsonObject) {
    val seq: Long get() = raw.long("seq") ?: 0
    val kind: String get() = raw.str("kind").orEmpty()
    val text: String? get() = raw.str("text")
    val name: String? get() = raw.str("name")
    val summary: String? get() = raw.str("summary")
    val question: String? get() = raw.str("question")
    val options: List<String> get() = raw.objects("options").mapNotNull { it.nonEmpty("label") }
    val attachments: List<String> get() = raw.objects("attachments").mapNotNull { it.nonEmpty("name") }
    val isError: Boolean get() = raw.bool("isError") == true
    val costUsd: Double? get() = raw.double("costUsd")
    val durationMs: Long? get() = raw.long("durationMs")
    val time: Instant? get() = parseTime(raw.str("t"))

    /** Tool events carry their detail in `summary`; other kinds use `text`. */
    val detail: String? get() = if (kind == "tool" || kind == "tool_error") summary ?: text else text

    /** Status and workspace setup output are dashboard plumbing, not part of the conversation. */
    val visible: Boolean
        get() = when (kind) {
            "user", "text", "ask", "tool", "tool_error", "result" -> true
            "info" -> raw.bool("hidden") == true || text != null
            else -> false
        }

    companion object {
        fun parse(e: JsonElement?): TranscriptEvent? = (e as? JsonObject)?.takeIf { it.long("seq") != null }?.let(::TranscriptEvent)
    }
}

/** Cursor and lines have one lifetime: an empty transcript starts at zero. */
class Transcript {
    private val bySeq = sortedMapOf<Long, TranscriptEvent>()

    val events: List<TranscriptEvent> get() = bySeq.values.toList()

    /** The highest `seq` held: the `since` of the next read and the `Last-Event-ID` of a resumed stream. */
    val cursor: Long get() = if (bySeq.isEmpty()) 0 else bySeq.lastKey()

    val size: Int get() = bySeq.size

    /** Adds every line not already held. Returns whether anything was new. */
    fun append(lines: Iterable<TranscriptEvent>): Boolean {
        var added = false
        for (line in lines) if (bySeq.putIfAbsent(line.seq, line) == null) added = true
        return added
    }

    fun append(array: JsonArray?): Boolean = append(array.orEmpty().mapNotNull(TranscriptEvent::parse))

    fun clear() = bySeq.clear()

    /** The question the agent waits on: the latest `ask` with nothing typed after it. */
    fun pendingQuestion(): TranscriptEvent? {
        val list = events
        val ask = list.indexOfLast { it.kind == "ask" }
        if (ask < 0) return null
        return list[ask].takeIf { list.subList(ask + 1, list.size).none { it.kind == "user" } }
    }
}

// MARK: - Runtimes

data class RuntimeModel(val id: String, val label: String, val efforts: List<String>, val defaultEffort: String?) {
    val title: String get() = label.ifBlank { id }
}

data class RuntimeProvider(val id: Int, val label: String, val available: Boolean, val models: List<RuntimeModel>, val defaultModel: String?)

/** What a start is asked to run on. The server may still fall back to a default model or effort. */
data class RuntimeChoice(val providerId: Int, val model: String?, val effort: String?) {
    fun arguments(): Map<String, Any?> = mapOf("provider" to providerId, "model" to model, "effort" to effort)
}

data class RuntimeCatalog(val default: RuntimeChoice?, val providers: List<RuntimeProvider>) {
    fun provider(id: Int): RuntimeProvider? = providers.firstOrNull { it.id == id }
    fun model(choice: RuntimeChoice): RuntimeModel? = provider(choice.providerId)?.models?.firstOrNull { it.id == choice.model }

    /** A provider's model with that model's own default effort, since efforts differ between models. */
    fun choice(providerId: Int, model: String? = null): RuntimeChoice? {
        val provider = provider(providerId) ?: return null
        val m = provider.models.firstOrNull { it.id == model } ?: provider.models.firstOrNull { it.id == provider.defaultModel } ?: provider.models.firstOrNull()
        return RuntimeChoice(providerId, m?.id, m?.defaultEffort ?: m?.efforts?.firstOrNull())
    }

    /** "Provider · Model". */
    fun label(choice: RuntimeChoice): String {
        val provider = provider(choice.providerId)?.label ?: "Provider ${choice.providerId}"
        val model = model(choice)?.title ?: choice.model
        return listOfNotNull(provider, model, choice.effort).joinToString(" · ")
    }

    companion object {
        fun parse(o: JsonObject): RuntimeCatalog {
            val default = o.obj("default")?.let { d -> d.int("providerId")?.let { RuntimeChoice(it, d.nonEmpty("model"), d.nonEmpty("effort")) } }
            val providers = o.objects("providers").mapNotNull { p ->
                RuntimeProvider(
                    id = p.int("id") ?: return@mapNotNull null,
                    label = p.str("label").orEmpty(),
                    // Only an explicit false greys a provider out; older servers omit the field.
                    available = p.bool("available") != false,
                    models = p.objects("models").mapNotNull { m ->
                        RuntimeModel(m.str("id") ?: return@mapNotNull null, m.str("label").orEmpty(), m.strings("efforts"), m.nonEmpty("defaultEffort"))
                    },
                    defaultModel = p.nonEmpty("defaultModel"),
                )
            }
            return RuntimeCatalog(default, providers)
        }
    }
}
