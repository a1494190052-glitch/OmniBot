package cn.com.omnimind.bot.omniflow

object OobFunctionArgumentBindingValidator {
    const val ERROR_CODE = "OOB_FUNCTION_ARGUMENT_BINDING_MISSING"

    data class Validation(
        val success: Boolean,
        val errorMessage: String = "",
        val diagnostics: Map<String, Any?> = emptyMap(),
    )

    fun validate(materializedSpec: Map<String, Any?>): Validation {
        val runtime = OobFunctionJson.mapArg(materializedSpec["runtime"])
        val arguments = OobFunctionJson.mapArg(runtime["arguments"])
        if (arguments.isEmpty()) return Validation(success = true, diagnostics = runtimeDiagnostics(materializedSpec))

        val unbound = OobFunctionJson.listArg(runtime["unbound_arguments"])
        val suppliedAppliedCount = OobFunctionJson.intArg(
            runtime["supplied_binding_applied_count"],
            defaultValue = 0,
        )
        if (unbound.isEmpty() && suppliedAppliedCount > 0) {
            return Validation(success = true, diagnostics = runtimeDiagnostics(materializedSpec))
        }
        val unboundNames = unbound.mapNotNull {
            OobFunctionJson.mapArg(it)["name"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }
        val message = if (unboundNames.isNotEmpty()) {
            "Function arguments were supplied but not bound to replay steps: ${unboundNames.joinToString(", ")}"
        } else {
            "Function arguments were supplied but no binding was applied to replay steps"
        }
        return Validation(
            success = false,
            errorMessage = message,
            diagnostics = runtimeDiagnostics(materializedSpec) + linkedMapOf(
                "binding_failure_reason" to if (unbound.isNotEmpty()) {
                    "unbound_arguments"
                } else {
                    "no_supplied_binding_applied"
                },
            ),
        )
    }

    fun runtimeDiagnostics(materializedSpec: Map<String, Any?>): Map<String, Any?> {
        val runtime = OobFunctionJson.mapArg(materializedSpec["runtime"])
        if (runtime.isEmpty()) return emptyMap()
        return linkedMapOf<String, Any?>(
            "arguments" to OobFunctionJson.sanitizeValue(runtime["arguments"]),
            "resolved_arguments" to OobFunctionJson.sanitizeValue(runtime["resolved_arguments"]),
            "binding_results" to OobFunctionJson.sanitizeValue(runtime["binding_results"]),
            "supplied_argument_names" to OobFunctionJson.sanitizeValue(runtime["supplied_argument_names"]),
            "argument_binding_status" to OobFunctionJson.sanitizeValue(runtime["argument_binding_status"]),
            "unbound_arguments" to OobFunctionJson.sanitizeValue(runtime["unbound_arguments"]),
            "binding_applied_count" to runtime["binding_applied_count"],
            "supplied_binding_applied_count" to runtime["supplied_binding_applied_count"],
        ).filterValues { it != null }
    }

    fun argumentSourcesByStepIndex(materializedSpec: Map<String, Any?>): Map<Int, Map<String, Any?>> {
        val runtime = OobFunctionJson.mapArg(materializedSpec["runtime"])
        val output = linkedMapOf<Int, Map<String, Any?>>()
        OobFunctionJson.listArg(runtime["binding_results"]).forEach { raw ->
            val result = OobFunctionJson.mapArg(raw)
            if (result["applied"] != true) return@forEach
            val binding = result["binding"]?.toString().orEmpty()
            val stepIndex = executionStepBindingRegex.matchEntire(binding)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: actionBindingRegex.matchEntire(binding)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: return@forEach
            output.putIfAbsent(
                stepIndex,
                linkedMapOf(
                    "argument_source" to result["parameter"],
                    "binding" to binding,
                    "value_redacted" to true,
                )
            )
        }
        return output
    }

    private val executionStepBindingRegex =
        Regex("""^\$\.execution\.steps\[(\d+)]\.args\.(text|content|value)$""")
    private val actionBindingRegex =
        Regex("""^\$\.actions\[(\d+)]\.(text|content|value)$""")
}
