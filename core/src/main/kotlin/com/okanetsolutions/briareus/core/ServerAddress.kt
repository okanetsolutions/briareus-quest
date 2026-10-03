package com.okanetsolutions.briareus.core

/** An HTTPS origin and the client API base beneath it. */
data class ServerAddress(val origin: String, val host: String, val port: Int?) {
    val baseUrl: String get() = "$origin/api/v1/"

    companion object {
        private val HOST_CHARS = Regex("^[a-z0-9._\\-]+$|^\\[[0-9a-f:.]+]$")
        private val ACCEPTED_PATHS = setOf("", "/", "/api/v1", "/api/v1/")

        /**
         * Reads what the user typed: `https://host[:port]`, optionally ending in `/api/v1`. No credentials, query, fragment
         * or other path, and never plain HTTP.
         */
        @Suppress("CyclomaticComplexMethod") // One rule per branch, read top to bottom; splitting it would hide the order.
        fun parse(input: String): ServerAddress? {
            val text = input.trim()
            if (!text.regionMatches(0, "https://", 0, 8, ignoreCase = true)) return null
            val rest = text.substring(8)
            if ('?' in rest || '#' in rest) return null
            val slash = rest.indexOf('/')
            val authority = if (slash >= 0) rest.substring(0, slash) else rest
            val path = if (slash >= 0) rest.substring(slash) else ""
            if (authority.isEmpty() || '@' in authority || path !in ACCEPTED_PATHS) return null
            val host: String
            var port: Int? = null
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                host = authority.substring(0, close + 1)
                val after = authority.substring(close + 1)
                if (after.isNotEmpty()) {
                    if (!after.startsWith(":")) return null
                    port = parsePort(after.substring(1)) ?: return null
                }
            } else {
                val colon = authority.indexOf(':')
                host = if (colon >= 0) authority.substring(0, colon) else authority
                if (colon >= 0) port = parsePort(authority.substring(colon + 1)) ?: return null
            }
            val folded = host.lowercase()
            if (folded.isEmpty() || !HOST_CHARS.matches(folded)) return null
            if (port == 443) port = null
            val origin = if (port != null) "https://$folded:$port" else "https://$folded"
            return ServerAddress(origin, folded, port)
        }

        private fun parsePort(text: String): Int? = text.toIntOrNull()?.takeIf { text.all(Char::isDigit) && it in 1..65535 }
    }
}

object Token {
    /** A device token: `brm_` and 43 URL-safe characters. */
    fun valid(token: String): Boolean =
        token.length == 47 && token.startsWith("brm_") && token.substring(4).all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
}
