package cn.com.omnimind.bot.plugin.official

import cn.com.omnimind.bot.plugin.OmniPluginProviderRegistry
import java.util.concurrent.atomic.AtomicBoolean

object OfficialOmniPluginProviders {
    private val registered = AtomicBoolean(false)

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        OmniPluginProviderRegistry.register(OmniVlmLiteProvider.ID) { context ->
            OmniVlmLiteProvider(context)
        }
    }
}
