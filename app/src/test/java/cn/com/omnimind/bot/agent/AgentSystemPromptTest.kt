package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSystemPromptTest {
    @Test
    fun buildMentionsWorkspaceVenvInsteadOfBreakingSystemPackages() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains(".venv"))
        assertTrue(prompt.contains("uv"))
        assertTrue(prompt.contains("--copies"))
        assertTrue(prompt.contains("--break-system-packages"))
        assertTrue(prompt.contains("shell.exec"))
        assertTrue(prompt.contains("android_privileged_session_*"))
    }

    @Test
    fun buildCachedSystemPromptContentAddsEphemeralCacheControl() {
        val content = OmniAgentExecutor.buildCachedSystemPromptContent("system prompt")
        val blocks = content as JsonArray
        val firstBlock = blocks.first() as JsonObject

        assertEquals("\"text\"", firstBlock["type"].toString())
        assertEquals("\"system prompt\"", firstBlock["text"].toString())
        assertEquals(
            "\"ephemeral\"",
            (firstBlock["cache_control"] as JsonObject)["type"].toString()
        )
    }

    @Test
    fun buildUsesEnglishPromptWhenLocaleIsEnglish() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.EN_US
        )

        assertTrue(prompt.contains("You are an AI Agent operating inside an Alpine workspace environment"))
        assertTrue(prompt.contains("File and artifact rules"))
        assertTrue(prompt.contains("Skills:"))
        assertTrue(prompt.contains("action=shell.exec"))
        assertTrue(prompt.contains("android_privileged_session_*"))
    }

    @Test
    fun buildOmitsEmptyOmniFlowCandidatePlaceholderFromDefaultPrompt() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.EN_US
        )

        assertFalse(prompt.contains("No OmniFlow Function candidates matched this turn"))
        assertFalse(prompt.contains("本轮没有命中的 OmniFlow Function 候选"))
    }

    @Test
    fun buildIncludesOmniFlowCandidateContextWhenProvided() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            oobFunctionCandidateContext = "OmniFlow runtime recall is available through vlm_task.",
            locale = PromptLocale.EN_US
        )

        assertTrue(prompt.contains("OmniFlow runtime recall is available through vlm_task."))
    }

    @Test
    fun installedSkillIndexShowsOmniFlowAsSingleRuntimeEntry() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "omniflow",
                    name = "omniflow",
                    description = "Single OOB phone automation and reusable command skill.",
                    rootPath = "/workspace/.omnibot/skills/omniflow",
                    shellRootPath = "/workspace/.omnibot/skills/omniflow",
                    skillFilePath = "/workspace/.omnibot/skills/omniflow/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/omniflow/SKILL.md",
                    hasScripts = false,
                    hasReferences = true,
                    hasAssets = false,
                    hasEvals = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("用 vlm_task 操作手机界面"))
        assertTrue(prompt.contains("管理或执行复用指令"))
        assertTrue(prompt.contains("用 update_function 修复 checker"))
        assertTrue(prompt.contains("涉及手机界面自动化、VLM 执行、RunLog、复用指令、update_function 或 checker 时。"))
    }

    @Test
    fun defaultAgentExecutorOnlyPrefetchesOmniFlowCandidatesForLightweightProfile() {
        val source = File(
            "src/main/java/cn/com/omnimind/bot/agent/runtime/OmniAgentExecutor.kt"
        ).readText()

        assertTrue(source.contains("if (isLightweightToolProfile)"))
        assertTrue(source.contains("OmniFlowFunctionApi.promptCandidateContext"))
        assertTrue(source.contains("currentPackageName = currentPackageName"))
        assertTrue(source.contains("} else {\n                null\n            }"))
    }
}
