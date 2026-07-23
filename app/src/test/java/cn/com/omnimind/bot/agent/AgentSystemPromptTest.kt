package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    fun buildDisablesAutomaticCrossContextMemoryForIsolatedRuns() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1-segment-task-1",
                rootPath = "/workspace/.omnibot/contexts/task-1",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/contexts/task-1",
                uriRoot = "omnibot://workspace/.omnibot/contexts/task-1",
                currentCwd = "/workspace/.omnibot/contexts/task-1",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/contexts/task-1",
                shellRootPath = "/workspace",
                retentionPolicy = "segment_scoped"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = WorkspaceMemoryPromptContext(
                soul = "Always write memory automatically.",
                longTermMemory = "another task",
                todayShortMemory = "another task today"
            ),
            automaticMemoryEnabled = false,
            locale = PromptLocale.ZH_CN
        )

        assertTrue(prompt.contains("当前是隔离的新运行上下文"))
        assertTrue(prompt.contains("只有用户在当前请求中明确要求"))
        assertTrue(prompt.contains("Workspace 持久记忆未注入"))
        assertTrue(prompt.contains("当前运行上下文的独立工作目录"))
        assertTrue(prompt.contains("不要读取或写入 `.omnibot/contexts` 下其它运行上下文"))
        assertTrue(prompt.contains("runtimeContextId"))
        assertTrue(!prompt.contains("another task today"))
    }
}
