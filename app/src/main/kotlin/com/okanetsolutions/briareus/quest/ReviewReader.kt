package com.okanetsolutions.briareus.quest

import com.okanetsolutions.briareus.core.Board
import com.okanetsolutions.briareus.core.Issue
import com.okanetsolutions.briareus.core.PullReviewStart
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.core.PullFiles
import com.okanetsolutions.briareus.core.args

/** Typed reads for the issue/file panels and the PR code review action. */
class ReviewReader(private val store: Store) {
    suspend fun board(repo: String): Board = Board.parse(read("pulls", repo))

    suspend fun issue(repo: String, number: Int): Issue =
        Issue.parse(read("issue", repo, "issue", number)) ?: error("The server did not return the issue.")

    suspend fun files(repo: String, number: Int, previous: PullFiles? = null): PullFiles {
        check(store.can("pull_files")) { "This server or token cannot read pull request files." }
        val client = store.client ?: error("Pair with a server first.")
        val page = PullFiles.parse(client.call("pull_files", previous?.arguments(repo, number) ?: args("repo" to repo, "pr" to number)))
        return previous?.append(page) ?: page
    }

    suspend fun start(repo: String, number: Int): Session? {
        if (!store.can("review") || !store.can("pull")) { store.say("This server or token cannot start a code review."); return null }
        val arguments = try { PullReviewStart.arguments(repo, number, read("pull", repo, "pr", number)) } catch (e: Exception) {
            store.failed(e); return null
        }
        return store.mutate("review", arguments)?.let { answer ->
            Session.parse(answer["session"]).also { if (it == null) store.say("The review request completed, but the server returned no conversation.") }
        }
    }

    private suspend fun read(name: String, repo: String, key: String? = null, number: Int? = null) = run {
        check(store.can(name)) { "This server or token cannot read that view." }
        val arguments = if (key == null) args("repo" to repo) else args("repo" to repo, key to number)
        (store.client ?: error("Pair with a server first.")).call(name, arguments)
    }
}
