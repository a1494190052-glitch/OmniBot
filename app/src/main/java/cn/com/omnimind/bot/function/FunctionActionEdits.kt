package cn.com.omnimind.bot.function

internal object FunctionActionEdits {
    fun apply(
        spec: MutableMap<String, Any?>,
        edits: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val target = actionList(spec) ?: return emptyList()
        val actions = target.actions
        val changes = mutableListOf<Map<String, Any?>>()
        val deletes = linkedSetOf<Int>()
        edits.forEach { edit ->
            val index = FunctionJson.intArg(
                edit["index"],
                edit["action_index"],
                edit["actionIndex"],
                defaultValue = -1,
            )
            if (index !in actions.indices) return@forEach
            val current = FunctionJson.mutableJsonMap(FunctionJson.mapArg(actions[index]))
            val tool = FunctionJson.firstNonBlank(current["tool"], current["type"], current["action"])
            val expectedTool = FunctionJson.firstNonBlank(edit["expected_tool"], edit["expectedTool"])
            if (expectedTool.isNotBlank() && expectedTool != tool) return@forEach
            when (FunctionJson.firstNonBlank(edit["op"]).lowercase()) {
                "delete" -> deletes += index
                "replace_args" -> {
                    val argsPatch = FunctionJson.mapArg(edit["args"])
                    if (argsPatch.isEmpty()) return@forEach
                    val sanitizedPatch = FunctionJson.mapArg(
                        FunctionContract.sanitize(mapOf("args" to argsPatch))["args"],
                    )
                    val oldArgs = FunctionJson.mapArg(current["args"])
                        .ifEmpty { FunctionJson.mapArg(current["params"]) }
                    val newArgs = linkedMapOf<String, Any?>().apply {
                        putAll(oldArgs)
                        putAll(sanitizedPatch)
                    }
                    if (newArgs == oldArgs) return@forEach
                    current["args"] = newArgs
                    current.remove("params")
                    actions[index] = current
                    changes += change("replace_args", index, tool, edit["reason"])
                }
            }
        }
        if (deletes.size >= actions.size) return emptyList()
        deletes.sortedDescending().forEach { index ->
            val tool = FunctionJson.firstNonBlank(
                FunctionJson.mapArg(actions[index])["tool"],
                FunctionJson.mapArg(actions[index])["type"],
                FunctionJson.mapArg(actions[index])["action"],
            )
            actions.removeAt(index)
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
        target.write(actions)
        return changes
    }

    private fun actionList(spec: MutableMap<String, Any?>): ActionList? {
        val execution = FunctionJson.mutableJsonMap(FunctionJson.mapArg(spec["execution"]))
        val executionSteps = FunctionJson.mutableJsonList(FunctionJson.listArg(execution["steps"]))
        if (executionSteps.isNotEmpty()) {
            return ActionList(executionSteps) { updated ->
                execution["steps"] = updated
                spec["execution"] = execution
            }
        }
        val steps = FunctionJson.mutableJsonList(FunctionJson.listArg(spec["steps"]))
        if (steps.isNotEmpty()) return ActionList(steps) { updated -> spec["steps"] = updated }
        val actions = FunctionJson.mutableJsonList(FunctionJson.listArg(spec["actions"]))
        return actions.takeIf { it.isNotEmpty() }?.let { ActionList(it) { updated -> spec["actions"] = updated } }
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

    private data class ActionList(
        val actions: MutableList<Any?>,
        val write: (MutableList<Any?>) -> Unit,
    )
}
