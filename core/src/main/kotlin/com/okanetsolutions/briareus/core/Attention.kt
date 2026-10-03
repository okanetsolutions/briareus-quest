package com.okanetsolutions.briareus.core

/**
 * What deserves a notification while the user is in another app: the moments a session starts waiting for them. Pure, so
 * the rules are tested without Android; the background service feeds it every `session` event of `GET /events`.
 */
data class Alert(val sessionId: String, val kind: Kind, val title: String, val text: String) {
    enum class Kind {
        /** The agent asked a question and waits. Answerable from the notification. */
        QUESTION,
        /** A turn ended and the session is ready for the next message. Repliable from the notification. */
        FINISHED,
        /** A review round holds findings for verdicts. */
        FINDINGS,
        FAILED,
    }

    /** Whether the notification offers a reply box. */
    val repliable: Boolean get() = kind == Kind.QUESTION || kind == Kind.FINISHED
}

class AttentionTracker(
    /** Sessions run by an orchestrator report to it, not to the user; their alerts would be noise. */
    private val skipWorkers: Boolean = true,
) {
    private val last = HashMap<String, Session.State>()

    /**
     * The alert a new copy of a session warrants, given the copy seen before it, or null. The first copy of each session
     * (the one `GET /events` sends on connect) only sets the baseline, so reconnecting never replays old alerts.
     */
    fun update(session: Session, projectTitle: String? = null): Alert? {
        val state = session.state
        val before = last.put(session.id, state) ?: return null
        if (before == state) return null
        if (skipWorkers && session.parentId != null) return null
        val where = projectTitle ?: session.repo.substringAfter('/')
        val title = "${session.title} · $where"
        return when (state) {
            Session.State.WAITING -> Alert(session.id, Alert.Kind.QUESTION, title, session.lastText ?: "The agent asked a question.")
            Session.State.FINDINGS -> Alert(session.id, Alert.Kind.FINDINGS, title, findingsText(session))
            Session.State.FAILED -> Alert(session.id, Alert.Kind.FAILED, title, session.error ?: "The session failed.")
            // Only a turn that was running and settled; a reopen or a close is the user's own doing.
            Session.State.IDLE -> if (before == Session.State.WORKING) Alert(session.id, Alert.Kind.FINISHED, title, session.lastText ?: "The turn finished.") else null
            Session.State.WORKING, Session.State.CLOSED -> null
        }
    }

    fun forget(sessionId: String) {
        last.remove(sessionId)
    }

    /** Forgets every baseline: after a reconnect, the next copies set them again without alerting. */
    fun reset() = last.clear()

    private fun findingsText(session: Session): String {
        val count = session.reviewTriage?.objects("findings")?.size ?: 0
        return if (count == 1) "A review found 1 finding to decide." else if (count > 1) "A review found $count findings to decide." else "A review round waits for your verdicts."
    }
}
