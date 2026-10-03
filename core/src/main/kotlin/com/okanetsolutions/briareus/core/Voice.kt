// The voice mode's side of GPT-Realtime, OpenAI's realtime voice model: the session it opens, the tools it calls, and
// what each answer becomes for the model to say. No Android code, no audio here.
//
// GPT-Realtime holds the spoken conversation and decides itself which tool to call. The headset runs each call on
// /api/v1 with its own token, or on its own windows for the tools that show something, and answers with a short JSON
// summary. Anything that changes something on the server is read back first and runs only on a yes heard after it.
package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale

object Voice {
    const val MODEL = "gpt-realtime-2.1-mini"
    /** Where a call starts: a WebSocket on which the session is set and JSON events and PCM audio travel both ways. */
    const val ENDPOINT = "wss://api.openai.com/v1/realtime?model=$MODEL"
    /** What writes out the user's speech for the captions; the model hears the audio itself. */
    const val TRANSCRIBER = "gpt-4o-mini-transcribe"
    /** 16-bit mono PCM at 24 kHz both ways, the Realtime API's own format. */
    const val SAMPLE_RATE = 24_000
    const val DEFAULT_VOICE = "marin"
    /** Marin first, the default; then the Realtime API's other voices. */
    val VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")
    /** The label a reviewer sets once the code is approved. */
    const val APPROVED_LABEL = "code-approved"
    /** How many timeline pages `read_issue` reads at most: 100 rows each, oldest first, so its latest comments are on the last. */
    const val ISSUE_TIMELINE_PAGES = 5

    /** How the voice speaks and what it may do, with the projects the token can see named. */
    fun instructions(projects: List<Project>): String {
        val named = projects.joinToString("; ") { if (it.title == it.repo) it.repo else "${it.title} (${it.repo})" }.ifEmpty { "none yet" }
        return """
        You are the voice of Briareus on a Meta Quest headset. Briareus runs coding agents on the user's projects. The user \
        talks to you hands-free while the app's panels float around them, often beside a video. Answer in the language the \
        user speaks, in one or two short sentences. Speak only when the user has said something; do not volunteer updates.

        ## Projects
        The projects this headset can see: $named. Tools take a project by its name or repository; omit it when the user \
        means the conversation on screen, or when there is only one project. If it is unclear which project the user means, \
        ask.

        ## The screen
        You also drive the app's panels. When the user says "show", "open", "bring up", "go to" or "let me see", use the \
        show_ tools: show_conversation puts a conversation in the main window (or in a panel of its own), \
        show_pull_requests puts a project's open pull requests there, show_pull_request one pull request (its \
        description, checks, reviews, findings and commits; its files and anything asked for in the browser open on \
        GitHub beside the app), show_issue opens an issue in the browser, show_preview opens a conversation's running \
        app, show_new_conversation opens the form to start one (with a prompt filled in if the user dictated one), \
        show_status_panel opens the status panel, and go_home leaves the main window on the list. These only show things; they need no confirmation. When the user says "this", "that", "it" or \
        "here", call read_screen first to learn what is on screen: "this pull request" is the pull request of the \
        conversation on screen. After showing something, say in a few words what is now on screen.

        ## Conversations and pull requests
        Find conversations with list_conversations before acting on one; never invent an id. Match what the user names \
        against titles loosely. Each conversation carries its pull_request with its state (open, merged or closed) and \
        checks, and each open pull request names the conversations working on it. list_pull_requests lists open pull \
        requests only; one missing from it was merged or closed, and the conversation's pull_request says which. \
        read_conversation tells what an agent did, said or asks. send_message also answers an agent's question. For what \
        a pull request changes (how many files, which ones, lines added and removed), use read_pull_request.

        ## Issues
        list_issues lists a project's open issues with their labels, epic progress, the pull requests that close them and \
        the conversations started on them. read_issue reads one in full. To have an agent do an issue, use work_on_issue: \
        it starts a conversation that reads the issue, implements it and opens a pull request that closes it. Before \
        starting one, say if a conversation or a pull request is already on that issue.

        ## Ready to merge
        A pull request is ready to be merged only when list_pull_requests marks it ready_to_merge: it carries the \
        $APPROVED_LABEL label, its checks passed, and it has no conflicts and is not a draft. Never call one ready on its \
        checks or reviews alone; say what it still lacks instead. merge_pull_request's first call answers a read_back with \
        what stands in its way; read all of it to the user, who may still choose to merge.

        ## Confirmation
        start_conversation, work_on_issue, send_message, stop_conversation and merge_pull_request change things. Call them \
        with confirmed=false first: the answer says what to read back. Call again with confirmed=true only after the user \
        clearly agreed to that exact action in their latest turn. Never pass confirmed=true on your own.

        ## Saying the result
        Transcripts can contain mistakes and later corrections; use the latest context, and ask when a needed detail is \
        unclear. Say the relevant facts in a few plain sentences, without ids or URLs. Report an action as done only when \
        the tool says it is.
        """.trimIndent()
    }

    /**
     * The `session` of the first `session.update`: the voice, the audio both ways, the transcription of the user's speech
     * for the captions, and the tools. Noise reduction is tuned for a headset's microphone, close to the mouth.
     */
    fun session(voice: String, projects: List<Project>): JsonObject {
        val pcm = mapOf("type" to "audio/pcm", "rate" to SAMPLE_RATE)
        return args(
            "type" to "realtime",
            "model" to MODEL,
            "instructions" to instructions(projects),
            "output_modalities" to listOf("audio"),
            "audio" to mapOf(
                "input" to mapOf(
                    "format" to pcm,
                    "transcription" to mapOf("model" to TRANSCRIBER),
                    "noise_reduction" to mapOf("type" to "near_field"),
                    "turn_detection" to mapOf("type" to "server_vad", "create_response" to true, "interrupt_response" to true),
                ),
                "output" to mapOf("format" to pcm, "voice" to (if (voice in VOICES) voice else DEFAULT_VOICE)),
            ),
            "tools" to VoiceTool.entries.map { it.definition },
            "tool_choice" to "auto",
        )
    }

    // MARK: - Projects

    /** Which project a call means. */
    sealed interface Pick {
        data class Found(val repo: String) : Pick
        data class Refuse(val why: String) : Pick
    }

    /**
     * The project [named] (its repository, or its title or repository name, loosely), else the conversation on screen's,
     * else the only one. Null [named] with neither is a question back to the user.
     */
    fun project(named: String?, projects: List<Project>, onScreen: String? = null): Pick {
        val q = named?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (q == null) {
            onScreen?.let { return Pick.Found(it) }
            projects.singleOrNull()?.let { return Pick.Found(it.repo) }
            return Pick.Refuse("Which project? " + projects.joinToString(", ") { it.title })
        }
        fun words(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val w = words(q)
        val match = projects.firstOrNull { it.repo.lowercase(Locale.ROOT) == q }
            ?: projects.firstOrNull { words(it.title) == w || words(it.repo.substringAfter('/')) == w }
            ?: projects.filter { words(it.title).contains(w) || words(it.repo).contains(w) }.singleOrNull()
        return match?.let { Pick.Found(it.repo) }
            ?: Pick.Refuse("No project matches \"$named\". The projects are: " + projects.joinToString(", ") { it.title })
    }

    // MARK: - Words

    /** The words inside inline Markdown: a link by its text, code and emphasis without their marks, on one line. */
    fun inline(text: String): String {
        var t = text
        for ((pattern, template) in listOf(
            "!\\[([^\\]]*)\\]\\([^)]*\\)" to "$1", "\\[([^\\]]+)\\]\\([^)]*\\)" to "$1", "`([^`]*)`" to "$1",
            "(\\*\\*|__)(.+?)\\1" to "$2", "~~(.+?)~~" to "$1",
        )) t = t.replace(Regex(pattern), template)
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** [text] cut to [length] characters, with an ellipsis when it was longer. */
    fun cut(text: String, length: Int): String = if (text.length > length) text.take(length) + "…" else text

    /** What a conversation is doing and what it waits for. */
    fun status(s: Session, asking: Boolean = false): String {
        val parts = mutableListOf(
            when {
                asking || s.state == Session.State.WAITING -> "Asks you a question"
                s.status == "running" -> "Working"
                s.isActive -> "Starting"
                else -> s.state.label
            },
        )
        if (s.queued.isNotEmpty()) parts += "${s.queued.size} queued"
        s.reviewTriage?.let { t -> t.array("findings")?.size?.let { parts += if (it == 1) "1 finding" else "$it findings" } }
        return parts.joinToString(" · ")
    }

    /** What a message sent now does: it goes into the running turn, waits for the next one, or starts one. */
    fun sent(s: Session?): String = when {
        s == null || !s.isActive -> "Sent"
        s.liveInput -> "Sent into the running turn"
        else -> "Queued for the next turn"
    }

    // MARK: - Conversations

    /** A conversation as the model reads it, with its question when the agent asks one. */
    fun conversation(s: Session, projectTitle: String? = null, asking: TranscriptEvent? = null): JsonObject {
        val out = linkedMapOf<String, Any?>(
            "session_id" to s.id, "title" to s.title, "status" to status(s, asking != null), "project" to projectTitle,
            "pull_request" to pullRequest(s),
        )
        if (asking != null) {
            out["question"] = inline(asking.question ?: asking.text.orEmpty())
            out["options"] = asking.options.ifEmpty { null }
        }
        return jsonOf(out) as JsonObject
    }

    /** A conversation's pull request as the server last synced it: its number, state and checks. */
    fun pullRequest(s: Session): JsonObject? {
        val pr = s.raw.obj("prStatus") ?: return null
        val number = pr.int("number") ?: return null
        val checks = pr.obj("checks")?.let { c ->
            val passed = c.int("passed") ?: 0; val failed = c.int("failed") ?: 0; val pending = c.int("pending") ?: 0
            if (passed + failed + pending == 0) "None" else "$passed passed · $failed failed · $pending running"
        }
        return args(
            "number" to number, "state" to (pr.str("state") ?: "open"), "title" to pr.nonEmpty("title")?.let(::inline),
            "draft" to pr.bool("draft")?.takeIf { it }, "checks" to checks,
        )
    }

    /** Whether a conversation was started on issue [number]: such a session is titled by its prompt's first line, `Issue #N: title`. */
    fun onIssue(s: Session, number: Int): Boolean = number >= 1 && s.title.startsWith("Issue #$number:")

    /** The last few things said in a conversation, oldest first, each cut short. */
    fun latest(events: List<TranscriptEvent>, count: Int = 6, length: Int = 600): List<JsonObject> =
        events.filter { it.kind in setOf("user", "text", "result", "ask") && !(it.question ?: it.text).isNullOrEmpty() }
            .takeLast(count)
            .map { e -> args("from" to (if (e.kind == "user") "user" else "agent"), "text" to cut(inline(e.question ?: e.text.orEmpty()), length)) }

    /** The read-back of a change asked twice is the same: the tool, what it acts on, and its words without case or punctuation. */
    fun readBackKey(tool: VoiceTool, input: JsonObject): String {
        fun words(s: String?) = s.orEmpty().lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")
        return listOf(
            tool.wire, input.str("session_id").orEmpty(), input.str("project").orEmpty().lowercase(Locale.ROOT), input.str("branch").orEmpty(),
            input.int("issue")?.toString().orEmpty(), input.int("number")?.toString().orEmpty(), words(input.str("text") ?: input.str("prompt")),
        ).joinToString("|")
    }

    // MARK: - Board

    /** Ready to merge: approved by its label, checks passed, no conflicts, not a draft. */
    fun readyToMerge(pr: JsonObject): Boolean =
        labels(pr).any { it.equals(APPROVED_LABEL, ignoreCase = true) } && pr.str("checks") == "success" && !conflicting(pr) && pr.bool("draft") != true

    fun labels(row: JsonObject): List<String> = row.array("labels").orEmpty().mapNotNull { l ->
        ((l as? JsonObject)?.nonEmpty("name") ?: (l as? JsonPrimitive)?.takeIf { it.isString }?.content)?.takeIf { it.isNotEmpty() }
    }

    private fun conflicting(pr: JsonObject) = pr.str("mergeable") == "conflicting" || labels(pr).any { it.equals("has-conflicts", ignoreCase = true) }

    /** A board row as the line under its title: number, draft, conflicts, checks and author. */
    fun pullLine(pr: JsonObject): String {
        val said = mutableListOf("#${pr.int("number")}")
        if (pr.bool("draft") == true) said += "Draft"
        if (conflicting(pr)) said += "Conflicts"
        pr.str("checks")?.let { said += if (it == "success") "Checks passed" else if (it == "failure" || it == "error") "Checks failed" else "Checks running" }
        pr.nonEmpty("author")?.let { said += "@$it" }
        return said.joinToString(" · ")
    }

    /** Where a pull request is read in the browser: its own `url` when the board gave one, else GitHub's, on the [view] asked for. */
    fun pullUrl(repo: String, number: Int, url: String? = null, view: String? = null): String {
        val base = url?.takeIf { it.startsWith("https://") }?.trimEnd('/') ?: "https://github.com/$repo/pull/$number"
        val tab = when (view) { "files" -> "/files"; "checks" -> "/checks"; "commits" -> "/commits"; else -> "" }
        return base + tab
    }

    fun issueUrl(repo: String, number: Int, url: String? = null): String =
        url?.takeIf { it.startsWith("https://") } ?: "https://github.com/$repo/issues/$number"

    /** What a session started on an issue is sent, as the other clients word it. Its first line names the session. */
    fun issuePrompt(issue: JsonObject, repo: String): String {
        val number = issue.int("number")
        val title = issue.str("title") ?: "Issue #$number"
        val s = StringBuilder("Issue #$number: $title\n\n")
        s.append("Read $repo issue #$number in full before you change anything: `gh issue view $number --repo $repo --comments`. Its comments usually carry decisions the description was written before.\n\n")
        issue.obj("parent")?.let { parent ->
            val parentRepo = parent.nonEmpty("repo") ?: repo
            s.append("It is a sub-issue of $parentRepo#${parent.int("number")} (${parent.str("title").orEmpty()}). Read that epic too, for the shape this piece has to fit; implement only this issue.\n\n")
        }
        s.append("Then implement it on this session’s own branch, verify the change the way this repository verifies changes, and open a pull request whose body says `Closes #$number`, so merging it closes the issue.\n\n")
        s.append("If the issue is too ambiguous to implement as written, say what is missing and stop rather than guessing at it.")
        return s.toString()
    }

    /** What `start_session` is sent for an agent on issue [number], from the board `pulls` answered; null when it is not open there. */
    fun issueStart(board: JsonObject, number: Int, repo: String): JsonObject? {
        val issue = board.objects("issues").firstOrNull { it.int("number") == number } ?: return null
        return args("repo" to repo, "prompt" to issuePrompt(issue, repo), "activity" to "issue")
    }

    /** An issue's state in words: open, closed, or closed with its reason. */
    fun issueState(issue: JsonObject): String {
        if (issue.str("state") != "closed") return issue.str("state") ?: "open"
        return when (issue.str("stateReason")) {
            "not_planned" -> "closed as not planned"
            "duplicate" -> "closed as a duplicate"
            "completed" -> "closed as completed"
            else -> "closed"
        }
    }

    /** What a pull request changes, from the first page of its files: totals, the files by top folder, and the files themselves. */
    fun changes(page: JsonObject, listed: Int = 40): JsonObject {
        val pr = page.obj("pr") ?: JsonObject(emptyMap())
        val files = page.objects("files")
        val folders = files.groupingBy { f -> f.str("filename").orEmpty().substringBefore('/') }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(8)
        val more = (page.int("nextPage") ?: 0) != 0 || page.bool("truncated") == true || files.size > listed
        return args(
            "title" to pr.nonEmpty("title")?.let(::inline),
            "changed_files" to (pr.int("changedFiles") ?: files.size),
            "lines_added" to pr.int("additions"), "lines_removed" to pr.int("deletions"), "commits" to pr.int("commits"),
            "by_folder" to folders.map { mapOf("folder" to it.key, "files" to it.value) },
            "files" to files.take(listed).map { f ->
                mapOf("path" to f.str("filename"), "change" to (f.str("status") ?: "modified"), "added" to f.int("additions"), "removed" to f.int("deletions"))
            },
            "files_listed" to if (more) "the first ${minOf(listed, files.size)} only" else null,
        )
    }
}

// MARK: - Cost

/**
 * What a conversation has cost so far, in dollars, from the token usage OpenAI reports: each response's on
 * `response.done`, and each transcription of the user's speech on its completed event. Prices per million tokens, as
 * OpenAI lists them for gpt-realtime-2.1-mini and gpt-4o-mini-transcribe. An estimate: OpenAI's own bill is the reference.
 */
class VoiceCost {
    var dollars = 0.0
        private set
    var tokens = 0
        private set

    /** A response's usage: input split into text, audio and image, each with a cached part; output into text and audio. */
    fun addResponse(usage: JsonObject?) {
        usage ?: return
        tokens += (usage.int("input_tokens") ?: 0) + (usage.int("output_tokens") ?: 0)
        val input = usage.obj("input_token_details"); val cached = input?.obj("cached_tokens_details"); val output = usage.obj("output_token_details")
        fun n(o: JsonObject?, key: String) = (o?.long(key) ?: 0).toDouble()
        val text = maxOf(n(input, "text_tokens") - n(cached, "text_tokens"), 0.0)
        val audio = maxOf(n(input, "audio_tokens") - n(cached, "audio_tokens"), 0.0)
        val image = maxOf(n(input, "image_tokens") - n(cached, "image_tokens"), 0.0)
        dollars += (text * 0.60 + n(cached, "text_tokens") * 0.06 + audio * 10 + n(cached, "audio_tokens") * 0.30 +
            image * 0.80 + n(cached, "image_tokens") * 0.08 + n(output, "text_tokens") * 2.40 + n(output, "audio_tokens") * 20) / 1_000_000
    }

    /** A transcription's usage, when it is billed by tokens. */
    fun addTranscription(usage: JsonObject?) {
        if (usage?.str("type") != "tokens") return
        val input = usage.int("input_tokens") ?: 0; val output = usage.int("output_tokens") ?: 0
        tokens += input + output
        dollars += (input * 1.25 + output * 5) / 1_000_000
    }

    /** "≈ $0.0123 · 3.4k tokens", as the voice panel shows it under the controls. */
    val line: String
        get() {
            val amount = when {
                dollars < 0.01 -> "$%.4f"; dollars < 1 -> "$%.3f"; else -> "$%.2f"
            }.format(Locale.ROOT, dollars)
            val count = if (tokens >= 1000) "%.1fk tokens".format(Locale.ROOT, tokens / 1000.0) else "$tokens tokens"
            return "≈ $amount · $count"
        }
}

// MARK: - Tools

/** What the screen can be asked to show; the app carries it out on its windows. */
sealed interface ScreenAction {
    data class Conversation(val sessionId: String, val ownPanel: Boolean) : ScreenAction
    data class NewConversation(val repo: String?, val prompt: String?) : ScreenAction
    /** One pull request: in the main window, or its [view] on GitHub in the browser when [browser]. */
    data class PullRequest(val repo: String, val number: Int, val view: String?, val browser: Boolean) : ScreenAction
    data class PullRequests(val repo: String) : ScreenAction
    data class Issue(val repo: String, val number: Int) : ScreenAction
    data class Preview(val sessionId: String) : ScreenAction
    data object StatusPanel : ScreenAction
    data object Home : ScreenAction
}

/** What a tool call becomes on the headset. */
sealed interface VoicePlan {
    /** Make the tool's client API call ([VoiceTool.operation]) with these arguments. */
    data class Call(val arguments: JsonObject) : VoicePlan
    /** Answer the model with this read-back; nothing is done until the call comes back confirmed. */
    data class Confirm(val readBack: String) : VoicePlan
    /** Answer the model with what is wrong with the call. */
    data class Refuse(val why: String) : VoicePlan
    /** Change what the windows show. */
    data class Show(val action: ScreenAction) : VoicePlan
    /** Answer with what is on screen. */
    data object ReadScreen : VoicePlan
}

/**
 * What a plan needs to know of the app: the projects, the conversations it knows, the conversation in the main window,
 * and the pull requests there: their repository, and the number when it shows one rather than the project's list.
 */
data class VoiceContext(
    val projects: List<Project>, val sessions: Map<String, Session>, val onScreen: Session? = null, val pullOnScreen: Pair<String, Int?>? = null,
)

/** What the model may call. Server tools run one client API call; screen tools change the windows; changes wait for a yes. */
enum class VoiceTool(val wire: String) {
    LIST_CONVERSATIONS("list_conversations"),
    READ_CONVERSATION("read_conversation"),
    LIST_PULL_REQUESTS("list_pull_requests"),
    READ_PULL_REQUEST("read_pull_request"),
    MERGE_PULL_REQUEST("merge_pull_request"),
    WAITING_FINDINGS("waiting_findings"),
    LIST_ISSUES("list_issues"),
    READ_ISSUE("read_issue"),
    START_CONVERSATION("start_conversation"),
    WORK_ON_ISSUE("work_on_issue"),
    SEND_MESSAGE("send_message"),
    STOP_CONVERSATION("stop_conversation"),
    READ_SCREEN("read_screen"),
    SHOW_CONVERSATION("show_conversation"),
    SHOW_PULL_REQUESTS("show_pull_requests"),
    SHOW_PULL_REQUEST("show_pull_request"),
    SHOW_ISSUE("show_issue"),
    SHOW_PREVIEW("show_preview"),
    SHOW_NEW_CONVERSATION("show_new_conversation"),
    SHOW_STATUS_PANEL("show_status_panel"),
    GO_HOME("go_home");

    /** The client API call each server tool makes; null for the screen's own. */
    val operation: String?
        get() = when (this) {
            LIST_CONVERSATIONS, WAITING_FINDINGS -> "sessions"
            READ_CONVERSATION -> "session"
            LIST_PULL_REQUESTS, LIST_ISSUES -> "pulls"
            READ_ISSUE -> "issue"
            READ_PULL_REQUEST -> "pull_files"
            MERGE_PULL_REQUEST -> "merge_pull"
            START_CONVERSATION, WORK_ON_ISSUE -> "start_session"
            SEND_MESSAGE -> "message"
            STOP_CONVERSATION -> "cancel"
            else -> null
        }

    val changes: Boolean get() = this in setOf(START_CONVERSATION, WORK_ON_ISSUE, SEND_MESSAGE, STOP_CONVERSATION, MERGE_PULL_REQUEST)

    /** Reads the project's conversations too, to link each pull request or issue to the conversations working on it. */
    val readsConversations: Boolean get() = this in setOf(LIST_PULL_REQUESTS, LIST_ISSUES, READ_ISSUE)

    /** A Realtime function tool. */
    val definition: JsonObject
        get() {
            val properties = linkedMapOf<String, Any?>()
            val required = mutableListOf<String>()
            fun add(name: String, type: String, about: String, isRequired: Boolean = true, enum: List<String>? = null) {
                properties[name] = mapOf("type" to type, "description" to about, "enum" to enum)
                if (isRequired) required += name
            }
            fun project() = add("project", "string", "The project's name or repository. Omit for the conversation on screen's project, or when there is only one.", false)
            fun session() = add("session_id", "string", "The conversation's id, from list_conversations or read_screen.")
            fun confirmed() = add("confirmed", "boolean", "True only after the user agreed to this exact action.")
            val description = when (this) {
                LIST_CONVERSATIONS -> {
                    add("project", "string", "Only this project's conversations, by name or repository. Omit for every project.", false)
                    add("active_only", "boolean", "Only the conversations an agent is working on or that wait for the user.", false)
                    "Conversations, newest first, with their project, status, whether the agent asks a question, and their pull request with its state (open, merged or closed) and checks."
                }
                READ_CONVERSATION -> { session(); "A conversation's status and its latest messages: what the user asked, what the agent said, and an open question." }
                LIST_PULL_REQUESTS -> { project(); "A project's open pull requests with their checks, conflicts, labels, whether each is ready to merge, and the conversations working on it." }
                READ_PULL_REQUEST -> {
                    add("number", "integer", "The pull request's number."); project()
                    "What one pull request changes: how many files, lines added and removed, the files by folder, and each file's path and change."
                }
                MERGE_PULL_REQUEST -> {
                    add("number", "integer", "The pull request's number."); project(); confirmed()
                    "Merges one of a project's open pull requests into its base branch. The first call reads it and answers what to read back, with what stands in the way."
                }
                WAITING_FINDINGS -> {
                    add("project", "string", "Only this project's, by name or repository. Omit for every project.", false)
                    "The review rounds waiting for the user's decision."
                }
                LIST_ISSUES -> { project(); "A project's open issues with their labels, epic progress, the pull requests that close them and the conversations started on them." }
                READ_ISSUE -> {
                    add("issue", "integer", "The issue's number, from list_issues or as the user said it."); project()
                    "One issue in full: its state, labels, description and latest comments, its epic or sub-issues, the pull requests that close it and the conversations started on it."
                }
                WORK_ON_ISSUE -> {
                    add("issue", "integer", "The issue's number, from list_issues."); project(); confirmed()
                    "Starts an agent on one of a project's open issues: it reads the issue in full, implements it and opens a pull request that closes it."
                }
                START_CONVERSATION -> {
                    add("prompt", "string", "What the agent should do, as the user said it."); project()
                    add("branch", "string", "The branch to start from. Omit for the project's default.", false); confirmed()
                    "Starts an agent on a project with a first prompt."
                }
                SEND_MESSAGE -> {
                    session(); add("text", "string", "The message, as the user said it."); confirmed()
                    "Sends a message to a conversation's agent, or answers its question. A busy agent gets it in its running turn or the next."
                }
                STOP_CONVERSATION -> { session(); confirmed(); "Stops the agent's running turn. The conversation stays open." }
                READ_SCREEN -> "What the app's windows show right now: the conversation or pull request in the main window with its project, and the conversations open in panels of their own."
                SHOW_CONVERSATION -> {
                    session()
                    add("own_panel", "boolean", "True to open it in a panel of its own beside the others instead of the main window.", false)
                    "Shows a conversation: its transcript, its question, its findings and its composer."
                }
                SHOW_PULL_REQUESTS -> { project(); "Shows a project's open pull requests in the main window, with their checks, labels and actions." }
                SHOW_PULL_REQUEST -> {
                    add("number", "integer", "The pull request's number, from a conversation's pull_request or list_pull_requests. Omit for the pull request on screen.", false); project()
                    add("view", "string", "What to show. files opens GitHub's files page in the browser, as the app does not list them. Omit for the overview.", false, listOf("overview", "files", "checks", "commits"))
                    add("in_browser", "boolean", "True to open its page on GitHub in the browser beside the app instead of the main window.", false)
                    "Shows one pull request in the main window: its status, description, checks, reviews, findings, linked issues and commits."
                }
                SHOW_ISSUE -> { add("issue", "integer", "The issue's number."); project(); "Opens an issue's page in the browser beside the app." }
                SHOW_PREVIEW -> { session(); "Opens what a conversation's ▶ Run serves, its running app, in the browser." }
                SHOW_NEW_CONVERSATION -> {
                    project(); add("prompt", "string", "A first message to fill in, as the user dictated it, for them to review and start.", false)
                    "Opens the form that starts a conversation, on a project, for the user to choose its runtime and start it themselves."
                }
                SHOW_STATUS_PANEL -> "Opens the status panel: what is waiting, working and ready, in a narrow window."
                GO_HOME -> "Leaves the main window on the conversation list, with nothing open."
            }
            return args(
                "type" to "function", "name" to wire, "description" to description,
                "parameters" to mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false),
            )
        }

    /**
     * What a call with these arguments does: the client API call to make, a read-back that waits for a yes, something
     * to show, or why it cannot be made. A merge's call is planned by [VoiceMerge] once the pull request is read.
     */
    fun plan(input: JsonObject, context: VoiceContext): VoicePlan {
        fun text(key: String) = input.str(key)?.trim()?.takeIf { it.isNotEmpty() }
        fun number(key: String) = (input[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it >= 1 }
        val confirmed = (input["confirmed"] as? JsonPrimitive)?.booleanOrNull == true
        val named = text("project")
        val repo: () -> Voice.Pick = { Voice.project(named, context.projects, context.onScreen?.repo) }
        /** The conversation named by id, which must be one this headset knows: an id is never guessed. */
        fun session(): Session? = text("session_id")?.let { context.sessions[it] }
        val unknown = VoicePlan.Refuse("There is no such conversation. Find it with list_conversations first.")
        fun inRepo(then: (String) -> VoicePlan): VoicePlan = when (val p = repo()) {
            is Voice.Pick.Found -> then(p.repo)
            is Voice.Pick.Refuse -> VoicePlan.Refuse(p.why)
        }
        return when (this) {
            LIST_CONVERSATIONS, WAITING_FINDINGS ->
                if (named == null) VoicePlan.Call(JsonObject(emptyMap())) else inRepo { VoicePlan.Call(args("repo" to it)) }
            LIST_PULL_REQUESTS, LIST_ISSUES -> inRepo { VoicePlan.Call(args("repo" to it)) }
            READ_ISSUE -> number("issue")?.let { n -> inRepo { VoicePlan.Call(args("repo" to it, "issue" to n)) } } ?: VoicePlan.Refuse("issue is missing.")
            READ_PULL_REQUEST -> number("number")?.let { n -> inRepo { VoicePlan.Call(args("repo" to it, "pr" to n)) } } ?: VoicePlan.Refuse("number is missing.")
            // The headset reads the pull request before either answer: the read-back says what stands in the way, and the
            // merge is pinned to the head the user heard about.
            MERGE_PULL_REQUEST -> number("number")?.let { n ->
                inRepo { if (confirmed) VoicePlan.Call(args("repo" to it, "pr" to n)) else VoicePlan.Confirm("Merge pull request #$n.") }
            } ?: VoicePlan.Refuse("number is missing.")
            WORK_ON_ISSUE -> number("issue")?.let { n ->
                inRepo { r -> if (confirmed) VoicePlan.Call(args("repo" to r, "issue" to n)) else VoicePlan.Confirm("Start an agent on ${title(context, r)} issue #$n.") }
            } ?: VoicePlan.Refuse("issue is missing.")
            READ_CONVERSATION -> session()?.let { VoicePlan.Call(args("sessionId" to it.id, "since" to 0)) } ?: unknown
            START_CONVERSATION -> {
                val prompt = text("prompt") ?: return VoicePlan.Refuse("prompt is missing.")
                val branch = text("branch")
                inRepo { r ->
                    if (confirmed) VoicePlan.Call(args("repo" to r, "prompt" to prompt, "branch" to branch))
                    else VoicePlan.Confirm("Start an agent on ${title(context, r)}${branch?.let { " from $it" }.orEmpty()} with: $prompt")
                }
            }
            SEND_MESSAGE -> {
                val s = session() ?: return unknown
                val message = text("text") ?: return VoicePlan.Refuse("text is missing.")
                if (confirmed) VoicePlan.Call(args("sessionId" to s.id, "text" to message)) else VoicePlan.Confirm("Send to \"${s.title}\": $message")
            }
            STOP_CONVERSATION -> {
                val s = session() ?: return unknown
                if (confirmed) VoicePlan.Call(args("sessionId" to s.id)) else VoicePlan.Confirm("Stop the agent of \"${s.title}\".")
            }
            READ_SCREEN -> VoicePlan.ReadScreen
            SHOW_CONVERSATION -> session()?.let { VoicePlan.Show(ScreenAction.Conversation(it.id, (input["own_panel"] as? JsonPrimitive)?.booleanOrNull == true)) } ?: unknown
            SHOW_PREVIEW -> {
                val s = session() ?: return unknown
                if (s.serveUrl == null) VoicePlan.Refuse("That conversation serves nothing right now; ▶ Run has to be started first.") else VoicePlan.Show(ScreenAction.Preview(s.id))
            }
            SHOW_PULL_REQUEST -> {
                // "This pull request" is the one on screen, or the one of the conversation on screen.
                val n = number("number") ?: context.pullOnScreen?.second ?: context.onScreen?.pullNumber
                    ?: return VoicePlan.Refuse("number is missing, and nothing on screen has a pull request.")
                val view = text("view")?.takeIf { v -> v != "overview" }
                val browser = view == "files" || (input["in_browser"] as? JsonPrimitive)?.booleanOrNull == true
                val pick = if (named == null && number("number") == null) context.pullOnScreen?.first else null
                pick?.let { VoicePlan.Show(ScreenAction.PullRequest(it, n, view, browser)) } ?: inRepo { VoicePlan.Show(ScreenAction.PullRequest(it, n, view, browser)) }
            }
            SHOW_PULL_REQUESTS -> {
                val repo = if (named == null) context.pullOnScreen?.first else null
                repo?.let { VoicePlan.Show(ScreenAction.PullRequests(it)) } ?: inRepo { VoicePlan.Show(ScreenAction.PullRequests(it)) }
            }
            SHOW_ISSUE -> number("issue")?.let { n -> inRepo { VoicePlan.Show(ScreenAction.Issue(it, n)) } } ?: VoicePlan.Refuse("issue is missing.")
            SHOW_NEW_CONVERSATION -> {
                val prompt = text("prompt")
                if (named == null) VoicePlan.Show(ScreenAction.NewConversation(context.onScreen?.repo, prompt))
                else inRepo { VoicePlan.Show(ScreenAction.NewConversation(it, prompt)) }
            }
            SHOW_STATUS_PANEL -> VoicePlan.Show(ScreenAction.StatusPanel)
            GO_HOME -> VoicePlan.Show(ScreenAction.Home)
        }
    }

    private fun title(context: VoiceContext, repo: String) = context.projects.firstOrNull { it.repo == repo }?.title ?: repo.substringAfter('/')

    /**
     * The answer of the call, cut to what the model needs to say it. [args] are the tool's own arguments, [sessions]
     * the conversations known when [readsConversations], and [titles] how each project is named.
     */
    fun summary(answer: JsonObject, input: JsonObject, sessions: List<Session> = emptyList(), titles: (String) -> String = { it.substringAfter('/') }): JsonObject = when (this) {
        LIST_CONVERSATIONS -> {
            var list = Session.list(answer).filter { it.parentId == null }.sortedByDescending { it.createdAt ?: java.time.Instant.EPOCH }
            if ((input["active_only"] as? JsonPrimitive)?.booleanOrNull == true) list = list.filter { it.isActive || it.state.needsYou }
            args("conversations" to list.take(20).map { Voice.conversation(it, titles(it.repo)) }, "total" to list.size)
        }
        READ_CONVERSATION -> {
            val s = Session.parse(answer["session"])
            if (s == null) args("error" to "The server did not return the conversation.") else {
                val transcript = Transcript().apply { append(answer["events"] as? JsonArray) }
                val asking = if (s.awaitingAnswer) transcript.pendingQuestion() else null
                JsonObject(Voice.conversation(s, titles(s.repo), asking) + ("latest" to JsonArray(Voice.latest(transcript.events))))
            }
        }
        LIST_PULL_REQUESTS -> {
            val pulls = answer.objects("pulls").filter { it.int("number") != null }
            args("pull_requests" to pulls.take(15).map { pr ->
                val n = pr.int("number")
                mapOf(
                    "number" to n, "title" to Voice.inline(pr.str("title").orEmpty()), "state" to Voice.pullLine(pr), "labels" to Voice.labels(pr),
                    "ready_to_merge" to Voice.readyToMerge(pr),
                    "conversations" to sessions.filter { it.pullNumber == n }.map { mapOf("session_id" to it.id, "title" to it.title) },
                )
            }, "total" to pulls.size)
        }
        READ_PULL_REQUEST -> if (answer.array("files") == null) args("error" to "The server did not return the pull request's files.") else Voice.changes(answer)
        MERGE_PULL_REQUEST -> args("done" to true, "result" to when (answer.str("status")) {
            "enqueued" -> "Queued to merge into ${input.str("base") ?: "its base"}"
            "pending" -> "GitHub is finishing the merge into ${input.str("base") ?: "its base"}"
            else -> "Merged into ${input.str("base") ?: "its base"}"
        })
        WAITING_FINDINGS -> args("waiting" to Session.list(answer).filter { it.state == Session.State.FINDINGS }.map { s ->
            mapOf("session_id" to s.id, "title" to s.title, "project" to titles(s.repo), "findings" to (s.reviewTriage?.array("findings")?.size ?: 0))
        })
        LIST_ISSUES -> {
            val issues = answer.objects("issues").filter { it.int("number") != null }
            args("issues" to issues.take(20).map { issue ->
                val n = issue.int("number")!!
                val sub = issue.obj("subIssues")
                mapOf(
                    "number" to n, "title" to Voice.inline(issue.str("title").orEmpty()), "labels" to Voice.labels(issue),
                    "pull_requests" to issue.objects("pulls").mapNotNull { it.int("number") },
                    "conversations" to sessions.filter { Voice.onIssue(it, n) }.map { mapOf("session_id" to it.id, "title" to it.title, "status" to Voice.status(it)) },
                    "sub_issues" to sub?.int("total")?.takeIf { it > 0 }?.let { "${sub.int("completed") ?: 0} of $it done" },
                    "epic" to issue.obj("parent")?.int("number"),
                )
            }, "total" to issues.size)
        }
        READ_ISSUE -> {
            val raw = answer.obj("issue")
            val n = raw?.int("number")
            if (raw == null || n == null) args("error" to "The server did not return the issue.") else {
                val sub = raw.obj("subIssues")
                val comments = answer.objects("timeline").filter { it.str("kind") == "commented" && it.nonEmpty("body") != null }
                args(
                    "number" to n, "title" to Voice.inline(raw.str("title").orEmpty()), "state" to Voice.issueState(raw), "labels" to Voice.labels(raw),
                    "type" to raw.nonEmpty("type"), "author" to raw.nonEmpty("author"), "assignees" to raw.strings("assignees").ifEmpty { null },
                    "description" to Voice.cut(Voice.inline(raw.str("body").orEmpty()), 4000),
                    "pull_requests" to raw.objects("pulls").map { pr ->
                        mapOf("number" to pr.int("number"), "title" to Voice.inline(pr.str("title").orEmpty()), "state" to pr.nonEmpty("state"), "draft" to pr.bool("draft")?.takeIf { it })
                    },
                    "conversations" to sessions.filter { Voice.onIssue(it, n) }.map { mapOf("session_id" to it.id, "title" to it.title, "status" to Voice.status(it)) },
                    "epic" to raw.obj("parent")?.let { mapOf("number" to it.int("number"), "title" to Voice.inline(it.str("title").orEmpty())) },
                    "sub_issues" to sub?.int("total")?.takeIf { it > 0 }?.let { "${sub.int("completed") ?: 0} of $it done" },
                    "open_sub_issues" to sub?.objects("items")?.filter { it.str("state") == "open" }?.take(10)
                        ?.map { mapOf("number" to it.int("number"), "title" to Voice.inline(it.str("title").orEmpty())) },
                    "comments" to comments.takeLast(5).map { c ->
                        mapOf("from" to (c.nonEmpty("actor") ?: "a deleted account"), "text" to Voice.cut(Voice.inline(c.str("body").orEmpty()), 800))
                    },
                    "comments_total" to raw.int("comments"),
                )
            }
        }
        START_CONVERSATION, WORK_ON_ISSUE -> Session.parse(answer["session"])?.let { args("done" to true, "session_id" to it.id, "title" to it.title) } ?: args("done" to true)
        SEND_MESSAGE -> args("done" to true, "delivery" to Voice.sent(Session.parse(answer["session"])))
        STOP_CONVERSATION -> args("done" to true)
        else -> args("done" to true)
    }

    companion object {
        fun of(wire: String): VoiceTool? = entries.firstOrNull { it.wire == wire }
    }
}

// MARK: - Merging

/** What merging a pull request takes, as the headset read it before the read-back. */
sealed interface VoiceMerge {
    data class Refuse(val why: String) : VoiceMerge
    /** The `merge_pull` arguments, pinned to the head read now; the base it goes into; and what to read back. */
    data class Ready(val arguments: JsonObject, val base: String, val readBack: String) : VoiceMerge

    companion object {
        /**
         * Reads `pull`'s answer, the first page of `pull_files` when there is one, and the board row when it is on the
         * board: an open, non-draft pull request merges, with what stands in its way said first.
         */
        fun check(number: Int, repo: String, pull: JsonObject, files: JsonObject?, row: JsonObject?): VoiceMerge {
            val pr = pull.obj("pr") ?: JsonObject(emptyMap())
            val live = files?.obj("pr")
            val state = pr.str("state") ?: "open"
            if (state != "open") return Refuse("Pull request #$number is already $state.")
            if (pr.bool("draft") == true || row?.bool("draft") == true) return Refuse("Pull request #$number is a draft; it cannot merge until it is marked ready.")
            val head = live?.nonEmpty("headSha") ?: pr.nonEmpty("headSha")
            val base = pr.nonEmpty("baseRef")
            if (head == null || base == null) return Refuse("Pull request #$number could not be read.")
            val notes = mutableListOf<String>()
            if (live != null) {
                val mergeable: JsonElement? = live["mergeable"]
                if ((mergeable as? JsonPrimitive)?.booleanOrNull == false) notes += "This branch has conflicts that must be resolved before it can merge."
                if (mergeable == JsonNull) notes += "GitHub is still checking whether this branch can merge."
                when (live.str("mergeableState")) {
                    "blocked" -> notes += "GitHub reports this pull request as blocked: a required review or check is missing."
                    "behind" -> notes += "This branch is behind its base branch and may need updating before it can merge."
                }
            }
            val failed = pr.obj("checks")?.int("failed") ?: 0
            val pending = pr.obj("checks")?.int("pending") ?: 0
            if (failed > 0) notes += if (failed == 1) "1 check is failing." else "$failed checks are failing."
            if (pending > 0) notes += if (pending == 1) "1 check is still running." else "$pending checks are still running."
            if (row != null && Voice.labels(row).none { it.equals(Voice.APPROVED_LABEL, ignoreCase = true) }) notes += "It does not carry the ${Voice.APPROVED_LABEL} label."
            if (changesRequested(row?.str("reviewDecision"), pr.objects("reviews"))) notes += "A reviewer has requested changes."
            val allowed = live?.strings("mergeMethods").orEmpty()
            val method = if (allowed.isEmpty() || "squash" in allowed) "squash" else listOf("merge", "rebase").firstOrNull { it in allowed } ?: "squash"
            val title = pr.nonEmpty("title")?.let { ", ${Voice.inline(it)}," }.orEmpty()
            val how = mapOf("merge" to "with a merge commit", "rebase" to "rebased")[method] ?: "squashed"
            val readBack = (listOf("Merge pull request #$number$title into $base, $how.") + notes).joinToString(" ")
            return Ready(args("repo" to repo, "pr" to number, "headSha" to head, "baseRef" to base, "method" to method), base, readBack)
        }

        /** GitHub's own decision wins; without one, a review that requested changes outweighs an approval. */
        internal fun changesRequested(decision: String?, reviews: List<JsonObject>): Boolean {
            val d = decision.orEmpty().lowercase(Locale.ROOT)
            if (d == "approved") return false
            if (d == "changes_requested") return true
            return reviews.any { it.str("state").orEmpty().lowercase(Locale.ROOT) == "changes_requested" }
        }
    }
}
