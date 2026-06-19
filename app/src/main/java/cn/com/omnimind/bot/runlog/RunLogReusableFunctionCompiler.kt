package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asBoolean
import cn.com.omnimind.bot.runlog.RunLogCardAccessors.asMap
import com.google.gson.GsonBuilder
import java.security.MessageDigest

object RunLogReusableFunctionCompiler {
    fun compile(record: InternalRunLogRecord): Map<String, Any?>? {
        val replayableCards = record.cards.filter(::isSuccessfulCard)
        val compileCards = RunLogStartupBridgeCleaner.dropTransientStartupBridgeCards(replayableCards)
        val droppedStartupBridgeCount = replayableCards.size - compileCards.size
        val hasRecordedReplayStep = compileCards.any(RunLogStartupBridgeCleaner::hasRecordedReplayStep)
        val rawSteps = compileCards
            .mapIndexedNotNull { index, card ->
                RunLogReplayStepCompiler.compileCard(
                    card = card,
                    skipPerceptionTools = hasRecordedReplayStep,
                    nextReplayableCard = compileCards
                        .asSequence()
                        .drop(index + 1)
                        .firstOrNull(RunLogStartupBridgeCleaner::hasRecordedReplayStep),
                )
            }
        val replaySteps = RunLogReplayStepNoiseNormalizer.normalize(rawSteps)
        val initialStepCleanup = RunLogStartupBridgeCleaner.normalizeInitialOpenAppStep(
            replayableCards,
            replaySteps,
        )
        val injectedLaunchBridgeDroppedCount = initialStepCleanup.injectedLaunchBridgeDroppedCount
        val steps = initialStepCleanup.steps
            .mapIndexed { index, rawStep ->
                linkedMapOf<String, Any?>().apply {
                    putAll(rawStep)
                    put("id", "step_${index + 1}")
                    put("index", index)
                }
            }

        if (steps.isEmpty()) return null

        val parameterization = RunLogReusableFunctionParameterizer.parameterize(steps)
        val behaviorIdentity = deriveBehaviorIdentity(record, parameterization.actions, steps)
        val now = System.currentTimeMillis().toString()
        val functionId = deriveFunctionId(record, behaviorIdentity)
        val goal = record.goal.ifBlank { record.operationDescription }
        val name = record.operationDescription.ifBlank { goal }.ifBlank { functionId }.take(80)
        val capabilities = executionCapabilities(steps)
        return linkedMapOf<String, Any?>(
            "schema_version" to "oob.reusable_function.v1",
            "function_id" to functionId,
            "name" to name,
            "description" to goal.ifBlank { name },
            "parameters" to parameterization.parameters,
            "actions" to parameterization.actions,
            "metadata" to linkedMapOf(
                "source" to "run_log_import",
                "run_id" to record.runId,
                "source_run_ids" to listOf(record.runId),
                "original_goal" to record.goal,
                "goal" to goal.ifBlank { name },
                "step_count" to parameterization.actions.size,
                "oob_parameter_bindings" to parameterization.parameterBindings,
                "oob_legacy_parameters" to parameterization.legacyParameters.takeIf { it.isNotEmpty() },
                "oob_compat_execution" to true,
                "oob_behavior_identity" to behaviorIdentity.toPayload().takeIf { it.isNotEmpty() },
            ).filterValues { it != null },
            "source" to linkedMapOf(
                "kind" to "run_log",
                "run_id" to record.runId,
                "goal" to record.goal,
                "tool_name" to record.toolName,
                "behavior_identity" to behaviorIdentity.toPayload().takeIf { it.isNotEmpty() },
                "card_count" to record.cards.size,
                "replayable_card_count" to replayableCards.size,
                "compiled_replayable_card_count" to compileCards.size,
                "transient_startup_bridge_dropped_count" to droppedStartupBridgeCount,
                "injected_launch_bridge_step_dropped_count" to injectedLaunchBridgeDroppedCount,
                "converted_at" to now,
                "converter" to "native_run_log_reusable_function_builder",
                "parameter_inference" to linkedMapOf(
                    "strategy" to "deterministic_input_text_bindings",
                    "parameter_count" to parameterization.legacyParameters.size,
                ),
            ),
            "execution" to linkedMapOf(
                "kind" to "tool_sequence",
                "runner" to "oob_tool_sequence",
                "entrypoint" to "execute",
                "capabilities" to capabilities,
                "steps" to steps,
                "step_count" to steps.size,
                "omniflow_step_count" to capabilities["omniflow_step_count"],
                "agent_step_count" to capabilities["agent_step_count"],
                "has_agent_steps" to
                    capabilities["has_agent_steps"],
            ),
            "_oob_registry" to linkedMapOf(
                "registered_at" to now,
                "updated_at" to now,
                "runner" to "oob_agent_reusable_function",
                "storage" to "workspace",
            ),
        )
    }

    private fun executionCapabilities(steps: List<Map<String, Any?>>): Map<String, Any?> {
        return linkedMapOf(
            "omniflow_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_OMNIFLOW },
            "agent_step_count" to steps.count { it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT },
            "has_agent_steps" to steps.any {
                it["executor"] == RunLogReplayPolicy.EXECUTOR_AGENT
            },
        )
    }

    private fun isSuccessfulCard(card: Map<String, Any?>): Boolean {
        val header = asMap(card["header"])
        return asBoolean(card["success"]) != false && asBoolean(header["success"]) != false
    }

    private fun deriveFunctionId(
        record: InternalRunLogRecord,
        behaviorIdentity: BehaviorIdentity,
    ): String {
        val base = record.toolName.ifBlank { record.goal }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "function" }
        val suffix = if (behaviorIdentity.stable) {
            behaviorIdentity.hash
        } else {
            record.runId.takeLast(8).replace(Regex("[^A-Za-z0-9]"), "")
        }
        return "oob_fn_${base}_$suffix"
    }

    private fun deriveBehaviorIdentity(
        record: InternalRunLogRecord,
        actions: List<Map<String, Any?>>,
        steps: List<Map<String, Any?>>,
    ): BehaviorIdentity {
        if (record.source != "vlm" || record.toolName != "vlm_task") {
            return BehaviorIdentity(stable = false)
        }
        val payload = linkedMapOf<String, Any?>(
            "goal" to normalizeText(record.goal.ifBlank { record.operationDescription }),
            "operation_description" to normalizeText(record.operationDescription),
            "tool_name" to record.toolName,
            "source_package" to firstSourcePackage(steps),
            "actions" to actions.map(::canonicalActionIdentity),
        )
        val hash = sha256(compactGson.toJson(payload)).take(STABLE_ID_HASH_CHARS)
        return BehaviorIdentity(
            stable = true,
            hash = hash,
            strategy = "vlm_goal_package_actions_v1",
            payload = payload,
        )
    }

    private fun canonicalActionIdentity(action: Map<String, Any?>): Map<String, Any?> {
        val args = OobActionCodec.mapArg(action["args"])
        return linkedMapOf(
            "tool" to action["tool"]?.toString().orEmpty(),
            "args" to args.entries
                .filterNot { (key, _) -> key in VOLATILE_ACTION_ARG_KEYS }
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalActionArgValue(key, value) },
        )
    }

    private fun canonicalActionArgValue(key: String, value: Any?): Any? {
        if (value == null) return null
        if (key in COORDINATE_ARG_KEYS && value is Number) {
            return (value.toDouble() / COORDINATE_BUCKET_PX).toInt()
        }
        if (key in COORDINATE_ARG_KEYS && value is String) {
            val number = value.trim().toDoubleOrNull()
            if (number != null) return (number / COORDINATE_BUCKET_PX).toInt()
        }
        return when (value) {
            is String -> normalizeText(value)
            is Number -> value
            is Boolean -> value
            is Map<*, *> -> value.entries
                .mapNotNull { (rawKey, rawValue) ->
                    rawKey?.toString()?.let { it to canonicalActionArgValue(it, rawValue) }
                }
                .sortedBy { it.first }
                .associate { it.first to it.second }
            is List<*> -> value.map { canonicalActionArgValue(key, it) }
            else -> normalizeText(value.toString())
        }
    }

    private fun firstSourcePackage(steps: List<Map<String, Any?>>): String =
        steps.asSequence()
            .mapNotNull { step ->
                val context = OobActionCodec.mapArg(step["source_context"])
                val srcCtx = OobActionCodec.mapArg(context["src_ctx"])
                srcCtx["package_name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
            .orEmpty()

    private fun normalizeText(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class BehaviorIdentity(
        val stable: Boolean,
        val hash: String = "",
        val strategy: String = "",
        val payload: Map<String, Any?> = emptyMap(),
    )

    private fun BehaviorIdentity.toPayload(): Map<String, Any?> {
        if (!stable) return emptyMap()
        return linkedMapOf(
            "stable" to true,
            "hash" to hash,
            "strategy" to strategy,
            "payload" to payload,
        )
    }

    private const val STABLE_ID_HASH_CHARS = 10
    private const val COORDINATE_BUCKET_PX = 16.0
    private val compactGson = GsonBuilder().disableHtmlEscaping().create()
    private val COORDINATE_ARG_KEYS = setOf("x", "y", "x1", "y1", "x2", "y2", "start_x", "start_y", "end_x", "end_y")
    private val VOLATILE_ACTION_ARG_KEYS = setOf(
        "duration_ms",
        "time_ms",
        "wait_ms",
        "summary",
        "message",
        "result",
        "screenshot_path",
    )
}
