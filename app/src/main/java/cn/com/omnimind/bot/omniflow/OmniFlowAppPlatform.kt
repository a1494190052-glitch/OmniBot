package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.content.res.AssetManager
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
        if (existing == null || (refresh && !isPackagedRuntimeSkill(existing.rootPath))) {
            runCatching { skills.syncOfficialSkillsRepository() }
                .getOrElse { error ->
                    if (existing == null) throw error
                }
        }
        var entry = skills.listSkillsForManagement()
            .firstOrNull { it.id == OmniFlowRuntimeProvider.SKILL_ID && it.installed }
        if (entry == null || (refresh && isPackagedRuntimeSkill(entry.rootPath))) {
            entry = installPackagedRuntimeSkill(appContext, skills)
        }
        val resolvedEntry = requireNotNull(entry) { "omniflow_runtime_skill_not_found" }
        if (!resolvedEntry.enabled) {
            skills.setSkillEnabled(resolvedEntry.id, true)
        }
        return OmniFlowSkillLocation(
            androidRoot = File(resolvedEntry.rootPath).canonicalFile,
            shellRoot = resolvedEntry.shellRootPath,
            source = resolvedEntry.source,
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
        if (isPackagedRuntimeSkill(entry.rootPath)) {
            require(skills.deleteSkill(entry.id)) { "omniflow_skill_delete_failed" }
            return
        }
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

    private fun installPackagedRuntimeSkill(
        context: Context,
        skills: SkillIndexService,
    ) = File(context.cacheDir, "omniflow-runtime-skill-${UUID.randomUUID()}").let { temporary ->
        val skillSource = File(temporary, OmniFlowRuntimeProvider.SKILL_ID)
        try {
            copyAssetTree(context.assets, PACKAGED_RUNTIME_SKILL_ASSET, skillSource)
            copyAssetTree(context.assets, PACKAGED_SCHEMA_ASSET, File(skillSource, "schemas"))
            skills.installSkillFromDirectory(skillSource.absolutePath)
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use(input::copyTo)
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetTree(assets, "$assetPath/$child", File(target, child))
        }
    }

    private fun isPackagedRuntimeSkill(rootPath: String): Boolean =
        File(rootPath, PACKAGED_RUNTIME_SKILL_MARKER).isFile

    private const val PACKAGED_RUNTIME_SKILL_ASSET =
        "runtime-skill/omniflow-gui-runtime"
    private const val PACKAGED_SCHEMA_ASSET = "schemas"
    private const val PACKAGED_RUNTIME_SKILL_MARKER = "PACKAGED_RUNTIME_SKILL"
}
