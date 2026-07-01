package cn.com.omnimind.bot.omniflow.function

object OmniFlowFunctionArgumentBindingValidator {
    const val ERROR_CODE = "OOB_FUNCTION_ARGUMENT_BINDING_MISSING"

    data class Validation(
        val success: Boolean,
        val errorMessage: String = "",
        val diagnostics: Map<String, Any?> = emptyMap(),
    )

    fun validate(materializedSpec: Map<String, Any?>): Validation {
        val runtime = OmniFlowFunctionJson.mapArg(materializedSpec["runtime"])
        val arguments = OmniFlowFunctionJson.mapArg(runtime["arguments"])
        if (arguments.isEmpty()) return Validation(success = true, diagnostics = runtimeDiagnostics(materializedSpec))

        val unbound = OmniFlowFunctionJson.listArg(runtime["unbound_arguments"])
        val ignored = OmniFlowFunctionJson.listArg(runtime["ignored_arguments"])
        val suppliedAppliedCount = OmniFlowFunctionJson.intArg(
            runtime["supplied_binding_applied_count"],
            defaultValue = 0,
        )
        if (unbound.isEmpty() && suppliedAppliedCount > 0) {
            return Validation(success = true, diagnostics = runtimeDiagnostics(materializedSpec))
        }
        if (unbound.isEmpty() && ignored.isNotEmpty()) {
            return Validation(success = true, diagnostics = runtimeDiagnostics(materializedSpec))
        }
        val unboundNames = unbound.mapNotNull {
            OmniFlowFunctionJson.mapArg(it)["name"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
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
        val runtime = OmniFlowFunctionJson.mapArg(materializedSpec["runtime"])
        if (runtime.isEmpty()) return emptyMap()
        return linkedMapOf<String, Any?>(
            "arguments" to OmniFlowFunctionJson.sanitizeValue(runtime["arguments"]),
            "resolved_arguments" to OmniFlowFunctionJson.sanitizeValue(runtime["resolved_arguments"]),
            "binding_results" to OmniFlowFunctionJson.sanitizeValue(runtime["binding_results"]),
            "supplied_argument_names" to OmniFlowFunctionJson.sanitizeValue(runtime["supplied_argument_names"]),
            "argument_binding_status" to OmniFlowFunctionJson.sanitizeValue(runtime["argument_binding_status"]),
            "unbound_arguments" to OmniFlowFunctionJson.sanitizeValue(runtime["unbound_arguments"]),
            "ignored_arguments" to OmniFlowFunctionJson.sanitizeValue(runtime["ignored_arguments"]),
            "binding_applied_count" to runtime["binding_applied_count"],
            "supplied_binding_applied_count" to runtime["supplied_binding_applied_count"],
        ).filterValues { it != null }
    }

    fun argumentSourcesByStepIndex(materializedSpec: Map<String, Any?>): Map<Int, Map<String, Any?>> {
        val runtime = OmniFlowFunctionJson.mapArg(materializedSpec["runtime"])
        val output = linkedMapOf<Int, Map<String, Any?>>()
        OmniFlowFunctionJson.listArg(runtime["binding_results"]).forEach { raw ->
            val result = OmniFlowFunctionJson.mapArg(raw)
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
        Regex("""^\$\.execution\.steps\[(\d+)]\.args\.text$""")
    private val actionBindingRegex =
        Regex("""^\$\.actions\[(\d+)]\.args\.text$""")
}
