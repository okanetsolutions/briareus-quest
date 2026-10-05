package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject

/** A changed file; a missing patch means GitHub did not supply a text diff. */
data class PullFile(val filename: String, val previousFilename: String?, val status: String, val additions: Int, val deletions: Int, val patch: String?) {
    companion object {
        fun list(o: JsonObject): List<PullFile> = o.objects("files").mapNotNull { f ->
            PullFile(
                f.nonEmpty("filename") ?: return@mapNotNull null, f.nonEmpty("previousFilename"), f.str("status").orEmpty(),
                f.int("additions") ?: 0, f.int("deletions") ?: 0, f.nonEmpty("patch"),
            )
        }
    }
}

/** Pages are tied to both revisions so a push cannot silently mix two diffs. */
data class PullFiles(val files: List<PullFile>, val headSha: String?, val baseSha: String?, val nextPage: Int?, val truncated: Boolean) {
    fun arguments(repo: String, number: Int): JsonObject {
        check(nextPage != null && headSha != null && baseSha != null) { "Reload the files before reading another page; the server did not identify both revisions." }
        return args("repo" to repo, "pr" to number, "page" to nextPage, "headSha" to headSha, "baseSha" to baseSha)
    }

    fun append(page: PullFiles): PullFiles {
        check(headSha == page.headSha && baseSha == page.baseSha) { "The pull request changed. Reload the files to read the new revision." }
        return page.copy(files = (files + page.files).distinctBy { it.filename })
    }

    companion object {
        fun parse(o: JsonObject) = PullFiles(
            PullFile.list(o), o.obj("pr")?.nonEmpty("headSha"), o.obj("pr")?.nonEmpty("baseSha"),
            o.int("nextPage")?.takeIf { it > 0 }, o.bool("truncated") == true,
        )
    }
}

/** Unified diff lines keep the old and new line numbers separate across hunks. */
data class DiffLine(val text: String, val old: Int?, val new: Int?, val kind: Kind) {
    enum class Kind { CONTEXT, ADDED, REMOVED, HEADER, NOTE }

    companion object {
        private val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")

        fun parse(patch: String): List<DiffLine> {
            var old = 0
            var new = 0
            var inHunk = false
            return patch.lineSequence().map { line ->
                val match = hunk.matchEntire(line)
                when {
                    match != null -> {
                        old = match.groupValues[1].toInt(); new = match.groupValues[2].toInt(); inHunk = true
                        DiffLine(line, null, null, Kind.HEADER)
                    }
                    !inHunk -> DiffLine(line, null, null, Kind.HEADER)
                    line.startsWith('+') -> DiffLine(line, null, new++, Kind.ADDED)
                    line.startsWith('-') -> DiffLine(line, old++, null, Kind.REMOVED)
                    line.startsWith(' ') -> DiffLine(line, old++, new++, Kind.CONTEXT)
                    else -> DiffLine(line, null, null, Kind.NOTE)
                }
            }.toList()
        }
    }
}

/** An issue on the board or in its detail response, with all its labels and linked work. */
data class Issue(val raw: JsonObject) {
    val number: Int get() = raw.int("number") ?: 0
    val title: String get() = raw.nonEmpty("title") ?: "Issue #$number"
    val body: String get() = raw.str("body").orEmpty()
    val state: String get() = Voice.issueState(raw)
    val labels: List<PullLabel> get() = PullLabel.list(raw)
    val author: String? get() = raw.nonEmpty("author")
    val parent: String? get() = raw.obj("parent")?.let { "#${it.int("number")} ${it.str("title").orEmpty()}" }
    val pulls: List<PullRef> get() = PullRef.list(raw, "pulls")

    companion object {
        fun list(o: JsonObject): List<Issue> = o.objects("issues").filter { it.int("number") != null }.map(::Issue)
        fun parse(o: JsonObject): Issue? = o.obj("issue")?.takeIf { it.int("number") != null }?.let(::Issue)
    }
}
