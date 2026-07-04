package cn.com.omnimind.bot.function

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Small JSON/value coercion helpers shared by Function services.
 *
 * Keep these helpers mechanical: they should normalize public tool payload
 * shapes, not encode Function policy.
 */
internal object FunctionJson {
    fun mutableJsonMap(value: Map<String, Any?>): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, mutableJsonValue(item))
            }
        }

    fun mutableJsonList(value: List<Any?>): MutableList<Any?> =
        value.map { mutableJsonValue(it) }.toMutableList()

    fun mutableJsonValue(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), mutableJsonValue(item))
                }
            }
            is List<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            is Array<*> -> value.map { mutableJsonValue(it) }.toMutableList()
            else -> value
        }

    fun sanitizeMap(value: Map<*, *>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) put(key.toString(), sanitizeValue(item))
            }
        }

    fun sanitizeValue(value: Any?): Any? =
        when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> sanitizeMap(value)
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }

    fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }

    fun listArg(value: Any?): List<Any?> =
        when (value) {
            is List<*> -> value
            is Array<*> -> value.toList()
            else -> emptyList()
        }

    fun boolArg(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.trim().equals("true", ignoreCase = true) || value.trim() == "1"
            is Number -> value.toInt() != 0
            else -> false
        }

    fun boolArgOrDefault(value: Any?, defaultValue: Boolean): Boolean =
        when (value) {
            null -> defaultValue
            is Boolean -> value
            is String -> {
                val text = value.trim().lowercase()
                when (text) {
                    "true", "1", "yes", "y", "on" -> true
                    "false", "0", "no", "n", "off" -> false
                    else -> defaultValue
                }
            }
            is Number -> value.toInt() != 0
            else -> defaultValue
        }

    fun intArg(vararg values: Any?, defaultValue: Int): Int {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    fun longArg(vararg values: Any?, defaultValue: Long): Long {
        values.forEach { value ->
            when (value) {
                is Number -> return value.toLong()
                is String -> value.trim().toLongOrNull()?.let { return it }
            }
        }
        return defaultValue
    }

    fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Array<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> {
        return jsonObject.entries.associate { (key, value) ->
            key to jsonElementToAny(value)
        }
    }

    fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonObject -> jsonObjectToMap(element)
            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonPrimitive -> when {
                element.isString -> element.contentOrNull.orEmpty()
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                element.doubleOrNull != null -> element.doubleOrNull
                else -> element.contentOrNull.orEmpty()
            }
        }
    }
}
