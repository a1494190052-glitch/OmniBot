package cn.com.omnimind.bot.llm

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDebugModelConfigContractTest {
    @Test
    fun `debug build seeds its bundled provider on app startup`() {
        val root = findProjectRoot()
        val buildSource = root.resolve("app/build.gradle.kts").readText()
        val appSource = root.resolve("app/src/main/java/cn/com/omnimind/bot/App.kt").readText()
        val installer = root.resolve(
            "app/src/main/java/cn/com/omnimind/bot/llm/BundledDebugModelConfigInstaller.kt",
        )

        assertTrue("Bundled debug provider installer is missing", installer.isFile)
        assertTrue(
            "App startup does not install the bundled debug provider",
            appSource.contains("BundledDebugModelConfigInstaller.installIfNeeded()"),
        )
        listOf(
            "BUNDLED_LLM_BASE_URL",
            "BUNDLED_LLM_API_KEY",
            "BUNDLED_AGENT_MODEL",
            "BUNDLED_VLM_MODEL",
            "BUNDLED_LLM_PROFILE_NAME",
        ).forEach { field ->
            assertTrue("Debug BuildConfig field $field is missing", buildSource.contains(field))
        }
    }

    @Test
    fun `stale acceptance mock provider is repairable`() {
        val profile = ModelProviderProfile(
            id = "debug-runtime-provider",
            name = "Provider",
            baseUrl = "http://127.0.0.1:53520",
            apiKey = "mock",
        )
        val binding = SceneModelBindingEntry(
            sceneId = "scene.vlm.operation.primary",
            providerProfileId = profile.id,
            modelId = "oob-acceptance-mock-vlm",
        )

        assertTrue(
            BundledDebugModelConfigInstaller.isAcceptanceMockContamination(
                profile,
                listOf(binding),
            ),
        )
        assertFalse(
            BundledDebugModelConfigInstaller.isAcceptanceMockContamination(
                profile.copy(baseUrl = "https://llmapi.paratera.com"),
                listOf(binding),
            ),
        )
    }

    private fun findProjectRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            if (current.resolve("settings.gradle.kts").isFile) return current
            current = current.parentFile
                ?: error("Could not locate project root from ${System.getProperty("user.dir")}")
        }
    }
}
