package com.okanetsolutions.briareus.core


/** A review round a conversation holds for verdicts: a loop's round or a hand-started review. */
data class Triage(
    val round: Int?,
    val prNumber: Int?,
    /** A hand-started review of somebody else's pull request takes no verdicts, only completion. */
    val mine: Boolean,
    val findings: List<Finding>,
) {
    data class Finding(
        val key: String?, val title: String, val severity: String?, val file: String?, val line: Int?, val url: String?,
        val parkedWhy: String?, val draft: String?, val reason: String?,
    ) {
        val location: String? get() = file?.let { f -> line?.let { "$f:$it" } ?: f }
    }

    val title: String
        get() = (round?.let { "Round $it findings" } ?: "Review findings") + (prNumber?.let { " · PR #$it" } ?: "")

    /** The verdicts `POST /sessions/{id}/findings/triage` takes: the user's choice, else the saved draft, else optional. */
    fun verdicts(chosen: Map<String, String>): List<Map<String, String>> = if (!mine) emptyList() else findings.mapNotNull { f ->
        val key = f.key ?: return@mapNotNull null
        val decision = chosen[key] ?: f.draft ?: "optional"
        buildMap { put("key", key); put("decision", decision); f.reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) } }
    }

    fun fixes(chosen: Map<String, String>): Int = verdicts(chosen).count { it["decision"] == "fix" }

    /** The button's words: what completing will do. */
    fun completeLabel(chosen: Map<String, String>): String {
        if (!mine) return "Complete"
        val n = fixes(chosen)
        return if (n > 0) "Complete · send $n to be fixed" else "Complete · nothing to fix, approve and close"
    }

    companion object {
        val DECISIONS = listOf("fix" to "Fix", "optional" to "Optional", "dismissed" to "Dismiss")

        /** The round a session holds, from `reviewTriage` or the review loop's `triage`; null without findings. */
        fun of(session: Session): Triage? {
            val raw = listOfNotNull(session.raw.obj("reviewTriage"), session.raw.obj("reviewLoop")?.obj("triage"))
                .firstOrNull { it.objects("findings").isNotEmpty() } ?: return null
            val drafts = raw.obj("drafts")?.obj("verdicts")
            return Triage(
                round = raw.int("round"),
                prNumber = raw.int("prNumber"),
                mine = raw.bool("mine") != false,
                findings = raw.objects("findings").map { f ->
                    val key = f.nonEmpty("key")
                    val draft = key?.let { drafts?.obj(it) }
                    Finding(
                        key, f.nonEmpty("title") ?: "Finding", f.nonEmpty("severity"), f.nonEmpty("file"), f.int("line"),
                        f.nonEmpty("url")?.takeIf { it.startsWith("https://") }, f.nonEmpty("parkedWhy"), draft?.nonEmpty("decision"), draft?.str("reason"),
                    )
                },
            )
        }
    }
}
