package cn.com.omnimind.bot.runlog

fun mapArg(value: Any?): Map<String, Any?> =
    when (value) {
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) -> if (key != null) put(key.toString(), item) }
        }
        else -> emptyMap()
    }

fun listArg(value: Any?): List<Any?> =
    when (value) {
        is List<*> -> value
        is Array<*> -> value.toList()
        else -> emptyList()
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

fun floatArg(vararg values: Any?, defaultValue: Float): Float {
    values.forEach { value ->
        when (value) {
            is Number -> return value.toFloat()
            is String -> value.trim().toFloatOrNull()?.let { return it }
        }
    }
    return defaultValue
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
            when (value.trim().lowercase()) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> defaultValue
            }
        }
        is Number -> value.toInt() != 0
        else -> defaultValue
    }

fun firstNonBlank(vararg values: Any?): String {
    values.forEach { value ->
        val text = value?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text
    }
    return ""
}
