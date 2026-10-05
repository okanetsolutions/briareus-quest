package com.okanetsolutions.briareus.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReviewTest {
    @Test fun codeReviewRequiresTheRequestedOpenPullAndItsBranch() {
        fun pull(number: Int = 42, state: String = "open", branch: String? = "topic", draft: Boolean = false) =
            args("pr" to mapOf("number" to number, "state" to state, "headRef" to branch, "draft" to draft))
        assertEquals(args("repo" to "org/repo", "prNumber" to 42, "branch" to "topic"), PullReviewStart.arguments("org/repo", 42, pull(draft = true)))
        for (answer in listOf(args(), pull(number = 8), pull(state = "closed"), pull(state = "merged"), pull(branch = null), pull(branch = " "))) {
            try { PullReviewStart.arguments("org/repo", 42, answer); fail("review without a current open branch") } catch (_: IllegalStateException) { }
        }
        val catalog = RouteCatalog(listOf(CatalogRoute("POST", "sessions", "manage")))
        assertTrue(catalog.allows("review", Permission.MANAGE))
        org.junit.Assert.assertFalse(catalog.allows("review", Permission.READ))
        org.junit.Assert.assertFalse(RouteCatalog(emptyList()).allows("review", Permission.MANAGE))
    }

    @Test fun fileTreeCompactsDirectoriesSortsAndCollapsesWithoutLosingFiles() {
        fun file(path: String) = PullFile(path, null, "modified", 1, 1, null)
        val files = listOf("z.txt", "src/main/kotlin/Z.kt", "src/main/kotlin/A.kt", "docs/readme.md", "README.md").map(::file)
        val tree = PullFileTree.build(files)
        assertEquals(listOf("docs", "src/main/kotlin", "README.md", "z.txt"), tree.map { it.name })
        val rows = PullFileTree.rows(tree, emptySet())
        assertEquals(listOf("docs/readme.md", "src/main/kotlin/A.kt", "src/main/kotlin/Z.kt", "README.md", "z.txt"), rows.mapNotNull { it.node.file?.filename })
        assertEquals(1, rows.first { it.node.name == "A.kt" }.depth)
        val collapsed = PullFileTree.rows(tree, setOf("src/main/kotlin"))
        assertTrue(collapsed.none { it.node.name == "A.kt" || it.node.name == "Z.kt" })
        assertEquals(2, PullFileTree.build(listOf(file("entry"), file("entry/child"))).size)
        assertTrue(PullFileTree.build(emptyList()).isEmpty())
    }

    @Test fun numbersBothSidesAcrossHunksAndKeepsMissingNewlineNotes() {
        val lines = DiffLine.parse("@@ -2,2 +5,2 @@ method\n-old\n+new\n same\n\\ No newline at end of file\n@@ -20,0 +24 @@\n+added")
        assertEquals(listOf(null, 2, null, 3, null, null, null), lines.map { it.old })
        assertEquals(listOf(null, null, 5, 6, null, null, 24), lines.map { it.new })
        assertEquals(DiffLine.Kind.NOTE, lines[4].kind)
        assertEquals(DiffLine.Kind.ADDED, lines.last().kind)
    }

    @Test fun readsRenamesBinaryFilesAndTruncation() {
        val page = PullFiles.parse(args(
            "files" to listOf(mapOf("filename" to "new.png", "previousFilename" to "old.png", "status" to "renamed", "patch" to null)),
            "truncated" to true,
        ))
        assertEquals("old.png", page.files.single().previousFilename)
        assertNull(page.files.single().patch)
        assertTrue(page.truncated)
    }

    @Test fun pinsFilePagingToBothRevisionsAndRejectsMixedHeads() = runTest {
        val server = FakeServer()
        val client = ApiClient(
            ServerAddress.parse("https://b.example")!!, "brm_" + "t".repeat(43),
            ApiClient.defaultHttpClient().newBuilder().addInterceptor(server).build(),
        )
        server.reply(body = """{"pr":{"headSha":"head","baseSha":"base"},"files":[{"filename":"a.kt"}],"nextPage":2}""")
        val first = PullFiles.parse(client.call("pull_files", args("repo" to "org/repo", "pr" to 7)))
        assertEquals("/api/v1/pulls/7/files", server.requests.last().url.encodedPath)
        server.reply(body = """{"pr":{"headSha":"head","baseSha":"base"},"files":[{"filename":"b.kt"}],"nextPage":null}""")
        val second = PullFiles.parse(client.call("pull_files", first.arguments("org/repo", 7)))
        val request = server.requests.last()
        assertEquals("GET", request.method)
        assertEquals("org/repo", request.url.queryParameter("repo"))
        assertEquals("2", request.url.queryParameter("page"))
        assertEquals("head", request.url.queryParameter("headSha"))
        assertEquals("base", request.url.queryParameter("baseSha"))
        assertEquals(listOf("a.kt", "b.kt"), first.append(second).files.map { it.filename })
        try { first.append(second.copy(headSha = "changed")); fail("mixed revisions") } catch (_: IllegalStateException) { }
        try { first.copy(baseSha = null).arguments("org/repo", 7); fail("unpinned page") } catch (_: IllegalStateException) { }
    }

    @Test fun keepsAllIssueLabelsAndLinkedPullRequests() {
        val board = Board.parse(args("issues" to listOf(mapOf(
            "number" to 3, "title" to "Issue", "labels" to listOf(mapOf("name" to "bug"), mapOf("name" to "quest")),
            "parent" to mapOf("number" to 1, "title" to "Epic"), "pulls" to listOf(mapOf("number" to 9, "title" to "Fix")),
        ))))
        val issue = board.issues.single()
        assertEquals(listOf("bug", "quest"), issue.labels.map { it.name })
        assertEquals("#1 Epic", issue.parent)
        assertEquals(9, issue.pulls.single().number)
        assertNull(Issue.parse(args()))
        assertEquals("Issues permission missing", Board.parse(args("issuesError" to "Issues permission missing")).issuesError)
    }
}
