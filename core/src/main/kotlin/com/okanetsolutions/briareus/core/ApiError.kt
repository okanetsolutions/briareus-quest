package com.okanetsolutions.briareus.core

/** Why a call did not answer. `message` is the server's `error` or the transport's words. */
class ApiError(
    val kind: Kind,
    val status: Int = 0,
    override val message: String? = null,
    /** Seconds from a Retry-After header, or null without one. */
    val retryAfter: Double? = null,
) : Exception(message) {
    enum class Kind { INVALID_ADDRESS, INVALID_TOKEN, REDIRECTED, NON_JSON, INCOMPATIBLE_VERSION, OVERSIZED_REQUEST, HTTP, NETWORK, CANCELLED }

    val unauthorized: Boolean get() = kind == Kind.HTTP && status == 401

    /** A 4xx is a definite refusal; anything else may have gone through, so a write is never retried on it. */
    val isRefusal: Boolean get() = kind == Kind.HTTP && status in 400..499

    /** What the user is told. */
    val description: String
        get() = when (kind) {
            Kind.INVALID_ADDRESS -> "Enter an HTTPS server address, optionally ending in /api/v1, without credentials or query parameters."
            Kind.INVALID_TOKEN -> "Paste the complete token from Settings → Devices and clients."
            Kind.REDIRECTED -> "The server redirected this request. Check the Cloudflare Access exception for /api/v1 and /api/v1/*."
            Kind.NON_JSON -> "The server returned an unexpected response. Check that the client API is deployed and reachable through Cloudflare Access."
            Kind.INCOMPATIBLE_VERSION -> "This server uses an unsupported client API version."
            Kind.OVERSIZED_REQUEST -> "This message exceeds the server’s 1 MiB request limit. Shorten it before sending."
            Kind.NETWORK -> message ?: "The server could not be reached."
            Kind.CANCELLED -> "Cancelled."
            Kind.HTTP -> when (status) {
                401 -> "This token has expired or was revoked. Reconnect with a new token."
                403 -> "Access denied: ${message.orEmpty()}"
                429 -> "The server is rate limiting requests. Updates will resume after a delay."
                else -> "${message ?: "Request failed"} (HTTP $status)"
            }
        }

    override fun toString(): String = "ApiError($kind, $status, $message)"
}
