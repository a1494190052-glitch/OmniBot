package cn.com.omnimind.bot.omniflow

import android.content.Context

object OmniFlowPluginRuntime {
    private val shared = OmniFlowPluginRuntimeController(DefaultOmniFlowPluginBackend)

    fun install(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider(),
    ) = shared.install(platform, runtimeProvider)

    fun enable(context: Context) = shared.enable(context)

    suspend fun disable() = shared.disable()

    suspend fun uninstall() = shared.uninstall()

    fun isEnabled(): Boolean = shared.isEnabled()
}

internal class OmniFlowPluginRuntimeController(
    private val backend: OmniFlowPluginBackend,
) {
    @Volatile
    private var installed = false

    @Volatile
    private var enabled = false

    fun install(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    ) {
        backend.configure(platform, runtimeProvider)
        installed = true
        enabled = false
    }

    fun enable(context: Context) {
        check(installed) { "omniflow_plugin_not_installed" }
        if (enabled) return
        enabled = true
        backend.warmup(context)
    }

    suspend fun disable() {
        if (!installed) return
        enabled = false
        backend.shutdown()
    }

    suspend fun uninstall() {
        disable()
        installed = false
    }

    fun isEnabled(): Boolean = installed && enabled
}

internal interface OmniFlowPluginBackend {
    fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    )

    fun warmup(context: Context)

    suspend fun shutdown()
}

private object DefaultOmniFlowPluginBackend : OmniFlowPluginBackend {
    override fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    ) = OmniFlow.configure(platform, runtimeProvider)

    override fun warmup(context: Context) = OmniFlow.warmup(context)

    override suspend fun shutdown() = OmniFlow.shutdown()
}
