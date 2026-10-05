package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.ClipData
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.okanetsolutions.briareus.core.NativeLinks
import com.okanetsolutions.briareus.quest.app
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.okanetsolutions.briareus.core.Block
import com.okanetsolutions.briareus.core.Inline
import com.okanetsolutions.briareus.core.Markdown

/** An agent's reply, drawn from the core's Markdown blocks. Text is selectable, as in a browser. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { Markdown.parse(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { BlockView(it, style) }
        }
    }
}

@Composable
private fun BlockView(block: Block, style: TextStyle) {
    val p = LocalPalette.current
    when (block) {
        is Block.Heading -> Text(
            inline(block.text), color = p.ink,
            style = style.copy(fontWeight = FontWeight.SemiBold, fontSize = when (block.level) { 1 -> 24.sp; 2 -> 21.sp; 3 -> 18.sp; else -> style.fontSize }),
        )
        is Block.Paragraph -> Text(inline(block.text), style = style, color = p.ink)
        is Block.Code -> CodeBlock(block)
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(p.lineStrong, RoundedCornerShape(2.dp)))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.blocks.forEach { BlockView(it, style.copy(color = p.muted)) }
            }
        }
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { i, item ->
                Row {
                    val marker = when {
                        item.checked == true -> "☑"
                        item.checked == false -> "☐"
                        block.ordered -> "${block.start + i}."
                        else -> "•"
                    }
                    Text(marker, Modifier.widthIn(min = 26.dp), style = style, color = p.muted)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { item.blocks.forEach { BlockView(it, style) } }
                }
            }
        }
        is Block.Table -> Box(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, p.line, RoundedCornerShape(8.dp))) {
            Column {
                TableRow(block.header, header = true, style)
                block.rows.forEach { TableRow(it, header = false, style) }
            }
        }
        Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean, style: TextStyle) {
    val p = LocalPalette.current
    Row(Modifier.background(if (header) p.field else p.canvas)) {
        cells.forEach { cell ->
            Text(
                inline(cell), Modifier.width(180.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                style = style.copy(fontWeight = if (header) FontWeight.SemiBold else null, fontSize = 15.sp), color = p.ink,
            )
        }
    }
}

@Composable
private fun CodeBlock(block: Block.Code) {
    val p = LocalPalette.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().background(p.sunken, RoundedCornerShape(10.dp)).border(1.dp, p.line, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language ?: "code", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = p.muted)
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", block.code))) } }) {
                Icon(Icons.Outlined.ContentCopy, "Copy code", tint = p.muted)
            }
        }
        Text(
            block.code, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            style = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp), color = p.ink, softWrap = false,
        )
    }
}

@Composable
fun inline(text: String): AnnotatedString {
    val p = LocalPalette.current
    val app = LocalContext.current.app
    val projects by app.store.projects.collectAsState()
    return remember(text, p, projects) {
        buildAnnotatedString {
            for (span in Inline.parse(text)) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) Mono else null,
                    background = if (span.code) p.field else androidx.compose.ui.graphics.Color.Unspecified,
                    fontSize = if (span.code) 0.9.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                )
                val action = span.link?.let { NativeLinks.resolve(it, projects.map { project -> project.repo }.toSet()) }
                if (action != null) {
                    withLink(LinkAnnotation.Clickable(span.link.orEmpty(), TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline))) {
                        runCatching { app.navigator.show(action) }.onFailure { app.store.failed(it) }
                    }) {
                        withStyle(style) { append(span.text) }
                    }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}

private val Double.em get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
