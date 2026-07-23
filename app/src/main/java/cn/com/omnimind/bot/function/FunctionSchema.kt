package cn.com.omnimind.bot.function

/** Read-only presentation helpers for canonical Function payloads. */
object FunctionSchema {
    fun inputSchema(spec: Map<String, Any?>): Map<String, Any?> =
        FunctionJson.mapArg(spec["input_schema"])

    fun functionId(spec: Map<String, Any?>): String =
        FunctionJson.firstNonBlank(spec["function_id"])

    fun parameterNames(spec: Map<String, Any?>): List<String> =
        FunctionJson.mapArg(inputSchema(spec)["properties"]).keys.toList()

    fun callableSummary(spec: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "function_id" to functionId(spec),
        "name" to FunctionJson.firstNonBlank(spec["name"]),
        "description" to FunctionJson.firstNonBlank(spec["description"]),
        "input_schema" to inputSchema(spec),
        "argument_names" to parameterNames(spec),
        "step_count" to FunctionJson.listArg(spec["steps"]).size,
    )

    fun stepSummaries(spec: Map<String, Any?>): List<Map<String, Any?>> =
        FunctionJson.listArg(spec["steps"]).mapIndexedNotNull { index, raw ->
            val action = FunctionJson.mapArg(FunctionJson.mapArg(raw)["action"])
            val tool = FunctionJson.firstNonBlank(action["tool"])
            tool.takeIf(String::isNotBlank)?.let {
                linkedMapOf("step_index" to index, "tool" to it)
            }
        }
}
