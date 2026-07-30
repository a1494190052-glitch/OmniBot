package cn.com.omnimind.bot.omniflow

import android.content.Context
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun mapValue(value: Any?): Map<String, Any?> =
    when (value) {
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) -> if (key != null) put(key.toString(), item) }
        }
        else -> emptyMap()
    }

internal fun intValue(vararg values: Any?, defaultValue: Int): Int {
    values.forEach { value ->
        when (value) {
            is Number -> return value.toInt()
            is String -> value.trim().toIntOrNull()?.let { return it }
        }
    }
    return defaultValue
}

internal fun firstText(vararg values: Any?): String {
    values.forEach { value ->
        val text = value?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text
    }
    return ""
}

internal fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(
        value.entries.associate { (key, item) -> key.toString() to jsonValue(item) },
    )
    is List<*> -> JsonArray(value.map(::jsonValue))
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

internal fun omniFlowInternalRoot(context: Context): File = File(
    context.applicationInfo.dataDir,
    "workspace/.omnibot",
)
