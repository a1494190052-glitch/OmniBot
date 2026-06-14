package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema

/**
 * Canonical action parser for OmniFlow Function steps.
 *
 * This is the single place where action/tool names are mapped into the compact
 * action vocabulary consumed by replay, UDEG, and update_function.
 */
object OobActionCodec {
    const val ACTION_CLICK = OobCanonicalActionSchema.TOOL_CLICK
    const val ACTION_LONG_PRESS = OobCanonicalActionSchema.TOOL_LONG_PRESS
    const val ACTION_INPUT_TEXT = OobCanonicalActionSchema.TOOL_INPUT_TEXT
    const val ACTION_SWIPE = OobCanonicalActionSchema.TOOL_SWIPE
    const val ACTION_OPEN_APP = OobCanonicalActionSchema.TOOL_OPEN_APP
    const val ACTION_PRESS_KEY = OobCanonicalActionSchema.TOOL_PRESS_KEY
    const val ACTION_WAIT = OobCanonicalActionSchema.TOOL_WAIT
    const val ACTION_FINISHED = OobCanonicalActionSchema.TOOL_FINISHED

    val executableActions: Set<String> = OobCanonicalActionSchema.replayableToolNames

    val coordinateActions: Set<String> = OobCanonicalActionSchema.coordinateToolNames

    val pointTargetActions: Set<String> = OobCanonicalActionSchema.pointTargetToolNames

    val userFacingActions: Set<String> = OobCanonicalActionSchema.recordableToolNames

    fun normalizeName(raw: String): String =
        OobCanonicalActionSchema.normalizeToolName(raw)

    fun canonicalActionForName(raw: String): String? {
        return OobCanonicalActionSchema.canonicalToolName(raw)
            ?.takeIf { it in executableActions }
    }

    fun actionNameForStep(step: Map<String, Any?>): String {
        val raw = rawActionNameForStep(step)
        return canonicalActionForName(raw)
            ?: normalizeName(raw).ifBlank { "unknown" }
    }

    fun isUserFacingAction(actionType: String): Boolean =
        canonicalActionForName(actionType) in userFacingActions

    fun isRouteAction(actionType: String, args: Map<String, Any?> = emptyMap()): Boolean {
        val canonical = canonicalActionForName(actionType) ?: normalizeName(actionType)
        return canonical in OobCanonicalActionSchema.routeToolNames
    }

    fun argsForStep(step: Map<String, Any?>): Map<String, Any?> {
        val rawAction = rawActionNameForStep(step)
        val canonicalAction = canonicalActionForName(rawAction)
            ?: return mapArg(step[OobCanonicalActionSchema.ROOT_ARGS])
        val allowedArgs = OobCanonicalActionSchema.argNames(canonicalAction)
        return normalizeActionArgs(
            action = canonicalAction,
            args = mapArg(step[OobCanonicalActionSchema.ROOT_ARGS]),
        )
            .filterKeys { it in allowedArgs }
    }

    fun sourceContextForStep(step: Map<String, Any?>): Map<String, Any?> =
        mapArg(step["source_context"]).ifEmpty { mapArg(mapArg(step[OobCanonicalActionSchema.ROOT_ARGS])["source_context"]) }

    fun sourceActionForStep(step: Map<String, Any?>): Map<String, Any?> =
        mapArg(sourceContextForStep(step)["action"])

    fun pageXmlFromContext(context: Map<String, Any?>): String =
        RunLogXmlArtifacts.pageXmlFromContext(context)

    private fun rawActionNameForStep(step: Map<String, Any?>): String {
        return firstNonBlankActionName(
            step[OobCanonicalActionSchema.ROOT_TOOL],
        )
    }

    private fun firstNonBlankActionName(vararg values: Any?): String {
        values.forEach { value ->
            val text = when (value) {
                is String -> value.trim()
                is CharSequence -> value.toString().trim()
                else -> ""
            }
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    fun actionArgsSummary(
        step: Map<String, Any?>,
        maxValueChars: Int = DEFAULT_ACTION_SUMMARY_VALUE_CHARS,
    ): Map<String, Any?> =
        actionArgsSummary(
            actionType = actionNameForStep(step),
            args = argsForStep(step),
            sourceAction = sourceActionForStep(step),
            maxValueChars = maxValueChars,
        )

    fun actionArgsSummary(
        actionType: String,
        args: Map<String, Any?>,
        sourceAction: Map<String, Any?>,
        maxValueChars: Int = DEFAULT_ACTION_SUMMARY_VALUE_CHARS,
    ): Map<String, Any?> {
        val summary = linkedMapOf<String, Any?>()
        for (key in OobCanonicalActionSchema.sourceContextArgNames) {
            if (key == OobCanonicalActionSchema.ARG_TEXT) continue
            summary.putFirstPresent(key, args[key], sourceAction[key])
        }
        if (actionType == ACTION_INPUT_TEXT) {
            val text = firstNonBlank(args[OobCanonicalActionSchema.ARG_TEXT], sourceAction[OobCanonicalActionSchema.ARG_TEXT])
            if (text.isNotBlank()) {
                summary["text_present"] = true
                summary["text_length"] = text.length
                summary["text_redacted"] = true
            }
        }
        return summary.filterValues { value ->
            value != null && value.toString().trim().isNotEmpty()
        }.mapValues { (_, value) ->
            if (value is String) value.take(maxValueChars) else value
        }
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

    fun firstNonBlank(vararg values: Any?): String {
        values.forEach { value ->
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun normalizeActionArgs(
        action: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        if (action != ACTION_OPEN_APP || args.containsKey(OobCanonicalActionSchema.ARG_PACKAGE_NAME)) {
            return args
        }
        val packageName = firstNonBlank(args["packageName"], args["package"])
        if (packageName.isBlank()) {
            return args
        }
        return LinkedHashMap<String, Any?>().apply {
            putAll(args)
            put(OobCanonicalActionSchema.ARG_PACKAGE_NAME, packageName)
        }
    }

    private fun MutableMap<String, Any?>.putFirstPresent(key: String, vararg values: Any?) {
        values.firstOrNull { value ->
            value != null && value.toString().trim().isNotEmpty()
        }?.let { put(key, it) }
    }

    private const val DEFAULT_ACTION_SUMMARY_VALUE_CHARS = 160
}
