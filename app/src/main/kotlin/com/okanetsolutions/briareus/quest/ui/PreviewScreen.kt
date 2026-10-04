package com.okanetsolutions.briareus.quest.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.core.net.toUri
import com.okanetsolutions.briareus.core.PreviewAccess
import com.okanetsolutions.briareus.quest.Store

/**
 * A ▶ Run preview in a browser of the app's own, as the Windows client does it. Cloudflare Access guards the preview
 * hosts; a page load there carries the server's service token, and Access answers with its own cookie, which lets the
 * page's other requests through. Without a token the page shows Access's sign-in, and its cookie keeps the sign-in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(store: Store, url: String) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var address by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf<PreviewAccess?>(null) }
    val web = remember {
        WebView(context).apply {
            // The served app is a web app and needs its scripts; nothing on the device is any business of its.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                // A load the page starts itself (a link, a redirect) has no headers of ours, so one to a preview host is
                // started again with them.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val headers = access?.headersFor(request.url.toString()).orEmpty()
                    if (headers.isEmpty()) return false
                    view.loadUrl(request.url.toString(), headers)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { address = url }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { address = url; canGoBack = view.canGoBack() }
            }
        }
    }
    fun load(target: String) = web.loadUrl(target, access?.headersFor(target).orEmpty())

    LaunchedEffect(url) { access = store.previewAccess(); load(url) }
    BackHandler(enabled = canGoBack) { web.goBack() }

    Column(Modifier.fillMaxSize().background(p.canvas)) {
        Row(Modifier.fillMaxWidth().background(p.sidebar).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▶ Run", style = MaterialTheme.typography.titleMedium, color = p.ink)
            Text(address, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { load(web.url ?: url) }) { Icon(Icons.Outlined.Refresh, "Reload", tint = p.muted) }
            IconButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, address.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)) }
            }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open in the browser", tint = p.muted) }
        }
        AndroidView({ web }, Modifier.weight(1f).fillMaxWidth())
    }
}
