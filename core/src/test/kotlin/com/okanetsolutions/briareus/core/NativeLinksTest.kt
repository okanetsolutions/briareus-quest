package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeLinksTest {
    private val repos = setOf("acme/web")

    @Test fun repositoryLinksResolveToNativePanels() {
        assertEquals(ScreenAction.PullRequest("acme/web", 42, "files", true), NativeLinks.resolve("https://github.com/acme/web/pull/42/files#diff", repos))
        assertEquals(ScreenAction.Issue("acme/web", 7, true), NativeLinks.resolve("https://github.com/acme/web/issues/7", repos))
    }

    @Test fun checksLinksOpenTheOverviewSummary() {
        assertEquals(ScreenAction.PullRequest("acme/web", 42, null, true), NativeLinks.resolve("https://github.com/acme/web/pull/42/checks", repos))
    }

    @Test fun otherLinksHaveNoBrowserFallback() {
        for (url in listOf(
            "https://example.com", "javascript:alert(1)", "intent://open", "http://github.com/acme/web/pull/42",
            "https://github.com/other/repo/pull/42", "https://github.com@evil.example/acme/web/pull/42", "https://github.com/acme/web/pull/0",
            "https://github.com/acme/web/actions/runs/42", "https://github.com/acme/web/pull/42/unknown",
        )) assertNull(url, NativeLinks.resolve(url, repos))
    }
}
