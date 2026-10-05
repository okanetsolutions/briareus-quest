package com.okanetsolutions.briareus.quest.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.okanetsolutions.briareus.core.PreviewAccess
import com.okanetsolutions.briareus.quest.Store

/**
 * A ▶ Run preview in a browser of the app's own, as the Windows client does it. Cloudflare Access guards the preview
 * hosts; a page load there carries the server's service token, and Access answers with its own cookie, which lets the
 * page's other requests through. Without a token the page shows Access's sign-in, and its cookie keeps the sign-in.
 */
@Composable
fun PreviewScreen(store: Store, url: String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var address by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    val browser = remember { PreviewBrowser(context) { now, back -> address = now; canGoBack = back } }

    DisposableEffect(browser) { onDispose { browser.destroy() } }
    LaunchedEffect(url) { browser.access = store.previewAccess(); browser.load(url) }
    BackHandler(enabled = canGoBack) { browser.back() }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().background(p.sidebar).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▶ Run", style = MaterialTheme.typography.titleMedium, color = p.ink)
            Text(address, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { browser.reload(url) }) { Icon(Icons.Outlined.Refresh, "Reload", tint = p.muted) }

        }
        AndroidView({ browser.view }, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * The preview's WebView. It is built and set up in one place so the settings visibly reach the one WebView there is:
 * scripts on, since the served app is a web app, and content and file URLs off, since nothing on the device is its business.
 */
@SuppressLint("SetJavaScriptEnabled")
private class PreviewBrowser(context: Context, onPage: (address: String, canGoBack: Boolean) -> Unit) {
    var access: PreviewAccess? = null
    private val web: WebView

    init {
        val created = WebView(context)
        val settings = created.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        created.webViewClient = object : WebViewClient() {
            // A load the page starts itself (a link, a redirect) has no headers of ours, so one to a preview host is
            // started again with them.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme != "https") return true
                if (!request.isForMainFrame) return false
                val headers = access?.headersFor(request.url.toString()).orEmpty()
                if (headers.isEmpty()) return false
                view.loadUrl(request.url.toString(), headers)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onPage(url, view.canGoBack())

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onPage(url, view.canGoBack())
        }
        web = created
    }

    val view: View get() = web

    fun load(target: String) = web.loadUrl(target, access?.headersFor(target).orEmpty())

    fun reload(fallback: String) = load(web.url ?: fallback)

    fun back() = web.goBack()

    fun destroy() { web.stopLoading(); web.destroy() }
}
