package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject

/** Resolves the current PR branch before starting the same review action as the desktop client. */
object PullReviewStart {
    fun arguments(repo: String, number: Int, answer: JsonObject): JsonObject {
        val pull = PullOverview.parse(answer)
        check(pull != null && pull.number == number) { "The server did not return pull request #$number." }
        check(pull.state == "open" || pull.state == "draft") { "Pull request #$number is no longer open." }
        val branch = pull.headRef
        check(!branch.isNullOrBlank()) { "The server did not identify the branch to review." }
        return args("repo" to repo, "prNumber" to number, "branch" to branch)
    }
}
