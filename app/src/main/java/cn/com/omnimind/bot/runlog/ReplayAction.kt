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
    return args.filterKeys { it in OobActionSchema.argNames(toolName) }
}
