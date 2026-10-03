package com.okanetsolutions.briareus.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient on what it reads: responses may grow, so unknown fields and kinds are ignored, never an error. */
val BriareusJson: Json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.nonEmpty(key: String): String? = str(key)?.takeIf { it.isNotBlank() }
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
fun JsonObject.int(key: String): Int? = long(key)?.toInt()
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.objects(key: String): List<JsonObject> = array(key)?.filterIsInstance<JsonObject>().orEmpty()
fun JsonObject.strings(key: String): List<String> =
    array(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

/** A JSON value from a Kotlin one, for building request arguments. */
fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.filter { it.value != null }.associate { it.key.toString() to jsonOf(it.value) })
    is Iterable<*> -> JsonArray(value.map(::jsonOf))
    else -> error("Not a JSON value: ${value::class}")
}

fun args(vararg pairs: Pair<String, Any?>): JsonObject = jsonOf(mapOf(*pairs)) as JsonObject
