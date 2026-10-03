package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffTest {
    private val files = PullFile.list(
        json(
            """{"pr":{"number":7},"files":[
            {"filename":"src/api/Routes.kt","status":"modified","additions":2,"deletions":1,
             "patch":"@@ -10,3 +10,4 @@ object Routes {\n     val a = 1\n-    val b = 2\n+    val b = 3\n+    val c = 4\n\\ No newline at end of file"},
            {"filename":"src/web/Routes.kt","previousFilename":"src/web/Old.kt","status":"renamed","additions":0,"deletions":0,"patch":null},
            {"filename":"README.md","status":"added","additions":1,"deletions":0,"patch":"@@ -0,0 +1 @@\n+Hello"},
            {"status":"modified"}
        ],"nextPage":null}""",
        ),
    )

    @Test fun readsTheFiles() {
        assertEquals(listOf("src/api/Routes.kt", "src/web/Routes.kt", "README.md"), files.map { it.path })
        assertEquals("src/web/Old.kt → src/web/Routes.kt", files[1].title)
        assertEquals(emptyList<DiffLine>(), files[1].lines)
    }

    @Test fun numbersEachSideOfAHunk() {
        val lines = files[0].lines
        assertEquals(
            listOf(DiffLine.Kind.HUNK, DiffLine.Kind.CONTEXT, DiffLine.Kind.REMOVED, DiffLine.Kind.ADDED, DiffLine.Kind.ADDED, DiffLine.Kind.NOTE),
            lines.map { it.kind },
        )
        assertEquals(DiffLine(DiffLine.Kind.CONTEXT, "    val a = 1", 10, 10), lines[1])
        assertEquals(DiffLine(DiffLine.Kind.REMOVED, "    val b = 2", 11, null), lines[2])
        assertEquals(DiffLine(DiffLine.Kind.ADDED, "    val c = 4", null, 12), lines[4])
        assertEquals("No newline at end of file", lines[5].text)
        assertEquals(DiffLine(DiffLine.Kind.ADDED, "Hello", null, 1), files[2].lines[1])
    }

    @Test fun findsAFileByWhatTheUserSays() {
        assertEquals("README.md", PullFile.find(files, "readme")?.path)
        assertEquals("src/api/Routes.kt", PullFile.find(files, "api/Routes.kt")?.path)
        // Two files are called Routes.kt: ask rather than guess.
        assertNull(PullFile.find(files, "Routes.kt"))
        assertNull(PullFile.find(files, " "))
    }
}
