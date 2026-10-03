package com.okanetsolutions.briareus.core

/**
 * The Markdown agents write, read into blocks the UI draws: headings, paragraphs, bullet, numbered and task lists, quotes,
 * fenced code, tables and rules. Inline styles are parsed separately by [Inline.parse] so each renderer picks its own type.
 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Code(val language: String?, val code: String) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<Item>) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block

    /** [checked] is null for a plain item, true or false for a task. */
    data class Item(val blocks: List<Block>, val checked: Boolean?)
}

object Markdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)\\s*([^`\\s]*)")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
    private val TABLE_DIVIDER = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<Block> = parseLines(text.replace("\r\n", "\n").split('\n'))

    @Suppress("CyclomaticComplexMethod") // A hand-written parser: one branch per block kind, in precedence order.
    private fun parseLines(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var i = 0
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.joinToString("\n") { it.trim() })
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            when {
                line.isBlank() -> { flush(); i++ }
                fence != null -> {
                    flush()
                    val marker = fence.groupValues[1]
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                    i++
                    blocks += Block.Code(fence.groupValues[2].ifEmpty { null }, code.joinToString("\n"))
                }
                HEADING.matches(line.trimEnd()) -> {
                    flush()
                    val m = HEADING.find(line.trimEnd())!!
                    blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                    i++
                }
                RULE.matches(line) -> { flush(); blocks += Block.Rule; i++ }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quoted = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) quoted += lines[i++].trimStart().removePrefix(">").removePrefix(" ")
                    blocks += Block.Quote(parseLines(quoted))
                }
                isTableStart(lines, i) -> {
                    flush()
                    val header = cells(line)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += cells(lines[i++]).let { r -> List(header.size) { r.getOrElse(it) { "" } } }
                    }
                    blocks += Block.Table(header, rows)
                }
                BULLET.matches(line) || ORDERED.matches(line) && (paragraph.isEmpty() || ORDERED.find(line)!!.groupValues[2] == "1") -> {
                    flush()
                    i = parseList(lines, i, blocks)
                }
                else -> { paragraph += line; i++ }
            }
        }
        flush()
        return blocks
    }

    /** Reads one list starting at [start] into [out]; returns the index after it. Deeper-indented lines nest. */
    @Suppress("CyclomaticComplexMethod")
    private fun parseList(lines: List<String>, start: Int, out: MutableList<Block>): Int {
        val first = BULLET.find(lines[start]) ?: ORDERED.find(lines[start])!!
        val ordered = first.groupValues[2].first().isDigit()
        val indent = first.groupValues[1].length
        val items = ArrayList<Block.Item>()
        var i = start
        while (i < lines.size) {
            val m = (if (ordered) ORDERED else BULLET).find(lines[i])
            if (m == null || m.groupValues[1].length != indent) break
            val body = ArrayList<String>()
            body += m.groupValues[3]
            i++
            // Continuation: indented deeper than the marker, or blank lines followed by such.
            while (i < lines.size) {
                val l = lines[i]
                val lead = l.length - l.trimStart().length
                if (l.isBlank()) {
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.isNotBlank() && next.length - next.trimStart().length > indent) { body += ""; i++; continue }
                    break
                }
                if (lead > indent) { body += l.drop(minOf(lead, indent + 2)); i++; continue }
                if (BULLET.matches(l) || ORDERED.matches(l) || FENCE.containsMatchIn(l) || HEADING.matches(l)) break
                // A lazy continuation of the item's paragraph.
                body += l.trim(); i++
            }
            val task = TASK.find(body[0])
            if (task != null) body[0] = task.groupValues[2]
            items += Block.Item(parseLines(body), task?.let { it.groupValues[1] != " " })
            if (i < lines.size && lines[i].isBlank()) {
                val next = lines.getOrNull(i + 1)
                val nm = next?.let { (if (ordered) ORDERED else BULLET).find(it) }
                if (nm != null && nm.groupValues[1].length == indent) i++
            }
        }
        out += Block.ListBlock(ordered, if (ordered) first.groupValues[2].toInt() else 1, items)
        return i
    }

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        lines[i].contains('|') && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1]) && lines[i + 1].contains('-')

    private fun cells(line: String): List<String> {
        var l = line.trim()
        if (l.startsWith("|")) l = l.substring(1)
        if (l.endsWith("|") && !l.endsWith("\\|")) l = l.dropLast(1)
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var k = 0
        while (k < l.length) {
            if (l[k] == '\\' && k + 1 < l.length && l[k + 1] == '|') { cell.append('|'); k += 2; continue }
            if (l[k] == '|') { out += cell.toString().trim(); cell.setLength(0) } else cell.append(l[k])
            k++
        }
        out += cell.toString().trim()
        return out
    }
}

/** A run of text and the styles on it. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

object Inline {
    /** Bold, italic, strikethrough, inline code, `[links](url)` and bare https links. */
    fun parse(text: String): List<Span> {
        val out = ArrayList<Span>()
        parseInto(text, Span(""), out)
        return merge(out)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // One branch per inline marker.
    private fun parseInto(text: String, style: Span, out: MutableList<Span>) {
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) out += style.copy(text = plain.toString()); plain.setLength(0) }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_~[]()#+-.!|>") { plain.append(text[i + 1]); i += 2; continue }
            if (c == '`') {
                val run = text.countRun(i, '`')
                val end = text.indexOf("`".repeat(run), i + run)
                if (end > 0) {
                    flush()
                    out += style.copy(text = text.substring(i + run, end).trim(), code = true)
                    i = end + run
                    continue
                }
            }
            if (c == '[') {
                val close = matching(text, i, '[', ']')
                if (close > 0 && close + 1 < text.length && text[close + 1] == '(') {
                    val end = text.indexOf(')', close + 2)
                    if (end > 0) {
                        flush()
                        val url = text.substring(close + 2, end).trim().substringBefore(' ')
                        parseInto(text.substring(i + 1, close), style.copy(link = url), out)
                        i = end + 1
                        continue
                    }
                }
            }
            if (text.startsWith("https://", i) || text.startsWith("http://", i)) {
                if (i == 0 || !text[i - 1].isLetterOrDigit()) {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>') end++
                    while (end > i && text[end - 1] in ".,;:!?)\"'") end--
                    flush()
                    val url = text.substring(i, end)
                    out += style.copy(text = url, link = style.link ?: url)
                    i = end
                    continue
                }
            }
            if (c == '*' || c == '_' || c == '~') {
                val run = text.countRun(i, c)
                val marker = c.toString().repeat(minOf(run, if (c == '~') 2 else 3))
                // An intraword underscore (snake_case) is not emphasis.
                val intraword = c == '_' && i > 0 && text[i - 1].isLetterOrDigit()
                val opens = i + marker.length < text.length && !text[i + marker.length].isWhitespace()
                if (!intraword && opens && (c != '~' || marker.length == 2)) {
                    val end = findClose(text, i + marker.length, marker)
                    if (end > 0) {
                        flush()
                        val inner = text.substring(i + marker.length, end)
                        val next = when {
                            c == '~' -> style.copy(strike = true)
                            marker.length == 3 -> style.copy(bold = true, italic = true)
                            marker.length == 2 -> style.copy(bold = true)
                            else -> style.copy(italic = true)
                        }
                        parseInto(inner, next, out)
                        i = end + marker.length
                        continue
                    }
                }
                plain.append(text, i, i + run)
                i += run
                continue
            }
            plain.append(c)
            i++
        }
        flush()
    }

    private fun findClose(text: String, from: Int, marker: String): Int {
        var k = from
        while (true) {
            val end = text.indexOf(marker, k)
            if (end < 0) return -1
            val after = end + marker.length
            val cleanBefore = end > from && !text[end - 1].isWhitespace()
            val cleanAfter = after >= text.length || text[after] != marker[0]
            val intraword = marker[0] == '_' && after < text.length && text[after].isLetterOrDigit()
            if (cleanBefore && cleanAfter && !intraword) return end
            k = end + 1
        }
    }

    private fun matching(text: String, open: Int, o: Char, c: Char): Int {
        var depth = 0
        for (k in open until text.length) {
            if (text[k] == o) depth++
            if (text[k] == c && --depth == 0) return k
        }
        return -1
    }

    private fun String.countRun(from: Int, c: Char): Int {
        var k = from
        while (k < length && this[k] == c) k++
        return k - from
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.copy(text = "") == s.copy(text = "")) out[out.size - 1] = prev.copy(text = prev.text + s.text) else out += s
        }
        return out
    }
}
