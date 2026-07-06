package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.AbortAction
import cn.com.omnimind.assists.task.vlmserver.ClickAction
import cn.com.omnimind.assists.task.vlmserver.FinishedAction
import cn.com.omnimind.assists.task.vlmserver.InfoAction
import cn.com.omnimind.assists.task.vlmserver.InputTextAction
import cn.com.omnimind.assists.task.vlmserver.LongPressAction
import cn.com.omnimind.assists.task.vlmserver.OpenAppAction
import cn.com.omnimind.assists.task.vlmserver.PressKeyAction
import cn.com.omnimind.assists.task.vlmserver.SwipeAction
import cn.com.omnimind.assists.task.vlmserver.UIAction
import cn.com.omnimind.assists.task.vlmserver.WaitAction
import cn.com.omnimind.baselib.runlog.OobActionSchema

// ── typed bridge ──────────────────────────────────────────────────────────────

fun Map<String, Any?>.toUIAction(): UIAction? {
    val rawName = this[OobActionSchema.ROOT_TOOL] as? String ?: return null
    val toolName = OobActionSchema.normalizeToolName(rawName).ifBlank { return null }
    val args = normalizeStepArgs(toolName, mapArg(this[OobActionSchema.ROOT_ARGS]))
    return buildUIAction(toolName, args)
}

private fun normalizeStepArgs(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
    if (toolName != OobActionSchema.TOOL_OPEN_APP || args.containsKey(OobActionSchema.ARG_PACKAGE_NAME)) return args
    val packageName = firstNonBlank(args["packageName"], args["package"])
    if (packageName.isBlank()) return args
    return LinkedHashMap<String, Any?>().apply {
        putAll(args)
        put(OobActionSchema.ARG_PACKAGE_NAME, packageName)
    }
}

private fun buildUIAction(toolName: String, args: Map<String, Any?>): UIAction? = when (toolName) {
    OobActionSchema.TOOL_CLICK -> ClickAction(
        targetDescription = firstNonBlank(args[OobActionSchema.ARG_TARGET_DESCRIPTION]),
        x = floatArg(args[OobActionSchema.ARG_X], defaultValue = 0f),
        y = floatArg(args[OobActionSchema.ARG_Y], defaultValue = 0f),
    )
    OobActionSchema.TOOL_INPUT_TEXT -> InputTextAction(
        targetDescription = firstNonBlank(args[OobActionSchema.ARG_TARGET_DESCRIPTION]),
        text = firstNonBlank(args[OobActionSchema.ARG_TEXT]),
        x = floatArg(args[OobActionSchema.ARG_X], defaultValue = 0f),
        y = floatArg(args[OobActionSchema.ARG_Y], defaultValue = 0f),
    )
    OobActionSchema.TOOL_SWIPE -> SwipeAction(
        targetDescription = firstNonBlank(args[OobActionSchema.ARG_TARGET_DESCRIPTION]),
        x1 = floatArg(args[OobActionSchema.ARG_X1], defaultValue = 0f),
        y1 = floatArg(args[OobActionSchema.ARG_Y1], defaultValue = 0f),
        x2 = floatArg(args[OobActionSchema.ARG_X2], defaultValue = 0f),
        y2 = floatArg(args[OobActionSchema.ARG_Y2], defaultValue = 0f),
        durationMs = longArg(args[OobActionSchema.ARG_DURATION_MS], defaultValue = 1500L),
        direction = (args[OobActionSchema.ARG_DIRECTION] as? String)?.lowercase(),
    )
    OobActionSchema.TOOL_LONG_PRESS -> LongPressAction(
        targetDescription = firstNonBlank(args[OobActionSchema.ARG_TARGET_DESCRIPTION]),
        x = floatArg(args[OobActionSchema.ARG_X], defaultValue = 0f),
        y = floatArg(args[OobActionSchema.ARG_Y], defaultValue = 0f),
    )
    OobActionSchema.TOOL_OPEN_APP -> OpenAppAction(
        packageName = firstNonBlank(args[OobActionSchema.ARG_PACKAGE_NAME]),
    )
    OobActionSchema.TOOL_PRESS_KEY -> PressKeyAction(
        key = firstNonBlank(args[OobActionSchema.ARG_KEY]).lowercase(),
    )
    OobActionSchema.TOOL_WAIT -> WaitAction(
        timeS = (args[OobActionSchema.ARG_TIME_S] as? Number)?.toDouble(),
        durationMs = longArg(args[OobActionSchema.ARG_DURATION_MS], defaultValue = 0L).takeIf { it > 0 },
    )
    OobActionSchema.TOOL_FINISHED -> FinishedAction(
        content = firstNonBlank(args[OobActionSchema.ARG_CONTENT]),
    )
    OobActionSchema.TOOL_INFO -> InfoAction(
        value = firstNonBlank(args[OobActionSchema.ARG_VALUE]),
    )
    OobActionSchema.TOOL_ABORT, OobActionSchema.TOOL_FEEDBACK -> AbortAction(
        value = firstNonBlank(args[OobActionSchema.ARG_VALUE]),
    )
    else -> null
}

// ── compat helpers (replaces OobActionCodec action-parsing methods) ────────────

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
