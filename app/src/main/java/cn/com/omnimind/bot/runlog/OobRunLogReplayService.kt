package cn.com.omnimind.bot.runlog

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.omniflow.OobFunctionRepository
import cn.com.omnimind.bot.omniflow.language.OmniflowCompiler
import cn.com.omnimind.bot.omniflow.language.OmniflowFunctionStore
import cn.com.omnimind.bot.omniflow.WorkspaceFunctionStore

/**
 * OOB-owned replay facade for RunLog-derived reusable Functions.
 *
 * This is intentionally smaller than the external OmniFlow provider: OOB keeps a
 * fixed registry, fixed dispatch, and deterministic local replay runner, while
 * preserving the Function schema fields external OmniFlow can import later.
 */
class OobRunLogReplayService(
    private val context: Context,
    private val workspaceFunctionStore: WorkspaceFunctionStore = WorkspaceFunctionStore(
        AgentWorkspaceManager.rootDirectory(context)
    ),
    private val functionRepository: OobFunctionRepository = OobFunctionRepository(
        context,
        workspaceFunctionStore
    )
) {
    fun convertRunLog(
        runId: String,
        register: Boolean = false,
        agentVisible: Boolean = false,
        functionIdOverride: String? = null,
        nameOverride: String? = null,
        descriptionOverride: String? = null,
    ): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return errorPayload(
                code = "RUN_LOG_ID_EMPTY",
                message = "run_id is required",
                runId = normalizedRunId
            )
        }
        val record = InternalRunLogStore.getRun(context, normalizedRunId)
            ?: return errorPayload(
                code = "RUN_LOG_NOT_FOUND",
                message = "RunLog not found: $normalizedRunId",
                runId = normalizedRunId
            )
        val runStatusWarnings = runStatusWarnings(record)
        val compiled = RunLogReusableFunctionCompiler.compile(record)
            ?: return errorPayload(
                code = "RUN_LOG_NO_REPLAYABLE_STEPS",
                message = "RunLog has no replayable steps",
                runId = normalizedRunId,
                extra = noReplayableStepDiagnostics(record) + runStatusWarningDiagnostics(runStatusWarnings)
            ).also {
                OmniLog.w(
                    TAG,
                    "convert runlog failed no replayable steps runId=$normalizedRunId " +
                        "cards=${record.cards.size} warnings=${runStatusWarnings.map { it.code }}"
                )
            }
        val spec = applyOverrides(
            spec = compiled,
            functionIdOverride = functionIdOverride,
            nameOverride = nameOverride,
            descriptionOverride = descriptionOverride,
            agentVisible = agentVisible,
        )
        val functionId = OobFunctionRepository.functionIdFromSpec(spec)
        if (!register) {
            return linkedMapOf<String, Any?>(
                "success" to true,
                "registered" to false,
                "run_id" to normalizedRunId,
                "function_id" to functionId,
                "created_function_id" to functionId,
                "function_spec" to spec,
                "summary" to functionRepository.summaryMap(spec),
                "function_kind" to "oob_reusable_function",
                "asset_state" to "native_local",
                "source" to "oob_run_log_replay_service"
            ).apply {
                putAll(conversionDiagnostics(record, spec, runStatusWarnings))
            }
        }

        mirrorRunLogForWorkspace(record)
        val registration = functionRepository.register(spec).toMutableMap()
        if (registration["success"] == true) {
            val compiledSaved = runCatching {
                val irFunction = OmniflowCompiler.compile(
                    cards = record.cards,
                    functionId = functionId,
                    goal = record.goal.ifBlank { record.operationDescription },
                    packageName = null,
                    skipPerceptionTools = true,
                )
                OmniflowFunctionStore.save(context, irFunction)
                true
            }.onFailure {
                OmniLog.w(TAG, "Function model compile/save failed for $functionId: ${it.message}")
            }.getOrDefault(false)
            registration["compiled_function_saved"] = compiledSaved
        }

        return registration.apply {
            put("registered", this["success"] == true)
            put("run_id", normalizedRunId)
            put("function_spec", spec)
            put("summary", functionRepository.summaryMap(spec))
            put("source", "oob_run_log_replay_service")
            putAll(conversionDiagnostics(record, spec, runStatusWarnings))
            if (this["success"] == true) {
                OmniLog.d(
                    TAG,
                    "convert runlog registered runId=$normalizedRunId functionId=$functionId " +
                        "cards=${record.cards.size} warnings=${runStatusWarnings.map { it.code }}"
                )
            } else {
                OmniLog.w(
                    TAG,
                    "convert runlog register failed runId=$normalizedRunId functionId=$functionId error=${this["error_message"] ?: this["error_code"]}"
                )
            }
        }
    }

    fun autoRegisterRecentRunLogs(limit: Int = 50): Map<String, Any?> {
        val records = InternalRunLogStore.listRunRecords(context, limit)
        var eligible = 0
        var registered = 0
        var alreadyExists = 0
        var skipped = 0
        val results = mutableListOf<Map<String, Any?>>()

        for (record in records) {
            val skipReason = autoRegisterSkipReason(record)
            if (skipReason != null) {
                skipped++
                continue
            }
            val spec = RunLogReusableFunctionCompiler.compile(record)
            if (spec == null) {
                skipped++
                results += linkedMapOf(
                    "run_id" to record.runId,
                    "success" to false,
                    "reason" to "no_replayable_steps"
                )
                continue
            }
            eligible++
            val functionId = OobFunctionRepository.functionIdFromSpec(spec)
            val exists = functionRepository.contains(functionId)
            mirrorRunLogForWorkspace(record)
            if (exists) {
                alreadyExists++
                continue
            }
            val result = functionRepository.register(spec)
            results += linkedMapOf(
                "run_id" to record.runId,
                "function_id" to functionId,
                "success" to (result["success"] == true),
                "already_exists" to result["already_exists"]
            )
            if (result["success"] == true) {
                if (result["already_exists"] == true) alreadyExists++ else registered++
            }
        }

        return linkedMapOf(
            "success" to true,
            "record_count" to records.size,
            "eligible_count" to eligible,
            "registered_count" to registered,
            "already_exists_count" to alreadyExists,
            "skipped_count" to skipped,
            "results" to results
        )
    }

    private fun autoRegisterSkipReason(record: InternalRunLogRecord): String? {
        if (record.finishedAtMs == null) return "unfinished"
        if (record.success != true) return "not_successful"
        if (record.cards.isEmpty()) return "empty"
        return null
    }

    private fun mirrorRunLogForWorkspace(record: InternalRunLogRecord) {
        runCatching { workspaceFunctionStore.mirrorRunLog(record) }
            .onFailure { error ->
                OmniLog.w(TAG, "mirror runlog before register failed: ${record.runId}, ${error.message}")
            }
    }

    private fun noReplayableStepDiagnostics(record: InternalRunLogRecord): Map<String, Any?> =
        linkedMapOf(
            "card_count" to record.cards.size,
            "successful_card_count" to successfulCardCount(record),
            "source_run_finished" to (record.finishedAtMs != null),
            "source_run_success" to (record.success == true),
            "source_run_done_reason" to record.doneReason.takeIf { it.isNotBlank() },
            "source_run_error_message" to record.errorMessage.takeIf { it.isNotBlank() },
        )

    private fun conversionDiagnostics(
        record: InternalRunLogRecord,
        spec: Map<String, Any?>,
        runStatusWarnings: List<RunStatusWarning> = runStatusWarnings(record),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "card_count" to record.cards.size,
        "successful_card_count" to successfulCardCount(record),
        "compiled_step_count" to compiledStepCount(spec),
        "source_run_finished" to (record.finishedAtMs != null),
        "source_run_success" to (record.success == true),
        "source_run_done_reason" to record.doneReason.takeIf { it.isNotBlank() },
        "source_run_error_message" to record.errorMessage.takeIf { it.isNotBlank() },
    ).apply {
        putAll(runStatusWarningDiagnostics(runStatusWarnings))
    }.filterValues { it != null }

    private fun runStatusWarningDiagnostics(
        warnings: List<RunStatusWarning>
    ): Map<String, Any?> {
        if (warnings.isEmpty()) return emptyMap()
        return linkedMapOf(
            "conversion_warning_code" to warnings.first().code,
            "conversion_warning_message" to warnings.first().message,
            "conversion_warning_codes" to warnings.map { it.code },
            "conversion_warnings" to warnings.map { warning ->
                linkedMapOf(
                    "code" to warning.code,
                    "message" to warning.message
                )
            }
        )
    }

    private fun runStatusWarnings(record: InternalRunLogRecord): List<RunStatusWarning> =
        buildList {
            if (record.finishedAtMs == null) {
                add(
                    RunStatusWarning(
                        code = "RUN_LOG_NOT_FINISHED",
                        message = "RunLog is not finished yet: ${record.runId}"
                    )
                )
            }
            if (record.success != true) {
                add(
                    RunStatusWarning(
                        code = "RUN_LOG_NOT_SUCCESSFUL",
                        message = "RunLog did not finish successfully: ${record.runId}"
                    )
                )
            }
        }

    private fun successfulCardCount(record: InternalRunLogRecord): Int =
        record.cards.count { card ->
            card["success"] != false &&
                (card["header"] as? Map<*, *>)?.get("success") != false
        }

    private fun compiledStepCount(spec: Map<String, Any?>): Int? {
        val execution = spec["execution"] as? Map<*, *> ?: return null
        return (execution["step_count"] as? Number)?.toInt()
            ?: (execution["steps"] as? List<*>)?.size
    }

    private fun applyOverrides(
        spec: Map<String, Any?>,
        functionIdOverride: String?,
        nameOverride: String?,
        descriptionOverride: String?,
        agentVisible: Boolean,
    ): Map<String, Any?> {
        val functionId = functionIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { OobFunctionRepository.normalizeFunctionId(it) }
        val name = nameOverride?.trim()?.takeIf { it.isNotEmpty() }
        val description = descriptionOverride?.trim()?.takeIf { it.isNotEmpty() }
        return linkedMapOf<String, Any?>().apply {
            putAll(spec)
            functionId?.let { put("function_id", it) }
            name?.let { put("name", it) }
            description?.let { put("description", it) }
            putAll(
                if (agentVisible) {
                    markAgentReusable(this)
                } else {
                    markManualDraft(this)
                }
            )
        }
    }

    private fun markAgentReusable(spec: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("agent_visible", true)
            put("visibility", "agent_reusable")
            val metadata = (spec["metadata"] as? Map<*, *>)
                ?.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
                ?.toMap()
                ?: emptyMap()
            put(
                "metadata",
                linkedMapOf<String, Any?>().apply {
                    putAll(metadata)
                    put("agent_visible", true)
                    put("visibility", "agent_reusable")
                    put("registered_via", metadata["registered_via"] ?: "run_log_agent_visible_convert")
                }
            )
        }

    private fun markManualDraft(spec: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("agent_visible", false)
            put("visibility", "manual_function")
            val metadata = (spec["metadata"] as? Map<*, *>)
                ?.mapNotNull { (key, value) -> key?.toString()?.let { it to value } }
                ?.toMap()
                ?: emptyMap()
            put(
                "metadata",
                linkedMapOf<String, Any?>().apply {
                    putAll(metadata)
                    put("agent_visible", false)
                    put("visibility", "manual_function")
                    put("registered_via", metadata["registered_via"] ?: "run_log_manual_convert")
                }
            )
        }

    private fun errorPayload(
        code: String,
        message: String,
        runId: String = "",
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "success" to false,
        "error_code" to code,
        "error_message" to message,
        "run_id" to runId,
        "function_kind" to "oob_reusable_function",
        "asset_state" to "native_local"
    ).apply {
        putAll(extra)
    }

    private data class RunStatusWarning(
        val code: String,
        val message: String
    )

    private companion object {
        const val TAG = "OobRunLogReplayService"
    }
}
