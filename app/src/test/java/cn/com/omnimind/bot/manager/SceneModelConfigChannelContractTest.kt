package cn.com.omnimind.bot.manager

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SceneModelConfigChannelContractTest {

    @Test
    fun `scene model config service methods are implemented by native channel`() {
        val root = findProjectRoot()
        val serviceSource = root.resolve("ui/lib/services/scene_model_config_service.dart")
            .readText()
        val channelSource = root
            .resolve("app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt")
            .readText()
        val managerSource = root
            .resolve("app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt")
            .readText()

        val sceneMethods = Regex("""invokeMethod(?:<[^(\n]+>)?\(\s*'([^']+)'""")
            .findAll(serviceSource)
            .map { it.groupValues[1] }
            .filter { method -> method.contains("Scene") }
            .toSet()

        assertTrue("Expected scene model config service to declare native methods", sceneMethods.isNotEmpty())

        sceneMethods.forEach { method ->
            assertTrue(
                "AssistsCoreChannel does not dispatch $method",
                channelSource.contains("\"$method\" ->")
            )
            assertTrue(
                "AssistsCoreManager does not implement $method",
                managerSource.contains("fun $method(")
            )
        }
    }

    private fun findProjectRoot(): File {
        val startDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        var current = File(startDir).absoluteFile
        while (true) {
            if (current.resolve("settings.gradle.kts").isFile) {
                return current
            }
            current = current.parentFile
                ?: error("Could not locate project root from ${System.getProperty("user.dir")}")
        }
    }
}
