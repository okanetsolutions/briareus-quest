package com.okanetsolutions.briareus.quest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.okanetsolutions.briareus.core.Session
import com.okanetsolutions.briareus.quest.Store

/**
 * A session's dot: the shared status roles from the tokens (working in accent, ready in ok, failed in danger, closed in
 * dot, interrupted and cancelled in warn), except that an idle session waiting for an answer or a findings decision
 * takes warn, the "needs attention" role, rather than idle's ok.
 */
@Composable
fun stateColor(session: Session): Color {
    val p = LocalPalette.current
    return if (session.state.needsYou) p.warn else p.statusColor(session.status)
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Int = 9) {
    Box(modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun StateBadge(session: Session) {
    val state = session.state
    val color = stateColor(session)
    Row(
        Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, size = 7)
        Text(state.label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        text, modifier.background(p.field, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun LinkIndicator(link: Store.Link) {
    val p = LocalPalette.current
    val (color, text) = when (link) {
        Store.Link.LIVE -> p.ok to "Live"
        Store.Link.CONNECTING -> p.warn to "Connecting"
        Store.Link.OFFLINE -> p.dot to "Offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, size = 7)
        Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = p.muted)
    }
}
