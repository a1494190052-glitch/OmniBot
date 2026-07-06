package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.function.FunctionSchema
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ReplayHelper {
    data class StepArgsResult(
        val args: Any?,
        val meta: Map<String, Any?> = emptyMap(),
    )

    internal data class ReplayState(
        val snapshot: BackendSnapshot,
        val page: ActionTransfer.PageModel?,
        val capturedAtMs: Long,
        val reason: String,
    )

    internal data class ReplayAction(
        val step: Map<String, Any?>,
        val action: String,
        val args: Map<String, Any?>,
    )

    class CheckerTriggerBudget {
        private val triggerCounts = linkedMapOf<String, Int>()

        fun canTrigger(rule: ReplayCheckerRule): Boolean =
            triggerCounts[rule.budgetKey()].orZero() < checkerTriggerLimit(rule)

        fun recordTrigger(rule: ReplayCheckerRule): CheckerTriggerRecord {
            val key = rule.budgetKey()
            val limit = checkerTriggerLimit(rule)
            val count = triggerCounts[key].orZero() + 1
            triggerCounts[key] = count
            return CheckerTriggerRecord(
                count = count,
                limit = limit,
                remaining = (limit - count).coerceAtLeast(0),
            )
        }
    }

    data class CheckerTriggerRecord(
        val count: Int,
        val limit: Int,
        val remaining: Int,
    )

    class ExecutionException(
        val errorCode: String,
        message: String,
        val diagnostics: Map<String, Any?> = emptyMap(),
    ) : IllegalStateException(message)

    suspend fun currentPageSnapshotForRecovery(
        deviceOperator: DeviceOperator,
        reason: String? = null,
    ): Map<String, Any?> =
        recoverySnapshotMap(readBackendSnapshot(deviceOperator), reason)

    fun isUIStep(step: Map<String, Any?>): Boolean {
        val action = actionNameForStep(step)
        return action in OobActionSchema.replayableToolNames
    }

    fun actionNameForStep(step: Map<String, Any?>): String {
        val raw = step[OobActionSchema.ROOT_TOOL] as? String ?: ""
        return resolveActionName(raw) ?: OobActionSchema.normalizeToolName(raw).ifBlank { "unknown" }
    }

    fun normalizeArgsMap(rawArgs: Any?): Map<String, Any?> =
        mapArg(rawArgs)

    fun requiresAccessibility(step: Map<String, Any?>): Boolean =
        isUIStep(step) && actionRequiresAccessibility(actionNameForStep(step))

    fun actionRequiresAccessibility(action: String): Boolean {
        val normalized = resolveActionName(action)
            ?: OobActionSchema.normalizeToolName(action)
        return normalized in OobActionSchema.replayableToolNames &&
            normalized != OobActionSchema.TOOL_OPEN_APP &&
            normalized != OobActionSchema.TOOL_WAIT &&
            normalized != OobActionSchema.TOOL_FINISHED
    }

    fun stringArg(args: Map<String, Any?>, vararg keys: String): String? {
        for (key in keys) {
            val value = args[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return null
    }

    private fun throwIfStopRequested(stopRequested: (() -> Boolean)?) {
        if (stopRequested?.invoke() == true) {
            throw ManualToolStopCancellationException("Function execution stopped manually")
        }
    }

    fun remapStepArgs(
        step: Map<String, Any?>,
        deviceOperator: DeviceOperator? = null,
    ): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = null, deviceOperator = deviceOperator)

    fun requireActionTransferApplied(
        attempted: StepArgsResult,
        initialArgs: Map<String, Any?>,
    ): StepArgsResult {
        if (attempted.meta["applied"] != false) return attempted
        val reason = attempted.meta["reason"]?.toString().orEmpty()
        if (isHardActionTransferFailure(reason) && !allowsCoordinateOnlySourceReplay(attempted.meta)) {
            val errorCode = "OOB_FUNCTION_SOURCE_NOT_REACHED"
            throw ExecutionException(
                errorCode = errorCode,
                message = "$errorCode: action transfer could not match the recorded source page: $reason",
                diagnostics = attempted.meta + mapOf(
                    "initial_args" to initialArgs,
                    "recorded_action_args_used" to false,
                ),
            )
        }
        return attempted
    }

    private fun allowsCoordinateOnlySourceReplay(meta: Map<String, Any?>): Boolean {
        if (meta["source_reason"]?.toString() == "missing_source_element" &&
            meta["source_page_matches_current"] == true
        ) {
            return true
        }
        return meta["reason"]?.toString() == "no_anchor_match" &&
            meta["current_sparse_overlay_page"] == true &&
            meta["current_privacy_notice_overlay"] == true
    }

    private fun isHardActionTransferFailure(reason: String): Boolean =
        reason in setOf(
            "no_anchor_match",
            "low_confidence_anchor_projection",
            "matcher_abstain",
            "invalid_source_page",
            "invalid_current_page",
            "missing_source_element",
            "missing_scroll_source_element",
        )

    private fun remapStepArgsInternal(
        step: Map<String, Any?>,
        currentXmlOverride: String?,
        deviceOperator: DeviceOperator?,
    ): StepArgsResult {
        val rawArgs = step["args"]
        val rawArgMap = mapArg(rawArgs)
        val args = replayArgsWithSemanticAliases(rawArgMap, argsForStep(step))
        if (rawArgs !is Map<*, *> && args.isEmpty()) return StepArgsResult(rawArgs)
        if (!shouldUseCoordinateHook(step)) {
            return StepArgsResult(args)
        }
        val tool = actionNameForStep(step)
        if (tool !in OobActionSchema.coordinateToolNames) {
            return StepArgsResult(args)
        }
        val sourceContext = sourceContextForStep(step)
            .takeIf { it.isNotEmpty() }
            ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_source_context", "algorithm" to "anchor_projection")
        )
        val srcCtx = mapArg(sourceContext["src_ctx"])
        val sourceXml = RunLogXmlArtifacts.pageXmlFromContext(srcCtx)
            .ifBlank { RunLogXmlArtifacts.pageXmlFromContext(mapArg(sourceContext)) }
        if (sourceXml.isEmpty()) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_source_xml", "algorithm" to "anchor_projection")
            )
        }
        val currentSnapshot = if (currentXmlOverride == null && deviceOperator != null) {
            readBackendSnapshotDirect(deviceOperator)
        } else {
            null
        }
        val currentXml = currentXmlOverride ?: currentSnapshot?.xml.orEmpty()
        if (currentXml.isEmpty()) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_current_xml", "algorithm" to "anchor_projection")
            )
        }
        val result = ActionTransfer.transfer(
            ActionTransfer.Request(
                action = tool,
                args = args,
                sourceContext = sourceContext + ("xml" to sourceXml),
                currentContext = linkedMapOf<String, Any?>(
                    "xml" to currentXml,
                    "package_name" to currentSnapshot?.rawPackage,
                    "activity_name" to currentSnapshot?.activityName,
                ).filterValues { it != null },
                options = ActionTransfer.Options(
                    allowRootProjectionFallback = coordinateReplayAllowed(args, mapArg(rawArgs)),
                ),
            ),
        )
        val currentContextMeta = linkedMapOf<String, Any?>(
            "current_package_name" to currentSnapshot?.rawPackage?.takeIf { it.isNotBlank() },
            "current_activity_name" to currentSnapshot?.activityName?.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
        return StepArgsResult(args = result.args, meta = result.diagnostics + currentContextMeta)
    }

    private fun replayArgsWithSemanticAliases(
        rawArgs: Map<String, Any?>,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        if (stringArg(args, "target_description")?.isNotBlank() == true) {
            return args
        }
        val targetDescription = firstNonBlank(
            stringArg(rawArgs, "target_description"),
            stringArg(rawArgs, "targetDescription"),
            stringArg(rawArgs, "clickPrompt"),
            stringArg(rawArgs, "label"),
            stringArg(rawArgs, "selector"),
        ).trim()
        return if (targetDescription.isBlank()) {
            args
        } else {
            args + ("target_description" to targetDescription)
        }
    }

    private fun readCurrentXmlForCoordinateRemapDirect(deviceOperator: DeviceOperator?): String =
        deviceOperator?.let { readBackendSnapshotDirect(it).xml }.orEmpty()

    private fun coordinateReplayAllowed(
        args: Map<String, Any?>,
        coordinateReplayControls: Map<String, Any?> = emptyMap(),
    ): Boolean =
        boolArg(args["coordinate_replay_allowed"]) ||
            boolArg(args["coordinateReplayAllowed"]) ||
            boolArg(args["raw_coordinate_replay_allowed"]) ||
            boolArg(args["allow_raw_coordinate_replay"]) ||
            boolArg(coordinateReplayControls["coordinate_replay_allowed"]) ||
            boolArg(coordinateReplayControls["coordinateReplayAllowed"]) ||
            boolArg(coordinateReplayControls["raw_coordinate_replay_allowed"]) ||
            boolArg(coordinateReplayControls["allow_raw_coordinate_replay"]) ||
            stringArg(args, "projection_mode", "projectionMode")
                ?.equals("fixed", ignoreCase = true) == true ||
            stringArg(coordinateReplayControls, "projection_mode", "projectionMode")
                ?.equals("fixed", ignoreCase = true) == true

    internal fun shouldUseCoordinateHook(step: Map<String, Any?>): Boolean {
        val coordinateHook = step["coordinate_hook"]?.toString()?.trim()?.lowercase().orEmpty()
        val replayEngine = step["replay_engine"]?.toString()?.trim()?.lowercase().orEmpty()
        val action = actionNameForStep(step)
        val sourceContext = sourceContextForStep(step)
        return FunctionSchema.isFunctionExecutor(coordinateHook) ||
            step["omniflow"] == true ||
            (FunctionSchema.isCoordinateAction(action) && sourceContext.isNotEmpty())
    }

    internal fun numberArg(args: Map<String, Any?>, vararg keys: String): Number? {
        for (key in keys) {
            val value = args[key] ?: continue
            when (value) {
                is Number -> return value
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    internal fun checkerSafeClickNodeResourceId(resourceId: String?): String {
        val value = resourceId?.trim().orEmpty()
        if (value.isBlank()) return ""
        val normalized = value.lowercase()
        if (normalized in GENERIC_ANDROID_CLICK_RESOURCE_IDS) return ""
        return value
    }

    private suspend fun runCheckerPhase(
        phase: String,
        deviceOperator: DeviceOperator,
        state: ReplayState,
        replayAction: ReplayAction,
        extraRules: List<ReplayCheckerRule>,
        checkerBudget: CheckerTriggerBudget,
    ): List<Map<String, Any?>> {
        val action = replayAction.action
        if (action == OobActionSchema.TOOL_FINISHED) return emptyList()
        if (action == OobActionSchema.TOOL_OPEN_APP && phase != ReplayCheckerRule.PHASE_POST_ACTION) {
            return emptyList()
        }
        val activeRules = extraRules.filter { it.phase == phase && it.enabled }
        for (rule in activeRules) {
            if (!checkerBudget.canTrigger(rule)) continue
            val result = evaluateAndExecuteRule(deviceOperator, rule, state, replayAction) ?: continue
            val trigger = checkerBudget.recordTrigger(rule)
            // Stop after the first rule that produces a recovery action.
            return listOf(result.withCheckerTrigger(trigger))
        }
        return emptyList()
    }

    private suspend fun runCheckerPhaseUntilStable(
        phase: String,
        deviceOperator: DeviceOperator,
        initialState: ReplayState,
        replayAction: ReplayAction,
        extraRules: List<ReplayCheckerRule>,
        checkerBudget: CheckerTriggerBudget,
        refreshState: suspend (String) -> ReplayState,
        refreshReasonPrefix: String,
    ): List<Map<String, Any?>> {
        val effects = mutableListOf<Map<String, Any?>>()
        var state = initialState
        repeat(MAX_CHECKER_PHASE_CONTROL_COUNT) { index ->
            val result = runCheckerPhase(
                phase = phase,
                deviceOperator = deviceOperator,
                state = state,
                replayAction = replayAction,
                extraRules = extraRules,
                checkerBudget = checkerBudget,
            )
            if (result.isEmpty()) return effects
            effects += result
            state = refreshState("${refreshReasonPrefix}_${index + 1}")
        }
        return effects
    }

    suspend fun runChecker(
        deviceOperator: DeviceOperator,
        step: Map<String, Any?>,
        action: String,
        args: Map<String, Any?>,
        checkerRules: List<ReplayCheckerRule> = emptyList(),
        checkerBudget: CheckerTriggerBudget = CheckerTriggerBudget(),
        stopRequested: (() -> Boolean)? = null,
    ): List<Map<String, Any?>> {
        throwIfStopRequested(stopRequested)
        val initialState = observeReplayState(
            deviceOperator = deviceOperator,
            timing = ReplayStepTiming(),
            reason = "before_checker",
        )
        val replayAction = ReplayAction(step, action, args)
        val effects = mutableListOf<Map<String, Any?>>()
        effects += runCheckerPhaseUntilStable(
            phase = ReplayCheckerRule.PHASE_PRE_TRANSFER,
            deviceOperator = deviceOperator,
            initialState = initialState,
            replayAction = replayAction,
            extraRules = checkerRules,
            checkerBudget = checkerBudget,
            refreshState = { reason ->
                throwIfStopRequested(stopRequested)
                observeReplayState(deviceOperator, ReplayStepTiming(), reason)
            },
            refreshReasonPrefix = "after_pre_transfer_controls",
        )
        throwIfStopRequested(stopRequested)
        val preActionState = observeReplayState(deviceOperator, ReplayStepTiming(), "before_pre_action_checker")
        effects += runCheckerPhaseUntilStable(
            phase = ReplayCheckerRule.PHASE_PRE_ACTION,
            deviceOperator = deviceOperator,
            initialState = preActionState,
            replayAction = replayAction,
            extraRules = checkerRules,
            checkerBudget = checkerBudget,
            refreshState = { reason ->
                throwIfStopRequested(stopRequested)
                observeReplayState(deviceOperator, ReplayStepTiming(), reason)
            },
            refreshReasonPrefix = "after_pre_action_controls",
        )
        return effects
    }

    internal fun stepSourcePackage(step: Map<String, Any?>): String {
        val sourceContext = mapArg(step["source_context"])
        val srcCtx = mapArg(sourceContext["src_ctx"])
        val action = mapArg(sourceContext["action"])
        return firstLaunchablePackage(
            srcCtx["package_name"],
            srcCtx["packageName"],
            packageFromResourceId(mapArg(step["args"])["node_resource_id"]),
            packageFromResourceId(mapArg(step["args"])["resource_id"]),
            packageFromResourceId(action["node_resource_id"]),
            packageFromResourceId(action["resource_id"]),
            RunLogPagePackageInference.effectivePackage(
                recordedPackage = "",
                xml = RunLogXmlArtifacts.pageXmlFromContext(srcCtx),
            ),
        )
    }

    private fun firstLaunchablePackage(vararg candidates: Any?): String {
        for (candidate in candidates) {
            val pkg = candidate?.toString()?.trim().orEmpty()
            if (isLaunchableReplayPackage(pkg)) return pkg
        }
        return ""
    }

    private fun packageFromResourceId(value: Any?): String {
        val resourceId = value?.toString()?.trim().orEmpty()
        val prefix = resourceId.substringBefore(":id/", missingDelimiterValue = "")
        return prefix.takeIf(::isLaunchableReplayPackage).orEmpty()
    }

    private fun isLaunchableReplayPackage(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (!PACKAGE_NAME_PATTERN.matches(normalized)) return false
        if (normalized.startsWith("cn.com.omnimind.")) return false
        if (normalized == "android") return false
        if (normalized == "com.android.systemui") return false
        if (normalized.startsWith("com.android.inputmethod")) return false
        if (normalized.startsWith("com.google.android.inputmethod")) return false
        if (normalized.contains("launcher", ignoreCase = true)) return false
        if (normalized.startsWith("com.example")) return false
        return true
    }

    internal fun packageMatchMode(expectedPackage: String, currentPackage: String): String? {
        if (expectedPackage.isBlank()) return "expected_missing"
        if (currentPackage.isBlank()) return null
        if (expectedPackage == currentPackage) return "exact"
        return if (isAndroidSystemPackageAlias(expectedPackage, currentPackage)) {
            "android_system_alias"
        } else {
            null
        }
    }

    private fun isAndroidSystemPackageAlias(expectedPackage: String, currentPackage: String): Boolean {
        val expectedTail = expectedPackage.substringAfterLast('.')
        val currentTail = currentPackage.substringAfterLast('.')
        if (expectedTail.isBlank() || expectedTail != currentTail) return false
        val expectedSystem = expectedPackage.startsWith("com.android.") ||
            expectedPackage.startsWith("com.google.android.")
        val currentSystem = currentPackage.startsWith("com.android.") ||
            currentPackage.startsWith("com.google.android.")
        return expectedSystem && currentSystem
    }

    internal data class BackendSnapshot(
        val xml: String,
        val rawPackage: String,
        val activityName: String,
    ) {
        fun effectivePackage(): String =
            RunLogPagePackageInference.effectivePackage(rawPackage, xml, activityName)
    }

    private suspend fun observeReplayState(
        deviceOperator: DeviceOperator,
        timing: ReplayStepTiming,
        reason: String,
    ): ReplayState {
        val snapshot = readBackendSnapshot(deviceOperator, timing)
        return ReplayState(
            snapshot = snapshot,
            page = ActionTransfer.parsePageModel(snapshot.xml),
            capturedAtMs = System.currentTimeMillis(),
            reason = reason,
        )
    }

    internal suspend fun readBackendSnapshot(
        deviceOperator: DeviceOperator,
        timing: ReplayStepTiming? = null,
    ): BackendSnapshot {
        val readBlock: suspend () -> BackendSnapshot = {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    readBackendSnapshotDirect(deviceOperator)
                }
            }.getOrElse {
                readBackendSnapshotDirect(deviceOperator)
            }
        }
        return if (timing == null) {
            readBlock()
        } else {
            timing.measureObserve(readBlock)
        }
    }

    private fun readBackendSnapshotDirect(deviceOperator: DeviceOperator): BackendSnapshot {
        runCatching { deviceOperator.isReady() }
        val currentXml = runCatching {
            deviceOperator.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        val rawPackage = runCatching {
            deviceOperator.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val activityName = runCatching {
            deviceOperator.currentActivityName()?.trim().orEmpty()
        }.getOrDefault("")
        return BackendSnapshot(
            xml = currentXml,
            rawPackage = rawPackage,
            activityName = activityName,
        )
    }

    private fun recoverySnapshotMap(
        snapshot: BackendSnapshot,
        reason: String?,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "refetched_current_page" to true,
        "reason" to reason?.trim()?.takeIf { it.isNotEmpty() },
        "captured_at_ms" to System.currentTimeMillis(),
        "package_name" to snapshot.rawPackage.takeIf { it.isNotBlank() },
        "effective_package" to snapshot.effectivePackage().takeIf { it.isNotBlank() },
        "activity_name" to snapshot.activityName.takeIf { it.isNotBlank() },
        "has_observation_xml" to snapshot.xml.isNotBlank(),
        "observation_xml_length" to snapshot.xml.length,
        "observation_xml" to snapshot.xml.takeIf { it.isNotBlank() },
    ).filterValues { it != null }

    internal class ReplayStepTiming {
        private val startedAtNanos = System.nanoTime()
        private val phaseNanos = linkedMapOf<String, Long>()
        private val overheadNanos = linkedMapOf<String, Long>()
        private var observedNanos = 0L
        val startedAtMs: Long = System.currentTimeMillis()

        suspend fun <T> measure(phaseName: String, block: suspend () -> T): T {
            val startedAt = System.nanoTime()
            val observedBefore = observedNanos
            return try {
                block()
            } finally {
                val elapsed = elapsedNanos(startedAt)
                val nestedObserve = (observedNanos - observedBefore).coerceAtLeast(0L)
                addNanos(phaseNanos, phaseName, (elapsed - nestedObserve).coerceAtLeast(0L))
            }
        }

        suspend fun <T> measureObserve(block: suspend () -> T): T {
            val startedAt = System.nanoTime()
            return try {
                block()
            } finally {
                val elapsed = elapsedNanos(startedAt)
                observedNanos += elapsed
                addNanos(phaseNanos, "observe_ms", elapsed)
            }
        }

        suspend fun <T> measureOverhead(phaseName: String, block: suspend () -> T): T {
            val startedAt = System.nanoTime()
            return try {
                block()
            } finally {
                addNanos(overheadNanos, phaseName, elapsedNanos(startedAt))
            }
        }

        fun finish(): Map<String, Any?> {
            val finishedAtMs = System.currentTimeMillis()
            val phases = linkedMapOf<String, Long>()
            REPLAY_STEP_PHASE_NAMES.forEach { phaseName ->
                phases[phaseName] = nanosToMs(phaseNanos[phaseName] ?: 0L)
            }
            phaseNanos.forEach { (phaseName, durationNanos) ->
                phases.putIfAbsent(phaseName, nanosToMs(durationNanos))
            }
            val overhead = overheadNanos.mapValues { (_, durationNanos) -> nanosToMs(durationNanos) }
                .filterValues { it > 0L }
            return linkedMapOf<String, Any?>(
                "source" to "function_step_executor",
                "started_at_ms" to startedAtMs,
                "finished_at_ms" to finishedAtMs,
                "duration_ms" to nanosToMs(elapsedNanos(startedAtNanos)),
                "phase_ms" to phases,
                "overhead_ms" to overhead.takeIf { it.isNotEmpty() },
            ).filterValues { it != null }
        }

        private fun addNanos(target: MutableMap<String, Long>, key: String, value: Long) {
            target[key] = (target[key] ?: 0L) + value.coerceAtLeast(0L)
        }

        private fun elapsedNanos(startedAtNanos: Long): Long =
            (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)

        private fun nanosToMs(nanos: Long): Long =
            (nanos / 1_000_000L).coerceAtLeast(0L)
    }

    private val REPLAY_STEP_PHASE_NAMES = listOf(
        "observe_ms",
        "checker_ms",
        "action_transfer_ms",
        "act_ms",
        "open_app_ready_wait_ms",
    )
    private val PACKAGE_NAME_PATTERN = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")

    private val GENERIC_ANDROID_CLICK_RESOURCE_IDS = setOf(
        "android:id/title",
        "android:id/summary",
        "android:id/text1",
        "android:id/text2",
        "android:id/icon",
        "android:id/widget_frame",
    )

}
