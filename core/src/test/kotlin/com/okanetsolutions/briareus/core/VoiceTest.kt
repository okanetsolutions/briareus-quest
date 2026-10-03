package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    private val web = Project("acme/web", "Website", false, emptyList())
    private val api = Project("acme/api", "", false, emptyList())
    private val projects = listOf(web, api)
    private fun session(id: String, repo: String, extra: String = "") =
        Session(json("""{"id":"$id","repo":"$repo","title":"Login page","status":"idle"$extra}"""))
    private val login = session("s1", "acme/web", ""","prStatus":{"number":42,"state":"open","url":"https://github.com/acme/web/pull/42"},"serveLinks":[{"url":"https://preview.example"}]""")
    private val context = VoiceContext(projects, mapOf("s1" to login), onScreen = null)
    private fun plan(tool: VoiceTool, input: String, ctx: VoiceContext = context) = tool.plan(json(input), ctx)

    @Test fun sessionCarriesTheVoiceTheAudioAndEveryTool() {
        val s = Voice.session("cedar", projects)
        assertEquals("realtime", s.str("type"))
        assertEquals(Voice.MODEL, s.str("model"))
        assertEquals("cedar", s.obj("audio")!!.obj("output")!!.str("voice"))
        assertEquals(Voice.SAMPLE_RATE, s.obj("audio")!!.obj("input")!!.obj("format")!!.int("rate"))
        assertEquals(Voice.TRANSCRIBER, s.obj("audio")!!.obj("input")!!.obj("transcription")!!.str("model"))
        assertEquals(VoiceTool.entries.size, s.objects("tools").size)
        assertTrue(s.str("instructions")!!.contains("Website (acme/web)"))
        // An unknown voice falls back to the default.
        assertEquals(Voice.DEFAULT_VOICE, Voice.session("nobody", projects).obj("audio")!!.obj("output")!!.str("voice"))
    }

    @Test fun toolDefinitionsAreStrictFunctions() {
        for (tool in VoiceTool.entries) {
            val d = tool.definition
            assertEquals("function", d.str("type"))
            assertEquals(tool, VoiceTool.of(d.str("name")!!))
            val parameters = d.obj("parameters")!!
            assertEquals(false, parameters.bool("additionalProperties"))
            val properties = parameters.obj("properties")!!.keys
            assertTrue(tool.wire, properties.containsAll(parameters.strings("required")))
            if (tool.changes) assertTrue(tool.wire, "confirmed" in parameters.strings("required"))
        }
        assertEquals(listOf("overview", "files", "checks", "commits"), VoiceTool.SHOW_PULL_REQUEST.definition.obj("parameters")!!.obj("properties")!!.obj("view")!!.strings("enum"))
    }

    @Test fun picksTheProjectByNameRepositoryScreenOrTheOnlyOne() {
        assertEquals(Voice.Pick.Found("acme/web"), Voice.project("website", projects))
        assertEquals(Voice.Pick.Found("acme/api"), Voice.project("acme/api", projects))
        assertEquals(Voice.Pick.Found("acme/api"), Voice.project("API", projects))
        assertEquals(Voice.Pick.Found("acme/web"), Voice.project("web", projects))
        assertEquals(Voice.Pick.Found("acme/web"), Voice.project(null, projects, onScreen = "acme/web"))
        assertEquals(Voice.Pick.Found("acme/api"), Voice.project(null, listOf(api)))
        assertTrue(Voice.project(null, projects) is Voice.Pick.Refuse)
        assertTrue(Voice.project("mobile", projects) is Voice.Pick.Refuse)
        // "acme" matches both: a question, not a guess.
        assertTrue(Voice.project("acme", projects) is Voice.Pick.Refuse)
    }

    @Test fun changesAreReadBackUntilConfirmed() {
        assertEquals(VoicePlan.Confirm("Send to \"Login page\": ship it"), plan(VoiceTool.SEND_MESSAGE, """{"session_id":"s1","text":" ship it ","confirmed":false}"""))
        assertEquals(VoicePlan.Call(args("sessionId" to "s1", "text" to "ship it")), plan(VoiceTool.SEND_MESSAGE, """{"session_id":"s1","text":"ship it","confirmed":true}"""))
        assertEquals(
            VoicePlan.Confirm("Start an agent on Website from dev with: fix the footer"),
            plan(VoiceTool.START_CONVERSATION, """{"prompt":"fix the footer","project":"website","branch":"dev","confirmed":false}"""),
        )
        assertEquals(
            VoicePlan.Call(args("repo" to "acme/web", "prompt" to "fix the footer")),
            plan(VoiceTool.START_CONVERSATION, """{"prompt":"fix the footer","project":"website","confirmed":true}"""),
        )
        assertEquals(VoicePlan.Confirm("Merge pull request #42."), plan(VoiceTool.MERGE_PULL_REQUEST, """{"number":42,"project":"web","confirmed":false}"""))
        assertEquals(VoicePlan.Call(args("repo" to "acme/web", "pr" to 42)), plan(VoiceTool.MERGE_PULL_REQUEST, """{"number":42,"project":"web","confirmed":true}"""))
        assertEquals(VoicePlan.Confirm("Start an agent on api issue #7."), plan(VoiceTool.WORK_ON_ISSUE, """{"issue":7,"project":"api","confirmed":false}"""))
        assertEquals(VoicePlan.Confirm("Stop the agent of \"Login page\"."), plan(VoiceTool.STOP_CONVERSATION, """{"session_id":"s1","confirmed":false}"""))
    }

    @Test fun anIdIsNeverGuessed() {
        for (tool in listOf(VoiceTool.READ_CONVERSATION, VoiceTool.SEND_MESSAGE, VoiceTool.STOP_CONVERSATION, VoiceTool.SHOW_CONVERSATION, VoiceTool.SHOW_PREVIEW)) {
            assertTrue(tool.wire, plan(tool, """{"session_id":"invented","text":"hi","confirmed":true}""") is VoicePlan.Refuse)
        }
        assertTrue(plan(VoiceTool.READ_ISSUE, """{"project":"web"}""") is VoicePlan.Refuse)
        // Without a project named, on screen, or alone, the model is asked which.
        assertTrue(plan(VoiceTool.LIST_PULL_REQUESTS, "{}") is VoicePlan.Refuse)
        // Listing conversations spans every project unless one is named.
        assertEquals(VoicePlan.Call(JsonObject(emptyMap())), plan(VoiceTool.LIST_CONVERSATIONS, "{}"))
        assertEquals(VoicePlan.Call(args("repo" to "acme/api")), plan(VoiceTool.LIST_CONVERSATIONS, """{"project":"api"}"""))
    }

    @Test fun screenToolsShowWithoutConfirmation() {
        assertEquals(VoicePlan.Show(ScreenAction.Conversation("s1", false)), plan(VoiceTool.SHOW_CONVERSATION, """{"session_id":"s1"}"""))
        assertEquals(VoicePlan.Show(ScreenAction.Conversation("s1", true)), plan(VoiceTool.SHOW_CONVERSATION, """{"session_id":"s1","own_panel":true}"""))
        assertEquals(VoicePlan.Show(ScreenAction.Preview("s1")), plan(VoiceTool.SHOW_PREVIEW, """{"session_id":"s1"}"""))
        assertEquals(VoicePlan.Show(ScreenAction.Issue("acme/api", 3)), plan(VoiceTool.SHOW_ISSUE, """{"issue":3,"project":"api"}"""))
        assertEquals(VoicePlan.Show(ScreenAction.PullRequests("acme/api")), plan(VoiceTool.SHOW_PULL_REQUESTS, """{"project":"api"}"""))
        assertEquals(VoicePlan.Show(ScreenAction.StatusPanel), plan(VoiceTool.SHOW_STATUS_PANEL, "{}"))
        assertEquals(VoicePlan.Show(ScreenAction.Home), plan(VoiceTool.GO_HOME, "{}"))
        assertEquals(VoicePlan.ReadScreen, plan(VoiceTool.READ_SCREEN, "{}"))
        assertEquals(
            VoicePlan.Show(ScreenAction.NewConversation("acme/api", "add rate limits")),
            plan(VoiceTool.SHOW_NEW_CONVERSATION, """{"project":"api","prompt":"add rate limits"}"""),
        )
        // A conversation serving nothing has no preview to show.
        val idle = VoiceContext(projects, mapOf("s2" to session("s2", "acme/api")))
        assertTrue(plan(VoiceTool.SHOW_PREVIEW, """{"session_id":"s2"}""", idle) is VoicePlan.Refuse)
    }

    @Test fun thisPullRequestIsTheOneOnScreen() {
        val watching = context.copy(onScreen = login)
        // In the main window, unless its files (which the app does not list) or the browser are asked for.
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest("acme/web", 42, null, false)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"view":"overview"}""", watching))
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest("acme/web", 42, "files", true)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"view":"files"}""", watching))
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest("acme/web", 42, "checks", true)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"view":"checks","in_browser":true}""", watching))
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest("acme/api", 9, null, false)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"number":9,"project":"api"}""", watching))
        // A pull request in the main window is "this" one; its project's list is where the list's project comes from.
        val onPull = context.copy(pullOnScreen = "acme/api" to 7)
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest("acme/api", 7, null, true)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"in_browser":true}""", onPull))
        val onList = context.copy(pullOnScreen = "acme/api" to null)
        assertTrue(plan(VoiceTool.SHOW_PULL_REQUEST, "{}", onList) is VoicePlan.Refuse)
        assertEquals(VoicePlan.Show(ScreenAction.PullRequests("acme/api")), plan(VoiceTool.SHOW_PULL_REQUESTS, "{}", onList))
        assertTrue(plan(VoiceTool.SHOW_PULL_REQUEST, "{}") is VoicePlan.Refuse)
        // The new conversation form opens on the project on screen.
        assertEquals(VoicePlan.Show(ScreenAction.NewConversation("acme/web", null)), plan(VoiceTool.SHOW_NEW_CONVERSATION, "{}", watching))
    }

    @Test fun pageAddresses() {
        assertEquals("https://github.com/acme/web/pull/4/files", Voice.pullUrl("acme/web", 4, null, "files"))
        assertEquals("https://ghe.example/acme/web/pull/4/checks", Voice.pullUrl("acme/web", 4, "https://ghe.example/acme/web/pull/4/", "checks"))
        assertEquals("https://github.com/acme/web/pull/4", Voice.pullUrl("acme/web", 4, "javascript:alert(1)", null))
        assertEquals("https://github.com/acme/web/issues/8", Voice.issueUrl("acme/web", 8))
    }

    @Test fun readBackKeysIgnoreCaseAndPunctuationButNotTheTarget() {
        val a = Voice.readBackKey(VoiceTool.SEND_MESSAGE, json("""{"session_id":"s1","text":"Ship it!","confirmed":false}"""))
        val b = Voice.readBackKey(VoiceTool.SEND_MESSAGE, json("""{"session_id":"s1","text":"ship   it","confirmed":true}"""))
        val c = Voice.readBackKey(VoiceTool.SEND_MESSAGE, json("""{"session_id":"s2","text":"ship it"}"""))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(
            Voice.readBackKey(VoiceTool.MERGE_PULL_REQUEST, json("""{"number":1}""")),
            Voice.readBackKey(VoiceTool.MERGE_PULL_REQUEST, json("""{"number":2}""")),
        )
    }

    @Test fun summariesSayWhatTheModelNeeds() {
        val list = VoiceTool.LIST_CONVERSATIONS.summary(
            json("""{"sessions":[
                {"id":"a","repo":"acme/web","title":"A","status":"running","createdAt":"2026-01-02T00:00:00Z"},
                {"id":"b","repo":"acme/web","title":"B","status":"closed","createdAt":"2026-01-03T00:00:00Z"},
                {"id":"w","repo":"acme/web","title":"W","status":"running","parentId":"a"}]}"""),
            json("""{"active_only":true}"""),
        )
        assertEquals(1, list.int("total"))
        assertEquals("Working", list.objects("conversations")[0].str("status"))

        val read = VoiceTool.READ_CONVERSATION.summary(
            json("""{"session":{"id":"a","repo":"acme/web","title":"A","status":"idle","awaitingAnswer":true},
                "events":[{"seq":1,"kind":"user","text":"Do it"},{"seq":2,"kind":"ask","question":"Which **colour**?","options":[{"label":"Red"},{"label":"Blue"}]}]}"""),
            JsonObject(emptyMap()),
        )
        assertEquals("Which colour?", read.str("question"))
        assertEquals(listOf("Red", "Blue"), read.strings("options"))
        assertEquals("Asks you a question", read.str("status"))
        assertEquals(2, read.objects("latest").size)

        val pulls = VoiceTool.LIST_PULL_REQUESTS.summary(
            json("""{"pulls":[
                {"number":42,"title":"Login","checks":"success","mergeable":"mergeable","labels":[{"name":"code-approved"}],"author":"ana"},
                {"number":43,"title":"Wip","checks":"failure","draft":true,"labels":[]}]}"""),
            JsonObject(emptyMap()), listOf(login),
        )
        val (ready, draft) = pulls.objects("pull_requests")
        assertEquals(true, ready.bool("ready_to_merge"))
        assertEquals("#42 · Checks passed · @ana", ready.str("state"))
        assertEquals("s1", ready.objects("conversations")[0].str("session_id"))
        assertEquals(false, draft.bool("ready_to_merge"))
        assertEquals("#43 · Draft · Checks failed", draft.str("state"))

        val files = VoiceTool.READ_PULL_REQUEST.summary(
            json("""{"pr":{"changedFiles":3,"additions":10,"deletions":2},"files":[
                {"filename":"src/a.kt","status":"added","additions":5},{"filename":"src/b.kt","additions":5},{"filename":"README.md","deletions":2}],"nextPage":2}"""),
            JsonObject(emptyMap()),
        )
        assertEquals(3, files.int("changed_files"))
        assertEquals("src", files.objects("by_folder")[0].str("folder"))
        assertEquals("the first 3 only", files.str("files_listed"))

        assertEquals("Queued for the next turn", VoiceTool.SEND_MESSAGE.summary(json("""{"session":{"id":"a","status":"running"}}"""), JsonObject(emptyMap())).str("delivery"))
        assertEquals("Merged into main", VoiceTool.MERGE_PULL_REQUEST.summary(json("{}"), json("""{"base":"main"}""")).str("result"))
    }

    @Test fun issuesAndTheirConversations() {
        val board = json("""{"issues":[{"number":7,"title":"Rate limits","labels":["api"],"subIssues":{"total":3,"completed":1},"pulls":[{"number":9}],"parent":{"number":2,"title":"Epic"}}]}""")
        val onIt = Session(json("""{"id":"x","repo":"acme/api","title":"Issue #7: Rate limits","status":"running"}"""))
        val issues = VoiceTool.LIST_ISSUES.summary(board, JsonObject(emptyMap()), listOf(onIt))
        val issue = issues.objects("issues")[0]
        assertEquals("1 of 3 done", issue.str("sub_issues"))
        assertEquals(2, issue.int("epic"))
        assertEquals("x", issue.objects("conversations")[0].str("session_id"))

        val start = Voice.issueStart(board, 7, "acme/api")!!
        assertTrue(start.str("prompt")!!.startsWith("Issue #7: Rate limits\n"))
        assertTrue(start.str("prompt")!!.contains("sub-issue of acme/api#2 (Epic)"))
        assertEquals("issue", start.str("activity"))
        assertNull(Voice.issueStart(board, 8, "acme/api"))

        val read = VoiceTool.READ_ISSUE.summary(
            json("""{"issue":{"number":7,"title":"Rate limits","state":"closed","stateReason":"not_planned","body":"Use **tokens**","comments":2},
                "timeline":[{"kind":"commented","actor":"ana","body":"Agreed"},{"kind":"labeled"}]}"""),
            JsonObject(emptyMap()),
        )
        assertEquals("closed as not planned", read.str("state"))
        assertEquals("Use tokens", read.str("description"))
        assertEquals(1, read.objects("comments").size)
    }

    @Test fun mergeChecksWhatStandsInTheWay() {
        val pull = json("""{"pr":{"state":"open","headSha":"abc","baseRef":"main","title":"Login","checks":{"failed":1,"pending":0},"reviews":[{"state":"CHANGES_REQUESTED"}]}}""")
        val files = json("""{"pr":{"headSha":"def","mergeable":false,"mergeableState":"blocked","mergeMethods":["merge","rebase"]}}""")
        val row = json("""{"number":4,"labels":[]}""")
        val ready = VoiceMerge.check(4, "acme/web", pull, files, row) as VoiceMerge.Ready
        assertEquals("def", ready.arguments.str("headSha"))
        assertEquals("merge", ready.arguments.str("method"))
        assertEquals("main", ready.base)
        for (note in listOf("Login", "with a merge commit", "conflicts", "blocked", "1 check is failing", "code-approved", "requested changes")) {
            assertTrue(note, ready.readBack.contains(note))
        }
        // A clean, approved one reads back only what it does.
        val clean = VoiceMerge.check(4, "acme/web", json("""{"pr":{"state":"open","headSha":"abc","baseRef":"main"}}"""), null, json("""{"labels":["code-approved"]}""")) as VoiceMerge.Ready
        assertEquals("Merge pull request #4 into main, squashed.", clean.readBack)
        assertTrue(VoiceMerge.check(4, "r", json("""{"pr":{"state":"merged"}}"""), null, null) is VoiceMerge.Refuse)
        assertTrue(VoiceMerge.check(4, "r", json("""{"pr":{"state":"open","draft":true,"headSha":"a","baseRef":"m"}}"""), null, null) is VoiceMerge.Refuse)
        assertFalse(VoiceMerge.changesRequested("APPROVED", listOf(json("""{"state":"changes_requested"}"""))))
    }

    @Test fun costAddsResponsesAndTranscriptions() {
        val cost = VoiceCost()
        assertEquals("≈ $0.0000 · 0 tokens", cost.line)
        cost.addResponse(json("""{"input_tokens":1000,"output_tokens":500,
            "input_token_details":{"text_tokens":800,"audio_tokens":200,"cached_tokens_details":{"text_tokens":400}},
            "output_token_details":{"text_tokens":100,"audio_tokens":400}}"""))
        // 400×0.60 + 400×0.06 + 200×10 + 100×2.40 + 400×20, per million.
        assertEquals((400 * 0.60 + 400 * 0.06 + 200 * 10 + 100 * 2.40 + 400 * 20) / 1_000_000, cost.dollars, 1e-12)
        cost.addTranscription(json("""{"type":"tokens","input_tokens":100,"output_tokens":20}"""))
        cost.addTranscription(json("""{"type":"duration","seconds":3}"""))
        assertEquals(1620, cost.tokens)
        assertEquals("≈ $0.011 · 1.6k tokens", cost.line)
    }

    @Test fun inlineMarkdownReadsAsWords() {
        assertEquals("See the docs and run it now", Voice.inline("See [the docs](https://x)  and\nrun `it` **now**"))
        assertEquals("ab…", Voice.cut("abc", 2))
    }
}
