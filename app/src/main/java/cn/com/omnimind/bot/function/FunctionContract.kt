package cn.com.omnimind.bot.function

internal object FunctionContract {
    private val sourceStateKeys = setOf(
        "after",
        "analysis",
        "before",
        "bounds",
        "cards",
        "currentxml",
        "diagnostics",
        "dstctx",
        "latestanalysis",
        "nextobservation",
        "observation",
        "observationafteract",
        "observationbeforeact",
        "observationxml",
        "page",
        "runlog",
        "screenshot",
        "screenshotbase64",
        "screenshotpath",
        "sourcepage",
        "sourcestate",
        "sourcexml",
        "srcctx",
        "targetevidence",
        "xml",
    )

    fun sanitize(spec: Map<*, *>): Map<String, Any?> = sanitizeMap(spec)

    private fun sanitizeMap(value: Map<*, *>): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            value.forEach { (rawKey, item) ->
                val key = rawKey?.toString() ?: return@forEach
                val normalized = normalizeKey(key)
                when {
                    normalized == "sourcecontext" -> {
                        sanitizeSourceContext(item).takeIf { it.isNotEmpty() }?.let { put(key, it) }
                    }
                    normalized !in sourceStateKeys -> put(key, sanitizeValue(item))
                }
            }
        }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> sanitizeMap(value)
        is Iterable<*> -> value.map(::sanitizeValue)
        is Array<*> -> value.map(::sanitizeValue)
        else -> value
    }

    private fun sanitizeSourceContext(value: Any?): Map<String, Any?> {
        val context = value as? Map<*, *> ?: return emptyMap()
        val coordinateSpace = context.entries.firstOrNull { normalizeKey(it.key?.toString().orEmpty()) == "coordinatespace" }
            ?.value?.toString()?.trim().orEmpty()
        val actionIndex = context.entries.firstOrNull { normalizeKey(it.key?.toString().orEmpty()) == "actionindex" }
            ?.value
        return linkedMapOf<String, Any?>().apply {
            coordinateSpace.takeIf { it.isNotEmpty() }?.let { put("coordinate_space", it) }
            when (actionIndex) {
                is Number -> put("action_index", actionIndex.toInt())
                is String -> actionIndex.trim().toIntOrNull()?.let { put("action_index", it) }
            }
        }
    }

    private fun normalizeKey(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}
