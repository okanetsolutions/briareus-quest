package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    private val web = Project("acme/web", "Website", false, emptyList())
    private val api = Project("acme/api", "", false, emptyList())
    private fun session(id: String, repo: String, extra: String = "") =
        Session(json("""{"id":"$id","repo":"$repo","title":"Login page","status":"idle"$extra}"""))
    private val login = session("s1", "acme/web", ""","prStatus":{"number":42,"state":"open","url":"https://github.com/acme/web/pull/42"},"serveLinks":[{"url":"https://preview.example"}]""")
    private val context = VoiceContext(web, mapOf("s1" to login), onScreen = null)
    private val apiContext = context.copy(project = api)
    private fun plan(tool: VoiceTool, input: String, ctx: VoiceContext = context) = tool.plan(json(input), ctx)

    @Test fun codeReviewStartsAnActionInsideTheBoundProject() {
        val tool = VoiceTool.START_CODE_REVIEW
        assertEquals("review", tool.operation)
        assertTrue(tool.changes)
        assertEquals(VoicePlan.Call(args("repo" to web.repo, "pr" to 42)), plan(tool, """{"number":42}"""))
        assertTrue(plan(tool, "{}") is VoicePlan.Refuse)
        assertTrue(plan(tool, """{"number":42,"project":"acme/api"}""") is VoicePlan.Refuse)
        val result = tool.summary(args("session" to mapOf("id" to "review1", "repo" to web.repo)), args())
        assertEquals("review1", result.str("session_id"))
        assertTrue(tool.summary(args(), args()).str("error") != null)
    }

    @Test fun sessionCarriesOnlyTheBoundProjectAndDirectActionInstructions() {
        val s = Voice.session("cedar", web)
        assertEquals("realtime", s.str("type"))
        assertEquals(Voice.MODEL, s.str("model"))
        assertEquals("cedar", s.obj("audio")!!.obj("output")!!.str("voice"))
        assertEquals(Voice.SAMPLE_RATE, s.obj("audio")!!.obj("input")!!.obj("format")!!.int("rate"))
        assertEquals(Voice.TRANSCRIBER, s.obj("audio")!!.obj("input")!!.obj("transcription")!!.str("model"))
        assertEquals(VoiceTool.entries.size, s.objects("tools").size)
        val instructions = s.str("instructions")!!
        assertTrue(instructions.contains("Website (acme/web)"))
        assertFalse(instructions.contains("acme/api"))
        assertTrue(instructions.contains("Do not ask for confirmation"))
        assertTrue(instructions.contains("Never open or suggest an external browser"))
        assertTrue(instructions.contains("show_conversation with own_panel=true"))
        assertEquals(Voice.DEFAULT_VOICE, Voice.session("nobody", web).obj("audio")!!.obj("output")!!.str("voice"))
    }

    @Test fun toolDefinitionsOfferNeitherConfirmationNorBrowserNorProjectSwitches() {
        for (tool in VoiceTool.entries) {
            val d = tool.definition
            assertEquals("function", d.str("type"))
            assertEquals(tool, VoiceTool.of(d.str("name")!!))
            val parameters = d.obj("parameters")!!
            assertEquals(false, parameters.bool("additionalProperties"))
            val properties = parameters.obj("properties")!!.keys
            assertTrue(tool.wire, properties.containsAll(parameters.strings("required")))
            assertFalse(tool.wire, properties.any { it in setOf("confirmed", "in_browser", "project") })
        }
    }

    @Test fun requestedWritesRunWithoutASecondYes() {
        assertEquals(VoicePlan.Call(args("sessionId" to "s1", "text" to "ship it")), plan(VoiceTool.SEND_MESSAGE, """{"session_id":"s1","text":" ship it "}"""))
        assertEquals(VoicePlan.Call(args("repo" to web.repo, "prompt" to "fix", "branch" to "dev")), plan(VoiceTool.START_CONVERSATION, """{"prompt":"fix","branch":"dev"}"""))
        assertEquals(VoicePlan.Call(args("repo" to web.repo, "issue" to 7)), plan(VoiceTool.WORK_ON_ISSUE, """{"issue":7}"""))
        assertEquals(VoicePlan.Call(args("sessionId" to "s1")), plan(VoiceTool.STOP_CONVERSATION, """{"session_id":"s1"}"""))
    }

    @Test fun everyToolRefusesAnUnboundOrDifferentProject() {
        val fields = args("session_id" to "s1", "number" to 42, "issue" to 7, "text" to "hi", "prompt" to "fix")
        for (tool in VoiceTool.entries) {
            assertTrue(tool.wire, tool.plan(fields, context.copy(project = null)) is VoicePlan.Refuse)
            assertTrue(tool.wire, tool.plan(JsonObject(fields + args("project" to api.repo)), context) is VoicePlan.Refuse)
        }
        for (tool in listOf(VoiceTool.LIST_CONVERSATIONS, VoiceTool.WAITING_FINDINGS, VoiceTool.LIST_ISSUES, VoiceTool.LIST_PULL_REQUESTS)) {
            assertEquals(tool.wire, VoicePlan.Call(args("repo" to web.repo)), plan(tool, "{}"))
        }
    }

    @Test fun otherProjectSessionsAndScreensCannotEscapeTheBinding() {
        for (tool in listOf(VoiceTool.READ_CONVERSATION, VoiceTool.SEND_MESSAGE, VoiceTool.STOP_CONVERSATION, VoiceTool.SHOW_CONVERSATION, VoiceTool.SHOW_PREVIEW)) {
            assertTrue(tool.wire, plan(tool, """{"session_id":"s1","text":"hi"}""", apiContext) is VoicePlan.Refuse)
            assertTrue(tool.wire, plan(tool, """{"session_id":"invented","text":"hi"}""") is VoicePlan.Refuse)
        }
        val otherScreen = context.copy(onScreen = session("other", api.repo), pullOnScreen = api.repo to 7)
        assertTrue(plan(VoiceTool.SHOW_PULL_REQUEST, "{}", otherScreen) is VoicePlan.Refuse)
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest(web.repo, 9, null)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"number":9}""", otherScreen))
        assertEquals(VoicePlan.Show(ScreenAction.NewConversation(web.repo, null)), plan(VoiceTool.SHOW_NEW_CONVERSATION, "{}", otherScreen))
    }

    @Test fun nativeNavigationKeepsTheProjectAndSeparatePanelRequest() {
        assertEquals(VoicePlan.Show(ScreenAction.Conversation("s1", true)), plan(VoiceTool.SHOW_CONVERSATION, """{"session_id":"s1","own_panel":true}"""))
        for (view in listOf("files", "reviews", "commits")) {
            assertEquals(VoicePlan.Show(ScreenAction.PullRequest(web.repo, 42, view, true)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"view":"$view","own_panel":true}""", context.copy(onScreen = login)))
        }
        // A stale browser argument never launches a browser.
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest(web.repo, 42, null)), plan(VoiceTool.SHOW_PULL_REQUEST, """{"number":42,"in_browser":true}"""))
        assertEquals(VoicePlan.Show(ScreenAction.Issue(api.repo, 3, true)), plan(VoiceTool.SHOW_ISSUE, """{"issue":3,"own_panel":true}""", apiContext))
        assertEquals(VoicePlan.Show(ScreenAction.Issues(web.repo, true)), plan(VoiceTool.SHOW_ISSUES, """{"own_panel":true}"""))
        assertEquals(VoicePlan.Show(ScreenAction.PullRequests(web.repo, true)), plan(VoiceTool.SHOW_PULL_REQUESTS, """{"own_panel":true}"""))
        assertEquals(VoicePlan.Show(ScreenAction.Home(web.repo)), plan(VoiceTool.GO_HOME, "{}"))
        assertEquals(VoicePlan.Show(ScreenAction.Preview(login.id)), plan(VoiceTool.SHOW_PREVIEW, """{"session_id":"s1"}"""))
        assertEquals(VoicePlan.ReadScreen, plan(VoiceTool.READ_SCREEN, "{}"))
    }

    @Test fun checksNavigationUsesOverviewAndIsNotOfferedAsATab() {
        val tool = VoiceTool.SHOW_PULL_REQUEST
        assertFalse(tool.definition.obj("parameters")!!.obj("properties")!!.obj("view")!!.strings("enum").contains("checks"))
        assertEquals(VoicePlan.Show(ScreenAction.PullRequest(web.repo, 42, null, true)), plan(tool, """{"number":42,"view":"checks","own_panel":true}"""))
    }

    @Test fun missingDetailsStillRequireClarification() {
        assertTrue(plan(VoiceTool.READ_ISSUE, "{}") is VoicePlan.Refuse)
        assertTrue(plan(VoiceTool.START_CONVERSATION, "{}") is VoicePlan.Refuse)
        assertTrue(plan(VoiceTool.SHOW_PULL_REQUEST, "{}") is VoicePlan.Refuse)
        assertTrue(plan(VoiceTool.SHOW_PREVIEW, """{"session_id":"s2"}""", context.copy(sessions = mapOf("s2" to session("s2", web.repo)))) is VoicePlan.Refuse)
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

    @Test fun statusPanelCannotBeAdvertisedOrDispatchedByVoice() {
        val session = Voice.session("cedar", web)
        assertFalse(session.objects("tools").any { it.str("name") == "show_status_panel" })
        assertNull(VoiceTool.of("show_status_panel"))
        assertFalse(session.str("instructions").orEmpty().contains("show_status_panel"))
    }

    @Test fun mergeCannotBeAdvertisedOrDispatchedByVoice() {
        val session = Voice.session("cedar", web)
        assertFalse(session.objects("tools").any { it.str("name") == "merge_pull_request" })
        assertNull(VoiceTool.of("merge_pull_request"))
        assertFalse(VoiceTool.entries.any { it.operation == "merge_pull" })
        assertFalse(session.str("instructions").orEmpty().contains("merge_pull_request"))
        assertTrue(session.str("instructions").orEmpty().contains("You cannot merge pull requests"))
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
