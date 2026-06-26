package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.runlog.boolArg
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.tool.handlers.VlmActExecutor
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object UIStepExecutor {
    private const val MIN_ANCHOR_PROJECTION_CONFIDENCE = 0.55f
    private val LOCAL_ANCHOR_RADIUS_RATIOS = floatArrayOf(0.12f, 0.20f, 0.35f, 0.50f, 0.75f, 1.00f)
    private const val MIN_LOCAL_ANCHOR_SOURCE_COUNT = 3
    private const val MAX_LOCAL_ANCHOR_SOURCE_COUNT = 12
    private const val LOCAL_ANCHOR_DISTANCE_SIGMA = 0.25f

    private val vlmActExecutor = VlmActExecutor()

    data class StepArgsResult(
        val args: Any?,
        val meta: Map<String, Any?> = emptyMap(),
    )

    data class PreflightResult(
        val args: Map<String, Any?>,
        val transfer: Map<String, Any?> = emptyMap(),
        val checker: Map<String, Any?> = emptyMap(),
        val controlEffects: List<Map<String, Any?>> = emptyList(),
        val timing: Map<String, Any?> = emptyMap(),
        val currentXml: String? = null,
        val currentPackageName: String? = null,
    )

    internal data class ReplayState(
        val snapshot: BackendSnapshot,
        val page: PageModel?,
        val capturedAtMs: Long,
        val reason: String,
    )

    internal data class ReplayAction(
        val step: Map<String, Any?>,
        val action: String,
        val args: Map<String, Any?>,
    )

    private data class SemanticTargetMatch(
        val node: UiNode,
        val matchedBy: String,
        val matchedValue: String,
    )

    class CheckerTriggerBudget {
        private val triggerCounts = linkedMapOf<String, Int>()

        fun canTrigger(rule: OmniflowCheckerRule): Boolean =
            triggerCounts[rule.budgetKey()].orZero() < checkerTriggerLimit(rule)

        fun recordTrigger(rule: OmniflowCheckerRule): CheckerTriggerRecord {
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

    suspend fun currentPageSnapshotForRecovery(reason: String? = null): Map<String, Any?> =
        recoverySnapshotMap(readBackendSnapshot(), reason)

    suspend fun runPageGuardOnce(
        execute: Boolean = true,
        source: String = "page_guard",
        checkerBudget: CheckerTriggerBudget = CheckerTriggerBudget(),
        conditions: Set<String> = DEFAULT_PAGE_GUARD_CONDITIONS,
    ): Map<String, Any?> {
        val capturedAtMs = System.currentTimeMillis()
        if (CHECKERS_DISABLED) {
            return pageGuardBaseResult(
                source = source,
                execute = execute,
                capturedAtMs = capturedAtMs,
                snapshot = null,
            ) + mapOf(
                "matched" to false,
                "reason" to "checker_disabled",
            )
        }
        if (!OmniflowActionRuntime.backend.isReady()) {
            return pageGuardBaseResult(
                source = source,
                execute = execute,
                capturedAtMs = capturedAtMs,
                snapshot = null,
            ) + mapOf(
                "matched" to false,
                "reason" to "backend_not_ready",
            )
        }

        val snapshot = readBackendSnapshot()
        val base = pageGuardBaseResult(
            source = source,
            execute = execute,
            capturedAtMs = capturedAtMs,
            snapshot = snapshot,
        )
        val effectivePackage = snapshot.effectivePackage()
        if (effectivePackage.startsWith("cn.com.omnimind")) {
            return base + mapOf(
                "matched" to false,
                "reason" to "oob_self_package",
            )
        }

        val page = parsePageModel(snapshot.xml)
            ?: return base + mapOf(
                "matched" to false,
                "reason" to "page_model_unavailable",
            )

        for (condition in PAGE_GUARD_CONDITION_ORDER.filter { it in conditions }) {
            val rule = pageGuardRule(condition)
            if (!rule.enabled) continue
            val candidate = pageGuardCandidate(condition, page) ?: continue
            val result = linkedMapOf<String, Any?>(
                "matched" to true,
                "executed" to false,
                "condition" to condition,
                "action" to OmniflowCheckerRule.ACTION_DISMISS,
                "controller" to rule.id,
                "x" to candidate.centerX,
                "y" to candidate.centerY,
                "button_text" to nodeDisplayLabel(candidate).takeIf { it.isNotBlank() },
                "target_element" to summarizeNode(candidate),
            ).filterValues { it != null }

            if (!execute) {
                return base + result + mapOf("reason" to "dry_run")
            }
            if (!checkerBudget.canTrigger(rule)) {
                return base + result + mapOf("reason" to "trigger_budget_exhausted")
            }

            val clickMeta = clickDismissCandidateWithRetry(candidate) { latestPage ->
                pageGuardCandidate(condition, latestPage)
            }
            val trigger = checkerBudget.recordTrigger(rule)
            return base + result + mapOf(
                "executed" to true,
                "effect" to "run_actions",
                "trigger_count" to trigger.count,
                "trigger_limit" to trigger.limit,
                "trigger_remaining" to trigger.remaining,
            ) + clickMeta
        }

        return base + mapOf(
            "matched" to false,
            "reason" to "no_guard_candidate",
        )
    }

    fun isUIStep(step: Map<String, Any?>): Boolean {
        val executor = step["executor"]?.toString()?.trim()?.lowercase().orEmpty()
        val modelFree = step["model_free"] == true ||
            step["modelFree"] == true ||
            step["model_free"]?.toString()?.equals("true", ignoreCase = true) == true
        val action = actionNameForStep(step)
        return action in OobActionSchema.replayableToolNames &&
            (executor == RunLogReplayPolicy.EXECUTOR_OMNIFLOW || modelFree)
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
            throw ManualToolStopCancellationException("OmniFlow execution stopped manually")
        }
    }

    private suspend fun <T> runWithStopPolling(
        stopRequested: (() -> Boolean)?,
        block: suspend () -> T,
    ): T {
        throwIfStopRequested(stopRequested)
        if (stopRequested == null) {
            return block()
        }
        return coroutineScope {
            val action = async { block() }
            while (action.isActive) {
                if (stopRequested()) {
                    action.cancel(
                        ManualToolStopCancellationException(
                            "OmniFlow execution stopped manually"
                        )
                    )
                    throw ManualToolStopCancellationException(
                        "OmniFlow execution stopped manually"
                    )
                }
                delay(STOP_POLL_INTERVAL_MS)
            }
            action.await()
        }
    }

    suspend fun execute(
        step: Map<String, Any?>,
        stepId: String,
        stepTitle: String,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
        checkerBudget: CheckerTriggerBudget = CheckerTriggerBudget(),
        stopRequested: (() -> Boolean)? = null,
    ): Map<String, Any?> {
        throwIfStopRequested(stopRequested)
        val action = actionNameForStep(step)
        if (action !in OobActionSchema.replayableToolNames) {
            throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        if (actionRequiresAccessibility(action) && !OmniflowActionRuntime.backend.isReady()) {
            throw IllegalStateException("OmniFlow action backend is not ready")
        }
        val initialArgs = normalizeArgsMap(argsForStep(step))
        val transfer = try {
            remapStepArgs(step)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            StepArgsResult(
                args = initialArgs,
                meta = mapOf(
                    "applied" to false,
                    "reason" to "action_transfer_exception",
                    "error_message" to e.message.orEmpty(),
                ),
            )
        }
        val args = normalizeArgsMap(transfer.args ?: initialArgs)
        throwIfStopRequested(stopRequested)
        vlmActExecutor.dispatch(
            action = action,
            args = args,
            source = "function_replay",
            diagnostics = transfer.meta,
        )
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to action,
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "model_free" to true,
            "success" to true,
            "summary" to stepTitle.takeIf { it.isNotBlank() }.orEmpty(),
            "action_transfer" to transfer.meta.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    suspend fun preflight(
        step: Map<String, Any?>,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
        checkerBudget: CheckerTriggerBudget = CheckerTriggerBudget(),
        respectFixedReplayPolicy: Boolean = true,
    ): PreflightResult {
        val timing = ReplayStepTiming()
        val action = actionNameForStep(step)
        if (action !in OobActionSchema.replayableToolNames) {
            return PreflightResult(
                args = normalizeArgsMap(step["args"]),
                timing = timing.finish(),
            )
        }
        val backend = OmniflowActionRuntime.backend
        if (actionRequiresAccessibility(action) && !backend.isReady()) {
            throw IllegalStateException("OmniFlow action backend is not ready")
        }
        val fixedReplay = respectFixedReplayPolicy && RunLogReplayPolicy.fixedReplayOnly
        val initialArgs = argsForStep(step)
        val transferRequested = !fixedReplay &&
            action in OobActionSchema.coordinateToolNames &&
            shouldUseCoordinateHook(step)
        var currentState: ReplayState? = null

        suspend fun getState(reason: String): ReplayState =
            currentState ?: observeReplayState(timing, reason).also { currentState = it }

        suspend fun refreshState(reason: String): ReplayState =
            observeReplayState(timing, reason).also { currentState = it }

        val (preTransferControls, preActionControls, remapResult, args) = runPreActionPhase(
            step = step, action = action, initialArgs = initialArgs,
            fixedReplay = fixedReplay, transferRequested = transferRequested,
            checkerRules = checkerRules, checkerBudget = checkerBudget,
            timing = timing, stopRequested = null,
            getState = ::getState, refreshState = ::refreshState,
        )
        val controlEffects = preTransferControls + preActionControls
        val checker = timing.measureOverhead("result_summary_ms") {
            replayCheckerSummary(
                action = action,
                fixedReplay = fixedReplay,
                transfer = remapResult.meta,
                controlEffects = controlEffects,
            )
        }
        val latestState = currentState?.snapshot
        return PreflightResult(
            args = args,
            transfer = remapResult.meta,
            checker = checker,
            controlEffects = controlEffects,
            timing = timing.finish(),
            currentXml = latestState?.xml,
            currentPackageName = latestState?.effectivePackage(),
        )
    }

    fun remapStepArgs(step: Map<String, Any?>): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = null)

    private fun recordedReplayFallbackIfNeeded(
        transferRequested: Boolean,
        attempted: StepArgsResult,
        initialArgs: Map<String, Any?>,
    ): StepArgsResult {
        if (!transferRequested || attempted.meta["applied"] != false) {
            return attempted
        }
        val reason = attempted.meta["reason"]?.toString().orEmpty()
        if (isHardActionTransferFailure(reason) && !allowsCoordinateOnlySourceReplay(attempted.meta)) {
            throw ExecutionException(
                errorCode = "OOB_FUNCTION_SOURCE_NOT_REACHED",
                message = "action transfer could not match the recorded source page: $reason",
                diagnostics = attempted.meta + mapOf(
                    "initial_args" to initialArgs,
                    "recorded_action_args_used" to false,
                ),
            )
        }
        return StepArgsResult(
            args = initialArgs,
            meta = attempted.meta + mapOf(
                "fallback_replay_mode" to "recorded_action_replay",
                "recorded_action_args_used" to true,
            )
        )
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

    private fun transferExceptionMeta(e: Exception, extraMeta: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        mapOf(
            "applied" to false,
            "reason" to "action_transfer_exception",
            "algorithm" to "anchor_projection",
            "error_message" to e.message.orEmpty(),
        ) + extraMeta

    private suspend fun safeRemapStep(
        step: Map<String, Any?>,
        state: ReplayState,
        transferRequested: Boolean,
        initialArgs: Map<String, Any?>,
        fixedReplay: Boolean,
        timing: ReplayStepTiming,
        extraMeta: Map<String, Any?> = emptyMap(),
    ): StepArgsResult {
        val attempted = timing.measure("action_transfer_ms") {
            if (fixedReplay) return@measure StepArgsResult(initialArgs)
            try {
                remapStepArgsForState(step, state)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                StepArgsResult(args = initialArgs, meta = transferExceptionMeta(e, extraMeta))
            }
        }
        return recordedReplayFallbackIfNeeded(transferRequested, attempted, initialArgs)
    }

    private data class PreActionPhaseResult(
        val preTransferControls: List<Map<String, Any?>>,
        val preActionControls: List<Map<String, Any?>>,
        val remapResult: StepArgsResult,
        val args: Map<String, Any?>,
    )

    private suspend fun runPreActionPhase(
        step: Map<String, Any?>,
        action: String,
        initialArgs: Map<String, Any?>,
        fixedReplay: Boolean,
        transferRequested: Boolean,
        checkerRules: List<OmniflowCheckerRule>,
        checkerBudget: CheckerTriggerBudget,
        timing: ReplayStepTiming,
        stopRequested: (() -> Boolean)? = null,
        getState: suspend (String) -> ReplayState,
        refreshState: suspend (String) -> ReplayState,
    ): PreActionPhaseResult {
        val preTransferControls = timing.measure("checker_ms") {
            if (fixedReplay) emptyList()
            else runCheckerPhaseUntilStable(
                phase = OmniflowCheckerRule.PHASE_PRE_TRANSFER,
                initialState = getState("before_step"),
                replayAction = ReplayAction(step, action, initialArgs),
                extraRules = checkerRules,
                checkerBudget = checkerBudget,
                refreshState = refreshState,
                refreshReasonPrefix = "after_pre_transfer_controls",
            )
        }
        val transferBlockingOverlayControls = if (
            !fixedReplay &&
            action != OobActionSchema.TOOL_OPEN_APP &&
            action != OobActionSchema.TOOL_FINISHED
        ) {
            runPreTransferBlockingOverlayIfPresent(
                state = getState("before_transfer_overlay_check"),
                checkerBudget = checkerBudget,
            )
        } else {
            emptyList()
        }
        if (transferBlockingOverlayControls.isNotEmpty()) {
            throwIfStopRequested(stopRequested)
            refreshState("after_pre_transfer_overlay_controls")
        }
        throwIfStopRequested(stopRequested)
        val allPreTransferControls = preTransferControls + transferBlockingOverlayControls
        var remapResult = try {
            safeRemapStep(step, getState("action_transfer"), transferRequested, initialArgs, fixedReplay, timing)
        } catch (e: ExecutionException) {
            if (allPreTransferControls.isEmpty()) throw e
            throw ExecutionException(
                errorCode = e.errorCode,
                message = e.message ?: "OmniFlow action transfer failed after pre-transfer controls",
                diagnostics = e.diagnostics + mapOf(
                    "pre_transfer_control_count" to allPreTransferControls.size,
                    "pre_transfer_control_effects" to allPreTransferControls,
                ),
            )
        }
        var args = normalizeArgsMap(remapResult.args)
        val preActionControls = timing.measure("checker_ms") {
            if (fixedReplay) emptyList()
            else runCheckerPhaseUntilStable(
                phase = OmniflowCheckerRule.PHASE_PRE_ACTION,
                initialState = getState("before_action"),
                replayAction = ReplayAction(step, action, args),
                extraRules = checkerRules,
                checkerBudget = checkerBudget,
                refreshState = refreshState,
                refreshReasonPrefix = "after_pre_action_controls",
            )
        }
        if (!fixedReplay && preActionControls.isNotEmpty()) {
            throwIfStopRequested(stopRequested)
            val refreshed = refreshState("after_pre_action_controls")
            if (transferRequested) {
                remapResult = safeRemapStep(step, refreshed, transferRequested, initialArgs, fixedReplay, timing)
                args = normalizeArgsMap(remapResult.args)
            }
        }
        return PreActionPhaseResult(allPreTransferControls, preActionControls, remapResult, args)
    }

    private suspend fun runPreTransferBlockingOverlayIfPresent(
        state: ReplayState,
        checkerBudget: CheckerTriggerBudget,
    ): List<Map<String, Any?>> {
        if (CHECKERS_DISABLED) return emptyList()
        val page = state.page ?: return emptyList()
        val candidate = blockingOverlayDismissCandidate(page) ?: return emptyList()
        val rule = OmniflowCheckerRule(
            id = "dismiss_blocking_overlay",
            condition = OmniflowCheckerRule.COND_OVERLAY_BLOCKING,
            action = OmniflowCheckerRule.ACTION_DISMISS,
            phase = OmniflowCheckerRule.PHASE_PRE_TRANSFER,
        )
        if (!checkerBudget.canTrigger(rule)) return emptyList()
        val clickMeta = clickDismissCandidateWithRetry(candidate, ::blockingOverlayDismissCandidate)
        val trigger = checkerBudget.recordTrigger(rule)
        return listOf(
            linkedMapOf(
                "phase" to OmniflowCheckerRule.PHASE_PRE_TRANSFER,
                "effect" to "run_actions",
                "controller" to rule.id,
                "condition" to OmniflowCheckerRule.COND_OVERLAY_BLOCKING,
                "action" to OmniflowCheckerRule.ACTION_DISMISS,
                "button_text" to nodeDisplayLabel(candidate).takeIf { it.isNotBlank() },
                "x" to candidate.centerX,
                "y" to candidate.centerY,
                "target_element" to summarizeNode(candidate),
            ).filterValues { it != null }.withCheckerTrigger(trigger) + clickMeta
        )
    }

    private fun remapStepArgsInternal(
        step: Map<String, Any?>,
        currentXmlOverride: String?,
    ): StepArgsResult {
        val rawArgs = step["args"]
        val rawArgMap = mapArg(rawArgs)
        val args = replayArgsWithSemanticAliases(rawArgMap, argsForStep(step))
        val coordinateReplayControls = coordinateReplayControlArgs(mapArg(rawArgs))
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
        val currentXml = currentXmlOverride ?: readCurrentXmlForCoordinateRemapDirect()
        if (currentXml.isEmpty()) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_current_xml", "algorithm" to "anchor_projection")
            )
        }
        val pageMatchMeta = sourceCurrentPageMatchMeta(sourceXml, currentXml)
        return when (tool) {
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_INPUT_TEXT -> remapPointActionArgs(
                tool,
                args,
                sourceXml,
                currentXml,
                coordinateReplayControls,
            ).withMeta(pageMatchMeta)
            OobActionSchema.TOOL_SWIPE -> remapSwipeActionArgs(
                tool,
                args,
                sourceXml,
                currentXml,
                coordinateReplayControls,
            ).withMeta(pageMatchMeta)
            else -> StepArgsResult(args)
        }
    }

    private fun StepArgsResult.withMeta(extra: Map<String, Any?>): StepArgsResult {
        if (extra.isEmpty()) return this
        return StepArgsResult(args = args, meta = meta + extra)
    }

    private fun sourceCurrentPageMatchMeta(
        sourceXml: String,
        currentXml: String,
    ): Map<String, Any?> {
        if (sourceXml.isBlank() || currentXml.isBlank()) return emptyMap()
        val currentPage = parsePageModel(currentXml)
        val firstRunDismissCandidate = currentPage?.let(::firstRunPromptDismissCandidate)
        val blockingDismissCandidate = currentPage?.let(::blockingOverlayDismissCandidate)
        val currentHasPrivacyNotice =
            currentPage?.nodes?.any(::hasPrivacyNoticeCue) == true ||
                xmlHasPrivacyNoticeCue(currentXml)
        return linkedMapOf<String, Any?>(
            "source_page_matches_current" to (sourceXml.trim() == currentXml.trim()),
            "source_xml_hash" to Integer.toHexString(sourceXml.hashCode()),
            "current_xml_hash" to Integer.toHexString(currentXml.hashCode()),
            "current_sparse_overlay_page" to currentPage?.let(::looksLikeSparseOverlayPage),
            "current_first_run_prompt_overlay" to (firstRunDismissCandidate != null),
            "current_blocking_overlay_candidate" to (blockingDismissCandidate != null),
            "current_blocking_overlay_candidate_text" to blockingDismissCandidate
                ?.let(::nodeDisplayLabel)
                ?.takeIf { it.isNotBlank() },
            "current_privacy_notice_overlay" to currentHasPrivacyNotice,
        ).filterValues { it != null }
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

    private fun xmlHasPrivacyNoticeCue(xml: String): Boolean {
        val text = xml.lowercase()
        return PRIVACY_NOTICE_TERMS.any { term -> text.contains(term) }
    }


    private fun remapStepArgsForState(
        step: Map<String, Any?>,
        state: ReplayState,
    ): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = state.snapshot.xml)

    private fun readCurrentXmlForCoordinateRemapDirect(): String =
        readBackendSnapshotDirect().xml

    internal fun shouldUseCoordinateHook(step: Map<String, Any?>): Boolean {
        val coordinateHook = step["coordinate_hook"]?.toString()?.trim()?.lowercase().orEmpty()
        val replayEngine = step["replay_engine"]?.toString()?.trim()?.lowercase().orEmpty()
        val action = actionNameForStep(step)
        val sourceContext = sourceContextForStep(step)
        return coordinateHook == RunLogReplayPolicy.EXECUTOR_OMNIFLOW ||
            step["omniflow"] == true ||
            replayEngine == RunLogReplayPolicy.REPLAY_ENGINE_OMNIFLOW_UTG ||
            (RunLogReplayPolicy.isCoordinateAction(action) && sourceContext.isNotEmpty())
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

    private fun durationMs(args: Map<String, Any?>, defaultMs: Long): Long {
        numberArg(args, "duration_ms")?.toLong()?.let {
            return it.coerceAtLeast(0L)
        }
        return defaultMs
    }

    private fun beforeActionCheckerSummary(
        action: String,
        transfer: Map<String, Any?>,
        controlEffects: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        if (transfer.isEmpty() && controlEffects.isEmpty()) return emptyMap()
        return mapOf(
            "phase" to if (controlEffects.any { it["phase"] == OmniflowCheckerRule.PHASE_POST_ACTION }) {
                "around_action"
            } else {
                "before_action"
            },
            "effect" to "continue",
            "verified" to true,
            "action" to action,
            "action_transfer_applied" to transfer["applied"],
            "action_transfer_reason" to transfer["reason"],
            "control_effect_count" to controlEffects.size.takeIf { it > 0 },
            "control_effect_phases" to controlEffects.mapNotNull { it["phase"]?.toString() }
                .distinct()
                .takeIf { it.isNotEmpty() },
            "controllers" to controlEffects.mapNotNull { it["controller"]?.toString() }
                .takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private suspend fun replayCheckerSummary(
        action: String,
        fixedReplay: Boolean,
        transfer: Map<String, Any?>,
        controlEffects: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        return if (fixedReplay) {
            emptyMap()
        } else {
            beforeActionCheckerSummary(action, transfer, controlEffects)
        }
    }

    val DEFAULT_PAGE_GUARD_CONDITIONS: Set<String> = setOf(
        OmniflowCheckerRule.COND_AD_BLOCKING,
        OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT,
        OmniflowCheckerRule.COND_OVERLAY_BLOCKING,
    )

    private val PAGE_GUARD_CONDITION_ORDER: List<String> = listOf(
        OmniflowCheckerRule.COND_AD_BLOCKING,
        OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT,
        OmniflowCheckerRule.COND_OVERLAY_BLOCKING,
    )

    private fun pageGuardBaseResult(
        source: String,
        execute: Boolean,
        capturedAtMs: Long,
        snapshot: BackendSnapshot?,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "schema_version" to "oob.page_guard.v1",
        "source" to source,
        "execute" to execute,
        "captured_at_ms" to capturedAtMs,
        "package_name" to snapshot?.rawPackage?.takeIf { it.isNotBlank() },
        "effective_package" to snapshot?.effectivePackage()?.takeIf { it.isNotBlank() },
        "activity_name" to snapshot?.activityName?.takeIf { it.isNotBlank() },
        "xml_chars" to snapshot?.xml?.length,
    ).filterValues { it != null }

    private fun pageGuardRule(condition: String): OmniflowCheckerRule =
        OmniflowCheckerRule(
            id = "floating_page_guard_$condition",
            condition = condition,
            action = OmniflowCheckerRule.ACTION_DISMISS,
            phase = OmniflowCheckerRule.phaseForCondition(condition),
            params = mapOf("max_triggers" to DEFAULT_PAGE_GUARD_TRIGGER_LIMIT),
        )

    private fun pageGuardCandidate(condition: String, page: PageModel): UiNode? =
        when (condition) {
            OmniflowCheckerRule.COND_AD_BLOCKING -> adBlockingDismissCandidate(page)
            OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT -> appUpgradeDismissCandidate(page)
            OmniflowCheckerRule.COND_OVERLAY_BLOCKING -> blockingOverlayDismissCandidate(page)
            else -> null
        }

    private suspend fun runCheckerPhase(
        phase: String,
        state: ReplayState,
        replayAction: ReplayAction,
        extraRules: List<OmniflowCheckerRule>,
        checkerBudget: CheckerTriggerBudget,
    ): List<Map<String, Any?>> {
        if (CHECKERS_DISABLED) return emptyList()
        val action = replayAction.action
        if (action == OobActionSchema.TOOL_FINISHED) return emptyList()
        if (action == OobActionSchema.TOOL_OPEN_APP && phase != OmniflowCheckerRule.PHASE_POST_ACTION) {
            return emptyList()
        }
        val globalRules = OmniflowCheckerRule.globalRulesForPhase(phase)
        val activeRules = globalRules + extraRules.filter { it.phase == phase && it.enabled }
        for (rule in activeRules) {
            if (!checkerBudget.canTrigger(rule)) continue
            val result = evaluateAndExecuteRule(rule, state, replayAction) ?: continue
            val trigger = checkerBudget.recordTrigger(rule)
            // Stop after the first rule that produces a recovery action.
            return listOf(result.withCheckerTrigger(trigger))
        }
        return emptyList()
    }

    private suspend fun runCheckerPhaseUntilStable(
        phase: String,
        initialState: ReplayState,
        replayAction: ReplayAction,
        extraRules: List<OmniflowCheckerRule>,
        checkerBudget: CheckerTriggerBudget,
        refreshState: suspend (String) -> ReplayState,
        refreshReasonPrefix: String,
    ): List<Map<String, Any?>> {
        val effects = mutableListOf<Map<String, Any?>>()
        var state = initialState
        repeat(MAX_CHECKER_PHASE_CONTROL_COUNT) { index ->
            val result = runCheckerPhase(
                phase = phase,
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

    internal fun stepSourcePackage(step: Map<String, Any?>): String {
        val srcCtx = (step["source_context"] as? Map<*, *>)?.get("src_ctx") as? Map<*, *>
        val pkg = srcCtx?.get("package_name")?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return ""
        if (pkg.startsWith("cn.com.omnimind")) return ""
        if (pkg == "android" || pkg == "com.android.systemui") return ""
        if (pkg.contains("launcher", ignoreCase = true)) return ""
        return pkg
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
        timing: ReplayStepTiming,
        reason: String,
    ): ReplayState {
        val snapshot = readBackendSnapshot(timing)
        return ReplayState(
            snapshot = snapshot,
            page = parsePageModel(snapshot.xml),
            capturedAtMs = System.currentTimeMillis(),
            reason = reason,
        )
    }

    internal suspend fun readBackendSnapshot(timing: ReplayStepTiming? = null): BackendSnapshot {
        val readBlock: suspend () -> BackendSnapshot = {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    readBackendSnapshotDirect()
                }
            }.getOrElse {
                readBackendSnapshotDirect()
            }
        }
        return if (timing == null) {
            readBlock()
        } else {
            timing.measureObserve(readBlock)
        }
    }

    private fun readBackendSnapshotDirect(): BackendSnapshot {
        runCatching { OmniflowActionRuntime.backend.isReady() }
        val currentXml = runCatching {
            OmniflowActionRuntime.backend.currentXml()?.trim().orEmpty()
        }.getOrDefault("")
        val rawPackage = runCatching {
            OmniflowActionRuntime.backend.currentPackageName()?.trim().orEmpty()
        }.getOrDefault("")
        val activityName = runCatching {
            OmniflowActionRuntime.backend.currentActivityName()?.trim().orEmpty()
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

    internal fun adBlockingDismissCandidate(page: PageModel): UiNode? {
        val hasExplicitAdCue = page.nodes.any(::hasExplicitAdCue)
        val hasFullScreenAdSurface = hasLikelyFullScreenAdSurface(page)
        return page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.area > 1f && it.interactive }
            .mapNotNull { node ->
                val score = adDismissCandidateScore(
                    node = node,
                    rootBounds = page.rootBounds,
                    hasExplicitAdCue = hasExplicitAdCue,
                    hasFullScreenAdSurface = hasFullScreenAdSurface,
                )
                if (score >= MIN_AD_DISMISS_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    internal fun blockingOverlayDismissCandidate(page: PageModel): UiNode? {
        val hasOverlayCue = page.nodes.any(::hasAdOrModalCue)
        val explicitDismiss = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.area > 1f && it.interactive }
            .mapNotNull { node ->
                val score = dismissCandidateScore(node, page.rootBounds, hasOverlayCue)
                if (score >= MIN_DISMISS_OVERLAY_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
        return explicitDismiss
            ?: firstRunPromptDismissCandidate(page)
            ?: privacyNoticeOverlayDismissCandidate(page)
    }

    internal fun firstRunPromptDismissCandidate(page: PageModel): UiNode? {
        if (!looksLikeSparseOverlayPage(page) && !looksLikeFirstRunSetupPage(page)) return null
        val pageText = page.nodes.joinToString(" ") { nodeLabelWithSubtreeText(it) }
        val hasFirstRunCue = FIRST_RUN_PROMPT_CUE_TERMS.any { pageText.contains(it) }
        if (!hasFirstRunCue) return null
        val rootArea = page.rootBounds.area.coerceAtLeast(1f)
        return page.nodes
            .asSequence()
            .filter { node ->
                node.visible &&
                    node.enabled &&
                    node.interactive &&
                    node.area > 1f
            }
            .mapNotNull { node ->
                val score = firstRunPromptDismissScore(node, page.rootBounds, rootArea)
                if (score >= MIN_FIRST_RUN_PROMPT_DISMISS_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun looksLikeFirstRunSetupPage(page: PageModel): Boolean {
        val pageText = page.nodes.joinToString(" ") { nodeLabelWithSubtreeText(it) }
        val hasFirstRunCue = FIRST_RUN_PROMPT_CUE_TERMS.any { pageText.contains(it) }
        if (!hasFirstRunCue) return false
        return page.nodes.any { node ->
            node.visible &&
                node.enabled &&
                node.interactive &&
                firstRunPromptSafeAdvanceLabel(node)
        }
    }

    private fun firstRunPromptDismissScore(
        node: UiNode,
        rootBounds: Rect,
        rootArea: Float,
    ): Float {
        val directLabels = listOf(node.text, node.contentDesc, node.hintText)
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
        val label = nodeLabelText(node)
        val exact = directLabels.any { direct ->
            FIRST_RUN_PROMPT_DISMISS_EXACT_LABELS.any { direct == it }
        }
        val contains = FIRST_RUN_PROMPT_DISMISS_CONTAINS_LABELS.any { label.contains(it) }
        val safeAdvance = firstRunPromptSafeAdvanceLabel(node)
        if (!exact && !contains && !safeAdvance) return 0f
        if (directLabels.any(::isAccountOrAuthAdvanceLabel)) return 0f
        if (directLabels.any { direct ->
                FIRST_RUN_PROMPT_AFFIRMATIVE_LABELS.any { direct == it || direct.contains(it) }
            } && !safeAdvance
        ) {
            return 0f
        }

        val relativeArea = node.area / rootArea
        val bottomButton = node.centerY >= rootBounds.top + rootBounds.height * 0.55f
        var score = 420f
        if (exact) score += 280f
        if (contains) score += 180f
        if (safeAdvance) score += 240f
        if (node.classSuffix == "button") score += 120f
        if (node.clickable) score += 100f
        if (bottomButton) score += 80f
        score -= relativeArea * 80f
        return score
    }

    private fun firstRunPromptSafeAdvanceLabel(node: UiNode): Boolean {
        val directLabels = listOf(node.text, node.contentDesc, node.hintText)
            .map { it.lowercase().trim() }
            .filter { it.isNotBlank() }
        return directLabels.any { direct ->
            FIRST_RUN_PROMPT_SAFE_ADVANCE_EXACT_LABELS.any { direct == it }
        }
    }

    private fun isAccountOrAuthAdvanceLabel(label: String): Boolean =
        FIRST_RUN_PROMPT_AUTH_ADVANCE_LABELS.any { term -> label == term || label.contains(term) }

    internal fun privacyNoticeOverlayDismissCandidate(page: PageModel): UiNode? {
        val hasPrivacyCue = page.nodes.any(::hasPrivacyNoticeCue)
        if (!hasPrivacyCue || !looksLikeSparseOverlayPage(page)) return null
        val rootArea = page.rootBounds.area.coerceAtLeast(1f)
        return page.nodes
            .asSequence()
            .filter { node ->
                node.visible &&
                    node.enabled &&
                    node.interactive &&
                    node.area > 1f
            }
            .mapNotNull { node ->
                val label = nodeLabelWithSubtreeText(node)
                val hasCueOnNode = PRIVACY_NOTICE_TERMS.any { label.contains(it) }
                val score = 360f +
                    (if (hasCueOnNode) 420f else 0f) +
                    (if (node.clickable) 120f else 0f) +
                    (if (node.focusable) 60f else 0f) -
                    (node.area / rootArea) * 100f
                if (score >= MIN_PRIVACY_NOTICE_OVERLAY_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    internal fun nodeLabelText(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText, node.resourceTail)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

    internal fun nodeDisplayLabel(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText)
            .firstOrNull { it.isNotBlank() }
            ?: nodeLabelText(node)

    internal fun nodeLabelWithSubtreeText(node: UiNode): String =
        listOf(nodeLabelText(node), node.subtreeText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

    internal fun permissionNodeLabelText(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText, node.resourceTail, node.subtreeText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .lowercase()

    internal fun nodeLabelForKeyboard(node: UiNode): String =
        listOf(
            node.text,
            node.contentDesc,
            node.hintText,
            node.resourceId,
            node.packageName,
            node.className,
        ).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    internal data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = max(0f, right - left)
        val height: Float get() = max(0f, bottom - top)
        val area: Float get() = width * height
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= right && y >= top && y <= bottom

        fun clampX(x: Float): Float = min(max(x, left), right)

        fun clampY(y: Float): Float = min(max(y, top), bottom)

        fun expanded(margin: Float): Rect = Rect(
            left = left - margin,
            top = top - margin,
            right = right + margin,
            bottom = bottom + margin,
        )
    }

    internal data class UiNode(
        val index: Int,
        val bounds: Rect,
        val className: String,
        val classSuffix: String,
        val resourceId: String,
        val resourceTail: String,
        val text: String,
        val contentDesc: String,
        val hintText: String,
        val subtreeText: String,
        val packageName: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val focusable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val selected: Boolean,
        val checkable: Boolean,
        val focused: Boolean,
        val isLeaf: Boolean,
        val hasSiblings: Boolean,
        val structSignature: String,
        val depth: Int = 0,
    ) {
        val centerX: Float get() = bounds.centerX
        val centerY: Float get() = bounds.centerY
        val area: Float get() = bounds.area
        val interactive: Boolean get() = clickable || focusable || editable || scrollable
    }

    private fun UiNode.toNodeInfo(rootArea: Float) = OmniflowNodeMatcher.NodeInfo(
        resourceId = resourceId,
        resourceTail = resourceTail,
        text = text,
        contentDesc = contentDesc,
        hintText = hintText,
        classSuffix = classSuffix,
        clickable = clickable,
        longClickable = longClickable,
        focusable = focusable,
        editable = editable,
        scrollable = scrollable,
        checkable = checkable,
        enabled = enabled,
        selected = selected,
        focused = focused,
        isLeaf = isLeaf,
        hasSiblings = hasSiblings,
        structSignature = structSignature,
        areaRatio = area / rootArea.coerceAtLeast(1f),
        centerX = centerX,
        centerY = centerY,
        depth = depth,
    )

    internal data class PageModel(
        val rootBounds: Rect,
        val nodes: List<UiNode>,
    )


    private data class TargetMatch(
        val node: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class TargetMatchAttempt(
        val match: TargetMatch?,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private data class LocalAnchorSourceSelection(
        val nodes: List<UiNode>,
        val radiusRatio: Float,
        val allCandidateCount: Int,
        val nonSelfCount: Int,
    ) {
        fun toDebugMap(): Map<String, Any?> = mapOf(
            "mode" to "local_radius",
            "radius_ratio" to radiusRatio,
            "selected_source_anchor_count" to nodes.size,
            "selected_non_self_source_anchor_count" to nonSelfCount,
            "all_source_anchor_candidate_count" to allCandidateCount,
            "min_local_source_anchor_count" to MIN_LOCAL_ANCHOR_SOURCE_COUNT,
            "local_distance_sigma" to LOCAL_ANCHOR_DISTANCE_SIGMA,
        )
    }

    private data class PointMapping(
        val newX: Float,
        val newY: Float,
        val sourceNode: UiNode,
        val targetNode: UiNode,
        val confidence: Float,
        val anchorCount: Int,
        val mode: String,
        val debug: Map<String, Any?> = emptyMap(),
    )

    private fun remapPointActionArgs(
        tool: String,
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
        coordinateReplayControls: Map<String, Any?>,
    ): StepArgsResult {
        val x = floatArg(args["x"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x", "algorithm" to "anchor_projection")
        )
        val y = floatArg(args["y"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y", "algorithm" to "anchor_projection")
        )
        val sourcePage = parsePageModel(sourceXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_source_page", "algorithm" to "anchor_projection")
        )
        val targetPage = parsePageModel(currentXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_current_page", "algorithm" to "anchor_projection")
        )
        val mapped = if (tool == OobActionSchema.TOOL_INPUT_TEXT) {
            remapPointByResourceId(tool, args, sourcePage, targetPage, x, y)
        } else {
            null
        }
            ?: remapPointWithinPages(tool, sourcePage, targetPage, x, y)
            ?: remapPointBySemanticTarget(tool, args, targetPage)
            ?: if (coordinateReplayAllowed(args, coordinateReplayControls)) {
                remapPointWithinRoots(sourcePage, targetPage, x, y)
            } else {
                null
            }
            ?: return StepArgsResult(
                args,
                meta = pointRemapFailureMeta(sourcePage, targetPage, x, y)
            )
        if (mapped.mode == "omniflow_bayesian" && mapped.confidence < MIN_ANCHOR_PROJECTION_CONFIDENCE) {
            return StepArgsResult(
                args,
                meta = mapOf(
                    "applied" to false,
                    "reason" to "low_confidence_anchor_projection",
                    "algorithm" to "anchor_projection",
                    "confidence" to mapped.confidence,
                    "min_confidence" to MIN_ANCHOR_PROJECTION_CONFIDENCE,
                    "anchor_count" to mapped.anchorCount,
                    "old" to mapOf("x" to x, "y" to y),
                    "rejected_new" to mapOf("x" to mapped.newX, "y" to mapped.newY),
                    "source_element" to summarizeNode(mapped.sourceNode),
                    "target_element" to summarizeNode(mapped.targetNode),
                    "debug" to mapped.debug,
                )
            )
        }
        return StepArgsResult(
            args = args + mapOf("x" to mapped.newX, "y" to mapped.newY),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to mapped.mode,
                "algorithm" to "anchor_projection",
                "confidence" to mapped.confidence,
                "anchor_count" to mapped.anchorCount,
                "old" to mapOf("x" to x, "y" to y),
                "new" to mapOf("x" to mapped.newX, "y" to mapped.newY),
                "source_element" to summarizeNode(mapped.sourceNode),
                "target_element" to summarizeNode(mapped.targetNode),
                "debug" to mapped.debug,
            )
        )
    }

    private fun remapPointBySemanticTarget(
        tool: String,
        args: Map<String, Any?>,
        targetPage: PageModel,
    ): PointMapping? {
        if (tool != OobActionSchema.TOOL_CLICK && tool != OobActionSchema.TOOL_LONG_PRESS) {
            return null
        }
        val targetTexts = listOf(
            args["target_description"],
            args["targetDescription"],
            args["clickPrompt"],
            args["label"],
            args["selector"],
        ).mapNotNull { value ->
            normalizeText(value?.toString()).takeIf(::isMeaningfulSemanticTargetText)
        }.distinct()
        if (targetTexts.isEmpty()) return null
        val match = findInteractivePointSemanticTarget(targetPage, targetTexts) ?: return null
        return PointMapping(
            newX = match.node.centerX,
            newY = match.node.centerY,
            sourceNode = match.node,
            targetNode = match.node,
            confidence = 1.0f,
            anchorCount = 0,
            mode = "semantic_target",
            debug = mapOf(
                "matched_by" to match.matchedBy,
                "matched_value" to match.matchedValue,
                "target_texts" to targetTexts.take(6),
                "reason" to "source_point_missing_semantic_target",
            ),
        )
    }

    private fun findInteractivePointSemanticTarget(
        page: PageModel,
        targetTexts: List<String>,
    ): SemanticTargetMatch? {
        val nodes = page.nodes.filter { it.visible && it.enabled && it.interactive }
        val texts = targetTexts.sortedByDescending { it.length }
        for (text in texts) {
            val exact = nodes.firstOrNull { node ->
                nodeVisibleTexts(node).any { it == text }
            }
            if (exact != null) {
                return SemanticTargetMatch(exact, "text_exact", text)
            }
        }
        for (text in texts) {
            val contains = nodes.firstOrNull { node ->
                val labels = nodeVisibleTexts(node)
                labels.any { label ->
                    label.contains(text) || (text.contains(label) && label.length >= 3)
                }
            }
            if (contains != null) {
                return SemanticTargetMatch(contains, "text_contains", text)
            }
        }
        return null
    }

    private fun isMeaningfulSemanticTargetText(text: String): Boolean {
        if (text.isBlank()) return false
        if (text in GENERIC_TARGET_TEXT_TOKENS) return false
        return text.length >= 2
    }

    private fun nodeVisibleTexts(node: UiNode): List<String> =
        listOf(node.text, node.contentDesc, node.hintText, node.subtreeText)
            .map(::normalizeText)
            .filter(::isMeaningfulSemanticTargetText)
            .distinct()

    private fun remapPointByResourceId(
        tool: String,
        args: Map<String, Any?>,
        sourcePage: PageModel,
        targetPage: PageModel,
        x: Float,
        y: Float,
    ): PointMapping? {
        val resourceId = stringArg(args, "node_resource_id", "resource_id", "resource-id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val sourceNode = bestResourceNodeForPoint(
            page = sourcePage,
            resourceId = resourceId,
            tool = tool,
            x = x,
            y = y,
            reference = null,
        ) ?: return null
        val targetNode = bestResourceNodeForPoint(
            page = targetPage,
            resourceId = resourceId,
            tool = tool,
            x = null,
            y = null,
            reference = sourceNode,
        ) ?: return null
        val offsetX = if (sourceNode.bounds.width > 0f) {
            ((x - sourceNode.bounds.left) / sourceNode.bounds.width).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val offsetY = if (sourceNode.bounds.height > 0f) {
            ((y - sourceNode.bounds.top) / sourceNode.bounds.height).coerceIn(0f, 1f)
        } else {
            0.5f
        }
        val newX = targetNode.bounds.left + targetNode.bounds.width * offsetX
        val newY = targetNode.bounds.top + targetNode.bounds.height * offsetY
        return PointMapping(
            newX = targetNode.bounds.clampX(newX),
            newY = targetNode.bounds.clampY(newY),
            sourceNode = sourceNode,
            targetNode = targetNode,
            confidence = 1.0f,
            anchorCount = 0,
            mode = "resource_id",
            debug = mapOf(
                "matched_by" to listOf("resource_id"),
                "resource_id" to resourceId,
            ),
        )
    }

    private fun bestResourceNodeForPoint(
        page: PageModel,
        resourceId: String,
        tool: String,
        x: Float?,
        y: Float?,
        reference: UiNode?,
    ): UiNode? {
        val tail = resourceTail(resourceId)
        return page.nodes
            .asSequence()
            .filter { node ->
                node.visible &&
                    node.enabled &&
                    (node.resourceId == resourceId || (tail.isNotBlank() && node.resourceTail == tail))
            }
            .map { node ->
                val containsPoint = x != null && y != null && node.bounds.contains(x, y)
                val typeScore = when (tool) {
                    OobActionSchema.TOOL_INPUT_TEXT -> if (node.editable) 1000f else -1000f
                    OobActionSchema.TOOL_CLICK -> if (node.clickable || node.focusable) 400f else 0f
                    OobActionSchema.TOOL_LONG_PRESS -> if (node.longClickable || node.clickable) 400f else 0f
                    else -> 0f
                }
                val pointScore = if (containsPoint) 700f else 0f
                val referenceScore = reference?.let {
                    var score = 0f
                    if (node.classSuffix == it.classSuffix) score += 160f
                    score -= abs(node.bounds.width - it.bounds.width) * 0.03f
                    score -= abs(node.bounds.height - it.bounds.height) * 0.03f
                    score -= abs(node.depth - it.depth) * 8f
                    score
                } ?: 0f
                val areaPenalty = node.area / 100000f
                node to (typeScore + pointScore + referenceScore - areaPenalty)
            }
            .filter { (_, score) -> score > -500f }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun remapSwipeActionArgs(
        tool: String,
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
        coordinateReplayControls: Map<String, Any?>,
    ): StepArgsResult {
        val x1 = floatArg(args["x1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x1", "algorithm" to "anchor_projection")
        )
        val y1 = floatArg(args["y1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y1", "algorithm" to "anchor_projection")
        )
        val x2 = floatArg(args["x2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x2", "algorithm" to "anchor_projection")
        )
        val y2 = floatArg(args["y2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y2", "algorithm" to "anchor_projection")
        )
        val sourcePage = parsePageModel(sourceXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_source_page", "algorithm" to "anchor_projection")
        )
        val targetPage = parsePageModel(currentXml) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "invalid_current_page", "algorithm" to "anchor_projection")
        )

        val sourceContainer = selectScrollSourceNode(sourcePage, x1, y1, x2, y2)
            ?: return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "missing_scroll_source_element", "algorithm" to "anchor_projection")
            )
        val targetMatch = matchTargetNode(sourcePage, targetPage, sourceContainer)
            ?: return if (coordinateReplayAllowed(args, coordinateReplayControls)) {
                rootProjectionFallbackForScroll(tool, args, sourceContainer, sourcePage.rootBounds, targetPage.rootBounds)
            } else {
                StepArgsResult(
                    args,
                    meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
                )
            }

        val start = projectPoint(sourceContainer.bounds, targetMatch.node.bounds, x1, y1)
        val end = projectPoint(sourceContainer.bounds, targetMatch.node.bounds, x2, y2)
        return StepArgsResult(
            args = args + mapOf(
                "x1" to start.first,
                "y1" to start.second,
                "x2" to end.first,
                "y2" to end.second,
            ),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to targetMatch.mode,
                "algorithm" to "anchor_projection",
                "confidence" to targetMatch.confidence,
                "anchor_count" to targetMatch.anchorCount,
                "old" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
                "new" to mapOf(
                    "x1" to start.first,
                    "y1" to start.second,
                    "x2" to end.first,
                    "y2" to end.second,
                ),
                "source_element" to summarizeNode(sourceContainer),
                "target_element" to summarizeNode(targetMatch.node),
                "debug" to targetMatch.debug,
            )
        )
    }

    private fun remapPointWithinRoots(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): PointMapping? {
        if (sourcePage.rootBounds.area <= 0f || targetPage.rootBounds.area <= 0f) {
            return null
        }
        val mapped = projectPoint(sourcePage.rootBounds, targetPage.rootBounds, sourceX, sourceY)
        return PointMapping(
            newX = mapped.first,
            newY = mapped.second,
            sourceNode = sourcePage.nodes.first(),
            targetNode = targetPage.nodes.first(),
            confidence = 0f,
            anchorCount = 0,
            mode = "root_projection_fallback",
            debug = mapOf(
                "source_root" to summarizeBounds(sourcePage.rootBounds),
                "target_root" to summarizeBounds(targetPage.rootBounds),
            )
        )
    }

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

    private fun coordinateReplayControlArgs(rawArgs: Map<String, Any?>): Map<String, Any?> =
        listOf(
            "coordinate_replay_allowed",
            "coordinateReplayAllowed",
            "raw_coordinate_replay_allowed",
            "allow_raw_coordinate_replay",
            "projection_mode",
            "projectionMode",
        ).mapNotNull { key ->
            rawArgs[key]?.let { key to it }
        }.toMap()

    private fun rootProjectionFallbackForScroll(
        tool: String,
        args: Map<String, Any?>,
        sourceContainer: UiNode,
        sourceRoot: Rect,
        targetRoot: Rect,
    ): StepArgsResult {
        val x1 = floatArg(args["x1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x1", "algorithm" to "anchor_projection")
        )
        val y1 = floatArg(args["y1"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y1", "algorithm" to "anchor_projection")
        )
        val x2 = floatArg(args["x2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_x2", "algorithm" to "anchor_projection")
        )
        val y2 = floatArg(args["y2"]) ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_y2", "algorithm" to "anchor_projection")
        )
        if (sourceRoot.area <= 0f || targetRoot.area <= 0f) {
            return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
            )
        }
        val start = projectPoint(sourceRoot, targetRoot, x1, y1)
        val end = projectPoint(sourceRoot, targetRoot, x2, y2)
        return StepArgsResult(
            args = args + mapOf(
                "x1" to start.first,
                "y1" to start.second,
                "x2" to end.first,
                "y2" to end.second,
            ),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to "root_projection_fallback",
                "algorithm" to "root_projection",
                "confidence" to 0f,
                "anchor_count" to 0,
                "old" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2),
                "new" to mapOf(
                    "x1" to start.first,
                    "y1" to start.second,
                    "x2" to end.first,
                    "y2" to end.second,
                ),
                "source_element" to summarizeNode(sourceContainer),
                "target_element" to mapOf(
                    "bounds" to summarizeBounds(targetRoot),
                    "fallback" to true,
                ),
                "debug" to mapOf(
                    "source_root" to summarizeBounds(sourceRoot),
                    "target_root" to summarizeBounds(targetRoot),
                ),
            )
        )
    }

    private fun remapPointWithinPages(
        tool: String,
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): PointMapping? {
        val sourceNode = selectPointSourceNode(sourcePage, sourceX, sourceY) ?: return null
        if (requiresConcreteSourcePoint(tool) && isPageBackgroundSourceNode(sourceNode, sourcePage)) {
            return null
        }
        val targetMatch = matchTargetNode(sourcePage, targetPage, sourceNode) ?: return null
        val mapped = projectPoint(sourceNode.bounds, targetMatch.node.bounds, sourceX, sourceY)
        return PointMapping(
            newX = mapped.first,
            newY = mapped.second,
            sourceNode = sourceNode,
            targetNode = targetMatch.node,
            confidence = targetMatch.confidence,
            anchorCount = targetMatch.anchorCount,
            mode = targetMatch.mode,
            debug = targetMatch.debug,
        )
    }

    private fun requiresConcreteSourcePoint(tool: String): Boolean =
        tool == OobActionSchema.TOOL_CLICK ||
            tool == OobActionSchema.TOOL_LONG_PRESS ||
            tool == OobActionSchema.TOOL_INPUT_TEXT

    private fun isPageBackgroundSourceNode(node: UiNode, page: PageModel): Boolean {
        val rootArea = page.rootBounds.area.coerceAtLeast(1f)
        val label = nodeLabelText(node)
        return !node.interactive &&
            label.isBlank() &&
            node.area >= rootArea * 0.85f
    }

    private fun pointRemapFailureMeta(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): Map<String, Any?> {
        val sourceNode = selectPointSourceNode(sourcePage, sourceX, sourceY)
            ?: return mapOf(
                "applied" to false,
                "reason" to "no_anchor_match",
                "source_reason" to "missing_source_element",
                "algorithm" to "anchor_projection",
                "old" to mapOf("x" to sourceX, "y" to sourceY),
            )
        val attempt = matchTargetNodeAttempt(sourcePage, targetPage, sourceNode)
        return mapOf(
            "applied" to false,
            "reason" to "no_anchor_match",
            "algorithm" to "anchor_projection",
            "old" to mapOf("x" to sourceX, "y" to sourceY),
            "source_element" to summarizeNode(sourceNode),
            "debug" to attempt.debug,
        )
    }

    private fun matchTargetNode(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? = matchTargetNodeAttempt(sourcePage, targetPage, sourceNode).match

    private fun matchTargetNodeAttempt(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatchAttempt {
        val srcArea = sourcePage.rootBounds.area.coerceAtLeast(1f)
        val tgtArea = targetPage.rootBounds.area.coerceAtLeast(1f)

        // Compute all target NodeInfos and vectors once; reuse for anchors and candidates
        val allTgtInfos = targetPage.nodes.map { it.toNodeInfo(tgtArea) }
        val allTgtVecs = allTgtInfos.map { OmniflowNodeMatcher.vector(it) }

        val anchorSourceSelection = selectLocalAnchorSourceNodes(sourcePage, sourceNode)
        val anchorSrcNodes = anchorSourceSelection.nodes.map { it.toNodeInfo(srcArea) }
        val anchorSrcVecs = anchorSrcNodes.map { OmniflowNodeMatcher.vector(it) }
        val anchorTgtIdx = targetPage.nodes.indices.filter { isAnchorCandidate(targetPage.nodes[it], targetPage.rootBounds) }
        val anchorTgtInfos = anchorTgtIdx.map { allTgtInfos[it] }
        val anchorTgtVecs = anchorTgtIdx.map { allTgtVecs[it] }
        val anchors = weightAnchorsByLocality(
            anchors = OmniflowNodeMatcher.findAnchors(anchorSrcNodes, anchorSrcVecs, anchorTgtInfos, anchorTgtVecs),
            sourceNode = sourceNode,
            rootBounds = sourcePage.rootBounds,
        )

        val candIdx = targetPage.nodes.indices.filter { targetPage.nodes[it].let { n -> n.visible && n.enabled && n.area > 1f } }
        if (candIdx.isEmpty()) {
            return TargetMatchAttempt(
                match = null,
                debug = mapOf(
                    "reason" to "no_candidates",
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to anchors.size,
                    "anchor_scope" to anchorSourceSelection.toDebugMap(),
                ),
            )
        }
        val candidates = candIdx.map { targetPage.nodes[it] }
        val candInfos = candIdx.map { allTgtInfos[it] }
        val candVecs = candIdx.map { allTgtVecs[it] }

        val srcInfo = sourceNode.toNodeInfo(srcArea)
        val srcVec = OmniflowNodeMatcher.vector(srcInfo)
        val srcDiagonal = hypot(sourcePage.rootBounds.width, sourcePage.rootBounds.height).coerceAtLeast(1f)
        val diagonal = hypot(targetPage.rootBounds.width, targetPage.rootBounds.height).coerceAtLeast(1f)
        val scaleX = targetPage.rootBounds.width / sourcePage.rootBounds.width.coerceAtLeast(1e-6f)
        val scaleY = targetPage.rootBounds.height / sourcePage.rootBounds.height.coerceAtLeast(1e-6f)

        val result = OmniflowNodeMatcher.match(srcInfo, srcVec, candInfos, candVecs, anchors, srcDiagonal, diagonal, scaleX, scaleY)
        if (result.index < 0) {
            return TargetMatchAttempt(
                match = null,
                debug = mapOf(
                    "reason" to "matcher_abstain",
                    "source_element" to summarizeNode(sourceNode),
                    "anchor_count" to anchors.size,
                    "anchor_scope" to anchorSourceSelection.toDebugMap(),
                ) + result.debug,
            )
        }

        val bestNode = candidates[result.index]
        val debug = mapOf(
            "source_element" to summarizeNode(sourceNode),
            "target_element" to summarizeNode(bestNode),
            "anchor_count" to anchors.size,
            "anchor_scope" to anchorSourceSelection.toDebugMap(),
        ) + result.debug
        return TargetMatchAttempt(
            match = TargetMatch(
                node = bestNode,
                confidence = result.confidence,
                anchorCount = anchors.size,
                mode = result.mode,
                debug = debug,
            ),
            debug = debug,
        )
    }

    private fun selectLocalAnchorSourceNodes(
        page: PageModel,
        sourceNode: UiNode,
    ): LocalAnchorSourceSelection {
        val allCandidates = page.nodes
            .filter { isAnchorCandidate(it, page.rootBounds) }
            .sortedWith(
                compareBy<UiNode> { anchorDistanceRatio(sourceNode, it, page.rootBounds) }
                    .thenBy { it.area }
                    .thenBy { it.index }
            )
        if (allCandidates.isEmpty()) {
            return LocalAnchorSourceSelection(
                emptyList(),
                radiusRatio = 0f,
                allCandidateCount = 0,
                nonSelfCount = 0,
            )
        }
        for (radius in LOCAL_ANCHOR_RADIUS_RATIOS) {
            val selected = allCandidates
                .filter { anchorDistanceRatio(sourceNode, it, page.rootBounds) <= radius }
                .take(MAX_LOCAL_ANCHOR_SOURCE_COUNT)
            val nonSelfCount = selected.count { !sameUiNode(it, sourceNode) }
            if (nonSelfCount >= MIN_LOCAL_ANCHOR_SOURCE_COUNT || radius == LOCAL_ANCHOR_RADIUS_RATIOS.last()) {
                return LocalAnchorSourceSelection(
                    nodes = selected,
                    radiusRatio = radius,
                    allCandidateCount = allCandidates.size,
                    nonSelfCount = nonSelfCount,
                )
            }
        }
        val selected = allCandidates.take(MAX_LOCAL_ANCHOR_SOURCE_COUNT)
        return LocalAnchorSourceSelection(
            nodes = selected,
            radiusRatio = LOCAL_ANCHOR_RADIUS_RATIOS.last(),
            allCandidateCount = allCandidates.size,
            nonSelfCount = selected.count { !sameUiNode(it, sourceNode) },
        )
    }

    private fun weightAnchorsByLocality(
        anchors: List<OmniflowNodeMatcher.Anchor>,
        sourceNode: UiNode,
        rootBounds: Rect,
    ): List<OmniflowNodeMatcher.Anchor> {
        if (anchors.isEmpty()) return emptyList()
        return anchors.map { anchor ->
            val distanceRatio = anchorDistanceRatio(
                sourceX = sourceNode.bounds.centerX,
                sourceY = sourceNode.bounds.centerY,
                anchorX = anchor.src.centerX,
                anchorY = anchor.src.centerY,
                rootBounds = rootBounds,
            )
            val localityPrior = exp(
                -((distanceRatio * distanceRatio) /
                    (2.0f * LOCAL_ANCHOR_DISTANCE_SIGMA * LOCAL_ANCHOR_DISTANCE_SIGMA)).toDouble()
            ).toFloat().coerceIn(0.05f, 1f)
            anchor.copy(sim = (anchor.sim * localityPrior).coerceIn(0f, 1f))
        }
    }

    private fun anchorDistanceRatio(
        sourceNode: UiNode,
        anchorNode: UiNode,
        rootBounds: Rect,
    ): Float {
        return anchorDistanceRatio(
            sourceX = sourceNode.bounds.centerX,
            sourceY = sourceNode.bounds.centerY,
            anchorX = anchorNode.bounds.centerX,
            anchorY = anchorNode.bounds.centerY,
            rootBounds = rootBounds,
        )
    }

    private fun anchorDistanceRatio(
        sourceX: Float,
        sourceY: Float,
        anchorX: Float,
        anchorY: Float,
        rootBounds: Rect,
    ): Float {
        val diagonal = hypot(rootBounds.width, rootBounds.height).coerceAtLeast(1f)
        return (hypot(sourceX - anchorX, sourceY - anchorY) / diagonal)
            .coerceAtLeast(0f)
    }

    private fun sameUiNode(left: UiNode, right: UiNode): Boolean =
        left.index == right.index ||
            (
                left.bounds == right.bounds &&
                    left.resourceId == right.resourceId &&
                    left.text == right.text &&
                    left.contentDesc == right.contentDesc &&
                    left.className == right.className
                )

    internal fun selectPointSourceNode(
        page: PageModel,
        x: Float,
        y: Float,
    ): UiNode? {
        val containing = page.nodes
            .filter { it.bounds.contains(x, y) }
            .sortedBy { it.area }
        if (containing.isEmpty()) {
            return null
        }
        return containing.firstOrNull { it.interactive } ?: containing.first()
    }

    private fun selectScrollSourceNode(
        page: PageModel,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): UiNode? {
        val containingBoth = page.nodes
            .filter { it.bounds.contains(x1, y1) && it.bounds.contains(x2, y2) }
            .sortedBy { it.area }
        containingBoth.firstOrNull { it.scrollable }?.let { return it }
        containingBoth.firstOrNull { it.interactive }?.let { return it }
        containingBoth.firstOrNull()?.let { return it }
        return selectPointSourceNode(page, (x1 + x2) / 2f, (y1 + y2) / 2f)
    }

    private fun projectPoint(
        sourceBounds: Rect,
        targetBounds: Rect,
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val relativeX = if (sourceBounds.width <= 1e-3f) {
            0.5f
        } else {
            ((x - sourceBounds.left) / sourceBounds.width).coerceIn(0f, 1f)
        }
        val relativeY = if (sourceBounds.height <= 1e-3f) {
            0.5f
        } else {
            ((y - sourceBounds.top) / sourceBounds.height).coerceIn(0f, 1f)
        }
        val newX = targetBounds.clampX(targetBounds.left + targetBounds.width * relativeX)
        val newY = targetBounds.clampY(targetBounds.top + targetBounds.height * relativeY)
        return newX to newY
    }

    internal fun parsePageModel(xml: String): PageModel? {
        val root = parseXmlRoot(xml) ?: return null
        val nodes = mutableListOf<UiNode>()
        val elements = root.getElementsByTagName("*")
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val bounds = parseBounds(element.getAttribute("bounds")) ?: continue
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val className = element.stringAttr("class-name").ifEmpty {
                element.stringAttr("class")
            }
            val resourceId = element.stringAttr("resource-id")
            val clickable = element.boolAttr("clickable")
            val focusable = element.boolAttr("focusable")
            val editable = element.boolAttr("editable")
            val scrollable = element.boolAttr("scrollable")
            nodes += UiNode(
                index = i,
                bounds = bounds,
                className = className,
                classSuffix = classSuffix(className),
                resourceId = resourceId,
                resourceTail = resourceTail(resourceId),
                text = normalizeText(element.getAttribute("text")),
                contentDesc = normalizeText(element.getAttribute("content-desc")),
                hintText = normalizeText(element.getAttribute("hint-text")),
                subtreeText = if (clickable) subtreeLabelText(element) else "",
                packageName = normalizeText(element.getAttribute("package")),
                clickable = clickable,
                longClickable = element.boolAttr("long-clickable"),
                focusable = focusable,
                editable = editable,
                scrollable = scrollable,
                enabled = element.boolAttr("enabled", defaultValue = true),
                visible = element.boolAttr("visible-to-user", defaultValue = true) &&
                    element.boolAttr("displayed", defaultValue = true),
                selected = element.boolAttr("selected"),
                checkable = element.boolAttr("checkable"),
                focused = element.boolAttr("focused"),
                isLeaf = elementIsLeaf(element),
                hasSiblings = elementHasSiblings(element),
                structSignature = subtreeSignature(element, depth = 2),
                depth = elementDepth(element),
            )
        }
        if (nodes.isEmpty()) {
            return null
        }
        val rootBounds = parseBounds(root.getAttribute("bounds")) ?: inferRootBounds(nodes)
        return PageModel(rootBounds = rootBounds, nodes = nodes)
    }

    private fun subtreeLabelText(element: Element): String {
        val labels = mutableListOf<String>()

        fun visit(current: Element) {
            labels += normalizeText(current.getAttribute("text"))
            labels += normalizeText(current.getAttribute("content-desc"))
            labels += normalizeText(current.getAttribute("hint-text"))
            val children = current.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index) as? Element ?: continue
                visit(child)
            }
        }

        visit(element)
        return labels
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    private fun parseXmlRoot(xml: String): Element? {
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                isExpandEntityReferences = false
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            }
            val builder = factory.newDocumentBuilder()
            builder.parse(InputSource(StringReader(xml))).documentElement
        }.getOrNull()
    }

    private fun elementDepth(element: Element): Int {
        var depth = 0
        var node: org.w3c.dom.Node? = element.parentNode
        while (node is Element) { depth++; node = node.parentNode }
        return depth
    }

    private fun elementIsLeaf(element: Element): Boolean =
        (0 until element.childNodes.length).none { element.childNodes.item(it) is Element }

    private fun elementHasSiblings(element: Element): Boolean {
        val parent = element.parentNode as? Element ?: return false
        return (0 until parent.childNodes.length).count { parent.childNodes.item(it) is Element } > 1
    }

    /**
     * Computes the subtree structural signature used for struct_hash.
     * Matches Python ElementFeatureExtractor._subtree_signature(depth=2):
     *   "ClassName|t{hasText}|c{childCount≤5}->[child1,child2,child3]"  (up to 3 children)
     */
    private fun subtreeSignature(element: Element, depth: Int): String {
        val cn = element.stringAttr("class").ifEmpty { element.stringAttr("class-name") }.substringAfterLast('.')
        val hasText = element.getAttribute("text").isNotBlank() || element.getAttribute("content-desc").isNotBlank()
        val children = (0 until element.childNodes.length)
            .mapNotNull { element.childNodes.item(it) as? Element }
        val token = "$cn|t${if (hasText) 1 else 0}|c${children.size.coerceAtMost(5)}"
        if (depth <= 0 || children.isEmpty()) return token
        val childSigs = children.take(3).map { subtreeSignature(it, depth - 1) }.sorted()
        return "$token->[${childSigs.joinToString(",")}]"
    }

    private fun inferRootBounds(nodes: List<UiNode>): Rect {
        val left = nodes.minOf { it.bounds.left }
        val top = nodes.minOf { it.bounds.top }
        val right = nodes.maxOf { it.bounds.right }
        val bottom = nodes.maxOf { it.bounds.bottom }
        return Rect(left, top, right, bottom)
    }

    private fun nodeSimilarity(source: UiNode, target: UiNode): Float {
        var score = 0f
        var total = 0f

        fun add(weight: Float, contribution: Float) {
            total += weight
            score += weight * contribution.coerceIn(0f, 1f)
        }

        if (source.resourceId.isNotBlank()) {
            add(
                6f,
                resourceAffinity(source, target)
            )
        }
        if (source.text.isNotBlank()) {
            add(4.5f, textAffinity(source.text, target.text))
        }
        if (source.contentDesc.isNotBlank()) {
            add(3.5f, textAffinity(source.contentDesc, target.contentDesc))
        }
        if (source.hintText.isNotBlank()) {
            add(2.5f, textAffinity(source.hintText, target.hintText))
        }
        add(2f, classAffinity(source.className, target.className, source.classSuffix, target.classSuffix))
        add(1.5f, interactionAffinity(source, target))
        add(1f, geometryAffinity(source.bounds, target.bounds))

        if (total <= 1e-6f) {
            return 0f
        }
        return (score / total).coerceIn(0f, 1f)
    }

    private fun resourceAffinity(source: UiNode, target: UiNode): Float {
        val generic = isGenericResourceId(source.resourceId)
        return when {
            source.resourceId == target.resourceId -> if (generic) 0.25f else 1f
            source.resourceTail.isNotBlank() && source.resourceTail == target.resourceTail ->
                if (generic || isGenericResourceId(target.resourceId)) 0.18f else 0.72f
            else -> 0f
        }
    }

    private fun isGenericResourceId(resourceId: String): Boolean {
        val tail = resourceTail(resourceId)
        if (tail.isBlank()) return false
        if (resourceId.startsWith("android:id/")) {
            return tail in GENERIC_RESOURCE_TAILS
        }
        return tail in GENERIC_RESOURCE_TAILS
    }

    private fun textAffinity(source: String, target: String): Float {
        if (source.isBlank() || target.isBlank()) {
            return 0f
        }
        if (source == target) {
            return 1f
        }
        if (source.contains(target) || target.contains(source)) {
            val shorter = min(source.length, target.length).toFloat()
            val longer = max(source.length, target.length).toFloat().coerceAtLeast(1f)
            return (0.72f + 0.28f * (shorter / longer)).coerceIn(0f, 1f)
        }
        val sourceTokens = source.split(' ').filter { it.isNotBlank() }.toSet()
        val targetTokens = target.split(' ').filter { it.isNotBlank() }.toSet()
        if (sourceTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0f
        }
        val intersect = sourceTokens.intersect(targetTokens).size.toFloat()
        val union = sourceTokens.union(targetTokens).size.toFloat().coerceAtLeast(1f)
        return (intersect / union).coerceIn(0f, 1f)
    }

    private fun classAffinity(
        sourceClass: String,
        targetClass: String,
        sourceSuffix: String,
        targetSuffix: String,
    ): Float {
        if (sourceClass.isBlank() || targetClass.isBlank()) {
            return 0f
        }
        return when {
            sourceClass == targetClass -> 1f
            sourceSuffix.isNotBlank() && sourceSuffix == targetSuffix -> 0.85f
            else -> 0f
        }
    }

    private fun interactionAffinity(source: UiNode, target: UiNode): Float {
        val signals = listOf(
            source.clickable to target.clickable,
            source.focusable to target.focusable,
            source.editable to target.editable,
            source.scrollable to target.scrollable,
            source.checkable to target.checkable,
        )
        val expected = signals.count { it.first }
        if (expected == 0) {
            return if (source.interactive == target.interactive) 0.5f else 0f
        }
        val matched = signals.count { it.first && it.second }
        return matched.toFloat() / expected.toFloat()
    }

    private fun geometryAffinity(source: Rect, target: Rect): Float {
        val sourceAspect = source.width / source.height.coerceAtLeast(1e-3f)
        val targetAspect = target.width / target.height.coerceAtLeast(1e-3f)
        val aspect = min(sourceAspect, targetAspect) / max(targetAspect, sourceAspect).coerceAtLeast(1e-3f)
        val sourceArea = source.area.coerceAtLeast(1f)
        val targetArea = target.area.coerceAtLeast(1f)
        val area = min(sourceArea, targetArea) / max(sourceArea, targetArea)
        return ((aspect + area) / 2f).coerceIn(0f, 1f)
    }

    private fun isAnchorCandidate(node: UiNode, rootBounds: Rect): Boolean {
        if (!node.visible || !node.enabled || node.area <= 1f) {
            return false
        }
        val rootArea = rootBounds.area.coerceAtLeast(1f)
        val fullScreenLike = node.area / rootArea >= 0.96f
        if (fullScreenLike && node.resourceId.isBlank() && node.text.isBlank() && node.contentDesc.isBlank()) {
            return false
        }
        return node.interactive || node.resourceId.isNotBlank() || node.text.isNotBlank() || node.contentDesc.isNotBlank()
    }

    internal fun summarizeNode(node: UiNode): Map<String, Any?> = mapOf(
        "index" to node.index,
        "bounds" to listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom),
        "class" to node.className,
        "resource_id" to node.resourceId,
        "text" to node.text,
        "content_desc" to node.contentDesc,
        "scrollable" to node.scrollable,
        "clickable" to node.clickable,
        "editable" to node.editable,
    )

    private fun summarizeBounds(bounds: Rect): Map<String, Any?> = mapOf(
        "bounds" to listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
        "x" to bounds.centerX,
        "y" to bounds.centerY,
        "width" to bounds.width,
        "height" to bounds.height,
    )

    internal fun parseBounds(bounds: String?): Rect? {
        val text = bounds?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val match = BOUNDS_REGEX.find(text) ?: return null
        val left = match.groupValues[1].toFloatOrNull() ?: return null
        val top = match.groupValues[2].toFloatOrNull() ?: return null
        val right = match.groupValues[3].toFloatOrNull() ?: return null
        val bottom = match.groupValues[4].toFloatOrNull() ?: return null
        if (right <= left || bottom <= top) {
            return null
        }
        return Rect(left, top, right, bottom)
    }

    private fun Element.stringAttr(name: String): String = getAttribute(name).trim()

    private fun Element.boolAttr(name: String, defaultValue: Boolean = false): Boolean {
        val value = getAttribute(name)?.trim()?.lowercase().orEmpty()
        if (value.isEmpty()) {
            return defaultValue
        }
        return value == "true" || value == "1" || value == "yes"
    }

    internal fun normalizeText(value: String?): String =
        value.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    internal fun classSuffix(className: String): String =
        className.substringAfterLast('.').lowercase()

    internal fun resourceTail(resourceId: String): String {
        if (resourceId.isBlank()) {
            return ""
        }
        return resourceId.substringAfterLast('/').substringAfterLast(':').lowercase()
    }

    private fun floatArg(value: Any?): Float? =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull()
            else -> null
        }

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
                "source" to "oob_omniflow_step_executor",
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

    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private val REPLAY_STEP_PHASE_NAMES = listOf(
        "observe_ms",
        "checker_ms",
        "action_transfer_ms",
        "act_ms",
        "open_app_ready_wait_ms",
    )
    private val GENERIC_RESOURCE_TAILS = setOf(
        "title",
        "summary",
        "content",
        "content_parent",
        "content_frame",
        "main_content",
        "container_material",
        "list_container",
        "recycler_view",
        "icon",
        "icon_frame",
        "widget_frame",
    )
    private const val STOP_POLL_INTERVAL_MS = 50L

    private val GENERIC_TARGET_TEXT_TOKENS = setOf(
        "click", "tap", "press", "button", "view", "viewgroup", "textview",
        "imageview", "android", "widget", "点击", "按钮", "文本", "视图",
    )

    private val GENERIC_ANDROID_CLICK_RESOURCE_IDS = setOf(
        "android:id/title",
        "android:id/summary",
        "android:id/text1",
        "android:id/text2",
        "android:id/icon",
        "android:id/widget_frame",
    )

}
