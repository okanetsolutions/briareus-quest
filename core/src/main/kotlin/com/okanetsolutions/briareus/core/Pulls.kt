package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject
import java.time.Instant

// MARK: - The board

/** A GitHub label as the board sends it; [color] is GitHub's six hex digits without the `#`, or null. */
data class PullLabel(val name: String, val color: String?) {
    /** The colour as 0xAARRGGBB, or null when GitHub sent none or one that cannot be read. */
    val argb: Long? get() = color?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it }

    companion object {
        fun list(o: JsonObject, key: String = "labels"): List<PullLabel> =
            o.objects(key).mapNotNull { l -> l.nonEmpty("name")?.let { PullLabel(it, l.nonEmpty("color")) } }
    }
}

/** A pull request or issue another one points at: `{ number, title, state, url }`. */
data class PullRef(val number: Int, val title: String, val state: String, val url: String?) {
    companion object {
        fun list(o: JsonObject, key: String): List<PullRef> = o.objects(key).mapNotNull { r ->
            PullRef(r.int("number") ?: return@mapNotNull null, r.str("title").orEmpty(), r.str("state").orEmpty(), r.nonEmpty("url"))
        }
    }
}

/** One open pull request on a project's board (`GET /pulls`). */
data class PullRow(val raw: JsonObject) {
    val number: Int get() = raw.int("number") ?: 0
    val title: String get() = raw.nonEmpty("title") ?: "Pull request #$number"
    val url: String? get() = raw.nonEmpty("url")
    val draft: Boolean get() = raw.bool("draft") == true
    val author: String? get() = raw.nonEmpty("author")
    val assignees: List<String> get() = raw.strings("assignees")
    val branch: String? get() = raw.nonEmpty("branch")
    val baseBranch: String? get() = raw.nonEmpty("baseBranch")
    val updatedAt: Instant? get() = parseTime(raw.str("updatedAt"))
    val labels: List<PullLabel> get() = PullLabel.list(raw)
    val issues: List<PullRef> get() = PullRef.list(raw, "issues")

    /** The head commit's check rollup: `success`, `failure`, `error`, `pending`, `expected`, or null with no checks. */
    val checks: String? get() = raw.nonEmpty("checks")

    /** True when GitHub says the branch conflicts with its base. */
    val conflicting: Boolean get() = raw.str("mergeable") == "conflicting"

    /** The errand the board would run next (`review`, `qa`, `implement-feedback`, `solve-conflicts`), or null. */
    val recommended: String? get() = raw.nonEmpty("recommended")

    /** "2/3" when it sits in a stack: its depth from the bottom, and how tall the stack is. */
    val stack: String?
        get() = raw.obj("stack")?.let { s ->
            val position = s.int("position") ?: return null
            val total = s.int("total") ?: return null
            "$position/$total" + if (s.bool("partial") == true) "+" else ""
        }

    val checksLabel: String?
        get() = when (checks) {
            null -> null
            "success" -> "checks"
            "failure", "error" -> "checks failing"
            else -> "checks running"
        }
}

/** A project's board: its open pull requests, as `GET /pulls` answers. */
data class Board(val repo: String, val pulls: List<PullRow>, val syncedAt: Instant?) {
    /** Every author on the board, sorted, for the filter. */
    val authors: List<String> get() = pulls.mapNotNull { it.author }.distinct().sortedBy { it.lowercase() }

    /** Every label on the board, by name, for the filter. */
    val labels: List<String> get() = pulls.flatMap { p -> p.labels.map { it.name } }.distinct().sortedBy { it.lowercase() }

    /** The rows by [author] and carrying [label], when given. */
    fun filter(author: String?, label: String?): List<PullRow> = pulls.filter { p ->
        (author == null || p.author.equals(author, ignoreCase = true)) && (label == null || p.labels.any { it.name.equals(label, ignoreCase = true) })
    }

    companion object {
        fun parse(o: JsonObject): Board = Board(
            o.str("repo").orEmpty(),
            o.objects("pulls").filter { it.int("number") != null }.map(::PullRow),
            parseTime(o.str("syncedAt")),
        )
    }
}

/** An errand that can run on a pull request (`GET /actions`): a review, QA, a test sheet. */
data class Errand(val id: String, val label: String, val hint: String?, val inputLabel: String?, val inputRequired: Boolean) {
    companion object {
        fun list(o: JsonObject): List<Errand> = o.objects("actions").mapNotNull { a ->
            val id = a.nonEmpty("id") ?: return@mapNotNull null
            val input = a.obj("input")
            Errand(id, a.nonEmpty("label") ?: id, a.nonEmpty("hint"), input?.let { it.nonEmpty("label") ?: "Details" }, input?.bool("required") == true)
        }
    }
}

// MARK: - One pull request

data class CheckRun(val name: String, val status: String, val conclusion: String?, val failed: Boolean, val url: String?) {
    /** What the row says: the conclusion once it finished, else where it stands. */
    val label: String get() = conclusion ?: status.replace('_', ' ')
    val passed: Boolean get() = conclusion == "success"
    val pending: Boolean get() = status != "completed"
}

data class PullReview(val user: String, val state: String, val url: String?) {
    val label: String get() = state.lowercase().replace('_', ' ')
}

data class PullCommit(val sha: String, val message: String, val url: String?)

/** A pull request's standing in one read (`GET /pulls/{number}`). */
data class PullOverview(val raw: JsonObject) {
    val number: Int get() = raw.int("number") ?: 0
    val title: String get() = raw.nonEmpty("title") ?: "Pull request #$number"
    val url: String? get() = raw.nonEmpty("url")
    val state: String get() = if (raw.bool("draft") == true && raw.str("state") == "open") "draft" else raw.str("state").orEmpty()
    val headSha: String? get() = raw.nonEmpty("headSha")
    val headRef: String? get() = raw.nonEmpty("headRef")
    val baseRef: String? get() = raw.nonEmpty("baseRef")
    val additions: Int get() = raw.int("additions") ?: 0
    val deletions: Int get() = raw.int("deletions") ?: 0
    val changedFiles: Int get() = raw.int("changedFiles") ?: 0
    val commitCount: Int? get() = raw.int("commits")
    val commits: List<PullCommit>
        get() = raw.objects("commitList").mapNotNull { c -> PullCommit(c.nonEmpty("sha") ?: return@mapNotNull null, c.str("message").orEmpty(), c.nonEmpty("url")) }
    val issues: List<PullRef> get() = PullRef.list(raw, "issues")
    val reviews: List<PullReview>
        get() = raw.objects("reviews").mapNotNull { r -> PullReview(r.nonEmpty("user") ?: return@mapNotNull null, r.str("state").orEmpty(), r.nonEmpty("url")) }
    val checks: List<CheckRun>
        get() = raw.obj("checks")?.objects("runs").orEmpty().mapNotNull { c ->
            CheckRun(c.nonEmpty("name") ?: return@mapNotNull null, c.str("status").orEmpty(), c.nonEmpty("conclusion"), c.bool("failed") == true, c.nonEmpty("url"))
        }

    /** "4 passed · 1 failed · 2 running", or null with no checks. */
    val checksSummary: String?
        get() {
            val c = raw.obj("checks") ?: return null
            if ((c.int("total") ?: 0) == 0) return null
            return listOfNotNull(
                c.int("passed")?.takeIf { it > 0 }?.let { "$it passed" },
                c.int("failed")?.takeIf { it > 0 }?.let { "$it failed" },
                c.int("pending")?.takeIf { it > 0 }?.let { "$it running" },
            ).joinToString(" · ").ifEmpty { "${c.int("total")} reported" }
        }

    companion object {
        fun parse(o: JsonObject): PullOverview? = o.obj("pr")?.takeIf { it.int("number") != null }?.let(::PullOverview)
    }
}

/** A finding a Briareus review declared on a pull request (`GET /pulls/{number}/findings`). */
data class PullFinding(val key: String, val severity: String, val title: String, val file: String?, val line: Int?, val url: String?, val decision: String?, val fixed: Boolean) {
    val where: String? get() = file?.let { f -> line?.let { "$f:$it" } ?: f }

    companion object {
        fun list(o: JsonObject): List<PullFinding> = o.objects("findings").mapNotNull { f ->
            PullFinding(
                f.nonEmpty("key") ?: return@mapNotNull null, f.str("severity").orEmpty(), f.str("title").orEmpty(),
                f.nonEmpty("file"), f.int("line"), f.nonEmpty("url"), f.nonEmpty("decision"), f.bool("fixed") == true,
            )
        }
    }
}
