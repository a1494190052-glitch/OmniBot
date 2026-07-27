package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogWriter
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.bot.omniflow.ui.ExecutionControls
import cn.com.omnimind.bot.omniflow.ui.ExecutionPhase
import cn.com.omnimind.bot.omniflow.ui.initialExecutionPhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object OmniFlow {
    data class Run(
        val id: String,
        val goal: String,
        val source: String,
        val toolName: String,
        val input: Map<String, Any?>,
        val title: String = goal,
        val operationDescription: String = goal,
        val startedAtMs: Long = System.currentTimeMillis(),
        val cancelledDoneReason: String = "cancelled",
        val stoppedErrorCode: String = "OOB_GUI_TASK_STOPPED",
        val failedErrorCode: String = "OOB_GUI_TASK_FAILED",
    )

    data class Hooks(
        val beforeOperation: suspend () -> Unit = {},
        val stopRequested: () -> Boolean = { false },
        val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
    )

    data class Result(
        val payload: Map<String, Any?>,
        val finalStateId: String?,
    )

    private val executionMutex = Mutex()
    private val executions = ExecutionRegistry()

    fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider(),
    ) {
        OmniFlowPlatformRegistry.configure(platform)
        OmniFlowPythonRuntime.configure(platform, runtimeProvider)
    }

    fun warmup(context: Context) {
        OmniFlowPythonRuntime.start(context)
    }

    suspend fun reloadRuntime(context: Context): OmniFlowRuntimeManifest =
        OmniFlowPythonRuntime.reload(context)

    suspend fun run(
        context: Context,
        request: Run,
        modelClient: OmniFlowModelClient? = null,
        hooks: Hooks = Hooks(),
    ): Result = executionMutex.withLock {
        require(request.id.isNotBlank()) { "run_id_required" }

        val executionJob = currentCoroutineContext()[Job]
        val stopped = AtomicBoolean(false)
        val requestStop = {
            if (stopped.compareAndSet(false, true)) {
                executionJob?.cancel(CancellationException("OmniFlow execution stopped"))
            }
        }
        val executionUi = ExecutionControls.start(
            context = context,
            title = request.title,
            initialPhase = initialExecutionPhase(usesModel = modelClient != null),
            onStop = requestStop,
        )
        val registration = executions.begin(
            runId = request.id,
            onStop = requestStop,
        )
        var result: Map<String, Any?>? = null
        var cancelled = false
        try {
            val beforeOperation: suspend () -> Unit = {
                executionUi.awaitRunning()
                ensureRunning(stopped, hooks)
                hooks.beforeOperation()
                ensureRunning(stopped, hooks)
            }
            val host = AndroidHost(
                context = context,
                request = request,
                modelClient = modelClient,
                beforeOperation = beforeOperation,
                stopRequested = { stopped.get() || hooks.stopRequested() },
                onPhase = executionUi::updatePhase,
                onProgress = { progress, extras ->
                    executionUi.update(progress)
                    hooks.onProgress(progress, extras)
                },
            )
            val payload = host.run()
            result = payload
            Result(payload, host.currentStateId)
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } finally {
            executions.end(registration)
            val message = completionMessage(result, cancelled || stopped.get())
            val visibleMs = if (result?.get("success") == false) 2_500L else 900L
            withContext(NonCancellable) {
                executionUi.finish(message, visibleMs)
            }
        }
    }

    suspend fun call(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        require(operation != "run") { "omniflow_run_must_use_OmniFlow_run" }
        return AndroidHost(context).call(operation, payload)
    }

    fun stop(runOrTaskId: String? = null): Boolean = executions.stop(runOrTaskId)

    private fun ensureRunning(stopped: AtomicBoolean, hooks: Hooks) {
        if (stopped.get() || hooks.stopRequested()) {
            throw CancellationException("OmniFlow execution stopped")
        }
    }

    private fun completionMessage(result: Map<String, Any?>?, stopped: Boolean): String = when {
        stopped -> "任务已停止"
        result?.get("done_reason") == "waiting_input" -> "任务等待输入"
        result?.get("success") == true -> "任务已完成"
        else -> "任务执行失败"
    }
}

private class AndroidHost(
    context: Context,
    private val request: OmniFlow.Run? = null,
    modelClient: OmniFlowModelClient? = null,
    private val beforeOperation: suspend () -> Unit = {},
    private val stopRequested: () -> Boolean = { false },
    private val onPhase: (ExecutionPhase) -> Unit = {},
    private val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val environment = AndroidGuiEnvironment(appContext)
    private val writer = request?.let { activeRun ->
        RunLogWriter { record ->
            InternalRunLogStore.upsertRecordedStep(appContext, activeRun.id, record)
        }
    }
    private val modelHost = modelClient?.let { client ->
        OmniFlowModelHost(client) { thinking ->
            onProgress(thinking, progressPayload(mapOf("thinking" to thinking)))
        }
    }
    private val hostCall = OmniFlowPythonHostCall(::invoke)

    var currentStateId: String? = null
        private set

    suspend fun call(
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = OmniFlowPythonRuntime.call(
        context = appContext,
        operation = operation,
        payload = payload,
        hostCall = hostCall,
    )

    suspend fun run(): Map<String, Any?> {
        val activeRun = requireNotNull(request) { "run_not_configured" }
        InternalRunLogStore.beginRun(
            context = appContext,
            runId = activeRun.id,
            goal = activeRun.goal,
            source = activeRun.source,
            toolName = activeRun.toolName,
            operationDescription = activeRun.operationDescription,
            startedAtMs = activeRun.startedAtMs,
        )
        return try {
            beforeOperation()
            check(environment.awaitReady()) { "android_gui_accessibility_not_ready" }
            beforeOperation()
            val payload = linkedMapOf<String, Any?>().apply {
                putAll(activeRun.input)
                put("run_id", activeRun.id)
            }
            call("run", payload).also { result ->
                require(firstText(result["run_id"]) == activeRun.id) {
                    "android_gui_run_id_mismatch"
                }
                finishRun(result)
            }
        } catch (error: CancellationException) {
            finishRun(cancelledFailure(activeRun.cancelledDoneReason, error))
            throw error
        } catch (error: Exception) {
            failure(activeRun, error).also(::finishRun)
        }
    }

    private suspend fun invoke(method: String, payload: Map<String, Any?>): Map<String, Any?> =
        when (method) {
            "observe" -> observe(payload)
            "act" -> act(payload)
            "get_run_log" -> getRunLog(payload)
            "get_state" -> getState(payload)
            "installed_apps" -> installedApps()
            "list_run_logs" -> listRunLogs(payload)
            "record_step" -> recordStep(payload)
            "model_turn" -> modelTurn(payload)
            "complete_json" -> completeJson(payload)
            "schedule_operation" -> schedule(payload)
            "update_run_log_diagnostics" -> updateDiagnostics(payload)
            "request_input" -> error("request_input_must_be_deferred")
            else -> error("unsupported_host_call:$method")
        }

    private suspend fun observe(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        return environment.observe(captureScreenshot = payload["screenshot"] != false)
            .also { currentStateId = it.stateId }
            .asMap()
    }

    private suspend fun act(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        onPhase(ExecutionPhase.AUTOMATIC)
        val action = Action.fromMap(mapValue(payload["action"]))
        val sourceState = State.fromMap(mapValue(payload["state"]))
        require(sourceState.stateId == currentStateId) { "host_action_state_stale" }
        val metadata = mapValue(payload["metadata"])
        onProgress(
            firstText(metadata["summary"], action.tool).ifBlank { "GUI action" },
            progressPayload(metadata + mapOf("action" to action.asMap())),
        )
        val result = environment.act(action)
        return linkedMapOf<String, Any?>(
            "success" to result.success,
            "error" to result.message.takeUnless { result.success },
            "extra" to linkedMapOf(
                "message" to result.message,
                "diagnostics" to result.diagnostics,
            ),
        ).filterValues { it != null }
    }

    private fun getRunLog(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedRunId = firstText(payload["run_id"])
        require(requestedRunId.isNotEmpty()) { "run_id_required" }
        return InternalRunLogStore.timelinePayload(appContext, requestedRunId)
    }

    private fun getState(payload: Map<String, Any?>): Map<String, Any?> {
        val stateId = firstText(payload["state_id"])
        require(stateId.isNotEmpty()) { "state_id_required" }
        return InternalRunLogStore.statePayload(appContext, stateId)
            .also { require(it.isNotEmpty()) { "state_not_found:$stateId" } }
    }

    private suspend fun installedApps(): Map<String, Any?> =
        mapOf("apps" to environment.installedApplications())

    private fun listRunLogs(payload: Map<String, Any?>): Map<String, Any?> =
        InternalRunLogStore.listRuns(
            context = appContext,
            limit = intValue(payload["limit"], defaultValue = 50).coerceIn(1, 200),
            offset = intValue(payload["offset"], defaultValue = 0).coerceAtLeast(0),
            source = firstText(payload["source"]),
            status = firstText(payload["status"]),
            model = firstText(payload["model"]),
            query = firstText(payload["query"]),
        )

    private suspend fun recordStep(payload: Map<String, Any?>): Map<String, Any?> {
        val fact = mapValue(payload["fact"])
        requireStateExists(firstText(fact["before_state_id"]))
        requireStateExists(firstText(fact["after_state_id"]))
        val record = requireNotNull(writer) { "record_step_run_not_configured" }.write(fact)
        return mapOf("step" to record.step)
    }

    private suspend fun modelTurn(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        onPhase(ExecutionPhase.REASONING)
        return requireNotNull(modelHost) { "model_turn_not_available" }.modelTurn(payload)
    }

    private suspend fun completeJson(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        return OmniFlowModelHost.completeJson(payload)
    }

    private fun schedule(payload: Map<String, Any?>): Map<String, Any?> =
        OmniFlowPythonRuntime.schedule(
            context = appContext,
            operation = firstText(payload["operation"]),
            payload = mapValue(payload["payload"]),
            hostCall = hostCall,
        )

    private fun updateDiagnostics(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedRunId = firstText(payload["run_id"])
        require(requestedRunId.isNotEmpty()) { "run_id_required" }
        val diagnostics = mapValue(payload["diagnostics"])
        require(diagnostics.isNotEmpty()) { "run_log_diagnostics_required" }
        InternalRunLogStore.updateDiagnostics(appContext, requestedRunId, diagnostics)
        return mapOf("updated" to true)
    }

    private fun requireStateExists(stateId: String) {
        require(stateId.isNotEmpty()) { "state_id_required" }
        require(InternalRunLogStore.statePayload(appContext, stateId).isNotEmpty()) {
            "run_log_state_not_persisted:$stateId"
        }
    }

    private fun progressPayload(value: Map<String, Any?>): Map<String, Any?> =
        request?.let { value + ("run_id" to it.id) } ?: value

    private fun finishRun(result: Map<String, Any?>) {
        val activeRun = requireNotNull(request)
        val success = result["success"] == true
        val resultFinalStateId = firstText(mapValue(result["final_state"])["state_id"])
        plannerRunLogDiagnostics(result)?.let { diagnostics ->
            InternalRunLogStore.updateDiagnostics(
                context = appContext,
                runId = activeRun.id,
                diagnostics = diagnostics,
            )
        }
        InternalRunLogStore.finishRun(
            context = appContext,
            runId = activeRun.id,
            success = success,
            doneReason = firstText(result["done_reason"]).ifBlank {
                if (success) "finished" else "error"
            },
            errorMessage = firstText(result["error_message"], result["error_code"])
                .takeIf(String::isNotEmpty),
            finishedAtMs = (result["finished_at_ms"] as? Number)?.toLong()
                ?: System.currentTimeMillis(),
            finalStateId = resultFinalStateId.ifEmpty { currentStateId },
        )
    }

    private fun failure(activeRun: OmniFlow.Run, error: Exception): Map<String, Any?> {
        val stopped = stopRequested()
        val finishedAtMs = System.currentTimeMillis()
        return linkedMapOf<String, Any?>(
            "success" to false,
            "status" to "failed",
            "run_id" to activeRun.id,
            "function_id" to firstText(activeRun.input["function_id"]).takeIf(String::isNotEmpty),
            "source" to activeRun.source,
            "runner" to "omniflow_python",
            "execution_mode" to firstText(activeRun.input["execution_mode"]).ifBlank { "foreground" },
            "started_at_ms" to activeRun.startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "duration_ms" to (finishedAtMs - activeRun.startedAtMs).coerceAtLeast(0L),
            "error_code" to if (stopped) activeRun.stoppedErrorCode else activeRun.failedErrorCode,
            "error_message" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            "done_reason" to if (stopped) activeRun.cancelledDoneReason else "error",
        ).filterValues { it != null }
    }

    private fun cancelledFailure(doneReason: String, error: Exception): Map<String, Any?> =
        mapOf(
            "success" to false,
            "done_reason" to doneReason,
            "error_message" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
        )
}

internal fun plannerRunLogDiagnostics(
    result: Map<String, Any?>,
): Map<String, Any?>? = mapValue(result["planner_diagnostics"])
    .takeIf(Map<String, Any?>::isNotEmpty)
    ?.let { mapOf("planner" to it) }
