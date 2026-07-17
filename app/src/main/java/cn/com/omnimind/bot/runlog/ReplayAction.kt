package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.OobActionSchema

fun resolveActionName(raw: String): String? =
    OobActionSchema.canonicalToolName(raw)
        ?.takeIf { it in OobActionSchema.replayableToolNames }

fun actionNameForStep(step: Map<String, Any?>): String {
    val raw = step[OobActionSchema.ROOT_TOOL] as? String ?: ""
    return resolveActionName(raw)
        ?: OobActionSchema.normalizeToolName(raw).ifBlank { "unknown" }
}

fun argsForStep(step: Map<String, Any?>): Map<String, Any?> {
    val raw = step[OobActionSchema.ROOT_TOOL] as? String ?: ""
    val toolName = resolveActionName(raw) ?: return mapArg(step[OobActionSchema.ROOT_ARGS])
    val args = mapArg(step[OobActionSchema.ROOT_ARGS])
    val normalized = if (
        toolName == OobActionSchema.TOOL_OPEN_APP &&
        !args.containsKey(OobActionSchema.ARG_PACKAGE_NAME)
    ) {
        firstNonBlank(args["packageName"], args["package"])
            .takeIf(String::isNotBlank)
            ?.let { args + (OobActionSchema.ARG_PACKAGE_NAME to it) }
            ?: args
    } else {
        args
    }
    return normalized.filterKeys { it in OobActionSchema.argNames(toolName) }
}

fun sourceContextForStep(step: Map<String, Any?>): Map<String, Any?> =
    mapArg(step["source_context"])
        .ifEmpty { mapArg(mapArg(step[OobActionSchema.ROOT_ARGS])["source_context"]) }
