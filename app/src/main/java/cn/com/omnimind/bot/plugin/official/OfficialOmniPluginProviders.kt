package cn.com.omnimind.bot.plugin.official

import cn.com.omnimind.bot.plugin.OmniPluginProviderRegistry
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleAdapterRegistry
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleCatalog
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginPool
import cn.com.omnimind.bot.plugin.sandbox.SandboxRuntimeBundleAdapter
import java.util.concurrent.atomic.AtomicBoolean

object OfficialOmniPluginProviders {
    private val registered = AtomicBoolean(false)

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        OmniPluginProviderRegistry.register(OmniVlmLiteProvider.ID) { context ->
            OmniVlmLiteProvider(context)
        }
        RuntimeBundleAdapterRegistry.register(SandboxRuntimeBundleAdapter.ADAPTER_ID) { context, definition ->
            SandboxRuntimeBundleAdapter(context, definition)
        }
        OmniPluginProviderRegistry.registerSource(RUNTIME_BUNDLE_SOURCE) { context ->
            RuntimeBundleAdapterRegistry.createProviders(
                context = context,
                catalog = RuntimeBundleCatalog.load(context.assets, profile = "main"),
            )
        }
        OmniPluginProviderRegistry.registerSource(SANDBOX_USER_POOL_SOURCE) { context ->
            SandboxPluginPool(context).createProviders()
        }
    }

    private const val RUNTIME_BUNDLE_SOURCE = "official-runtime-bundles"
    private const val SANDBOX_USER_POOL_SOURCE = "sandbox-user-plugin-pool"
}
