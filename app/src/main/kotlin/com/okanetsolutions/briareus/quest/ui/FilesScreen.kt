package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.ApiError
import com.okanetsolutions.briareus.core.DiffLine
import com.okanetsolutions.briareus.core.PullFile
import com.okanetsolutions.briareus.core.PullFiles
import com.okanetsolutions.briareus.core.PullFileTree
import com.okanetsolutions.briareus.quest.Store
import kotlinx.coroutines.CancellationException

/** A persistent file picker on the left and the chosen patch on the right, each scrolling independently. */
@Composable
fun FilesScreen(store: Store, repo: String, number: Int) {
    var selected by remember(repo, number) { mutableStateOf<String?>(null) }
    var wrap by remember { mutableStateOf(true) }
    ReadPanel(store, repo to number, { store.review.files(repo, number) }) { first: PullFiles ->
        var page by remember(first) { mutableStateOf(first) }
        var more by remember(first) { mutableIntStateOf(0) }
        var busy by remember(first) { mutableStateOf(false) }
        var error by remember(first) { mutableStateOf<String?>(null) }
        LaunchedEffect(first, more) {
            if (more == 0) return@LaunchedEffect
            busy = true
            try { page = store.review.files(repo, number, page); error = null } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? ApiError)?.unauthorized == true) store.failed(e)
                error = e.message ?: "The next page could not be read."
            } finally { busy = false }
        }
        val file = page.files.firstOrNull { it.filename == selected } ?: page.files.firstOrNull()
        LaunchedEffect(file?.filename) { selected = file?.filename }
        val p = LocalPalette.current
        Column(Modifier.fillMaxSize()) {
            error?.let { Text(it, Modifier.padding(12.dp), color = p.danger) }
            if (page.truncated) Text("GitHub limited this file list; some files are not shown.", Modifier.padding(12.dp), color = p.warn)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val treeWidth = (maxWidth * 0.32f).coerceAtMost(320.dp)
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(treeWidth).fillMaxHeight()) {
                        Text("Files changed · ${page.files.size}", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall, color = p.ink)
                        FileTree(page.files, file?.filename, Modifier.weight(1f)) { selected = it }
                        if (page.nextPage != null) TextButton(onClick = { more++ }, enabled = !busy) { Text(if (busy) "Loading…" else "Load more files") }
                    }
                    VerticalDivider(color = p.line)
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (file == null) Text("No changed files.", Modifier.padding(16.dp), color = p.muted)
                        else key(file.filename, file.patch) { FileDiff(file, wrap) { wrap = !wrap } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTree(files: List<PullFile>, selected: String?, modifier: Modifier, onSelect: (String) -> Unit) {
    val p = LocalPalette.current
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val tree = remember(files) { PullFileTree.build(files) }
    val rows = remember(tree, collapsed) { PullFileTree.rows(tree, collapsed) }
    LazyColumn(modifier.fillMaxWidth()) {
        items(rows, key = { "${it.node.file != null}:${it.node.path}" }) { row ->
            val node = row.node
            val chosen = node.file != null && selected == node.path
            Column(
                Modifier.fillMaxWidth().background(if (chosen) p.accent.copy(alpha = 0.16f) else p.canvas)
                    .selectable(selected = chosen, role = Role.Button) {
                        if (node.file != null) onSelect(node.path)
                        else collapsed = if (node.path in collapsed) collapsed - node.path else collapsed + node.path
                    }.heightIn(min = 48.dp).padding(start = 12.dp + (row.depth.coerceAtMost(6) * 12).dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val marker = if (node.file != null) "" else if (node.path in collapsed) "▸ " else "▾ "
                Text(marker + node.name, style = MaterialTheme.typography.bodyMedium, color = if (chosen) p.accent else p.ink)
                node.file?.let { file ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(file.status, style = MaterialTheme.typography.labelSmall, color = p.muted)
                        Text("+${file.additions}", style = MaterialTheme.typography.labelSmall, color = p.ok)
                        Text("−${file.deletions}", style = MaterialTheme.typography.labelSmall, color = p.danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDiff(file: PullFile, wrap: Boolean, onWrap: () -> Unit) {
    val p = LocalPalette.current
    val lines = remember(file.patch) { DiffLine.parse(file.patch.orEmpty()).map { it.copy(text = it.text.replace("\t", "    ")) } }
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val textWidth = remember(lines, style, density) {
        with(density) { (lines.maxOfOrNull { measurer.measure(it.text, style, softWrap = false).size.width } ?: 0).toDp() + 16.dp }
    }
    val scroll = rememberScrollState()
    val list = rememberLazyListState()
    Column(Modifier.fillMaxWidth().background(p.raise).padding(12.dp)) {
        SelectionContainer { Text(file.filename, style = MaterialTheme.typography.titleSmall, color = p.ink) }
        Text("${file.status} · +${file.additions} −${file.deletions}", color = p.muted, style = MaterialTheme.typography.labelMedium)
        file.previousFilename?.let { Text("Renamed from $it", color = p.muted) }
        TextButton(onClick = onWrap) { Text(if (wrap) "Wrap lines: on" else "Wrap lines: off") }
    }
    if (file.patch == null) Text("No text patch supplied by GitHub (binary or unavailable diff).", Modifier.padding(16.dp), color = p.muted)
    else BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth = if (wrap) maxWidth else maxOf(maxWidth, textWidth + 112.dp)
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(scroll).width(contentWidth), state = list) {
                items(lines) { line -> DiffRow(line, wrap) }
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine, wrap: Boolean) {
    val p = LocalPalette.current
    val color = when (line.kind) {
        DiffLine.Kind.ADDED -> p.ok
        DiffLine.Kind.REMOVED -> p.danger
        DiffLine.Kind.HEADER -> p.accent
        else -> p.ink
    }
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono)
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.08f)).padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        if (line.kind == DiffLine.Kind.HEADER || line.kind == DiffLine.Kind.NOTE) {
            Text(line.text, Modifier.padding(horizontal = 8.dp), style = style, color = color, softWrap = wrap)
        } else {
            Text(line.old?.toString().orEmpty(), Modifier.width(52.dp), style = style, textAlign = TextAlign.End, color = p.muted)
            Text(line.new?.toString().orEmpty(), Modifier.width(52.dp), style = style, textAlign = TextAlign.End, color = p.muted)
            Text(line.text, Modifier.weight(1f).padding(start = 8.dp, end = 8.dp), style = style, color = color, softWrap = wrap)
        }
    }
}
