package cn.com.omnimind.bot.agent.config

import android.content.Context

object AgentToolFeatureStore {
    private const val PREFS_NAME = "agent_tool_features"
    private const val KEY_FUNCTION_RECALL = "oob_function_as_tool_enabled"
    private const val KEY_FUNCTION_RECALL_USER_SET = "oob_function_as_tool_user_set"
    private const val DEFAULT_FUNCTION_RECALL_ENABLED = false

    fun isFunctionRecallEnabled(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!isFunctionRecallUserSet(context)) {
            return DEFAULT_FUNCTION_RECALL_ENABLED
        }
        return prefs.getBoolean(KEY_FUNCTION_RECALL, DEFAULT_FUNCTION_RECALL_ENABLED)
    }

    fun isFunctionRecallUserSet(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FUNCTION_RECALL_USER_SET, false)
    }

    fun setFunctionRecallEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FUNCTION_RECALL, enabled)
            .putBoolean(KEY_FUNCTION_RECALL_USER_SET, true)
            .apply()
    }

    fun clearFunctionRecallEnabled(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_FUNCTION_RECALL)
            .remove(KEY_FUNCTION_RECALL_USER_SET)
            .apply()
    }

    fun getFeatures(context: Context): Map<String, Any?> = linkedMapOf(
        "functionRecallEnabled" to isFunctionRecallEnabled(context),
        "functionRecallDefaultEnabled" to DEFAULT_FUNCTION_RECALL_ENABLED,
        "functionRecallUserSet" to isFunctionRecallUserSet(context),
    )
}
