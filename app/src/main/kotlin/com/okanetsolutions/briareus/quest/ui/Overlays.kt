package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Dialogs and menus drawn inside the panel. A panel in the space is not an Android window that can open others, so
 * Compose's own dialogs and popups crash there; these draw over the panel instead, through the [OverlayHost] that
 * [PanelFrame] puts around each panel. Outside a panel (no host) they fall back to Material's own.
 */
class OverlayHost {
    class Layer(val anchor: Offset?, val onDismiss: () -> Unit, val content: @Composable () -> Unit)

    val layers = mutableStateListOf<Layer>()
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** Wraps a panel's content so its dialogs and menus have somewhere to draw. */
@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
    val p = LocalPalette.current
    val density = LocalDensity.current
    CompositionLocalProvider(LocalOverlayHost provides host) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            content()
            host.layers.forEach { layer ->
                val scrim = if (layer.anchor == null) Color.Black.copy(alpha = 0.45f) else Color.Transparent
                Box(Modifier.fillMaxSize().background(scrim).clickable(interactionSource = null, indication = null) { layer.onDismiss() })
                if (layer.anchor == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { layer.content() }
                } else {
                    // Below the anchor, kept inside the panel.
                    val x = with(density) { layer.anchor.x.toDp() }.coerceIn(0.dp, (maxWidth - MENU_WIDTH).coerceAtLeast(0.dp))
                    val y = with(density) { layer.anchor.y.toDp() }.coerceIn(0.dp, (maxHeight - 120.dp).coerceAtLeast(0.dp))
                    Box(
                        Modifier.offset { with(density) { IntOffset(x.roundToPx(), y.roundToPx()) } }
                            .widthIn(min = 200.dp, max = MENU_WIDTH).heightIn(max = maxHeight - y)
                            .background(p.raise, RoundedCornerShape(10.dp)),
                    ) { layer.content() }
                }
            }
        }
    }
}

private val MENU_WIDTH = 360.dp
private val ANCHOR_HEIGHT = 44.dp

/** Shows [layer] in the panel's host while this is composed. */
@Composable
private fun Overlay(anchor: Offset?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current ?: return
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, anchor) {
        val layer = OverlayHost.Layer(anchor, { latestDismiss() }) { latestContent() }
        host.layers += layer
        onDispose { host.layers -= layer }
    }
}

/** [AlertDialog]'s shape, drawn in the panel. */
@Composable
fun PanelAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    if (LocalOverlayHost.current == null) {
        AlertDialog(onDismissRequest = onDismissRequest, confirmButton = confirmButton, dismissButton = dismissButton, title = title, text = text)
        return
    }
    val p = LocalPalette.current
    Overlay(null, onDismissRequest) {
        Column(
            Modifier.widthIn(min = 320.dp, max = 560.dp).padding(24.dp).background(p.raise, RoundedCornerShape(24.dp))
                .clickable(interactionSource = null, indication = null) {}.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(color = p.ink)) { it() } }
            text?.let {
                Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = p.muted)) { it() }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                dismissButton?.let { it(); Spacer(Modifier.size(8.dp)) }
                confirmButton()
            }
        }
    }
}

/** [DropdownMenu], drawn in the panel just below where it is placed. */
@Composable
fun PanelMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (LocalOverlayHost.current == null) {
        DropdownMenu(expanded, onDismissRequest, content = content)
        return
    }
    var anchor by remember { mutableStateOf<Offset?>(null) }
    val below = with(LocalDensity.current) { ANCHOR_HEIGHT.toPx() }
    // Where the menu's anchor is in the panel: the menu sits in its anchor's box, at the top corner, so it opens one button's height below.
    Box(Modifier.size(0.dp).onGloballyPositioned { anchor = it.positionInRoot() + Offset(0f, below) })
    val at = anchor
    if (expanded && at != null) Overlay(at, onDismissRequest) {
        Column(Modifier.padding(vertical = 8.dp).verticalScroll(rememberScrollState()), content = content)
    }
}
