package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonObject

/**
 * The Cloudflare Access service token the server hands a manage token (`GET /preview/access`), so a ▶ Run preview opens
 * without Access's emailed code. It opens every preview, so it goes only to hosts under [hostSuffix], over HTTPS.
 */
data class PreviewAccess(val clientId: String, val clientSecret: String, val hostSuffix: String) {
    /** The headers Access reads, for a request to [url]; empty for any other host. */
    fun headersFor(url: String): Map<String, String> =
        if (applies(url, hostSuffix)) mapOf("CF-Access-Client-Id" to clientId, "CF-Access-Client-Secret" to clientSecret) else emptyMap()

    companion object {
        fun parse(o: JsonObject): PreviewAccess? {
            val suffix = o.nonEmpty("hostSuffix")?.trim('.')?.ifEmpty { null } ?: return null
            return PreviewAccess(o.nonEmpty("clientId") ?: return null, o.nonEmpty("clientSecret") ?: return null, suffix)
        }

        /** Whether [url] is an HTTPS address on a host strictly under [hostSuffix]. The Windows client's rule. */
        fun applies(url: String, hostSuffix: String): Boolean {
            val suffix = hostSuffix.removePrefix(".")
            if (suffix.isEmpty() || !url.startsWith("https://", ignoreCase = true)) return false
            val authority = url.substring(8).takeWhile { it !in "/?#" }
            // Credentials in the address would let it name one host and reach another.
            if ('@' in authority) return false
            val host = authority.substringBefore(':').trimEnd('.')
            return host.length > suffix.length + 1 && host.endsWith(".$suffix", ignoreCase = true)
        }
    }
}
