package cn.com.omnimind.bot.plugin

import android.content.Context

internal class SharedPreferencesOmniPluginStateStore(context: Context) : OmniPluginStateStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): List<OmniPluginStoredState> {
        return preferences.getStringSet(STATES_KEY, emptySet()).orEmpty()
            .mapNotNull(::decode)
            .sortedBy { it.pluginId }
    }

    override fun readWithDefaults(
        defaults: List<OmniPluginStoredState>
    ): List<OmniPluginStoredState> {
        if (preferences.getBoolean(DEFAULTS_SEEDED_KEY, false)) {
            return read()
        }
        val current = read()
        val currentIds = current.mapTo(mutableSetOf()) { it.pluginId }
        val seeded = (current + defaults.filter { currentIds.add(it.pluginId) })
            .sortedBy { it.pluginId }
        val encoded = seeded.mapTo(linkedSetOf(), ::encode)
        check(
            preferences.edit()
                .putStringSet(STATES_KEY, encoded)
                .putBoolean(DEFAULTS_SEEDED_KEY, true)
                .commit()
        ) {
            "Failed to seed default plugin state"
        }
        return seeded
    }

    override fun write(states: List<OmniPluginStoredState>) {
        val encoded = states.mapTo(linkedSetOf(), ::encode)
        check(preferences.edit().putStringSet(STATES_KEY, encoded).commit()) {
            "Failed to persist plugin state"
        }
    }

    private fun encode(state: OmniPluginStoredState): String {
        return "${state.pluginId}$SEPARATOR${if (state.enabled) ENABLED else DISABLED}"
    }

    private fun decode(value: String): OmniPluginStoredState? {
        val separatorIndex = value.lastIndexOf(SEPARATOR)
        if (separatorIndex <= 0) return null
        val pluginId = value.substring(0, separatorIndex)
        val enabled = when (value.substring(separatorIndex + SEPARATOR.length)) {
            ENABLED -> true
            DISABLED -> false
            else -> return null
        }
        return OmniPluginStoredState(pluginId = pluginId, enabled = enabled)
    }

    private companion object {
        const val PREFERENCES_NAME = "omni_plugin_platform"
        const val STATES_KEY = "installed_plugins"
        const val DEFAULTS_SEEDED_KEY = "default_plugins_seeded_v2"
        const val SEPARATOR = "|"
        const val ENABLED = "1"
        const val DISABLED = "0"
    }
}
