package com.okanetsolutions.briareus.quest

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.okanetsolutions.briareus.quest.ui.Pane

/**
 * Horizon OS shows each task as a panel of its own. A conversation opened this way gets its own panel beside the main
 * window; opening the same conversation again brings its panel back rather than adding another.
 */
object Windows {
    fun conversationIntent(context: Context, sessionId: String): Intent =
        Intent(context, ConversationActivity::class.java)
            .setData("briareus://session/$sessionId".toUri())
            .putExtra(ConversationActivity.EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

    fun openConversation(context: Context, sessionId: String) = context.startActivity(conversationIntent(context, sessionId))

    /** Each native view has a stable document identity, so different pull requests stay in different panels. */
    fun openPanel(context: Context, pane: Pane) {
        val uri = Uri.Builder().scheme("briareus").authority("panel")
        when (pane) {
            is Pane.Pull -> uri.appendPath("pull").appendPath(pane.repo).appendPath(pane.number.toString()).appendQueryParameter("view", pane.view)
            is Pane.Pulls -> uri.appendPath("pulls").appendPath(pane.repo)
            is Pane.Issue -> uri.appendPath("issue").appendPath(pane.repo).appendPath(pane.number.toString())
            is Pane.Issues -> uri.appendPath("issues").appendPath(pane.repo)
            else -> error("That view has no document panel.")
        }
        context.startActivity(Intent(context, PanelActivity::class.java).setData(uri.build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT))
    }

    fun openPullRun(context: Context, repo: String, number: Int) = context.startActivity(
        Intent(context, PreviewActivity::class.java)
            .setData(Uri.Builder().scheme("briareus").authority("run-pull").appendPath(repo).appendPath(number.toString()).build())
            .putExtra(PreviewActivity.EXTRA_REPO, repo).putExtra(PreviewActivity.EXTRA_PULL, number)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
    )

    /** Follows a Run session while setup publishes its preview address. */
    fun openRun(context: Context, sessionId: String) = context.startActivity(
        Intent(context, PreviewActivity::class.java).setData(Uri.Builder().scheme("briareus").authority("run").appendPath(sessionId).build())
            .putExtra(PreviewActivity.EXTRA_SESSION, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
    )

    /** A ▶ Run preview, in the app's own browser panel so it carries the server's Cloudflare Access service token. */
    fun openPreview(context: Context, url: String) = context.startActivity(
        Intent(context, PreviewActivity::class.java).setData(url.toUri()).putExtra(PreviewActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
    )

    fun openVoice(context: Context, repo: String? = null) = context.startActivity(
        Intent(context, VoiceActivity::class.java).putExtra("repo", repo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT),
    )
}
