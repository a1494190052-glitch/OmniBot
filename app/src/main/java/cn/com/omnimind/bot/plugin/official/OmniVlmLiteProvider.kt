package cn.com.omnimind.bot.plugin.official

import android.content.Context
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

    override val descriptor = OmniPluginDescriptor(
        id = ID,
        name = "Omni VLM Lite",
        version = VERSION,
        description = "内置的 Android GUI 视觉操作能力。安装只登记并启用工具，不下载插件包或 Python 依赖。",
        publisher = "OmniMind",
        kind = OmniPluginKind.BUNDLED_MODULE,
        downloadSizeBytes = 0L,
        capabilities = listOf(
            "Android GUI observation",
            "Android GUI actions",
            "VLM planning",
            "Canonical RunLog",
        ),
        settingsSchema = JsonObject(emptyMap()),
    )

    override fun create(): OmniPlugin {
        OmniVlmPlugin.install(enabled = false)
        return object : OmniPlugin {
            override fun contribution(): OmniPluginContribution =
                OmniPluginContribution(
                    toolGroups = listOf(
                        OmniPluginToolGroup(
                            definitions = listOf(toolDefinition()),
                            handlerFactory = { OmniVlmToolHandler(appContext) },
                        )
                    )
                )

            override suspend fun onEnable() {
                OmniVlmPlugin.setEnabled(true)
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
        const val VERSION = "1.0.0"
        const val TOOL_NAME = "vlm_task"
    }
}
