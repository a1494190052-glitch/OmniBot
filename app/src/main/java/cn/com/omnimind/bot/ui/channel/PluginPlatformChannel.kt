package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.OmniPluginState
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

class PluginPlatformChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var channel: MethodChannel? = null

    fun onCreate(context: Context) {
        this.context = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(::handleMethodCall)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val safeContext = context
        if (safeContext == null) {
            result.error("PLUGIN_CONTEXT_ERROR", "Plugin platform is not initialized", null)
            return
        }
        val host = OmniPluginHost.get(safeContext)
        scope.launch {
            runCatching {
                when (call.method) {
                    "list" -> host.list().map(::stateToMap)
                    "install" -> stateToMap(host.install(call.requirePluginId()))
                    "setEnabled" -> stateToMap(
                        host.setEnabled(
                            pluginId = call.requirePluginId(),
                            enabled = call.argument<Boolean>("enabled")
                                ?: throw IllegalArgumentException("enabled is required")
                        )
                    )
                    "uninstall" -> {
                        host.uninstall(call.requirePluginId())
                        true
                    }
                    else -> throw NotImplementedError(call.method)
                }
            }.onSuccess(result::success).onFailure { error ->
                if (error is NotImplementedError) {
                    result.notImplemented()
                } else {
                    result.error(
                        "PLUGIN_PLATFORM_CALL_FAILED",
                        error.message ?: error.javaClass.simpleName,
                        null
                    )
                }
            }
        }
    }

    private fun MethodCall.requirePluginId(): String {
        return argument<String>("pluginId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("pluginId is required")
    }

    private fun stateToMap(state: OmniPluginState): Map<String, Any?> {
        val descriptor = state.descriptor
        return mapOf(
            "id" to descriptor.id,
            "name" to descriptor.name,
            "version" to descriptor.version,
            "interfaceVersion" to descriptor.interfaceVersion,
            "description" to descriptor.description,
            "publisher" to descriptor.publisher,
            "kind" to descriptor.kind.wireName,
            "downloadSizeBytes" to descriptor.downloadSizeBytes,
            "capabilities" to descriptor.capabilities,
            "settingsSchema" to descriptor.settingsSchema.toPlatformValue(),
            "installed" to state.installed,
            "enabled" to state.enabled,
            "compatible" to state.compatible,
            "errorMessage" to state.errorMessage
        )
    }

    private fun JsonElement.toPlatformValue(): Any? {
        return when (this) {
            JsonNull -> null
            is JsonObject -> entries.associate { (key, value) -> key to value.toPlatformValue() }
            is JsonArray -> map { it.toPlatformValue() }
            is JsonPrimitive -> when {
                isString -> content
                booleanOrNull != null -> booleanOrNull
                longOrNull != null -> longOrNull
                doubleOrNull != null -> doubleOrNull
                else -> content
            }
        }
    }

    private companion object {
        const val CHANNEL_NAME = "cn.com.omnimind.bot/PluginPlatform"
    }
}
