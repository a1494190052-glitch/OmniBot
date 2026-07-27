package cn.com.omnimind.bot.omniflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowExecutionOverlayContractTest {
    @Test
    fun `VLM and Function replay use only the OmniFlow execution interface`() {
        val root = projectRoot()
        val vlmSource = root.resolve(
            "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/GuiTaskToolHandler.kt",
        ).readText()
        val replaySource = root.resolve(
            "app/src/main/java/cn/com/omnimind/bot/function/FunctionRun.kt",
        ).readText()
        val replayFrontendSource = root.resolve(
            "ui/lib/features/task/run_log/run_log_function_service.dart",
        ).readText()
        val omniFlowSource = root.resolve(
            "omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlow.kt",
        ).readText()
        val debugReplaySource = root.resolve(
            "app/src/debug/java/cn/com/omnimind/bot/debug/DebugRunLogFunctionReplayReceiver.kt",
        ).readText()
        val channelSource = root.resolve(
            "app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt",
        ).readText()
        val managerSource = root.resolve(
            "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt",
        ).readText()

        assertTrue(vlmSource.contains("OmniFlow.run("))
        assertTrue(replaySource.contains("OmniFlow.run("))
        assertTrue(omniFlowSource.contains("ExecutionControls"))
        assertTrue(omniFlowSource.contains("\"act\" -> act(payload)"))
        assertTrue(omniFlowSource.contains("environment.act(action)"))
        forbiddenExecutionTypes.forEach { forbidden ->
            assertFalse(vlmSource.contains(forbidden))
            assertFalse(replaySource.contains(forbidden))
        }
        assertFalse(debugReplaySource.contains("frontend_parent"))
        assertFalse(replaySource.contains("frontend_run_id"))
        assertFalse(replaySource.contains("frontend_task_id"))
        assertFalse(replayFrontendSource.contains("frontend_task_id"))
        assertFalse(omniFlowSource.contains("controlRunId"))
        assertFalse(omniFlowSource.contains("controlTaskId"))
        assertTrue(channelSource.contains("FunctionService.isChannelMethod"))
        assertTrue(channelSource.contains("handleChannelMethod(call, result)"))
        assertFalse(managerSource.contains("FunctionRun("))
        sharedModuleFiles.forEach { path -> assertTrue(root.resolve(path).isFile) }
        removedLegacyFiles.forEach { path -> assertFalse(root.resolve(path).exists()) }
    }

    @Test
    fun `OmniFlow module owns every portable VLM runtime component`() {
        val root = projectRoot()
        val moduleRoot = root.resolve("omniflow-android")
        val moduleBuild = moduleRoot.resolve("build.gradle.kts").readText()
        val moduleSources = moduleRoot.resolve("src/main").walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        val appAdapterDirectory = root.resolve(
            "app/src/main/java/cn/com/omnimind/bot/omniflow",
        )
        val adapterFiles = appAdapterDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .map(File::getName)
            .sorted()

        assertTrue(moduleBuild.contains("val prepareOmniFlowRuntime"))
        assertTrue(moduleBuild.contains("assets.srcDir(omniFlowRuntimeAssetsRootDir)"))
        assertFalse(moduleBuild.contains("project(\":assists\")"))
        assertFalse(moduleSources.contains("cn.com.omnimind.assists"))
        assertFalse(moduleSources.contains("com.ai.assistance"))
        assertEquals(listOf("OmniFlowAppPlatform.kt"), adapterFiles)
        val adapterSource = appAdapterDirectory.resolve("OmniFlowAppPlatform.kt").readText()
        assertTrue(adapterSource.contains("OmniFlowPlatform"))
        assertTrue(adapterSource.contains("asOmniFlowModelClient"))
        assertTrue(legacyVlmDirectories.all { path ->
            root.resolve(path).walkTopDown().none(File::isFile)
        })
    }

    private val forbiddenExecutionTypes = listOf(
        "AndroidGuiEnvironment",
        "DraggableBallInstance",
        "FunctionFrontendSessionController",
        "FunctionUiSession",
        "GuiTaskControlOverlay",
        "OmniFlowAndroidExtension",
        "OmniFlowExecutionUiSession",
        "OmniFlowPythonRuntime",
    )

    private val sharedModuleFiles = listOf(
        "omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/ui/ExecutionOverlay.kt",
        "omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/ui/ExecutionControls.kt",
    )

    private val removedLegacyFiles = listOf(
        "app/src/main/java/cn/com/omnimind/bot/function/FunctionFrontendSessionController.kt",
        "assists/src/main/java/cn/com/omnimind/assists/FunctionUiSession.kt",
        "omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlowAndroidExtension.kt",
        "omniflow-ui/src/main/java/cn/com/omnimind/bot/omniflow/ui/FunctionUiSession.kt",
        "omniflow-ui/src/main/java/cn/com/omnimind/bot/omniflow/ui/OmniFlowExecutionUiSession.kt",
        "omniflow-ui/src/main/java/cn/com/omnimind/bot/omniflow/ui/ExecutionControls.kt",
        "omniflow-ui/src/main/java/cn/com/omnimind/bot/omniflow/ui/GuiTaskControlOverlay.kt",
        "omniflow-android/src/main/java/cn/com/omnimind/bot/omniflow/ExecutionOverlay.kt",
        "uikit/src/main/java/cn/com/omnimind/uikit/loader/GuiTaskControlOverlay.kt",
    )

    private val legacyVlmDirectories = listOf(
        "app/src/main/java/cn/com/omnimind/bot/vlm",
        "assists/src/main/java/cn/com/omnimind/assists/task/vlmserver",
    )

    private fun projectRoot(): File {
        var current = File(
            System.getProperty("user.dir") ?: error("user.dir system property is not set"),
        ).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current
    }
}
