package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobActionSchema

private fun normalizeStepArgs(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
    if (toolName != OobActionSchema.TOOL_OPEN_APP || args.containsKey(OobActionSchema.ARG_PACKAGE_NAME)) return args
    val packageName = firstNonBlank(args["packageName"], args["package"])
    if (packageName.isBlank()) return args
    return LinkedHashMap<String, Any?>().apply {
        putAll(args)
        put(OobActionSchema.ARG_PACKAGE_NAME, packageName)
    }
}

fun resolveActionName(raw: String): String? =
    OobActionSchema.canonicalToolName(raw)?.takeIf { it in OobActionSchema.replayableToolNames }

fun actionNameForStep(step: Map<String, Any?>): String {
    val raw = step[OobActionSchema.ROOT_TOOL] as? String ?: ""
    return resolveActionName(raw) ?: OobActionSchema.normalizeToolName(raw).ifBlank { "unknown" }
}

fun argsForStep(step: Map<String, Any?>): Map<String, Any?> {
    val raw = step[OobActionSchema.ROOT_TOOL] as? String ?: ""
    val toolName = resolveActionName(raw) ?: return mapArg(step[OobActionSchema.ROOT_ARGS])
    val allowedArgs = OobActionSchema.argNames(toolName)
    return normalizeStepArgs(toolName, mapArg(step[OobActionSchema.ROOT_ARGS]))
        .filterKeys { it in allowedArgs }
}

fun sourceContextForStep(step: Map<String, Any?>): Map<String, Any?> =
    mapArg(step["source_context"]).ifEmpty { mapArg(mapArg(step[OobActionSchema.ROOT_ARGS])["source_context"]) }

fun sourceActionForStep(step: Map<String, Any?>): Map<String, Any?> =
    mapArg(sourceContextForStep(step)["action"])

fun isUserFacingAction(actionType: String): Boolean =
    resolveActionName(actionType) in OobActionSchema.recordableToolNames

fun isRouteAction(actionType: String): Boolean {
    val name = resolveActionName(actionType) ?: OobActionSchema.normalizeToolName(actionType)
    return name in OobActionSchema.routeToolNames
}

fun actionArgsSummary(
    step: Map<String, Any?>,
    maxValueChars: Int = 160,
): Map<String, Any?> = actionArgsSummary(
    actionType = actionNameForStep(step),
    args = argsForStep(step),
    sourceAction = sourceActionForStep(step),
    maxValueChars = maxValueChars,
)

fun actionArgsSummary(
    actionType: String,
    args: Map<String, Any?>,
    sourceAction: Map<String, Any?>,
    maxValueChars: Int = 160,
): Map<String, Any?> {
    val summary = linkedMapOf<String, Any?>()
    for (key in OobActionSchema.sourceContextArgNames) {
        if (key == OobActionSchema.ARG_TEXT) continue
        val v = args[key] ?: sourceAction[key]
        if (v != null && v.toString().trim().isNotEmpty()) summary[key] = v
    }
    if (actionType == OobActionSchema.TOOL_INPUT_TEXT) {
        val text = firstNonBlank(args[OobActionSchema.ARG_TEXT], sourceAction[OobActionSchema.ARG_TEXT])
        if (text.isNotBlank()) {
            summary["text_present"] = true
            summary["text_length"] = text.length
            summary["text_redacted"] = true
        }
    }
    return summary.mapValues { (_, value) ->
        if (value is String) value.take(maxValueChars) else value
    }
}

fun pageXmlFromContext(context: Map<String, Any?>): String =
    RunLogXmlArtifacts.pageXmlFromContext(context)
