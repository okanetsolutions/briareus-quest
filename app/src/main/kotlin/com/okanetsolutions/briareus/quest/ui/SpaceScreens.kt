package com.okanetsolutions.briareus.quest.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.okanetsolutions.briareus.core.ApiError
import com.okanetsolutions.briareus.core.DiffLine
import com.okanetsolutions.briareus.core.PullFile
import com.okanetsolutions.briareus.core.PullFiles
import com.okanetsolutions.briareus.core.SpacePanel
import com.okanetsolutions.briareus.core.pullFiles
import com.okanetsolutions.briareus.quest.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every panel in the space: a slim bar to bring it to the front or close it, the store's messages when it is the panel
 * the user looks at, and the panel's own screen below.
 */
@Composable
fun PanelFrame(store: Store, title: String, inFront: Boolean, onFront: () -> Unit, onClose: (() -> Unit)?, onDrag: (Offset) -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inFront) { if (inFront) store.messages.collect { message = it } }
    LaunchedEffect(message) { if (message != null) { delay(6_000); message = null } }
    OverlayHost { Frame(title, inFront, onFront, onClose, onDrag, message, { message = null }, content) }
}

@Composable
private fun Frame(
    title: String, inFront: Boolean, onFront: () -> Unit, onClose: (() -> Unit)?, onDrag: (Offset) -> Unit,
    message: String?, onMessage: () -> Unit, content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    Column(Modifier.fillMaxSize().background(p.canvas, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().background(p.sidebar, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // The title bar moves the panel. The panel follows the pointer, so where the pointer is now against where it
            // took hold is how far the panel still has to go, in dp: deltas between events would overshoot by a frame.
            var hold by remember { mutableStateOf(Offset.Zero) }
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).pointerInput(Unit) {
                    detectDragGestures(onDragStart = { hold = it }) { change, _ ->
                        change.consume()
                        onDrag(with(density) { Offset((change.position.x - hold.x).toDp().value, (change.position.y - hold.y).toDp().value) })
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DragIndicator, "Drag to move", Modifier.padding(end = 8.dp).size(18.dp), tint = p.muted)
                Text(title, style = MaterialTheme.typography.labelLarge, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!inFront) IconButton(onClick = onFront) { Icon(Icons.Outlined.CenterFocusStrong, "Bring to the front", tint = p.muted) }
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close this panel", tint = p.muted) }
        }
        message?.let {
            Text(
                it, Modifier.fillMaxWidth().background(p.field).clickable(onClick = onMessage).padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = p.ink,
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** What a panel's title bar says. */
fun panelTitle(store: Store, panel: SpacePanel): String = when (panel) {
    SpacePanel.Main -> "Briareus"
    SpacePanel.Status -> "Status"
    SpacePanel.Voice -> "Voice"
    is SpacePanel.Conversation -> store.sessions.value[panel.sessionId]?.title ?: "Conversation"
    is SpacePanel.Pulls -> "Pull requests · ${store.projectTitle(panel.repo)}"
    is SpacePanel.Pull -> "${store.projectTitle(panel.repo)} #${panel.number}"
    is SpacePanel.Diff -> "Diff · ${store.projectTitle(panel.repo)} #${panel.number}"
    is SpacePanel.Preview -> "Preview · " + (store.sessions.value[panel.sessionId]?.title ?: panel.url)
    is SpacePanel.Web -> panel.url.toUri().let { (it.host ?: "") + (it.path ?: "") }
}

/** What a panel other than the main window shows before the headset is paired. */
@Composable
fun PairFirst() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.canvas), contentAlignment = Alignment.Center) {
        Text("Pair with a Briareus server in the main window first.", Modifier.padding(24.dp), color = p.muted)
    }
}

// MARK: - The diff

/** A pull request's changed files: the list on the left, every file's lines on the right, opened at [file] when one was asked for. */
@Composable
fun DiffScreen(store: Store, repo: String, number: Int, file: String?) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var read by remember(repo, number) { mutableStateOf<PullFiles?>(null) }
    var error by remember(repo, number) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(repo, number, reload) {
        val c = store.client ?: return@LaunchedEffect
        try {
            read = c.pullFiles(repo, number); error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if ((e as? ApiError)?.unauthorized == true) store.failed(e)
            error = (e as? ApiError)?.description ?: e.message ?: "The pull request's files could not be read."
        }
    }
    val files = read?.files.orEmpty()
    // Where each file's header sits in the list: one row for the header, then its lines.
    val starts = remember(files) { files.runningFold(0) { at, f -> at + 1 + f.lines.size.coerceAtLeast(1) } }
    LaunchedEffect(files, file) {
        val at = file?.let { PullFile.find(files, it) }?.let(files::indexOf) ?: return@LaunchedEffect
        list.scrollToItem(starts[at])
    }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${store.projectTitle(repo)} #$number", style = MaterialTheme.typography.titleMedium, color = p.ink)
                if (files.isNotEmpty()) Text(
                    "${files.size} file${if (files.size == 1) "" else "s"}" + (if (read?.more == true) " shown, more on GitHub" else "") +
                        " · +${files.sumOf { it.additions }} −${files.sumOf { it.deletions }}",
                    style = MaterialTheme.typography.labelMedium, color = p.muted,
                )
            }
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Read it again", tint = p.muted) }
        }
        HorizontalDivider(color = p.line)
        error?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = p.danger) }
        if (read == null && error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        if (read == null) return@Column
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(280.dp).fillMaxHeight().background(p.sidebar)) {
                itemsIndexed(files, key = { _, f -> f.path }) { i, f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { scope.launch { list.animateScrollToItem(starts[i]) } }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f.path.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("+${f.additions}", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = p.ok)
                        Text("−${f.deletions}", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = p.danger)
                    }
                }
            }
            VerticalDivider(color = p.line)
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = list) {
                files.forEach { f ->
                    item(key = "h:" + f.path) {
                        Row(Modifier.fillMaxWidth().background(p.raise).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontFamily = Mono), color = p.ink)
                            Text(f.status, style = MaterialTheme.typography.labelSmall, color = p.muted)
                        }
                    }
                    val lines = f.lines
                    if (lines.isEmpty()) item(key = "e:" + f.path) {
                        Text("No diff to show: binary, or too large for GitHub to render.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = p.muted)
                    }
                    itemsIndexed(lines, key = { i, _ -> "l:${f.path}:$i" }) { _, line -> DiffRow(line) }
                }
            }
        }
    }
}

@Composable
private fun DiffRow(line: DiffLine) {
    val p = LocalPalette.current
    val (background, ink) = when (line.kind) {
        DiffLine.Kind.ADDED -> p.ok.copy(alpha = 0.14f) to p.ink
        DiffLine.Kind.REMOVED -> p.danger.copy(alpha = 0.14f) to p.ink
        DiffLine.Kind.HUNK -> p.sunken to p.muted
        DiffLine.Kind.NOTE -> Color.Transparent to p.muted
        DiffLine.Kind.CONTEXT -> Color.Transparent to p.ink
    }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono)
    Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 1.dp)) {
        Text(line.old?.toString().orEmpty(), Modifier.width(44.dp), style = mono, color = p.muted, maxLines = 1)
        Text(line.new?.toString().orEmpty(), Modifier.width(44.dp), style = mono, color = p.muted, maxLines = 1)
        val mark = when (line.kind) { DiffLine.Kind.ADDED -> "+"; DiffLine.Kind.REMOVED -> "−"; else -> " " }
        Text(mark, Modifier.width(16.dp), style = mono, color = if (line.kind == DiffLine.Kind.ADDED) p.ok else p.danger)
        Text(line.text, Modifier.weight(1f), style = mono, color = ink)
    }
}

// MARK: - Pages

/**
 * A web page inside the space: what a conversation's ▶ Run serves, or a GitHub page. Opening the browser would end the
 * immersive space, so pages stay here: navigation keeps to the page's own site, and a link elsewhere opens another page
 * panel through [onElsewhere]. A new [url] needs a new page: the caller keys this on it.
 */
@SuppressLint("SetJavaScriptEnabled") // The pages are the user's own app and GitHub, and both need their scripts.
@Composable
fun PageScreen(url: String, onElsewhere: (String) -> Unit) {
    val p = LocalPalette.current
    val origin = remember(url) { url.toUri().let { "${it.scheme}://${it.authority}" } }
    var view by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var at by remember { mutableStateOf(url) }
    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { view?.takeIf { it.canGoBack() }?.goBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = p.muted) }
            IconButton(onClick = { view?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload", tint = p.muted) }
            Text(
                at, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (loading) CircularProgressIndicator(Modifier.padding(end = 12.dp).width(20.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider(color = p.line)
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val target = request.url
                            if ("${target.scheme}://${target.authority}" == origin) return false
                            if (target.scheme == "https" || target.scheme == "http") onElsewhere(target.toString())
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true; url?.let { at = it } }
                        override fun onPageFinished(view: WebView, url: String?) { loading = false }
                    }
                    loadUrl(url)
                    view = this
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
