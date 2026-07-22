package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.runlog.CanonicalActionConverter
import cn.com.omnimind.baselib.runlog.OobActionSchema

internal object FunctionActionEdits {
    fun apply(
        spec: MutableMap<String, Any?>,
        edits: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val steps = FunctionJson.mutableJsonList(FunctionJson.listArg(spec["steps"]))
        if (steps.isEmpty()) return emptyList()
        val changes = mutableListOf<Map<String, Any?>>()
        val deletes = linkedSetOf<Int>()
        edits.forEach { edit ->
            val index = FunctionJson.intArg(
                edit["index"],
                defaultValue = -1,
            )
            if (index !in steps.indices) return@forEach
            val currentStep = FunctionJson.mutableJsonMap(FunctionJson.mapArg(steps[index]))
            val current = FunctionJson.mutableJsonMap(FunctionJson.mapArg(currentStep["action"]))
            val tool = FunctionJson.firstNonBlank(current["tool"])
            val expectedTool = FunctionJson.firstNonBlank(edit["expected_tool"])
            if (expectedTool.isNotBlank() && expectedTool != tool) return@forEach
            when (FunctionJson.firstNonBlank(edit["op"]).lowercase()) {
                "delete" -> deletes += index
                "replace_args" -> {
                    val argsPatch = FunctionJson.mapArg(edit["args"])
                    if (argsPatch.isEmpty()) return@forEach
                    val oldArgs = FunctionJson.mapArg(current["args"])
                    val mergedArgs = oldArgs + argsPatch
                    val canonical = CanonicalActionConverter.convert(
                        tool = tool,
                        args = mergedArgs,
                        replayableOnly = true,
                        persistedOnly = true,
                    )
                    val newArgs = FunctionJson.mapArg(canonical[OobActionSchema.ROOT_ARGS])
                    if (newArgs == oldArgs) return@forEach
                    current["args"] = newArgs
                    currentStep["action"] = current
                    steps[index] = currentStep
                    changes += change("replace_args", index, tool, edit["reason"])
                }
            }
        }
        if (deletes.size >= steps.size || (deletes.isNotEmpty() && FunctionJson.listArg(spec["bindings"]).isNotEmpty())) {
            return emptyList()
        }
        deletes.sortedDescending().forEach { index ->
            val tool = FunctionJson.firstNonBlank(
                FunctionJson.mapArg(FunctionJson.mapArg(steps[index])["action"])["tool"],
            )
            steps.removeAt(index)
            changes += change(
                "delete",
                index,
                tool,
                edits.firstOrNull {
                    FunctionJson.firstNonBlank(it["op"]).equals("delete", ignoreCase = true) &&
                        FunctionJson.intArg(it["index"], defaultValue = -1) == index
                }?.get("reason"),
            )
        }
        steps.forEachIndexed { index, raw ->
            val step = FunctionJson.mutableJsonMap(FunctionJson.mapArg(raw))
            step["step_index"] = index
            steps[index] = step
        }
        spec["steps"] = steps
        return changes
    }

    private fun change(op: String, index: Int, tool: String, reason: Any?): Map<String, Any?> =
        linkedMapOf(
            "part" to "action",
            "field" to op,
            "op" to op,
            "step_index" to index,
            "tool" to tool,
            "reason" to reason?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
}
