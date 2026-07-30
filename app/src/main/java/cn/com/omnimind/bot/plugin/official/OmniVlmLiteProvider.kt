package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.bot.omniflow.OmniFlowAppPlatform
import cn.com.omnimind.bot.omniflow.OmniFlowRuntimeProvider
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.plugin.OmniPlugin
import cn.com.omnimind.bot.plugin.OmniPluginContribution
import cn.com.omnimind.bot.plugin.OmniPluginDescriptor
import cn.com.omnimind.bot.plugin.OmniPluginKind
import cn.com.omnimind.bot.plugin.OmniPluginProvider
import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import cn.com.omnimind.bot.plugin.OmniPluginToolGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OmniVlmLiteProvider(context: Context) : OmniPluginProvider {
    private val appContext = context.applicationContext
    private val runtimeProvider = OmniFlowRuntimeProvider()

    override val descriptor = OmniPluginDescriptor(
        id = ID,
        name = "Omni VLM Lite",
        version = VERSION,
        description = "可重放的 Android GUI 视觉操作能力。安装时从官方 Skills 仓库准备隔离运行时，APK 不包含 Python、NumPy 或模型文件。",
        publisher = "OmniMind",
        kind = OmniPluginKind.RUNTIME_BUNDLE,
        downloadSizeBytes = DOWNLOAD_SIZE_BYTES,
        capabilities = listOf(
            "Android GUI observation",
            "Android GUI actions",
            "VLM planning",
            "Canonical RunLog",
            "Function replay with OmniTransfer",
        ),
        settingsSchema = JsonObject(emptyMap()),
    )

    override suspend fun install() {
        OmniVlmPlugin.install(
            platform = OmniFlowAppPlatform,
            enabled = false,
            runtimeProvider = runtimeProvider,
        )
        runtimeProvider.install(appContext, OmniFlowAppPlatform)
    }

    override suspend fun uninstall() {
        OmniVlmPlugin.uninstall()
        runtimeProvider.reclaim(appContext, OmniFlowAppPlatform)
    }

    override suspend fun update() {
        OmniVlmPlugin.uninstall()
        try {
            runtimeProvider.update(appContext, OmniFlowAppPlatform)
        } finally {
            OmniVlmPlugin.install(
                platform = OmniFlowAppPlatform,
                enabled = false,
                runtimeProvider = runtimeProvider,
            )
        }
    }

    override fun create(): OmniPlugin {
        OmniVlmPlugin.install(
            platform = OmniFlowAppPlatform,
            enabled = false,
            runtimeProvider = runtimeProvider,
        )
        return object : OmniPlugin {
            override fun contribution(): OmniPluginContribution =
                OmniPluginContribution(
                    toolGroups = listOf(
                        OmniPluginToolGroup(
                            definitions = listOf(toolDefinition()),
                            handlerFactory = { OmniVlmToolHandler(appContext) },
                        ),
                        OmniPluginToolGroup(
                            definitions = OmniFlowManagementTools.definitions(),
                            handlerFactory = { OmniFlowManagementToolHandler(appContext) },
                        ),
                    )
                )

            override suspend fun onEnable() {
                OmniVlmPlugin.setEnabled(true)
                OmniVlmPlugin.warmup(appContext)
            }

            override suspend fun onDisable() {
                OmniVlmPlugin.setEnabled(false)
            }
        }
    }

    private fun toolDefinition(): OmniPluginToolDefinition =
        OmniPluginToolDefinition(
            name = TOOL_NAME,
            displayName = "VLM GUI",
            description =
                "Use vision to operate the current Android UI. The plugin observes the screen, " +
                    "plans actions, executes them, and records a canonical RunLog.",
            parameters = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { add(JsonPrimitive("goal")) })
                put("properties", buildJsonObject {
                    put("goal", buildJsonObject {
                        put("type", "string")
                        put("description", "The concrete goal to complete in the Android UI.")
                    })
                })
                put("additionalProperties", false)
            },
        )

    companion object {
        const val ID = "com.omnimind.omni-vlm-lite"
        const val VERSION = "2.0.0"
        const val TOOL_NAME = "vlm_task"
        private const val DOWNLOAD_SIZE_BYTES = 18_500_000L
    }
}
