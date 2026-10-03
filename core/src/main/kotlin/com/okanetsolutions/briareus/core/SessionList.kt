package com.okanetsolutions.briareus.core

import java.time.Duration
import java.time.Instant

/** A project in the sidebar with its conversations. */
data class ProjectGroup(val project: Project, val sessions: List<Session>) {
    val working: Boolean get() = sessions.any { it.isActive }
    val needsYou: Int get() = sessions.count { it.state.needsYou }
}

object SessionList {
    /**
     * The projects in the server's order, each with its conversations, newest first, minus orchestrator workers (shown
     * under their orchestrator instead). [query] narrows by title, branch or project; a project left with nothing is
     * dropped unless nothing is being searched for.
     */
    fun group(projects: List<Project>, sessions: List<Session>, query: String = ""): List<ProjectGroup> {
        val q = query.trim().lowercase()
        val byRepo = sessions.filter { it.parentId == null }.groupBy { it.repo }
        return projects.mapNotNull { project ->
            val mine = byRepo[project.repo].orEmpty()
                .filter { q.isEmpty() || matches(it, project, q) }
                .sortedByDescending { it.createdAt ?: Instant.EPOCH }
            if (q.isNotEmpty() && mine.isEmpty() && !project.title.lowercase().contains(q)) null else ProjectGroup(project, mine)
        }
    }

    /** Every conversation waiting for its user, the longest-waiting first: the status panel's list. */
    fun needingYou(sessions: List<Session>): List<Session> =
        sessions.filter { it.state.needsYou && it.parentId == null }.sortedBy { it.endedAt ?: it.createdAt ?: Instant.EPOCH }

    data class Counts(val working: Int, val needsYou: Int, val ready: Int)

    fun counts(sessions: List<Session>): Counts {
        val top = sessions.filter { it.parentId == null }
        return Counts(top.count { it.isActive }, top.count { it.state.needsYou }, top.count { it.state == Session.State.IDLE })
    }

    /** "now", "5m", "3h", "2d", "4w": how the list shows a conversation's age. */
    fun age(since: Instant?, now: Instant): String {
        since ?: return ""
        val d = Duration.between(since, now)
        return when {
            d.toMinutes() < 1 -> "now"
            d.toHours() < 1 -> "${d.toMinutes()}m"
            d.toDays() < 1 -> "${d.toHours()}h"
            d.toDays() < 7 -> "${d.toDays()}d"
            else -> "${d.toDays() / 7}w"
        }
    }

    /** "$0.42", or null when the provider does not price turns. */
    fun cost(usd: Double?): String? = usd?.let { if (it < 0.01 && it > 0) "<$0.01" else "$" + "%.2f".format(java.util.Locale.ROOT, it) }

    private fun matches(s: Session, p: Project, q: String): Boolean =
        s.title.lowercase().contains(q) || s.branch?.lowercase()?.contains(q) == true || p.title.lowercase().contains(q)
}
