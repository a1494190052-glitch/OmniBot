package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import com.ai.assistance.operit.terminal.TerminalManager
import java.io.File
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
              python3 -m pip --version >/dev/null 2>&1 &&
              test -e /usr/lib/libstdc++.so.6
            }
            if ! python_ready; then
              apk update >/dev/null &&
              apk add --no-cache python3 py3-pip libstdc++ ca-certificates >/dev/null
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

    override suspend fun resolveRuntimeSkill(
        context: Context,
        refresh: Boolean,
    ): OmniFlowSkillLocation {
        val appContext = context.applicationContext
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val existing = skills.listSkillsForManagement()
            .firstOrNull { it.id == OmniFlowRuntimeProvider.SKILL_ID && it.installed }
        if (refresh || existing == null) {
            runCatching { skills.syncOfficialSkillsRepository() }
                .getOrElse { error ->
                    if (existing == null) throw error
                }
        }
        val entry = skills.listSkillsForManagement()
            .firstOrNull { it.id == OmniFlowRuntimeProvider.SKILL_ID && it.installed }
            ?: error("omniflow_runtime_skill_not_found")
        if (!entry.enabled) {
            skills.setSkillEnabled(entry.id, true)
        }
        return OmniFlowSkillLocation(
            androidRoot = File(entry.rootPath).canonicalFile,
            shellRoot = entry.shellRootPath,
            source = entry.source,
        )
    }

    override suspend fun bootstrapRuntimeSkill(
        context: Context,
        location: OmniFlowSkillLocation,
    ) {
        val skillRoot = TermuxCommandBuilder.quoteForShell(location.shellRoot)
        val command = """
            set -eu
            SKILL_ROOT=$skillRoot
            python3 "${'$'}SKILL_ROOT/scripts/bootstrap_runtime.py" --skill-root "${'$'}SKILL_ROOT"
        """.trimIndent()
        val result = TerminalManager.getInstance(context.applicationContext).executeHiddenCommand(
            command = command,
            executorKey = "omniflow-skill-bootstrap",
            timeoutMs = 15 * 60_000L,
        )
        require(result.isOk && result.exitCode == 0) {
            result.error.takeIf(String::isNotBlank)
                ?: result.output.takeLast(1_200).trim()
                    .ifBlank { "omniflow_skill_bootstrap_failed" }
        }
    }

    override suspend fun reclaimRuntimeSkill(context: Context) {
        val appContext = context.applicationContext
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val entry = skills.listSkillsForManagement()
            .firstOrNull { it.id == OmniFlowRuntimeProvider.SKILL_ID && it.installed }
            ?: return
        if (entry.enabled) {
            skills.setSkillEnabled(entry.id, false)
        }
        val runtime = File(entry.rootPath, "scripts/runtime/.runtime")
        require(!runtime.exists() || runtime.deleteRecursively()) {
            "omniflow_skill_runtime_reclaim_failed"
        }
    }

    override suspend fun completeJson(request: ChatCompletionRequest): String {
        val response = HttpController.postSceneChatCompletion(request)
        check(response.success) { response.message.ifBlank { "model_completion_failed" } }
        return response.content.ifBlank { response.message }
    }
}
