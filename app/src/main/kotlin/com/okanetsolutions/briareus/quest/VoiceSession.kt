package com.okanetsolutions.briareus.quest

import android.content.Context
import com.okanetsolutions.briareus.core.ApiClient
import com.okanetsolutions.briareus.core.ApiError
import com.okanetsolutions.briareus.core.BriareusJson
import com.okanetsolutions.briareus.core.Project
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.Voice
import com.okanetsolutions.briareus.core.VoiceContext
import com.okanetsolutions.briareus.core.VoiceCost
import com.okanetsolutions.briareus.core.PullReviewStart
import com.okanetsolutions.briareus.core.VoicePlan
import com.okanetsolutions.briareus.core.VoiceTool
import com.okanetsolutions.briareus.core.args
import com.okanetsolutions.briareus.core.int
import com.okanetsolutions.briareus.core.obj
import com.okanetsolutions.briareus.core.objects
import com.okanetsolutions.briareus.core.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * One spoken conversation with GPT-Realtime: the call, the captions, and the tools it calls, on the server and on the
 * windows. It outlives the panel that started it, so it goes on while you look at another app, and ends when you end it
 * or after a silence. Tools and context stay inside the selected project, and requested writes run directly.
 */
class VoiceSession(context: Context, private val store: Store, private val navigator: Navigator, private val settings: VoiceSettings) {
    private val context = context.applicationContext

    enum class Phase { OFF, CONNECTING, LIVE, CLOSING }
    data class Line(val user: Boolean, val text: String, val id: String = UUID.randomUUID().toString())
    /** A tool call, as the panel lists it. */
    data class Step(val tool: VoiceTool?, val name: String, val input: JsonObject, val state: State, val id: String = UUID.randomUUID().toString()) {
        sealed interface State {
            data object Running : State
            data object Done : State
            data class Failed(val why: String) : State
        }
    }

    val projects = store.projects
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    /** A live conversation cannot carry its transcript or tool context into a different project. */
    fun selectProject(repo: String) {
        if (isOn) return
        val selected = store.projects.value.firstOrNull { it.repo == repo } ?: return
        if (_project.value?.repo != repo) { _lines.value = emptyList(); _steps.value = emptyList(); _notice.value = null; _cost.value = null }
        _project.value = selected
    }

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    /** The voice is saying something, as its audio arrives. */
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()
    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()
    /** When the call went live, in milliseconds since 1970; null before. */
    private val _started = MutableStateFlow<Long?>(null)
    val started: StateFlow<Long?> = _started.asStateFlow()
    /** What the conversation has cost so far, as the panel shows it; kept after it ends until the next one starts. */
    private val _cost = MutableStateFlow<String?>(null)
    val cost: StateFlow<String?> = _cost.asStateFlow()
    /** Why the last conversation failed or ended by itself. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var call: RealtimeCall? = null
    private var reader: Job? = null
    private var hush: Job? = null
    private var watchdog: Job? = null
    private var lastActivity = 0L
    private var meter = VoiceCost()
    /** The function calls of each response, by response id, answered together once the response is done. */
    private val calls = HashMap<String, MutableList<Deferred<Pair<String, String>>>>()
    private val seenCalls = HashSet<String>()
    private var sequence = 0

    val isOn: Boolean get() = _phase.value != Phase.OFF

    init {
        store.scope.launch {
            var connection = store.connection.value?.let { it.address.origin to it.device.id }
            store.connection.collect { current ->
                val identity = current?.let { it.address.origin to it.device.id }
                if (identity != connection) {
                    stop()
                    _project.value = null; _lines.value = emptyList(); _steps.value = emptyList()
                    connection = identity
                }
            }
        }
    }

    fun start() {
        if (_phase.value != Phase.OFF) return
        _notice.value = null
        val selected = _project.value?.let { p -> store.projects.value.firstOrNull { it.repo == p.repo } }
            ?: run { _notice.value = "Select a project to talk about."; return }
        val key = settings.key() ?: run { _notice.value = "Add your OpenAI API key in the voice settings."; return }
        if (store.client == null) { _notice.value = "Pair with a Briareus server first."; return }
        _project.value = selected
        _phase.value = Phase.CONNECTING
        _lines.value = emptyList(); _steps.value = emptyList(); _muted.value = false; _started.value = null
        calls.clear(); seenCalls.clear()
        meter = VoiceCost(); _cost.value = null
        val call = RealtimeCall(context).also { call = it }
        val session = Voice.session(settings.voice.value, selected)
        VoiceService.start(context)
        reader = store.scope.launch {
            try {
                call.open(key, session).collect(::handle)
                ended(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ended(e.message ?: "The conversation ended.")
            }
        }
    }

    /** Hangs up: closing the call ends the Realtime session. */
    fun stop(reason: String? = null) {
        if (_phase.value != Phase.CONNECTING && _phase.value != Phase.LIVE) return
        reason?.let { _notice.value = it }
        _phase.value = Phase.CLOSING
        ended(null)
    }

    fun toggleMute() {
        _muted.value = !_muted.value
        call?.mute(_muted.value)
    }

    private fun ended(failure: String?) {
        if (_phase.value == Phase.OFF) return
        if (failure != null && _phase.value != Phase.CLOSING) _notice.value = failure
        val reader = reader
        this.reader = null
        watchdog?.cancel(); watchdog = null
        calls.values.flatten().forEach { it.cancel() }; calls.clear()
        hush?.cancel(); hush = null
        call?.hangUp(); call = null
        _speaking.value = false
        _phase.value = Phase.OFF
        VoiceService.stop(context)
        reader?.cancel()
    }

    private fun nextId(): String = "briareus_${++sequence}"
    private fun touch() { lastActivity = System.currentTimeMillis() }

    // MARK: - Events

    private fun handle(event: JsonObject) {
        when (event.str("type")) {
            "session.created" -> if (_phase.value == Phase.CONNECTING) {
                _phase.value = Phase.LIVE
                _started.value = System.currentTimeMillis()
                _cost.value = meter.line
                touch()
                watch()
            }
            // A piece of the user's speech the model hears, transcribed or not.
            "input_audio_buffer.committed" -> touch()
            "conversation.item.input_audio_transcription.completed" -> {
                caption(user = true, event.str("transcript"))
                meter.addTranscription(event.obj("usage")); _cost.value = meter.line
                touch()
            }
            "response.output_audio_transcript.delta" -> { caption(user = false, event.str("delta")); touch() }
            "response.output_audio.delta" -> { talking(); touch() }
            "response.output_item.done" -> tools(event)
            "response.done" -> {
                meter.addResponse(event.obj("response")?.obj("usage")); _cost.value = meter.line
                tools(event)
            }
            "error" -> _notice.value = event.obj("error")?.str("message") ?: "GPT-Realtime reported an error."
        }
    }

    /** The voice reads as speaking while its audio keeps coming. */
    private fun talking() {
        _speaking.value = true
        hush?.cancel()
        hush = store.scope.launch { delay(1_500); _speaking.value = false }
    }

    /** Ends the conversation after the silence the settings allow, as GPT-Realtime bills the audio it hears and says. */
    private fun watch() {
        watchdog = store.scope.launch {
            while (isActive) {
                delay(10_000)
                if (_phase.value != Phase.LIVE) continue
                if (_speaking.value || calls.isNotEmpty()) { touch(); continue }
                val minutes = settings.idleMinutes.value
                if (minutes > 0 && System.currentTimeMillis() - lastActivity > minutes * 60_000L) {
                    stop("Ended after $minutes minute${if (minutes == 1) "" else "s"} of silence.")
                }
            }
        }
    }

    private fun caption(user: Boolean, delta: String?) {
        if (delta.isNullOrEmpty()) return
        _lines.update { lines ->
            val last = lines.lastOrNull()
            if (last != null && last.user == user && !user) lines.dropLast(1) + last.copy(text = last.text + delta)
            else (lines + Line(user, delta.trim())).takeLast(60)
        }
    }

    // MARK: - Tools

    /**
     * A response's event: a finished function call starts running at once; when the response is done, every call it
     * made is answered and the model is told to go on.
     */
    private fun tools(event: JsonObject) {
        when (event.str("type")) {
            "response.output_item.done" -> {
                val item = event.obj("item") ?: return
                val response = event.str("response_id").orEmpty()
                val id = item.str("call_id") ?: return
                val name = item.str("name") ?: return
                if (item.str("type") != "function_call" || !seenCalls.add(id)) return
                val input = item.str("arguments")?.let { runCatching { BriareusJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())
                val step = Step(VoiceTool.of(name), name, input, Step.State.Running)
                _steps.update { it + step }
                touch()
                calls.getOrPut(response) { mutableListOf() } += store.scope.async { id to run(step) }
            }
            "response.done" -> {
                val response = event.obj("response")
                val pending = calls.remove(response?.str("id").orEmpty())?.takeIf { it.isNotEmpty() } ?: return
                val call = call ?: return
                // A response cut off by the user does not go on by itself; what its calls did is still told.
                val goOn = response?.str("status") == "completed"
                store.scope.launch {
                    val outputs = pending.map { it.await() }
                    if (this@VoiceSession.call !== call) return@launch
                    for ((id, output) in outputs) {
                        call.send(args("type" to "conversation.item.create", "event_id" to nextId(), "item" to mapOf("type" to "function_call_output", "call_id" to id, "output" to output)))
                    }
                    if (goOn) call.send(args("type" to "response.create", "event_id" to nextId()))
                }
            }
        }
    }

    /** Runs one tool call and answers with what the model should know, as JSON text. */
    private suspend fun run(step: Step): String {
        fun finish(state: Step.State, answer: JsonObject): String {
            _steps.update { steps -> steps.map { if (it.id == step.id) it.copy(state = state) else it } }
            touch()
            return answer.toString()
        }
        fun fail(why: String) = finish(Step.State.Failed(why), args("error" to why))
        val tool = step.tool ?: return fail("There is no tool named ${step.name}.")
        val context = voiceContext()
        val plan = tool.plan(step.input, context)
        return when (plan) {
            is VoicePlan.Refuse -> fail(plan.why)
            is VoicePlan.Show -> try {
                finish(Step.State.Done, args("done" to true, "on_screen" to navigator.show(plan.action)))
            } catch (e: Exception) {
                fail(e.message ?: "That could not be shown.")
            }
            VoicePlan.ReadScreen -> finish(Step.State.Done, navigator.screen(context.project!!.repo))
            is VoicePlan.Call -> call(step, tool, plan.arguments, ::finish, ::fail)
        }
    }

    private suspend fun call(
        step: Step, tool: VoiceTool, planned: JsonObject,
        finish: (Step.State, JsonObject) -> String, fail: (String) -> String,
    ): String {
        val operation = tool.operation ?: return fail("That is not a server call.")
        if (!store.can(operation)) return fail("This headset's token cannot do that on the server.")
        val client = store.client ?: return fail("The headset is not connected to a server.")
        return try {
            var arguments = planned
            if (tool == VoiceTool.WORK_ON_ISSUE) {
                val repo = planned.str("repo").orEmpty()
                val number = planned.int("issue") ?: 0
                arguments = Voice.issueStart(client.call("pulls", args("repo" to repo), TIMEOUT), number, repo)
                    ?: return fail("Issue #$number is not open on ${store.projectTitle(repo)}.")
            }
            if (tool == VoiceTool.START_CODE_REVIEW) {
                arguments = reviewArguments(client, planned)
            }
            val answer = client.call(operation, arguments, TIMEOUT)
            val full = if (tool == VoiceTool.READ_ISSUE && store.can("issue_timeline")) JsonObject(answer + ("timeline" to timeline(client, arguments))) else answer
            Session.parse(answer["session"])?.let { if (tool.changes) store.upsert(it) }
            val repo = planned.str("repo")
            val sessions = if (tool.readsConversations) store.sessions.value.values.filter { it.repo == repo } else emptyList()
            finish(Step.State.Done, tool.summary(full, step.input, sessions, store::projectTitle))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.unauthorized) store.failed(e)
            fail(e.description)
        } catch (e: Exception) {
            fail(e.message ?: "The call failed.")
        }
    }

    private suspend fun reviewArguments(client: ApiClient, planned: JsonObject): JsonObject {
        check(store.can("pull")) { "This headset's token cannot read the pull request to review." }
        return PullReviewStart.arguments(planned.str("repo").orEmpty(), planned.int("pr") ?: 0, client.call("pull", planned, TIMEOUT))
    }

    /** An issue's comments are on its timeline, oldest first: a few of its pages are read, for the latest. */
    private suspend fun timeline(client: ApiClient, place: JsonObject): JsonArray {
        val rows = ArrayList<JsonObject>()
        var page = 1
        repeat(Voice.ISSUE_TIMELINE_PAGES) {
            if (page == 0) return@repeat
            val timeline = runCatching { client.call("issue_timeline", JsonObject(place + ("page" to JsonPrimitive(page))), TIMEOUT) }.getOrNull()
            rows += timeline?.objects("events").orEmpty()
            page = timeline?.int("nextPage") ?: 0
        }
        return JsonArray(rows)
    }

    private fun voiceContext(): VoiceContext {
        val selected = _project.value?.takeIf { p -> store.projects.value.any { it.repo == p.repo } }
        val repo = selected?.repo
        return VoiceContext(
            selected, store.sessions.value.filterValues { it.repo == repo },
            navigator.onScreen()?.takeIf { it.repo == repo }, navigator.pullOnScreen()?.takeIf { it.first == repo },
        )
    }

    private companion object {
        const val TIMEOUT = 60_000L
    }
}
