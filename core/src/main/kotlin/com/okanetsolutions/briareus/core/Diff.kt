package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject

/** A pull request's changed files as far as they were read, and whether more were left unread. */
data class PullFiles(val files: List<PullFile>, val more: Boolean)

/**
 * Reads a pull request's changed files page by page, up to [pages] of 100, each pinned to the revision the first page
 * read: a push in between fails the read (409) instead of mixing two revisions.
 */
suspend fun ApiClient.pullFiles(repo: String, number: Int, pages: Int = 5): PullFiles {
    val first = call("pull_files", args("repo" to repo, "pr" to number))
    val pr = first.obj("pr")
    val files = PullFile.list(first).toMutableList()
    var next = first.int("nextPage")
    var read = 1
    while (next != null && next > 0 && read < pages) {
        val page = call("pull_files", args("repo" to repo, "pr" to number, "page" to next, "headSha" to pr?.nonEmpty("headSha"), "baseSha" to pr?.nonEmpty("baseSha")))
        files += PullFile.list(page)
        next = page.int("nextPage")
        read++
    }
    return PullFiles(files, (next != null && next > 0) || first.bool("truncated") == true)
}

/** One line of a unified diff, with its number on each side: null where the line is not on that side. */
data class DiffLine(val kind: Kind, val text: String, val old: Int?, val new: Int?) {
    enum class Kind { HUNK, CONTEXT, ADDED, REMOVED, NOTE }
}

/** One changed file of a pull request (`GET /pulls/{number}/files`); [patch] is null for a binary or unrendered diff. */
data class PullFile(val path: String, val previousPath: String?, val status: String, val additions: Int, val deletions: Int, val patch: String?) {
    /** What the file's header says of a rename: "old → new", else the path. */
    val title: String get() = previousPath?.takeIf { it != path }?.let { "$it → $path" } ?: path

    val lines: List<DiffLine> get() = patch?.let(::parsePatch).orEmpty()

    companion object {
        fun list(page: JsonObject): List<PullFile> = page.objects("files").mapNotNull { f ->
            PullFile(
                f.nonEmpty("filename") ?: return@mapNotNull null, f.nonEmpty("previousFilename"), f.str("status") ?: "modified",
                f.int("additions") ?: 0, f.int("deletions") ?: 0, f.str("patch"),
            )
        }

        /** The file [named] loosely: its exact path, else the one path that ends with it, else the one whose name contains it. */
        fun find(files: List<PullFile>, named: String): PullFile? {
            val n = named.trim().trimStart('/').lowercase()
            if (n.isEmpty()) return null
            files.firstOrNull { it.path.lowercase() == n }?.let { return it }
            files.filter { it.path.lowercase().endsWith("/$n") || it.path.lowercase().endsWith(n) }.singleOrNull()?.let { return it }
            return files.filter { it.path.substringAfterLast('/').lowercase().contains(n) }.singleOrNull()
        }

        private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

        /** GitHub's patch, which starts at the first hunk header, as numbered lines. */
        fun parsePatch(patch: String): List<DiffLine> {
            var old = 0
            var new = 0
            return patch.trimEnd('\n').split('\n').map { line ->
                val hunk = HUNK.find(line)
                when {
                    hunk != null -> {
                        old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt()
                        DiffLine(DiffLine.Kind.HUNK, line, null, null)
                    }
                    line.startsWith("+") -> DiffLine(DiffLine.Kind.ADDED, line.drop(1), null, new++)
                    line.startsWith("-") -> DiffLine(DiffLine.Kind.REMOVED, line.drop(1), old++, null)
                    line.startsWith("\\") -> DiffLine(DiffLine.Kind.NOTE, line.drop(1).trim(), null, null)
                    else -> DiffLine(DiffLine.Kind.CONTEXT, line.removePrefix(" "), old++, new++)
                }
            }
        }
    }
}
