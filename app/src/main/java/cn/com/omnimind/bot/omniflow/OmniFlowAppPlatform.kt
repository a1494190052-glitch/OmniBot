package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.controller.http.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.plugin.runtime.RuntimeSkillBundleManager
import com.ai.assistance.operit.terminal.TerminalManager
import java.util.UUID

internal class OmniFlowAppPlatform(
    private val runtimeSkills: RuntimeSkillBundleManager,
) : OmniFlowPlatform {
    private companion object {
        const val TAG = "[OmniFlowAppPlatform]"
        const val PREFS_NAME = "omniflow_python_runtime"
        const val READY_VERSION_KEY = "python_ready_version"
    }

    override suspend fun startProcess(
        context: Context,
        command: String,
        environment: Map<String, String>,
    ): Process = TerminalManager.getInstance(context.applicationContext)
        .startLongLivedAlpineProcess(
            command = command,
            executorKey = "omniflow-${UUID.randomUUID()}",
            redirectErrorStream = false,
            extraEnvironment = environment,
        )

    override suspend fun ensurePython(context: Context, expectedVersion: String) {
        val appContext = context.applicationContext
        val terminalStartedAt = System.currentTimeMillis()
        val terminalStatus = EmbeddedTerminalRuntime.warmup(appContext)
        log(
            "terminal_ready initialized=${terminalStatus.initialized} " +
                "durationMs=${System.currentTimeMillis() - terminalStartedAt}",
        )
        require(terminalStatus.success && terminalStatus.initialized) {
            terminalStatus.message.ifBlank { "omniflow_terminal_runtime_unavailable" }
        }
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(READY_VERSION_KEY, null) == expectedVersion) {
            log("python_ready_cached version=$expectedVersion")
            return
        }
        val startedAt = System.currentTimeMillis()
        log("python_prepare_start version=$expectedVersion")
        val command = """
            expected='$expectedVersion'
            packages_ready() {
              for package in python3 py3-pip libstdc++ ca-certificates; do
                apk info -e "${'$'}package" >/dev/null 2>&1 || return 1
              done
              command -v python3 >/dev/null 2>&1 &&
              python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' | grep -qx "${'$'}expected" &&
              python3 -m pip --version >/dev/null 2>&1 &&
              test -e /usr/lib/libstdc++.so.6
            }
            if ! packages_ready; then
              apk update >/dev/null &&
              apk add --no-cache python3 py3-pip libstdc++ ca-certificates >/dev/null
            fi
            packages_ready
        """.trimIndent()
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = "omniflow-python-runtime",
            timeoutMs = 5 * 60_000L,
        )
        require(result.isOk && result.exitCode == 0) {
            result.error.takeIf(String::isNotBlank)
                ?: result.output.takeLast(800).trim()
                    .ifBlank { "omniflow_python_runtime_unavailable" }
        }
        prefs.edit().putString(READY_VERSION_KEY, expectedVersion).apply()
        log(
            "python_prepare_ready version=$expectedVersion " +
                "durationMs=${System.currentTimeMillis() - startedAt}",
        )
    }

    private fun log(message: String) {
        runCatching { OmniLog.i(TAG, message) }
    }

    override suspend fun resolveRuntimeSkill(
        context: Context,
        refresh: Boolean,
    ): OmniFlowSkillLocation {
        val location = runtimeSkills.resolve(refresh)
        return OmniFlowSkillLocation(
            androidRoot = location.androidRoot,
            shellRoot = location.shellRoot,
            source = location.source,
        )
    }

    override suspend fun bootstrapRuntimeSkill(
        context: Context,
        location: OmniFlowSkillLocation,
    ) {
        runtimeSkills.bootstrap(
            cn.com.omnimind.bot.plugin.runtime.RuntimeSkillLocation(
                androidRoot = location.androidRoot,
                shellRoot = location.shellRoot,
                source = location.source,
            )
        )
    }

    override suspend fun reclaimRuntimeSkill(context: Context) {
        runtimeSkills.reclaim()
    }

    override suspend fun completeJson(request: ChatCompletionRequest): String {
        val response = HttpController.postSceneChatCompletion(request)
        return resolveOmniFlowJsonCompletion(response)
    }

}

internal fun resolveOmniFlowJsonCompletion(response: SceneChatCompletionResponse): String {
    check(response.success) { response.message.ifBlank { "model_completion_failed" } }
    val toolCall = response.toolCalls.singleOrNull {
        it.function.name == "submit_json"
    } ?: error("model_completion_submit_json_required")
    return toolCall.function.arguments.trim().ifBlank {
        error("model_completion_submit_json_empty")
    }
}
