package cn.com.omnimind.bot.plugin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmPluginBoundaryTest {
    @Test
    fun `investor build defaults GUI plugin through plugin host`() {
        val provider = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/plugin/official/OmniVlmLiteProvider.kt",
        )
        val catalog = projectSource("plugins/catalog.v1.json")
        val vlmHandler = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/VlmToolHandler.kt",
        )
        val host = projectSource(
            "app/src/main/java/cn/com/omnimind/bot/plugin/OmniPluginHost.kt",
        )
        val appBuild = projectSource("app/build.gradle.kts")

        assertTrue(provider.contains("RuntimeBundleAdapter"))
        assertTrue(provider.contains("runtimeProvider.install(appContext, platform)"))
        assertFalse(provider.contains("RuntimeBundlePrepareMode.INSTALL -> Unit"))
        assertTrue(provider.contains("OmniFlowPluginRuntime.enable(appContext)"))
        assertFalse(provider.contains("finally"))
        assertFalse(provider.contains("vlm_task"))
        assertFalse(provider.contains("VlmToolHandler"))
        assertTrue(vlmHandler.contains("OmniVlmPlugin.execute"))
        assertFalse(vlmHandler.contains("OmniFlowRuntimeProvider"))
        assertTrue(catalog.contains("\"name\": \"OmniFlow\""))
        assertFalse(catalog.contains("\"name\": \"Android GUI\""))
        assertTrue(catalog.contains("投资人 Debug 包已默认安装并启用"))
        assertTrue(host.contains("BuildConfig.DEFAULT_INSTALL_GUI_PLUGIN"))
        assertTrue(host.contains("setOf(OmniVlmLiteProvider.ID)"))
        assertTrue(
            appBuild.contains(
                "buildConfigField(\"boolean\", \"DEFAULT_INSTALL_GUI_PLUGIN\", \"false\")"
            )
        )
        assertTrue(
            appBuild.contains(
                "buildConfigField(\"boolean\", \"DEFAULT_INSTALL_GUI_PLUGIN\", \"true\")"
            )
        )
    }

    private fun projectSource(path: String): String {
        var current = File(System.getProperty("user.dir")).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Could not locate project root")
        }
        return current.resolve(path).readText()
    }
}
