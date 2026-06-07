package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.runlog.OobActionCodec.boolArg
import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object UIStepExecutor {
    private const val MIN_ANCHOR_PROJECTION_CONFIDENCE = 0.55f

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

    private data class ReplayState(
        val snapshot: BackendSnapshot,
        val page: PageModel?,
        val capturedAtMs: Long,
        val reason: String,
    )

    private data class ReplayAction(
        val step: Map<String, Any?>,
        val action: String,
        val args: Map<String, Any?>,
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
                "button_text" to nodeLabelText(candidate).takeIf { it.isNotBlank() },
                "target_element" to summarizeNode(candidate),
            ).filterValues { it != null }

            if (!execute) {
                return base + result + mapOf("reason" to "dry_run")
            }
            if (!checkerBudget.canTrigger(rule)) {
                return base + result + mapOf("reason" to "trigger_budget_exhausted")
            }

            OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
            delay(PRE_ACTION_CONTROL_DELAY_MS)
            val trigger = checkerBudget.recordTrigger(rule)
            return base + result + mapOf(
                "executed" to true,
                "effect" to "run_actions",
                "trigger_count" to trigger.count,
                "trigger_limit" to trigger.limit,
                "trigger_remaining" to trigger.remaining,
            )
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
        return action in OobActionCodec.executableActions &&
            (executor == RunLogReplayPolicy.EXECUTOR_OMNIFLOW || modelFree)
    }

    fun actionNameForStep(step: Map<String, Any?>): String =
        OobActionCodec.actionNameForStep(step)

    fun normalizeArgsMap(rawArgs: Any?): Map<String, Any?> =
        OobActionCodec.mapArg(rawArgs)

    fun requiresAccessibility(step: Map<String, Any?>): Boolean =
        isUIStep(step) && actionRequiresAccessibility(actionNameForStep(step))

    fun actionRequiresAccessibility(action: String): Boolean {
        val normalized = OobActionCodec.canonicalActionForName(action)
            ?: OobActionCodec.normalizeName(action)
        return normalized in OobActionCodec.executableActions &&
            normalized != OobActionCodec.ACTION_OPEN_APP &&
            normalized != OobActionCodec.ACTION_FINISHED
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

    private suspend fun delayWithStopPolling(
        delayMs: Long,
        stopRequested: (() -> Boolean)?,
    ) {
        var remainingMs = delayMs.coerceAtLeast(0L)
        while (remainingMs > 0L) {
            throwIfStopRequested(stopRequested)
            val sliceMs = min(remainingMs, STOP_POLL_INTERVAL_MS)
            delay(sliceMs)
            remainingMs -= sliceMs
        }
        throwIfStopRequested(stopRequested)
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
        val timing = ReplayStepTiming()
        throwIfStopRequested(stopRequested)
        val action = actionNameForStep(step)
        if (action !in OobActionCodec.executableActions) {
            throw IllegalArgumentException("Unsupported omniflow action: $action")
        }
        val backend = OmniflowActionRuntime.backend
        if (actionRequiresAccessibility(action) && !backend.isReady()) {
            throw IllegalStateException("OmniFlow action backend is not ready")
        }
        val fixedReplay = RunLogReplayPolicy.fixedReplayOnly
        val initialArgs = OobActionCodec.argsForStep(step)
        val transferRequested = !fixedReplay &&
            action in OobActionCodec.coordinateActions &&
            shouldUseCoordinateHook(step)
        var currentState: ReplayState? = null

        suspend fun replayState(reason: String): ReplayState {
            val existing = currentState
            if (existing != null) return existing
            return observeReplayState(timing, reason).also { currentState = it }
        }

        suspend fun refreshReplayState(reason: String): ReplayState =
            observeReplayState(timing, reason).also { currentState = it }

        val preTransferControls = timing.measure("checker_ms") {
            if (fixedReplay) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_PRE_TRANSFER,
                    state = replayState("before_step"),
                    replayAction = ReplayAction(step, action, initialArgs),
                    extraRules = checkerRules,
                    checkerBudget = checkerBudget,
                )
            }
        }
        if (!fixedReplay && preTransferControls.isNotEmpty()) {
            throwIfStopRequested(stopRequested)
            refreshReplayState("after_pre_transfer_controls")
        }
        throwIfStopRequested(stopRequested)
        val attemptedRemapResult = timing.measure("action_transfer_ms") {
            if (fixedReplay) {
                StepArgsResult(initialArgs)
            } else {
                try {
                    remapStepArgsForState(step, replayState("action_transfer"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StepArgsResult(
                        args = initialArgs,
                        meta = mapOf(
                            "applied" to false,
                            "reason" to "action_transfer_exception",
                            "algorithm" to "anchor_projection",
                            "error_message" to e.message.orEmpty(),
                        )
                    )
                }
            }
        }
        var remapResult = recordedReplayFallbackIfNeeded(
            transferRequested = transferRequested,
            attempted = attemptedRemapResult,
            initialArgs = initialArgs,
        )
        var args = normalizeArgsMap(remapResult.args)
        throwIfStopRequested(stopRequested)
        val preActionControls = timing.measure("checker_ms") {
            if (fixedReplay) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_PRE_ACTION,
                    state = replayState("before_action"),
                    replayAction = ReplayAction(step, action, args),
                    extraRules = checkerRules,
                    checkerBudget = checkerBudget,
                )
            }
        }
        if (!fixedReplay && preActionControls.isNotEmpty()) {
            throwIfStopRequested(stopRequested)
            val refreshed = refreshReplayState("after_pre_action_controls")
            if (transferRequested) {
                val remappedAfterControl = timing.measure("action_transfer_ms") {
                    try {
                        remapStepArgsForState(step, refreshed)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StepArgsResult(
                            args = initialArgs,
                            meta = mapOf(
                                "applied" to false,
                                "reason" to "action_transfer_exception",
                                "algorithm" to "anchor_projection",
                                "error_message" to e.message.orEmpty(),
                            )
                        )
                    }
                }
                remapResult = recordedReplayFallbackIfNeeded(
                    transferRequested = transferRequested,
                    attempted = remappedAfterControl,
                    initialArgs = initialArgs,
                )
                args = normalizeArgsMap(remapResult.args)
            }
        }
        throwIfStopRequested(stopRequested)
        val actionTransferApplied = transferRequested && remapResult.meta["applied"] == true
        var actionDispatchWarning: Map<String, Any?> = emptyMap()
        val summary = timing.measure("act_ms") {
            runWithStopPolling(stopRequested) {
                when (action) {
                    OobActionCodec.ACTION_CLICK -> {
                        val x = numberArg(args, "x")?.toFloat()
                            ?: throw IllegalArgumentException("click requires x")
                        val y = numberArg(args, "y")?.toFloat()
                            ?: throw IllegalArgumentException("click requires y")
                        runReplayGestureIgnoringDispatchTimeout(
                            action = action,
                            onWarning = { actionDispatchWarning = it },
                        ) {
                            backend.click(x, y)
                        }
                        OobActionCodec.ACTION_CLICK
                    }

                    OobActionCodec.ACTION_LONG_PRESS -> {
                        val x = numberArg(args, "x")?.toFloat()
                            ?: throw IllegalArgumentException("long_press requires x")
                        val y = numberArg(args, "y")?.toFloat()
                            ?: throw IllegalArgumentException("long_press requires y")
                        runReplayGestureIgnoringDispatchTimeout(
                            action = action,
                            onWarning = { actionDispatchWarning = it },
                        ) {
                            backend.longPress(
                                x = x,
                                y = y,
                                durationMs = durationMs(args, defaultMs = 1000L)
                            )
                        }
                        OobActionCodec.ACTION_LONG_PRESS
                    }

                    OobActionCodec.ACTION_SWIPE -> {
                        val swipe = swipeSpec(args, replayState("act_swipe"))
                        runReplayGestureIgnoringDispatchTimeout(
                            action = action,
                            onWarning = { actionDispatchWarning = it },
                        ) {
                            backend.scrollWithContext(
                                x = swipe.x,
                                y = swipe.y,
                                direction = swipe.direction,
                                distance = swipe.distance,
                                durationMs = durationMs(args, defaultMs = 1500L),
                                targetDescription = stringArg(args, "target_description").orEmpty()
                            )
                        }
                        action
                    }

                    OobActionCodec.ACTION_INPUT_TEXT -> {
                        val text = stringArg(args, "text")
                            ?: throw IllegalArgumentException("$action requires text")
                        val targetDescription = stringArg(args, "target_description").orEmpty()
                        val x = numberArg(args, "x")?.toFloat()
                        val y = numberArg(args, "y")?.toFloat()
                        val nodeResourceId = stringArg(args, "node_resource_id").orEmpty()
                        if (stringArg(args, "input_mode")?.equals("typed", ignoreCase = true) == true) {
                            backend.inputTextByTyping(
                                text = text,
                                targetDescription = targetDescription,
                                x = x,
                                y = y,
                                nodeResourceId = nodeResourceId,
                            )
                        } else {
                            backend.inputText(
                                text = text,
                                targetDescription = targetDescription,
                                x = x,
                                y = y,
                                nodeResourceId = nodeResourceId,
                            )
                        }
                        action
                    }

                    OobActionCodec.ACTION_OPEN_APP -> {
                        val packageName = stringArg(args, "package_name")
                            ?: throw IllegalArgumentException("open_app requires package_name")
                        backend.launchApplication(packageName)
                        OobActionCodec.ACTION_OPEN_APP
                    }

                    OobActionCodec.ACTION_PRESS_KEY -> {
                        backend.pressHotKey(pressKeyArg(args))
                        action
                    }

                    OobActionCodec.ACTION_FINISHED -> OobActionCodec.ACTION_FINISHED

                    else -> throw IllegalArgumentException("Unsupported omniflow action: $action")
                }
            }
        }
        if (action != OobActionCodec.ACTION_FINISHED) {
            delayWithStopPolling(REPLAY_ACTION_FIXED_WAIT_MS, stopRequested)
        }
        throwIfStopRequested(stopRequested)
        val postActionControls = timing.measure("checker_ms") {
            val hasPostActionRules = checkerRules.any {
                it.phase == OmniflowCheckerRule.PHASE_POST_ACTION && it.enabled
            }
            if (fixedReplay || (action != OobActionCodec.ACTION_OPEN_APP && !hasPostActionRules)) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_POST_ACTION,
                    state = refreshReplayState("after_action"),
                    replayAction = ReplayAction(step, action, args),
                    extraRules = checkerRules,
                    checkerBudget = checkerBudget,
                )
            }
        }
        val controlEffects = preTransferControls + preActionControls + postActionControls
        val checker = timing.measureOverhead("result_summary_ms") {
            replayCheckerSummary(
                action = action,
                fixedReplay = fixedReplay,
                transfer = remapResult.meta,
                controlEffects = controlEffects,
            )
        }
        val timingResult = timing.finish()
        return linkedMapOf<String, Any?>(
            "step_id" to stepId,
            "tool" to action,
            "executor" to RunLogReplayPolicy.EXECUTOR_OMNIFLOW,
            "model_free" to true,
            "replay_mode" to replayMode(actionTransferApplied, transferRequested, remapResult.meta),
            "success" to true,
            "summary" to (stepTitle.takeIf { it.isNotBlank() } ?: summary),
            "started_at_ms" to timingResult["started_at_ms"],
            "finished_at_ms" to timingResult["finished_at_ms"],
            "duration_ms" to timingResult["duration_ms"],
            "timing" to timingResult,
        ).apply {
            if (remapResult.meta.isNotEmpty()) {
                put("action_transfer", remapResult.meta)
            }
            if (checker.isNotEmpty()) {
                put("checker", checker)
            }
            if (controlEffects.isNotEmpty()) {
                put("control_effects", controlEffects)
            }
            if (actionDispatchWarning.isNotEmpty()) {
                put("action_dispatch", actionDispatchWarning)
            }
        }
    }

    suspend fun preflight(
        step: Map<String, Any?>,
        checkerRules: List<OmniflowCheckerRule> = emptyList(),
        checkerBudget: CheckerTriggerBudget = CheckerTriggerBudget(),
        respectFixedReplayPolicy: Boolean = true,
    ): PreflightResult {
        val timing = ReplayStepTiming()
        val action = actionNameForStep(step)
        if (action !in OobActionCodec.executableActions) {
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
        val initialArgs = OobActionCodec.argsForStep(step)
        val transferRequested = !fixedReplay &&
            action in OobActionCodec.coordinateActions &&
            shouldUseCoordinateHook(step)
        var currentState: ReplayState? = null

        suspend fun replayState(reason: String): ReplayState {
            val existing = currentState
            if (existing != null) return existing
            return observeReplayState(timing, reason).also { currentState = it }
        }

        suspend fun refreshReplayState(reason: String): ReplayState =
            observeReplayState(timing, reason).also { currentState = it }

        val preTransferControls = timing.measure("checker_ms") {
            if (fixedReplay) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_PRE_TRANSFER,
                    state = replayState("before_step"),
                    replayAction = ReplayAction(step, action, initialArgs),
                    extraRules = checkerRules,
                    checkerBudget = checkerBudget,
                )
            }
        }
        if (!fixedReplay && preTransferControls.isNotEmpty()) {
            refreshReplayState("after_pre_transfer_controls")
        }
        val attemptedRemapResult = timing.measure("action_transfer_ms") {
            if (fixedReplay) {
                StepArgsResult(initialArgs)
            } else {
                try {
                    remapStepArgsForState(step, replayState("action_transfer"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StepArgsResult(
                        args = initialArgs,
                        meta = mapOf(
                            "applied" to false,
                            "reason" to "action_transfer_exception",
                            "algorithm" to "anchor_projection",
                            "error_message" to e.message.orEmpty(),
                        )
                    )
                }
            }
        }
        var remapResult = recordedReplayFallbackIfNeeded(
            transferRequested = transferRequested,
            attempted = attemptedRemapResult,
            initialArgs = initialArgs,
        )
        var args = normalizeArgsMap(remapResult.args)
        val preActionControls = timing.measure("checker_ms") {
            if (fixedReplay) {
                emptyList()
            } else {
                runCheckerPhase(
                    phase = OmniflowCheckerRule.PHASE_PRE_ACTION,
                    state = replayState("before_action"),
                    replayAction = ReplayAction(step, action, args),
                    extraRules = checkerRules,
                    checkerBudget = checkerBudget,
                )
            }
        }
        if (!fixedReplay && preActionControls.isNotEmpty()) {
            val refreshed = refreshReplayState("after_pre_action_controls")
            if (transferRequested) {
                val remappedAfterControl = timing.measure("action_transfer_ms") {
                    try {
                        remapStepArgsForState(step, refreshed)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StepArgsResult(
                            args = initialArgs,
                            meta = mapOf(
                                "applied" to false,
                                "reason" to "action_transfer_exception",
                                "algorithm" to "anchor_projection",
                                "error_message" to e.message.orEmpty(),
                            )
                        )
                    }
                }
                remapResult = recordedReplayFallbackIfNeeded(
                    transferRequested = transferRequested,
                    attempted = remappedAfterControl,
                    initialArgs = initialArgs,
                )
                args = normalizeArgsMap(remapResult.args)
            }
        }
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

    private fun replayMode(
        actionTransferApplied: Boolean,
        transferRequested: Boolean,
        transfer: Map<String, Any?>,
    ): String = when {
        actionTransferApplied -> "action_transfer"
        transferRequested && transfer["recorded_action_args_used"] == true -> "recorded_action_replay"
        transferRequested -> "action_transfer_skipped"
        else -> "direct_replay"
    }

    private suspend fun runReplayGestureIgnoringDispatchTimeout(
        action: String,
        onWarning: (Map<String, Any?>) -> Unit,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (error: Exception) {
            if (!isGestureDispatchTimeout(error)) throw error
            onWarning(
                mapOf(
                    "status" to "dispatch_timeout_ignored",
                    "action" to action,
                    "message" to error.message.orEmpty(),
                    "fixed_post_action_wait_ms" to REPLAY_ACTION_FIXED_WAIT_MS,
                )
            )
        }
    }

    private fun isGestureDispatchTimeout(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.startsWith("dispatch_timeout:")
    }

    fun remapStepArgs(step: Map<String, Any?>): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = null)

    private fun recordedReplayFallbackIfNeeded(
        transferRequested: Boolean,
        attempted: StepArgsResult,
        initialArgs: Map<String, Any?>,
    ): StepArgsResult {
        return if (transferRequested && attempted.meta["applied"] == false) {
            StepArgsResult(
                args = initialArgs,
                meta = attempted.meta + mapOf(
                    "fallback_replay_mode" to "recorded_action_replay",
                    "recorded_action_args_used" to true,
                )
            )
        } else {
            attempted
        }
    }

    private fun remapStepArgsInternal(
        step: Map<String, Any?>,
        currentXmlOverride: String?,
    ): StepArgsResult {
        val rawArgs = step["args"]
        val args = OobActionCodec.argsForStep(step)
        if (rawArgs !is Map<*, *> && args.isEmpty()) return StepArgsResult(rawArgs)
        if (!shouldUseCoordinateHook(step)) {
            return StepArgsResult(args)
        }
        val tool = actionNameForStep(step)
        if (tool !in OobActionCodec.coordinateActions) {
            return StepArgsResult(args)
        }
        val sourceContext = (step["source_context"] as? Map<*, *>)
            ?: (args["source_context"] as? Map<*, *>)
            ?: return StepArgsResult(
            args,
            meta = mapOf("applied" to false, "reason" to "missing_source_context", "algorithm" to "anchor_projection")
        )
        val srcCtx = sourceContext["src_ctx"] as? Map<*, *>
        val sourceXml = firstNonBlank(
            srcCtx?.get("page"),
            sourceContext["page"],
            sourceContext["xml"],
        )
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
        if (tool == OobActionCodec.ACTION_CLICK || tool == OobActionCodec.ACTION_LONG_PRESS) {
            directCurrentTargetArgs(tool, args, currentXml)?.let { return it }
        }
        return when (tool) {
            OobActionCodec.ACTION_CLICK,
            OobActionCodec.ACTION_LONG_PRESS,
            OobActionCodec.ACTION_INPUT_TEXT -> remapPointActionArgs(tool, args, sourceXml, currentXml)
            OobActionCodec.ACTION_SWIPE -> remapSwipeActionArgs(tool, args, sourceXml, currentXml)
            else -> StepArgsResult(args)
        }
    }

    private fun directCurrentTargetArgs(
        tool: String,
        args: Map<String, Any?>,
        currentXml: String,
    ): StepArgsResult? {
        val nodeResourceId = stringArg(args, "node_resource_id")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val target = stringArg(args, "target_description")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        if (nodeResourceId.isNullOrBlank() && target.isNullOrBlank()) return null
        val page = parsePageModel(currentXml) ?: return null
        if (!nodeResourceId.isNullOrBlank()) {
            page.nodes
                .asSequence()
                .filter { it.visible && it.enabled && it.clickable && it.resourceId == nodeResourceId }
                .sortedWith(compareBy<UiNode> { it.area }.thenBy { it.bounds.top }.thenBy { it.bounds.left })
                .firstOrNull()
                ?.let { node ->
                    return targetArgsForNode(
                        tool = tool,
                        args = args,
                        node = node,
                        mode = "current_node_resource_id",
                        algorithm = "node_resource_id_lookup",
                        confidence = 600f,
                    )
                }
        }
        target ?: return null
        val candidate = page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.clickable }
            .mapNotNull { node ->
                val score = currentTargetScore(node, target)
                if (score <= 0f) null else node to score
            }
            .sortedWith(
                compareByDescending<Pair<UiNode, Float>> { it.second }
                    .thenBy { it.first.area }
                    .thenBy { it.first.bounds.top }
                    .thenBy { it.first.bounds.left }
            )
            .firstOrNull()
            ?: return null
        val node = candidate.first
        return targetArgsForNode(
            tool = tool,
            args = args,
            node = node,
            mode = "current_target_description",
            algorithm = "target_description_lookup",
            confidence = candidate.second,
        )
    }

    private fun targetArgsForNode(
        tool: String,
        args: Map<String, Any?>,
        node: UiNode,
        mode: String,
        algorithm: String,
        confidence: Float,
    ): StepArgsResult =
        StepArgsResult(
            args = args + mapOf("x" to node.centerX, "y" to node.centerY),
            meta = mapOf(
                "applied" to true,
                "tool" to tool,
                "mode" to mode,
                "algorithm" to algorithm,
                "confidence" to confidence,
                "old" to mapOf(
                    "x" to numberArg(args, "x"),
                    "y" to numberArg(args, "y"),
                ).filterValues { it != null },
                "new" to mapOf("x" to node.centerX, "y" to node.centerY),
                "target_element" to summarizeNode(node),
            )
        )

    private fun currentTargetScore(node: UiNode, target: String): Float {
        fun scoreText(value: String, exact: Float, contains: Float): Float {
            val normalized = value.trim().lowercase()
            if (normalized.isBlank()) return 0f
            return when {
                normalized == target -> exact
                normalized.contains(target) || target.contains(normalized) -> contains
                else -> 0f
            }
        }
        return maxOf(
            scoreText(node.text, exact = 500f, contains = 260f),
            scoreText(node.contentDesc, exact = 480f, contains = 240f),
            scoreText(node.subtreeText, exact = 360f, contains = 180f),
            scoreText(node.resourceTail, exact = 440f, contains = 0f),
            scoreText(node.resourceId, exact = 320f, contains = 0f),
        )
    }


    private fun remapStepArgsForState(
        step: Map<String, Any?>,
        state: ReplayState,
    ): StepArgsResult =
        remapStepArgsInternal(step, currentXmlOverride = state.snapshot.xml)

    private fun readCurrentXmlForCoordinateRemapDirect(): String =
        readBackendSnapshotDirect().xml

    private fun shouldUseCoordinateHook(step: Map<String, Any?>): Boolean {
        val coordinateHook = step["coordinate_hook"]?.toString()?.trim()?.lowercase().orEmpty()
        val replayEngine = step["replay_engine"]?.toString()?.trim()?.lowercase().orEmpty()
        val action = actionNameForStep(step)
        val sourceContext = OobActionCodec.sourceContextForStep(step)
        return coordinateHook == RunLogReplayPolicy.EXECUTOR_OMNIFLOW ||
            step["omniflow"] == true ||
            replayEngine == RunLogReplayPolicy.REPLAY_ENGINE_OMNIFLOW_UTG ||
            (RunLogReplayPolicy.isCoordinateAction(action) && sourceContext.isNotEmpty())
    }

    private fun numberArg(args: Map<String, Any?>, vararg keys: String): Number? {
        for (key in keys) {
            val value = args[key] ?: continue
            when (value) {
                is Number -> return value
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun durationMs(args: Map<String, Any?>, defaultMs: Long): Long {
        numberArg(args, "duration_ms")?.toLong()?.let {
            return it.coerceAtLeast(0L)
        }
        return defaultMs
    }

    private fun pressKeyArg(args: Map<String, Any?>): String {
        return when (stringArg(args, "key")?.trim()?.lowercase()) {
            "back" -> "BACK"
            "home" -> "HOME"
            "enter" -> "ENTER"
            else -> throw IllegalArgumentException("press_key requires key=back/home/enter")
        }
    }

    private data class SwipeSpec(
        val x: Float,
        val y: Float,
        val direction: ScrollDirection,
        val distance: Float,
    )

    private fun swipeSpec(
        args: Map<String, Any?>,
        state: ReplayState,
    ): SwipeSpec {
        val x1 = numberArg(args, "x1")?.toFloat()
        val y1 = numberArg(args, "y1")?.toFloat()
        val x2 = numberArg(args, "x2")?.toFloat()
        val y2 = numberArg(args, "y2")?.toFloat()
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            val dx = x2 - x1
            val dy = y2 - y1
            val direction = if (abs(dy) > abs(dx)) {
                if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
            } else {
                if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
            }
            return SwipeSpec(x1, y1, direction, hypot(dx, dy))
        }

        val direction = directionArg(args)
            ?: throw IllegalArgumentException("swipe requires direction or x1/y1/x2/y2")
        val rootCenter = currentRootCenter(state)
        val x: Float = numberArg(args, "x")?.toFloat()
            ?: rootCenter?.first
            ?: DEFAULT_SCREEN_CENTER_X
        val y: Float = numberArg(args, "y")?.toFloat()
            ?: rootCenter?.second
            ?: DEFAULT_SCREEN_CENTER_Y
        val distance: Float = numberArg(args, "distance")
            ?.toFloat()
            ?.coerceAtLeast(1f)
            ?: DEFAULT_SWIPE_DISTANCE
        return SwipeSpec(x, y, direction, distance)
    }

    private fun directionArg(args: Map<String, Any?>): ScrollDirection? {
        val raw = stringArg(args, "direction")
            ?.trim()
            ?.lowercase()
            ?: return null
        return when (raw) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> null
        }
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
        val action = replayAction.action
        if (action == OobActionCodec.ACTION_FINISHED) return emptyList()
        if (action == OobActionCodec.ACTION_OPEN_APP && phase != OmniflowCheckerRule.PHASE_POST_ACTION) {
            return emptyList()
        }
        val globalRules = when (phase) {
            OmniflowCheckerRule.PHASE_PRE_TRANSFER -> OmniflowCheckerRule.GLOBAL_PRE_TRANSFER
            OmniflowCheckerRule.PHASE_PRE_ACTION -> OmniflowCheckerRule.GLOBAL_PRE_ACTION
            OmniflowCheckerRule.PHASE_POST_ACTION -> OmniflowCheckerRule.GLOBAL_POST_ACTION
            else -> emptyList()
        }
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

    private suspend fun evaluateAndExecuteRule(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? = when (rule.condition) {
        OmniflowCheckerRule.COND_RESOLVER_DIALOG ->
            checkerResolverDialog(rule, state, replayAction)
        OmniflowCheckerRule.COND_PERMISSION_DIALOG ->
            checkerPermissionDialog(rule, state, replayAction)
        OmniflowCheckerRule.COND_PACKAGE_MISMATCH ->
            checkerPackageMismatch(rule, state, replayAction)
        OmniflowCheckerRule.COND_AD_BLOCKING ->
            checkerAdBlocking(rule, state, replayAction)
        OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT ->
            checkerAppUpgradePrompt(rule, state, replayAction)
        OmniflowCheckerRule.COND_OVERLAY_BLOCKING ->
            checkerOverlayBlocking(rule, state, replayAction)
        OmniflowCheckerRule.COND_KEYBOARD_OBSCURING ->
            checkerKeyboardObscuring(rule, state, replayAction)
        else -> null
    }

    private fun checkerTriggerLimit(rule: OmniflowCheckerRule): Int =
        OobActionCodec.intArg(
            rule.params["max_triggers"],
            rule.params["maxTriggers"],
            rule.params["trigger_limit"],
            rule.params["triggerLimit"],
            rule.params["max_count"],
            rule.params["maxCount"],
            defaultValue = DEFAULT_CHECKER_TRIGGER_LIMIT,
        ).coerceAtLeast(0)

    private fun OmniflowCheckerRule.budgetKey(): String =
        listOf(phase, id, condition, action).joinToString("|")

    private fun Map<String, Any?>.withCheckerTrigger(
        trigger: CheckerTriggerRecord,
    ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(this@withCheckerTrigger)
        put("trigger_count", trigger.count)
        put("trigger_limit", trigger.limit)
        put("trigger_remaining", trigger.remaining)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private suspend fun checkerResolverDialog(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeResolverConfirm(replayAction.args)) return null
        val page = state.page ?: return null
        if (!looksLikeResolverDialog(page)) return null
        if (recordedStepLooksLikeResolverDialog(replayAction.step)) return null

        val immediateAlways = resolverAlwaysCandidate(page, requireEnabled = true)
        if (immediateAlways != null) {
            return clickResolverAlways(rule, immediateAlways, selectedApp = null)
        }

        val appChoice = resolverAppChoiceCandidate(page) ?: return null
        OmniflowActionRuntime.backend.click(appChoice.centerX, appChoice.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)

        val refreshedPage = parsePageModel(readBackendSnapshot().xml)
            ?.takeIf(::looksLikeResolverDialog)
        val refreshedAlways = refreshedPage?.let {
            resolverAlwaysCandidate(it, requireEnabled = true)
        }
        if (refreshedAlways != null) {
            return clickResolverAlways(rule, refreshedAlways, selectedApp = appChoice)
        }

        return linkedMapOf(
            "phase" to rule.phase,
            "effect" to "run_actions",
            "controller" to rule.id,
            "condition" to OmniflowCheckerRule.COND_RESOLVER_DIALOG,
            "action" to OmniflowCheckerRule.ACTION_SELECT_RESOLVER_APP,
            "pending_action" to OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS,
            "selected_app_text" to nodeLabelText(appChoice),
            "x" to appChoice.centerX,
            "y" to appChoice.centerY,
            "target_element" to summarizeNode(appChoice),
        )
    }

    private suspend fun clickResolverAlways(
        rule: OmniflowCheckerRule,
        candidate: UiNode,
        selectedApp: UiNode?,
    ): Map<String, Any?> {
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to rule.phase,
            "effect" to "run_actions",
            "controller" to rule.id,
            "condition" to OmniflowCheckerRule.COND_RESOLVER_DIALOG,
            "action" to OmniflowCheckerRule.ACTION_CONFIRM_RESOLVER_ALWAYS,
            "button_text" to nodeLabelText(candidate),
            "x" to candidate.centerX,
            "y" to candidate.centerY,
            "target_element" to summarizeNode(candidate),
        ).apply {
            selectedApp?.let {
                put("preselected_app_text", nodeLabelText(it))
                put("preselected_app_element", summarizeNode(it))
            }
        }
    }

    private suspend fun checkerPermissionDialog(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        val page = state.page ?: return null
        if (!looksLikePermissionDialog(page)) return null
        if (recordedActionTargetsPermissionDialog(replayAction)) return null
        val candidate = permissionAllowCandidate(page) ?: return null
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OmniflowCheckerRule.ACTION_ALLOW,
            "button_text" to permissionNodeLabelText(candidate),
            "x" to candidate.centerX,
            "y" to candidate.centerY,
        )
    }

    private fun looksLikePermissionDialog(page: PageModel): Boolean =
        page.nodes.any(::isPermissionControllerNode)

    private fun permissionAllowCandidate(page: PageModel): UiNode? {
        if (!looksLikePermissionDialog(page)) return null
        return page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.clickable }
            .mapNotNull { node ->
                val score = allowButtonScore(node)
                if (score > 0f) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun isPermissionControllerNode(node: UiNode): Boolean {
        val packageName = node.packageName.lowercase()
        val resourceId = node.resourceId.lowercase()
        return PERMISSION_PACKAGES.any { prefix ->
            packageName.startsWith(prefix) || resourceId.startsWith("$prefix:")
        } || PERMISSION_RESOURCE_PACKAGE_TERMS.any { term ->
            resourceId.contains(term)
        }
    }

    private fun resolverAlwaysCandidate(
        page: PageModel,
        requireEnabled: Boolean,
    ): UiNode? {
        return page.nodes
            .asSequence()
            .filter { it.visible && (!requireEnabled || it.enabled) && it.area > 1f }
            .mapNotNull { node ->
                val score = resolverAlwaysButtonScore(node)
                if (score > 0f) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun resolverAppChoiceCandidate(page: PageModel): UiNode? {
        return page.nodes
            .asSequence()
            .mapNotNull { node ->
                val score = resolverAppChoiceScore(node, page)
                if (score > 0f) node to score else null
            }
            .maxWithOrNull(
                compareBy<Pair<UiNode, Float>> { it.second }
                    .thenByDescending { -it.first.bounds.top }
            )
            ?.first
    }

    private fun looksLikeResolverDialog(page: PageModel): Boolean {
        val hasResolverPackage = page.nodes.any { node ->
            RESOLVER_PACKAGES.any { prefix -> node.packageName.startsWith(prefix) } ||
                RESOLVER_PACKAGE_TERMS.any { term ->
                    node.packageName.contains(term) || node.resourceId.contains(term)
                }
        }
        val hasResolverTitle = page.nodes.any { node ->
            val label = nodeLabelText(node).lowercase()
            RESOLVER_TITLE_CONTAINS_LABELS.any { label.contains(it) }
        }
        val hasOnceButton = page.nodes.any { node ->
            val label = nodeLabelText(node).lowercase()
            RESOLVER_ONCE_LABELS.any { label == it || label.contains(it) }
        }
        val hasAlwaysButton = page.nodes.any { resolverAlwaysButtonScore(it) > 0f }
        return hasAlwaysButton && (hasResolverPackage || hasResolverTitle || hasOnceButton)
    }

    private fun resolverAlwaysButtonScore(node: UiNode): Float {
        val label = nodeLabelText(node).lowercase()
        val resource = node.resourceTail.lowercase()
        val exactLabel = RESOLVER_ALWAYS_EXACT_LABELS.any { label == it }
        val containsLabel = RESOLVER_ALWAYS_CONTAINS_LABELS.any { label.contains(it) }
        val resourceMatch = RESOLVER_ALWAYS_RESOURCE_TAILS.any { resource == it || resource.contains(it) }
        if (!exactLabel && !containsLabel && !resourceMatch) return 0f

        var score = 0f
        if (exactLabel) score += 520f
        if (containsLabel) score += 360f
        if (resourceMatch) score += 280f
        if (node.clickable) score += 90f
        if (node.classSuffix == "button") score += 70f
        return score
    }

    private fun resolverOnceButtonScore(node: UiNode): Float {
        val label = nodeLabelText(node).lowercase()
        val resource = node.resourceTail.lowercase()
        val labelMatch = RESOLVER_ONCE_LABELS.any { label == it || label.contains(it) }
        val resourceMatch = RESOLVER_ONCE_RESOURCE_TAILS.any {
            resource == it || resource.contains(it)
        }
        if (!labelMatch && !resourceMatch) return 0f
        var score = 0f
        if (labelMatch) score += 260f
        if (resourceMatch) score += 180f
        if (node.clickable) score += 70f
        if (node.classSuffix == "button") score += 50f
        return score
    }

    private fun resolverAppChoiceScore(node: UiNode, page: PageModel): Float {
        if (!node.visible || !node.enabled || node.area <= 1f || !node.interactive) return 0f
        if (resolverAlwaysButtonScore(node) > 0f || resolverOnceButtonScore(node) > 0f) return 0f

        val label = nodeLabelText(node).lowercase()
        val resource = node.resourceTail.lowercase()
        if (label.isNotBlank() && RESOLVER_TITLE_CONTAINS_LABELS.any { label.contains(it) }) return 0f
        if (RESOLVER_NON_CHOICE_RESOURCE_TAILS.any { resource == it || resource.contains(it) }) return 0f

        val rootArea = page.rootBounds.area.coerceAtLeast(1f)
        val relativeArea = node.area / rootArea
        if (relativeArea > 0.45f) return 0f

        var score = 0f
        if (node.clickable) score += 260f
        if (node.focusable) score += 120f
        if (label.isNotBlank()) score += 90f
        if (RESOLVER_APP_CHOICE_RESOURCE_TAILS.any { resource == it || resource.contains(it) }) {
            score += 140f
        }
        if (node.classSuffix in RESOLVER_APP_CHOICE_CLASS_SUFFIXES) score += 80f
        if (node.bounds.centerY < page.rootBounds.bottom - page.rootBounds.height * 0.18f) {
            score += 60f
        }
        return if (score >= 240f) score else 0f
    }

    private fun allowButtonScore(node: UiNode): Float {
        val label = permissionNodeLabelText(node)
        val resource = node.resourceTail.lowercase()
        val resourceScore = when {
            ALLOW_RESOURCE_TAILS.any { resource == it } -> 400f
            else -> 0f
        }
        val labelScore = when {
            ALLOW_EXACT_LABELS.any { label == it } -> 300f
            ALLOW_CONTAINS_LABELS.any { label.contains(it) } -> 150f
            else -> 0f
        }
        val oncePenalty = if (ALLOW_ONCE_LABELS.any { label.contains(it) }) -100f else 0f
        return resourceScore + labelScore + oncePenalty
    }

    private suspend fun checkerPackageMismatch(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeDismiss(replayAction.args)) return null
        if (recordedActionTargetsPermissionDialog(replayAction)) return null
        val expectedPkg = rule.params["package_name"]?.toString()?.trim()
            ?: stepSourcePackage(replayAction.step)
        if (expectedPkg.isBlank()) return null
        val currentPkg = state.snapshot.effectivePackage()
        if (packageMatchMode(expectedPkg, currentPkg) != null) return null
        runCatching {
            OmniflowActionRuntime.backend.launchApplication(expectedPkg, resetTask = false)
        }
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OmniflowCheckerRule.ACTION_OPEN_APP,
            "expected_package" to expectedPkg,
            "current_package" to currentPkg,
        )
    }

    private suspend fun checkerAdBlocking(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeDismiss(replayAction.args)) return null
        val page = state.page ?: return null
        val candidate = adBlockingDismissCandidate(page) ?: return null
        if (actionTargetHitsNode(replayAction.action, replayAction.args, candidate)) return null
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "condition" to OmniflowCheckerRule.COND_AD_BLOCKING,
            "action" to OmniflowCheckerRule.ACTION_DISMISS,
            "x" to candidate.centerX,
            "y" to candidate.centerY,
            "target_element" to summarizeNode(candidate),
        )
    }

    private suspend fun checkerAppUpgradePrompt(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeDismiss(replayAction.args)) return null
        val page = state.page ?: return null
        val candidate = appUpgradeDismissCandidate(page) ?: return null
        if (actionTargetHitsNode(replayAction.action, replayAction.args, candidate)) return null
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to rule.phase,
            "effect" to "run_actions",
            "controller" to rule.id,
            "condition" to OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT,
            "action" to OmniflowCheckerRule.ACTION_DISMISS,
            "button_text" to nodeLabelText(candidate),
            "x" to candidate.centerX,
            "y" to candidate.centerY,
            "target_element" to summarizeNode(candidate),
        )
    }

    private suspend fun checkerOverlayBlocking(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        if (targetLooksLikeDismiss(replayAction.args)) return null
        val page = state.page ?: return null
        val candidate = blockingOverlayDismissCandidate(page) ?: return null
        OmniflowActionRuntime.backend.click(candidate.centerX, candidate.centerY)
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OobActionCodec.ACTION_CLICK,
            "x" to candidate.centerX,
            "y" to candidate.centerY,
            "target_element" to summarizeNode(candidate),
        )
    }

    private suspend fun checkerKeyboardObscuring(
        rule: OmniflowCheckerRule,
        state: ReplayState,
        replayAction: ReplayAction,
    ): Map<String, Any?>? {
        val action = replayAction.action
        if (action !in OobActionCodec.pointTargetActions + OobActionCodec.ACTION_SWIPE) return null
        val page = state.page ?: return null
        val keyboardTop = keyboardTop(page) ?: return null
        if (!actionTargetIntersectsKeyboard(action, replayAction.args, keyboardTop)) return null
        OmniflowActionRuntime.backend.hideKeyboard()
        delay(PRE_ACTION_CONTROL_DELAY_MS)
        return linkedMapOf(
            "phase" to "before_action",
            "effect" to "run_actions",
            "controller" to rule.id,
            "action" to OmniflowCheckerRule.ACTION_HIDE_KEYBOARD,
            "keyboard_top" to keyboardTop,
        )
    }

    private fun stepSourcePackage(step: Map<String, Any?>): String {
        val srcCtx = (step["source_context"] as? Map<*, *>)?.get("src_ctx") as? Map<*, *>
        val pkg = srcCtx?.get("package_name")?.toString()?.trim().orEmpty()
        if (pkg.isBlank()) return ""
        if (pkg.startsWith("cn.com.omnimind")) return ""
        if (pkg == "android" || pkg == "com.android.systemui") return ""
        if (pkg.contains("launcher", ignoreCase = true)) return ""
        return pkg
    }

    private fun packageMatchMode(expectedPackage: String, currentPackage: String): String? {
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

    private data class BackendSnapshot(
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

    private suspend fun readBackendSnapshot(timing: ReplayStepTiming? = null): BackendSnapshot {
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

    private fun adBlockingDismissCandidate(page: PageModel): UiNode? {
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

    private fun blockingOverlayDismissCandidate(page: PageModel): UiNode? {
        val hasOverlayCue = page.nodes.any(::hasAdOrModalCue)
        return page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.area > 1f && it.interactive }
            .mapNotNull { node ->
                val score = dismissCandidateScore(node, page.rootBounds, hasOverlayCue)
                if (score >= MIN_DISMISS_OVERLAY_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun appUpgradeDismissCandidate(page: PageModel): UiNode? {
        val hasUpgradeCue = page.nodes.any(::hasAppUpgradeCue)
        if (!hasUpgradeCue) return null
        return page.nodes
            .asSequence()
            .filter { it.visible && it.enabled && it.area > 1f && it.interactive }
            .mapNotNull { node ->
                val score = appUpgradeDismissCandidateScore(node, page.rootBounds)
                if (score >= MIN_APP_UPGRADE_DISMISS_SCORE) node to score else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun adDismissCandidateScore(
        node: UiNode,
        rootBounds: Rect,
        hasExplicitAdCue: Boolean,
        hasFullScreenAdSurface: Boolean,
    ): Float {
        val label = nodeLabelText(node)
        val resource = node.resourceId.lowercase()
        val topRight = isTopRightSmallControl(node, rootBounds)
        val small = node.area / rootBounds.area.coerceAtLeast(1f) <= 0.08f
        val explicitAdDismiss = AD_DISMISS_CONTAINS_LABELS.any { label.contains(it) }
        val skipDismiss = AD_SKIP_EXACT_LABELS.any { label == it || label.startsWith("$it ") } ||
            SKIP_COUNTDOWN_REGEX.containsMatchIn(label)
        val closeDismiss = AD_CLOSE_EXACT_LABELS.any { label == it }
        val adResourceDismiss = hasAdDismissResourceCue(resource)
        val genericDismissResource = DISMISS_RESOURCE_TAILS.any {
            node.resourceTail == it || node.resourceTail.contains(it)
        }
        if (!explicitAdDismiss && !skipDismiss && !closeDismiss && !adResourceDismiss && !genericDismissResource) {
            return 0f
        }
        if (!explicitAdDismiss &&
            !hasExplicitAdCue &&
            !adResourceDismiss &&
            !(skipDismiss && (topRight || hasFullScreenAdSurface))
        ) {
            return 0f
        }

        var score = 0f
        if (explicitAdDismiss) score += 560f
        if (SKIP_COUNTDOWN_REGEX.containsMatchIn(label)) score += 520f
        if (skipDismiss) score += 380f
        if (closeDismiss) score += 260f
        if (adResourceDismiss) score += 460f
        if (genericDismissResource) score += 180f
        if (hasExplicitAdCue) score += 240f
        if (hasFullScreenAdSurface) score += 130f
        if (small) score += 130f
        if (topRight) score += 150f
        return score
    }

    private fun dismissCandidateScore(
        node: UiNode,
        rootBounds: Rect,
        hasOverlayCue: Boolean,
    ): Float {
        val label = nodeLabelText(node)
        val resource = node.resourceTail
        val hasAdCue = hasAdOrModalCue(node)
        val dismissByLabel = DISMISS_EXACT_LABELS.any { label == it } ||
            DISMISS_CONTAINS_LABELS.any { label.contains(it) }
        val dismissByResource = DISMISS_RESOURCE_TAILS.any { resource == it || resource.contains(it) }
        if (!dismissByLabel && !dismissByResource) return 0f
        if (!hasOverlayCue && !hasAdCue) return 0f

        val rootArea = rootBounds.area.coerceAtLeast(1f)
        val relativeArea = node.area / rootArea
        val smallButtonScore = if (relativeArea <= 0.08f) 160f else -220f
        val topRightScore = if (
            node.centerX >= rootBounds.left + rootBounds.width * 0.60f &&
            node.centerY <= rootBounds.top + rootBounds.height * 0.35f
        ) {
            130f
        } else {
            0f
        }
        val labelScore = when {
            DISMISS_CONTAINS_LABELS.any { label.contains(it) } -> 520f
            DISMISS_EXACT_LABELS.any { label == it } -> 420f
            else -> 0f
        }
        val resourceScore = if (dismissByResource) 360f else 0f
        val overlayScore = if (hasAdCue) 220f else 120f
        return labelScore + resourceScore + overlayScore + smallButtonScore + topRightScore
    }

    private fun appUpgradeDismissCandidateScore(node: UiNode, rootBounds: Rect): Float {
        val label = nodeLabelText(node)
        val resource = node.resourceTail.lowercase()
        val topRight = isTopRightSmallControl(node, rootBounds)
        val small = node.area / rootBounds.area.coerceAtLeast(1f) <= 0.08f
        val dismissByExact = APP_UPGRADE_DISMISS_EXACT_LABELS.any { label == it }
        val dismissByContains = APP_UPGRADE_DISMISS_CONTAINS_LABELS.any { label.contains(it) }
        val dismissByResource = APP_UPGRADE_DISMISS_RESOURCE_TAILS.any {
            resource == it || resource.contains(it)
        }
        val closeControl = DISMISS_EXACT_LABELS.any { label == it } && (topRight || small)
        val explicitDismiss = dismissByExact || dismissByContains || dismissByResource || closeControl
        if (!explicitDismiss) return 0f
        if (APP_UPGRADE_AFFIRMATIVE_LABELS.any { label == it || label.contains(it) } &&
            !dismissByExact &&
            !dismissByContains
        ) {
            return 0f
        }

        var score = 180f
        if (dismissByExact) score += 520f
        if (dismissByContains) score += 560f
        if (dismissByResource) score += 360f
        if (closeControl) score += 260f
        if (small) score += 80f
        if (topRight) score += 120f
        return score
    }

    private fun hasExplicitAdCue(node: UiNode): Boolean {
        val text = nodeLabelText(node)
        val classText = node.className.lowercase()
        val resource = node.resourceId.lowercase()
        return AD_LABEL_TERMS.any { term ->
            text.contains(term) || classText.contains(term) || resource.contains(term)
        } || AD_RESOURCE_TOKEN_REGEX.containsMatchIn(resource) ||
            AD_RESOURCE_CUE_TERMS.any { resource.contains(it) }
    }

    private fun hasAppUpgradeCue(node: UiNode): Boolean {
        val text = nodeLabelText(node)
        val classText = node.className.lowercase()
        val resource = node.resourceId.lowercase()
        return APP_UPGRADE_CUE_TERMS.any { term ->
            text.contains(term) || classText.contains(term) || resource.contains(term)
        }
    }

    private fun hasAdOrModalCue(node: UiNode): Boolean {
        val text = nodeLabelText(node)
        val classText = node.className.lowercase()
        val resource = node.resourceId.lowercase()
        return AD_OR_MODAL_TERMS.any { term ->
            text.contains(term) || classText.contains(term) || resource.contains(term)
        }
    }

    private fun hasLikelyFullScreenAdSurface(page: PageModel): Boolean {
        val rootArea = page.rootBounds.area.coerceAtLeast(1f)
        return page.nodes.any { node ->
            node.visible &&
                node.area / rootArea >= 0.72f &&
                FULLSCREEN_AD_SURFACE_TERMS.any { term ->
                    node.className.contains(term, ignoreCase = true) ||
                        node.resourceId.contains(term, ignoreCase = true)
                }
        }
    }

    private fun hasAdDismissResourceCue(resource: String): Boolean =
        AD_DISMISS_RESOURCE_TERMS.any { resource.contains(it) } ||
            AD_RESOURCE_TOKEN_REGEX.containsMatchIn(resource)

    private fun isTopRightSmallControl(node: UiNode, rootBounds: Rect): Boolean =
        node.centerX >= rootBounds.left + rootBounds.width * 0.58f &&
            node.centerY <= rootBounds.top + rootBounds.height * 0.26f &&
            node.area / rootBounds.area.coerceAtLeast(1f) <= 0.10f

    private fun targetLooksLikeDismiss(args: Map<String, Any?>): Boolean {
        val target = listOf(
            stringArg(args, "target_description"),
            stringArg(args, "label"),
            stringArg(args, "selector"),
        ).filterNotNull().joinToString(" ").lowercase()
        return DISMISS_EXACT_LABELS.any { target == it } ||
            DISMISS_CONTAINS_LABELS.any { target.contains(it) } ||
            APP_UPGRADE_DISMISS_EXACT_LABELS.any { target == it } ||
            APP_UPGRADE_DISMISS_CONTAINS_LABELS.any { target.contains(it) } ||
            AD_DISMISS_CONTAINS_LABELS.any { target.contains(it) } ||
            AD_SKIP_EXACT_LABELS.any { target == it || target.startsWith("$it ") } ||
            SKIP_COUNTDOWN_REGEX.containsMatchIn(target)
    }

    private fun targetLooksLikeResolverConfirm(args: Map<String, Any?>): Boolean {
        val target = listOf(
            stringArg(args, "target_description"),
            stringArg(args, "label"),
            stringArg(args, "selector"),
        ).filterNotNull().joinToString(" ").lowercase()
        return RESOLVER_ALWAYS_EXACT_LABELS.any { target == it } ||
            RESOLVER_ALWAYS_CONTAINS_LABELS.any { target.contains(it) }
    }

    private fun recordedStepLooksLikeResolverDialog(step: Map<String, Any?>): Boolean {
        val srcCtx = (step["source_context"] as? Map<*, *>)?.get("src_ctx") as? Map<*, *>
        val sourceXml = srcCtx?.get("page")?.toString()?.trim().orEmpty()
        if (sourceXml.isBlank()) return false
        return parsePageModel(sourceXml)?.let(::looksLikeResolverDialog) == true
    }

    private fun recordedActionTargetsPermissionDialog(replayAction: ReplayAction): Boolean {
        if (replayAction.action !in OobActionCodec.pointTargetActions) return false
        if (!shouldUseCoordinateHook(replayAction.step)) return false
        val sourceXml = sourceXmlForStep(replayAction.step)
        if (sourceXml.isBlank()) return false
        val sourcePage = parsePageModel(sourceXml) ?: return false
        if (!looksLikePermissionDialog(sourcePage)) return false
        val x = numberArg(replayAction.args, "x")?.toFloat() ?: return false
        val y = numberArg(replayAction.args, "y")?.toFloat() ?: return false
        val sourceNode = selectPointSourceNode(sourcePage, x, y) ?: return false
        if (!sourceNode.visible || !sourceNode.enabled || !sourceNode.interactive) return false
        return sourcePage.nodes.any { node ->
            isPermissionControllerNode(node) && node.bounds.contains(x, y)
        }
    }

    private fun sourceXmlForStep(step: Map<String, Any?>): String {
        val sourceContext = OobActionCodec.sourceContextForStep(step)
        val srcCtx = OobActionCodec.mapArg(sourceContext["src_ctx"])
        return firstNonBlank(
            srcCtx["page"],
            sourceContext["page"],
            sourceContext["xml"],
        )
    }

    private fun actionTargetHitsNode(
        action: String,
        args: Map<String, Any?>,
        node: UiNode,
    ): Boolean {
        if (action !in OobActionCodec.coordinateActions) return false
        val x = numberArg(args, "x")?.toFloat()
        val y = numberArg(args, "y")?.toFloat()
        if (x != null && y != null) {
            return node.bounds.expanded(ACTION_TARGET_HIT_MARGIN_PX).contains(x, y)
        }
        val x1 = numberArg(args, "x1")?.toFloat()
        val y1 = numberArg(args, "y1")?.toFloat()
        val x2 = numberArg(args, "x2")?.toFloat()
        val y2 = numberArg(args, "y2")?.toFloat()
        val expanded = node.bounds.expanded(ACTION_TARGET_HIT_MARGIN_PX)
        return listOfNotNull(
            x1?.let { px -> y1?.let { py -> px to py } },
            x2?.let { px -> y2?.let { py -> px to py } },
        ).any { (px, py) -> expanded.contains(px, py) }
    }

    private fun keyboardTop(page: PageModel): Float? {
        val rootHeight = page.rootBounds.height.coerceAtLeast(1f)
        return page.nodes
            .asSequence()
            .filter { node ->
                node.visible &&
                    node.bounds.bottom >= page.rootBounds.bottom - rootHeight * 0.04f &&
                    node.bounds.height >= rootHeight * 0.18f &&
                    nodeLabelForKeyboard(node).let { label ->
                        KEYBOARD_TERMS.any { label.contains(it) }
                    }
            }
            .minOfOrNull { it.bounds.top }
    }

    private fun actionTargetIntersectsKeyboard(
        action: String,
        args: Map<String, Any?>,
        keyboardTop: Float,
    ): Boolean {
        val threshold = keyboardTop - KEYBOARD_OBSCURE_MARGIN_PX
        if (action == OobActionCodec.ACTION_SWIPE) {
            val y1 = numberArg(args, "y1")?.toFloat()
            val y2 = numberArg(args, "y2")?.toFloat()
            return listOfNotNull(y1, y2).any { it >= threshold }
        }
        val y = numberArg(args, "y")?.toFloat() ?: return false
        return y >= threshold
    }

    private fun nodeLabelText(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText, node.resourceTail)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

    private fun permissionNodeLabelText(node: UiNode): String =
        listOf(node.text, node.contentDesc, node.hintText, node.resourceTail, node.subtreeText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .lowercase()

    private fun nodeLabelForKeyboard(node: UiNode): String =
        listOf(
            node.text,
            node.contentDesc,
            node.hintText,
            node.resourceId,
            node.packageName,
            node.className,
        ).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    private fun currentRootCenter(state: ReplayState): Pair<Float, Float>? =
        state.page?.let { it.rootBounds.centerX to it.rootBounds.centerY }

    private data class Rect(
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

    private data class UiNode(
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

    private data class PageModel(
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
        val mapped = remapPointWithinPages(sourcePage, targetPage, x, y)
            ?: if (coordinateReplayAllowed(args)) {
                remapPointWithinRoots(sourcePage, targetPage, x, y)
            } else {
                null
            }
            ?: return StepArgsResult(
                args,
                meta = mapOf("applied" to false, "reason" to "no_anchor_match", "algorithm" to "anchor_projection")
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

    private fun remapSwipeActionArgs(
        tool: String,
        args: Map<String, Any?>,
        sourceXml: String,
        currentXml: String,
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
            ?: return if (coordinateReplayAllowed(args)) {
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

    private fun coordinateReplayAllowed(args: Map<String, Any?>): Boolean =
        boolArg(args["coordinate_replay_allowed"]) ||
            boolArg(args["coordinateReplayAllowed"]) ||
            boolArg(args["raw_coordinate_replay_allowed"]) ||
            boolArg(args["allow_raw_coordinate_replay"]) ||
            stringArg(args, "projection_mode", "projectionMode")
                ?.equals("fixed", ignoreCase = true) == true

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
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceX: Float,
        sourceY: Float,
    ): PointMapping? {
        val sourceNode = selectPointSourceNode(sourcePage, sourceX, sourceY) ?: return null
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

    private fun matchTargetNode(
        sourcePage: PageModel,
        targetPage: PageModel,
        sourceNode: UiNode,
    ): TargetMatch? {
        // Fast path: exact identity match bypasses Bayesian
        signatureGuard(sourceNode, targetPage)?.let { return it }

        val srcArea = sourcePage.rootBounds.area.coerceAtLeast(1f)
        val tgtArea = targetPage.rootBounds.area.coerceAtLeast(1f)

        // Compute all target NodeInfos and vectors once; reuse for anchors and candidates
        val allTgtInfos = targetPage.nodes.map { it.toNodeInfo(tgtArea) }
        val allTgtVecs = allTgtInfos.map { OmniflowNodeMatcher.vector(it) }

        val anchorSrcNodes = sourcePage.nodes.filter { isAnchorCandidate(it, sourcePage.rootBounds) }
            .map { it.toNodeInfo(srcArea) }
        val anchorSrcVecs = anchorSrcNodes.map { OmniflowNodeMatcher.vector(it) }
        val anchorTgtIdx = targetPage.nodes.indices.filter { isAnchorCandidate(targetPage.nodes[it], targetPage.rootBounds) }
        val anchorTgtInfos = anchorTgtIdx.map { allTgtInfos[it] }
        val anchorTgtVecs = anchorTgtIdx.map { allTgtVecs[it] }
        val anchors = OmniflowNodeMatcher.findAnchors(anchorSrcNodes, anchorSrcVecs, anchorTgtInfos, anchorTgtVecs)

        val candIdx = targetPage.nodes.indices.filter { targetPage.nodes[it].let { n -> n.visible && n.enabled && n.area > 1f } }
        if (candIdx.isEmpty()) return null
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
        if (result.abstain) return null

        val bestNode = candidates[result.index]
        if (sourceNode.interactive && !bestNode.interactive) {
            return null
        }
        return TargetMatch(
            node = bestNode,
            confidence = result.confidence,
            anchorCount = anchors.size,
            mode = result.mode,
            debug = mapOf(
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(bestNode),
                "anchor_count" to anchors.size,
            ) + result.debug,
        )
    }

    private fun signatureGuard(sourceNode: UiNode, targetPage: PageModel): TargetMatch? {
        val resourceId = sourceNode.resourceId.takeIf { it.isNotBlank() }
        val text = sourceNode.text.takeIf { it.isNotBlank() }
        val contentDesc = sourceNode.contentDesc.takeIf { it.isNotBlank() }
        if (resourceId == null && text == null && contentDesc == null) return null

        val matches = targetPage.nodes.filter { t ->
            t.visible && t.enabled && (
                (resourceId != null && resourceId == t.resourceId) ||
                (text != null && text == t.text) ||
                (contentDesc != null && contentDesc == t.contentDesc))
        }
        if (matches.size != 1) return null

        val node = matches[0]
        return TargetMatch(
            node = node,
            confidence = 1f,
            anchorCount = 0,
            mode = "unique_action_signature",
            debug = mapOf(
                "source_element" to summarizeNode(sourceNode),
                "target_element" to summarizeNode(node),
                "matched_by" to listOfNotNull(
                    "resource_id".takeIf { resourceId == node.resourceId },
                    "text".takeIf { text == node.text },
                    "content_desc".takeIf { contentDesc == node.contentDesc },
                ),
            ),
        )
    }

    private fun selectPointSourceNode(
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

    private fun parsePageModel(xml: String): PageModel? {
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

    private fun summarizeNode(node: UiNode): Map<String, Any?> = mapOf(
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

    private fun parseBounds(bounds: String?): Rect? {
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

    private fun normalizeText(value: String?): String =
        value.orEmpty().trim().lowercase().replace(Regex("\\s+"), " ")

    private fun classSuffix(className: String): String =
        className.substringAfterLast('.').lowercase()

    private fun resourceTail(resourceId: String): String {
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

    private class ReplayStepTiming {
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

    private const val PRE_ACTION_CONTROL_DELAY_MS = 1_000L
    private const val DEFAULT_SCREEN_CENTER_X = 540f
    private const val DEFAULT_SCREEN_CENTER_Y = 960f
    private const val DEFAULT_SWIPE_DISTANCE = 600f
    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    private const val MIN_AD_DISMISS_SCORE = 760f
    private const val MIN_DISMISS_OVERLAY_SCORE = 760f
    private const val MIN_APP_UPGRADE_DISMISS_SCORE = 700f
    private const val KEYBOARD_OBSCURE_MARGIN_PX = 16f
    private const val ACTION_TARGET_HIT_MARGIN_PX = 24f
    private val REPLAY_STEP_PHASE_NAMES = listOf(
        "observe_ms",
        "checker_ms",
        "action_transfer_ms",
        "act_ms",
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
    private val AD_OR_MODAL_TERMS = setOf(
        "advert",
        "sponsor",
        "promo",
        "promotion",
        "dialog",
        "popup",
        "modal",
        "广告",
        "推广",
        "赞助",
        "弹窗",
    )
    private val AD_LABEL_TERMS = setOf(
        "advert",
        "sponsored",
        "sponsor",
        "promotion",
        "interstitial",
        "splash ad",
        "广告",
        "推广",
        "赞助",
        "开屏",
        "插屏",
    )
    private val AD_RESOURCE_CUE_TERMS = setOf(
        "advert",
        "splash",
        "interstitial",
        "reward",
        "rewarded",
        "ksad",
        "gdt",
        "tt_splash",
        "admob",
        "bytedance",
        "pangle",
    )
    private val AD_DISMISS_RESOURCE_TERMS = setOf(
        "skip_ad",
        "ad_skip",
        "close_ad",
        "ad_close",
        "btn_skip",
        "skip_btn",
        "splash_skip",
        "tt_splash_skip",
        "ksad_skip",
        "gdt_skip",
    )
    private val AD_SKIP_EXACT_LABELS = setOf(
        "skip",
        "跳过",
    )
    private val AD_CLOSE_EXACT_LABELS = setOf(
        "close",
        "dismiss",
        "x",
        "×",
        "关闭",
    )
    private val AD_DISMISS_CONTAINS_LABELS = setOf(
        "close ad",
        "close ads",
        "skip ad",
        "skip ads",
        "dismiss ad",
        "关闭广告",
        "跳过广告",
    )
    private val DISMISS_EXACT_LABELS = setOf(
        "close",
        "dismiss",
        "skip",
        "x",
        "×",
        "关闭",
        "跳过",
    )
    private val DISMISS_CONTAINS_LABELS = setOf(
        "close ad",
        "close ads",
        "skip ad",
        "skip ads",
        "dismiss ad",
        "not now",
        "关闭广告",
        "跳过广告",
        "关闭弹窗",
        "稍后再说",
        "以后再说",
    )
    private val DISMISS_RESOURCE_TAILS = setOf(
        "close",
        "close_button",
        "btn_close",
        "iv_close",
        "dismiss",
        "skip",
        "skip_ad",
        "ad_close",
        "close_ad",
    )
    private val APP_UPGRADE_CUE_TERMS = setOf(
        "app update",
        "app upgrade",
        "new version",
        "new_version",
        "update available",
        "upgrade available",
        "version update",
        "version upgrade",
        "upgrade",
        "更新提示",
        "版本更新",
        "版本升级",
        "检测到新版本",
        "发现新版本",
        "新版本",
        "新版",
        "升级",
        "更新",
    )
    private val APP_UPGRADE_DISMISS_EXACT_LABELS = setOf(
        "not now",
        "later",
        "maybe later",
        "skip",
        "cancel",
        "close",
        "dismiss",
        "x",
        "×",
        "稍后再说",
        "以后再说",
        "下次再说",
        "暂不升级",
        "暂不更新",
        "暂不",
        "稍后",
        "以后",
        "取消",
        "忽略",
        "跳过",
        "关闭",
    )
    private val APP_UPGRADE_DISMISS_CONTAINS_LABELS = setOf(
        "not now",
        "maybe later",
        "remind me later",
        "skip update",
        "skip upgrade",
        "cancel update",
        "cancel upgrade",
        "稍后再说",
        "以后再说",
        "下次再说",
        "暂不升级",
        "暂不更新",
        "取消升级",
        "取消更新",
        "跳过升级",
        "跳过更新",
    )
    private val APP_UPGRADE_DISMISS_RESOURCE_TAILS = setOf(
        "cancel",
        "btn_cancel",
        "button_cancel",
        "later",
        "btn_later",
        "not_now",
        "btn_not_now",
        "skip",
        "btn_skip",
        "close",
        "close_button",
        "btn_close",
        "iv_close",
    )
    private val APP_UPGRADE_AFFIRMATIVE_LABELS = setOf(
        "update",
        "upgrade",
        "update now",
        "upgrade now",
        "install",
        "download",
        "立即更新",
        "立即升级",
        "马上更新",
        "马上升级",
        "去更新",
        "去升级",
        "下载安装",
        "安装",
        "下载",
        "更新",
        "升级",
    )
    private val FULLSCREEN_AD_SURFACE_TERMS = setOf(
        "webview",
        "image",
        "frame",
        "layout",
        "splash",
        "interstitial",
        "ad",
    )
    private val AD_RESOURCE_TOKEN_REGEX = Regex("""(^|[/:_.-])ads?($|[/:_.-])""")
    private val SKIP_COUNTDOWN_REGEX = Regex("""(跳过|skip)\s*\d+\s*(s|sec|秒)?""", RegexOption.IGNORE_CASE)
    private val KEYBOARD_TERMS = setOf(
        "keyboard",
        "inputmethod",
        "input_method",
        "latin",
        "gboard",
        "softinput",
        "软键盘",
        "键盘",
    )

    private val RESOLVER_PACKAGES = setOf(
        "android",
        "com.android.intentresolver",
        "com.google.android.intentresolver",
        "com.vivo.appfilter",
    )

    private val RESOLVER_PACKAGE_TERMS = setOf(
        "resolver",
        "chooser",
        "intentresolver",
        "appfilter",
    )

    private val RESOLVER_TITLE_CONTAINS_LABELS = setOf(
        "打开方式",
        "选择应用",
        "选择要使用的应用",
        "使用以下方式打开",
        "默认打开",
        "想要打开",
        "open with",
        "complete action using",
        "choose an app",
        "choose app",
    )

    private val RESOLVER_ALWAYS_EXACT_LABELS = setOf(
        "始终打开",
        "始终",
        "always",
        "always open",
        "open always",
    )

    private val RESOLVER_ALWAYS_CONTAINS_LABELS = setOf(
        "始终打开",
        "always open",
        "open always",
    )

    private val RESOLVER_ONCE_LABELS = setOf(
        "仅此一次",
        "仅限一次",
        "只此一次",
        "仅打开一次",
        "just once",
        "only once",
        "once",
    )

    private val RESOLVER_ONCE_RESOURCE_TAILS = setOf(
        "once",
        "button_once",
        "once_button",
        "resolver_once",
        "button_once_open",
    )

    private val RESOLVER_ALWAYS_RESOURCE_TAILS = setOf(
        "always",
        "button_always",
        "always_button",
        "resolver_always",
        "button_always_open",
        "always_open",
    )

    private val RESOLVER_APP_CHOICE_RESOURCE_TAILS = setOf(
        "text1",
        "text2",
        "title",
        "app_name",
        "resolver_list",
        "profile_button",
    )

    private val RESOLVER_NON_CHOICE_RESOURCE_TAILS = setOf(
        "button_bar",
        "button_once",
        "button_always",
        "always_button",
        "once_button",
        "resolver_button_bar",
    )

    private val RESOLVER_APP_CHOICE_CLASS_SUFFIXES = setOf(
        "textview",
        "linearlayout",
        "relativelayout",
        "framelayout",
        "recyclerview",
    )

    private val PERMISSION_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
    )

    private val PERMISSION_RESOURCE_PACKAGE_TERMS = setOf(
        ".permissioncontroller:id/",
        ".packageinstaller:id/",
    )

    private const val DEFAULT_CHECKER_TRIGGER_LIMIT = 1
    private const val DEFAULT_PAGE_GUARD_TRIGGER_LIMIT = 3
    private const val REPLAY_ACTION_FIXED_WAIT_MS = 1000L
    private const val STOP_POLL_INTERVAL_MS = 50L

    private val ALLOW_EXACT_LABELS = setOf(
        "允许", "allow", "始终允许", "always allow",
        "authorize", "授权", "同意", "agree",
    )

    private val ALLOW_CONTAINS_LABELS = setOf("允许", "allow")

    // "仅此一次" / "one time" style — valid allow but deprioritised vs broader grants.
    private val ALLOW_ONCE_LABELS = setOf("仅此一次", "one time", "once")

    private val ALLOW_RESOURCE_TAILS = setOf(
        "permission_allow_button",
        "permission_allow_one_time_button",
        "permission_allow_foreground_only_button",
        "allow_button",
        "btn_allow",
    )
}
