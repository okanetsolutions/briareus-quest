package com.okanetsolutions.briareus.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun blocks() {
        val blocks = Markdown.parse(
            """
            # Title ##
            Some *text*
            continued.

            ```kotlin
            val x = 1

            println(x)
            ```
            > quoted
            > more
            ---
            | A | B |
            |---|:-:|
            | 1 | 2 \| 3 |
            | 4 |
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.Heading(1, "Title"),
                Block.Paragraph("Some *text*\ncontinued."),
                Block.Code("kotlin", "val x = 1\n\nprintln(x)"),
                Block.Quote(listOf(Block.Paragraph("quoted\nmore"))),
                Block.Rule,
                Block.Table(listOf("A", "B"), listOf(listOf("1", "2 | 3"), listOf("4", ""))),
            ),
            blocks,
        )
    }

    @Test fun lists() {
        val blocks = Markdown.parse(
            """
            - one
            - [x] done
              still done
            - [ ] todo
              - nested

            1. first
            2. second
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Block.ListBlock(
                    false, 1,
                    listOf(
                        Block.Item(listOf(Block.Paragraph("one")), null),
                        Block.Item(listOf(Block.Paragraph("done\nstill done")), true),
                        Block.Item(listOf(Block.Paragraph("todo"), Block.ListBlock(false, 1, listOf(Block.Item(listOf(Block.Paragraph("nested")), null)))), false),
                    ),
                ),
                Block.ListBlock(true, 1, listOf(Block.Item(listOf(Block.Paragraph("first")), null), Block.Item(listOf(Block.Paragraph("second")), null))),
            ),
            blocks,
        )
    }

    @Test fun aNumberInsideAParagraphIsNotAList() {
        assertEquals(listOf(Block.Paragraph("We shipped in\n2024. It went well.")), Markdown.parse("We shipped in\n2024. It went well."))
    }

    @Test fun inline() {
        assertEquals(
            listOf(
                Span("Use "), Span("code", code = true), Span(", "), Span("bold", bold = true), Span(" and "),
                Span("it", italic = true), Span(" "), Span("gone", strike = true), Span(" "), Span("site", link = "https://x.io"),
                Span(" or "), Span("https://y.io/a", link = "https://y.io/a"), Span(". snake_case_name * 2"),
            ),
            Inline.parse("Use `code`, **bold** and _it_ ~~gone~~ [site](https://x.io) or https://y.io/a. snake_case_name * 2"),
        )
        assertEquals(listOf(Span("a "), Span("b", bold = true, italic = true)), Inline.parse("a ***b***"))
        assertEquals(listOf(Span("**not closed")), Inline.parse("**not closed"))
        assertEquals(listOf(Span("*literal*")), Inline.parse("\\*literal\\*"))
    }
}
