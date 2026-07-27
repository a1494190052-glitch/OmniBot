package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.bot.agent.AgentLlmClient
import com.ai.assistance.operit.terminal.TerminalManager
import java.util.UUID

internal object OmniFlowAppPlatform : OmniFlowPlatform {
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
        val command = """
            expected='$expectedVersion'
            python_ready() {
              command -v python3 >/dev/null 2>&1 &&
              python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])' | grep -qx "${'$'}expected" &&
              test -e /usr/lib/libstdc++.so.6
            }
            if ! python_ready; then
              apk update >/dev/null && apk add --no-cache python3 libstdc++ >/dev/null
            fi
            python_ready
        """.trimIndent()
        val result = TerminalManager.getInstance(context.applicationContext).executeHiddenCommand(
            command = command,
            executorKey = "omniflow-python-runtime",
            timeoutMs = 5 * 60_000L,
        )
        require(result.isOk && result.exitCode == 0) {
            result.error.takeIf(String::isNotBlank)
                ?: result.output.takeLast(800).trim()
                    .ifBlank { "omniflow_python_runtime_unavailable" }
        }
    }

    override suspend fun completeJson(request: ChatCompletionRequest): String {
        val response = HttpController.postSceneChatCompletion(request)
        check(response.success) { response.message.ifBlank { "model_completion_failed" } }
        return response.content.ifBlank { response.message }
    }
}

internal fun AgentLlmClient.asOmniFlowModelClient(): OmniFlowModelClient =
    object : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn = this@asOmniFlowModelClient.streamTurn(
            request = request,
            onReasoningUpdate = onReasoningUpdate,
        )
    }
