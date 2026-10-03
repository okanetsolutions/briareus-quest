package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One of the calls this app makes, and the route that answers it. A `{name}` in [path] is filled with the argument of that
 * name, URL-encoded; the other arguments go in the query of a GET or DELETE and in the JSON body otherwise. [set] names a
 * body flag the call always sends as true. [filter] names an argument the route has no parameter for: it is kept back,
 * and the answer's [list] is cut to the rows whose field of that name equals it.
 */
data class ApiRoute(
    val name: String,
    val method: String,
    val path: String,
    val set: String? = null,
    val filter: String? = null,
    val list: String? = null,
)

object Routes {
    val all: List<ApiRoute> = listOf(
        // The token
        ApiRoute("revoke_token", "DELETE", "token"),
        ApiRoute("events", "GET", "events"),
        // Projects
        ApiRoute("projects", "GET", "projects"),
        ApiRoute("branches", "GET", "branches"),
        ApiRoute("runtimes", "GET", "runtimes"),
        ApiRoute("usage", "GET", "usage"),
        ApiRoute("actions", "GET", "actions"),
        ApiRoute("action", "POST", "actions"),
        // Pull requests
        ApiRoute("pulls", "GET", "pulls"),
        ApiRoute("pull", "GET", "pulls/{pr}"),
        ApiRoute("pull_description", "GET", "pulls/{pr}/description"),
        ApiRoute("findings", "GET", "pulls/{pr}/findings"),
        ApiRoute("merge_pull", "POST", "pulls/{pr}/merge"),
        ApiRoute("serve_pull", "POST", "pulls/{pr}/serve"),
        // Sessions. The list has no project parameter: a `repo` argument cuts the answer down here instead.
        ApiRoute("sessions", "GET", "sessions", filter = "repo", list = "sessions"),
        ApiRoute("start_session", "POST", "sessions"),
        ApiRoute("session", "GET", "sessions/{sessionId}"),
        ApiRoute("session_events", "GET", "sessions/{sessionId}/events"),
        ApiRoute("delete", "DELETE", "sessions/{sessionId}"),
        ApiRoute("message", "POST", "sessions/{sessionId}/messages"),
        ApiRoute("drop_message", "DELETE", "sessions/{sessionId}/queue/{index}"),
        ApiRoute("cancel", "POST", "sessions/{sessionId}/cancel"),
        ApiRoute("close", "POST", "sessions/{sessionId}/close"),
        ApiRoute("reopen", "POST", "sessions/{sessionId}/reopen"),
        ApiRoute("review_loop", "POST", "sessions/{sessionId}/review-loop"),
        ApiRoute("complete_findings", "POST", "sessions/{sessionId}/findings/triage"),
        // Composer. These two send raw bytes; the entries say whether the server has them.
        ApiRoute("upload", "POST", "uploads"),
        ApiRoute("transcribe", "POST", "transcribe"),
    )

    private val byName = all.associateBy { it.name }

    /** The table entry for a call name, or null for a name this app does not make. */
    operator fun get(name: String): ApiRoute? = byName[name]
}

enum class Permission(val rank: Int) {
    READ(0), MANAGE(1), ADMIN(2);

    companion object {
        /** Null for a permission this app does not know, which may do nothing. */
        fun of(name: String?): Permission? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** One route the server lists: its method, its path with `{...}` for each parameter, and the least permission that may call it. */
data class CatalogRoute(val method: String, val path: String, val access: String)

/**
 * The routes the server has and who may call each, read from `GET /openapi.json` at pairing and on every launch. A control
 * whose route the server lacks, or that the token's permission may not call, stays unavailable, so the app adapts to
 * older and newer servers.
 */
class RouteCatalog(val routes: List<CatalogRoute>) {
    /** Whether the server has `method path` and [permission] ranks high enough. */
    fun allows(method: String, path: String, permission: Permission?): Boolean {
        permission ?: return false
        // The closest route speaks for the path, as a server's router picks a literal segment over a parameter.
        val best = routes.filter { it.method.equals(method, ignoreCase = true) }
            .mapNotNull { route -> looseness(route.path, path)?.let { route to it } }
            .minByOrNull { it.second }?.first ?: return false
        val needed = Permission.of(best.access) ?: return false
        return permission.rank >= needed.rank
    }

    /** Whether a call of this app's table may be made. */
    fun allows(call: String, permission: Permission?): Boolean {
        val route = Routes[call] ?: return false
        return allows(route.method, route.path, permission)
    }

    fun toJson(): JsonArray = JsonArray(routes.map { args("method" to it.method, "path" to it.path, "access" to it.access) })

    companion object {
        private val METHODS = listOf("get", "post", "put", "patch", "delete")

        /** Reads the server's OpenAPI document (`paths`, with `x-briareus-access` on each operation). */
        fun fromOpenApi(document: JsonObject): RouteCatalog? {
            val paths = document.obj("paths") ?: return null
            val routes = paths.entries.flatMap { (path, ops) ->
                val operations = ops as? JsonObject ?: return@flatMap emptyList()
                METHODS.mapNotNull { method ->
                    val op = operations.obj(method) ?: return@mapNotNull null
                    // A route that says nothing about who may call it is the operator's.
                    CatalogRoute(method.uppercase(), path.trim('/'), op.str("x-briareus-access") ?: "admin")
                }
            }
            return RouteCatalog(routes)
        }

        /** Reads a list saved with [toJson]. */
        fun fromSaved(saved: JsonArray): RouteCatalog? {
            val routes = saved.map { entry ->
                val o = entry as? JsonObject ?: return null
                CatalogRoute(o.str("method") ?: return null, o.str("path") ?: return null, o.str("access") ?: return null)
            }
            return RouteCatalog(routes)
        }

        /**
         * How many parameter segments it took to match, or null when the paths differ. A `{...}` segment on either side
         * matches any segment.
         */
        internal fun looseness(pattern: String, path: String): Int? {
            val a = pattern.trim('/').split('/')
            val b = path.trim('/').split('/')
            if (a.size != b.size) return null
            var loose = 0
            for (i in a.indices) {
                val pa = a[i].startsWith("{"); val pb = b[i].startsWith("{")
                if (pa || pb) loose++ else if (a[i] != b[i]) return null
            }
            return loose
        }
    }
}

internal fun JsonPrimitive.urlValue(inPath: Boolean): String? {
    if (isString) return if (inPath && content.isEmpty()) null else content
    content.toLongOrNull()?.let { return it.toString() }
    content.toDoubleOrNull()?.let { return if (inPath) null else content }
    return when (content) {
        "true" -> if (inPath) null else "1"
        "false" -> if (inPath) null else "0"
        else -> null
    }
}
