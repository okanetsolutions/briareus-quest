package com.okanetsolutions.briareus.core

import java.net.URI

/** Recognized repository links stay in native views; other links remain selectable text. */
object NativeLinks {
    fun resolve(url: String, repositories: Set<String>): ScreenAction? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        val parts = uri.path.orEmpty().trim('/').split('/')
        if (parts.size !in 4..5) return null
        val repo = parts.take(2).joinToString("/")
        if (repo !in repositories) return null
        val number = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return when (parts[2]) {
            "pull" -> {
                val tab = parts.getOrNull(4)
                if (tab != null && tab !in setOf("files", "checks", "commits")) return null
                ScreenAction.PullRequest(repo, number, tab?.takeUnless { it == "checks" }, ownPanel = true)
            }
            "issues" -> if (parts.size == 4) ScreenAction.Issue(repo, number, ownPanel = true) else null
            else -> null
        }
    }
}
