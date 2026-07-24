package cn.com.omnimind.bot.function

import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg

internal class FunctionRegistrationCoordinator(
    private val managementCall: suspend (
        operation: String,
        payload: Map<String, Any?>,
    ) -> Map<String, Any?>,
    private val enhancementCall: suspend (
        functionId: String,
        runId: String,
    ) -> Map<String, Any?>,
    private val launchBackground: (suspend () -> Unit) -> Unit,
    private val updateEnhancementDiagnostics: (
        runId: String,
        diagnostics: Map<String, Any?>,
    ) -> Unit,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun convert(payload: Map<String, Any?>): Map<String, Any?> {
        val enhanceAfterRegistration = payload["enhance"] != false
        val compileResult = managementCall(
            "compile",
            payload + ("enhance" to false),
        )
        if (
            !enhanceAfterRegistration ||
            payload["register"] != true ||
            compileResult["success"] != true ||
            compileResult["registered"] != true
        ) {
            return compileResult
        }

        val runId = firstNonBlank(payload["run_id"], compileResult["run_id"])
        val functionId = firstNonBlank(
            compileResult["function_id"],
            mapArg(compileResult["function"])["function_id"],
        )
        if (runId.isBlank() || functionId.isBlank()) return compileResult

        writeDiagnostics(
            runId,
            status = "enhancing",
            functionId = functionId,
            message = ENHANCING_MESSAGE,
        )
        val queued = runCatching {
            launchBackground {
                val enhancementResult = enhancementCall(functionId, runId)
                val status = terminalStatus(enhancementResult)
                writeDiagnostics(
                    runId = runId,
                    status = status,
                    functionId = functionId,
                    message = firstNonBlank(
                        enhancementResult["message"],
                        enhancementResult["error_message"],
                        terminalMessage(status),
                    ),
                    changes = (enhancementResult["changes"] as? List<*>).orEmpty(),
                    errorCode = firstNonBlank(enhancementResult["error_code"]),
                )
            }
        }.isSuccess

        if (!queued) {
            writeDiagnostics(
                runId,
                status = "failed",
                functionId = functionId,
                message = QUEUE_FAILED_MESSAGE,
            )
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(compileResult)
            put("enhancement_status", if (queued) "enhancing" else "failed")
            put("enhancement_queued", queued)
            put("changes", emptyList<Map<String, Any?>>())
            put("message", if (queued) ENHANCING_MESSAGE else QUEUE_FAILED_MESSAGE)
        }
    }

    private fun writeDiagnostics(
        runId: String,
        status: String,
        functionId: String,
        message: String,
        changes: List<*> = emptyList<Any?>(),
        errorCode: String = "",
    ) {
        runCatching {
            updateEnhancementDiagnostics(
                runId,
                linkedMapOf<String, Any?>(
                    "status" to status,
                    "function_id" to functionId,
                    "message" to message,
                    "changes" to changes,
                    "updated_at_ms" to currentTimeMillis(),
                ).apply {
                    if (errorCode.isNotBlank()) put("error_code", errorCode)
                },
            )
        }
    }

    private fun terminalStatus(result: Map<String, Any?>): String {
        if (result["success"] != true) return "failed"
        return when (val status = firstNonBlank(result["enhancement_status"]).lowercase()) {
            "enhanced", "partial", "unchanged", "failed" -> status
            else -> if (result["changed"] == true) "enhanced" else "unchanged"
        }
    }

    private fun terminalMessage(status: String): String = when (status) {
        "enhanced", "partial" -> "Function enhancement completed."
        "unchanged" -> "Function enhancement completed with no changes."
        else -> "Function enhancement failed; the base Function remains registered."
    }

    private companion object {
        const val ENHANCING_MESSAGE =
            "Base Function registered; offline enhancement is running."
        const val QUEUE_FAILED_MESSAGE =
            "Base Function registered, but offline enhancement could not be queued."
    }
}
