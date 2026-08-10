package cn.com.omnimind.bot.plugin

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

object OmniPluginProviderRegistry {
    private data class Registration(
        val id: String,
        val factory: (Context) -> OmniPluginProvider
    )

    private val registrations = CopyOnWriteArrayList<Registration>()
    private data class SourceRegistration(
        val id: String,
        val source: (Context) -> List<OmniPluginProvider>
    )
    private val sources = CopyOnWriteArrayList<SourceRegistration>()

    fun register(id: String, factory: (Context) -> OmniPluginProvider) {
        require(id.isNotBlank()) { "Plugin provider registration id cannot be blank" }
        require(registrations.none { it.id == id }) {
            "Plugin provider is already registered: $id"
        }
        registrations += Registration(id = id, factory = factory)
    }

    fun registerSource(id: String, source: (Context) -> List<OmniPluginProvider>) {
        require(id.isNotBlank()) { "Plugin provider source id cannot be blank" }
        require(sources.none { it.id == id }) {
            "Plugin provider source is already registered: $id"
        }
        sources += SourceRegistration(id = id, source = source)
    }

    internal fun createProviders(context: Context): List<OmniPluginProvider> {
        return registrations.map { registration -> registration.factory(context) } +
            sources.flatMap { registration -> registration.source(context) }
    }
}
