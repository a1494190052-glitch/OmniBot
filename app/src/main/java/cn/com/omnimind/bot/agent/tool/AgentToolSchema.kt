package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object AgentToolSchema {
    fun validate(toolName: String, schema: JsonObject, arguments: JsonObject) {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(type.isEmpty() || type == "object") { "Tool $toolName schema type must be object" }
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        required.forEach { field ->
            require(arguments[field] != null && arguments[field] !is JsonNull) {
                "Tool $toolName missing required argument: $field"
            }
        }
        if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
            require(arguments.keys.all(properties::containsKey)) {
                "Tool $toolName contains unknown arguments"
            }
        }
        arguments.forEach { (field, value) ->
            val fieldSchema = properties[field] as? JsonObject ?: return@forEach
            validateField(toolName, field, value, fieldSchema)
        }
    }

    private fun validateField(
        toolName: String,
        field: String,
        value: JsonElement,
        schema: JsonObject,
    ) {
        val expected = schema["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(expected.isEmpty() || value.matchesType(expected)) {
            "Tool $toolName argument $field expected $expected"
        }
        val allowed = (schema["enum"] as? JsonArray).orEmpty()
        require(allowed.isEmpty() || value in allowed) {
            "Tool $toolName argument $field is not allowed"
        }
        (value as? JsonPrimitive)?.doubleOrNull?.let { number ->
            val minimum = schema["minimum"]?.jsonPrimitive?.doubleOrNull
            val maximum = schema["maximum"]?.jsonPrimitive?.doubleOrNull
            require(minimum == null || number >= minimum) {
                "Tool $toolName argument $field is below minimum"
            }
            require(maximum == null || number <= maximum) {
                "Tool $toolName argument $field is above maximum"
            }
        }
    }

    private fun JsonElement.matchesType(expected: String): Boolean = when (expected) {
        "string" -> this is JsonPrimitive && isString
        "number" -> this is JsonPrimitive && !isString && doubleOrNull != null
        "integer" -> this is JsonPrimitive && !isString && longOrNull != null
        "boolean" -> this is JsonPrimitive && !isString && booleanOrNull != null
        "array" -> this is JsonArray
        "object" -> this is JsonObject
        "null" -> this is JsonNull
        else -> true
    }
}
