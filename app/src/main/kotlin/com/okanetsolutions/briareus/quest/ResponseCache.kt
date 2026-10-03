package com.okanetsolutions.briareus.quest

import android.content.Context
import com.okanetsolutions.briareus.core.BriareusJson
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Saved responses, so a screen opens on what it last showed and then asks only for what changed. Each entry is sealed by
 * the [Vault] and kept in the no-backup directory; entries untouched for 30 days are dropped, and everything goes when the
 * connection is forgotten, revoked or replaced.
 */
class ResponseCache(context: Context, private val vault: Vault) {
    private val dir = File(context.noBackupFilesDir, "responses").apply { mkdirs() }

    fun read(key: String): JsonElement? {
        val file = file(key)
        if (!file.exists()) return null
        val plain = vault.open(file.readBytes(), "cache:$key") ?: run { file.delete(); return null }
        file.setLastModified(System.currentTimeMillis())
        return runCatching { BriareusJson.parseToJsonElement(plain.toString(Charsets.UTF_8)) }.getOrNull()
    }

    fun write(key: String, value: JsonElement) {
        val sealed = vault.seal(value.toString().toByteArray(), "cache:$key")
        val tmp = File(dir, file(key).name + ".tmp")
        tmp.writeBytes(sealed)
        tmp.renameTo(file(key))
    }

    fun remove(key: String) {
        file(key).delete()
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        val limit = now - TimeUnit.DAYS.toMillis(30)
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, digest)
    }
}
