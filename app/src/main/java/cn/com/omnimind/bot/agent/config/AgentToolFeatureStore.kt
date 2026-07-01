package cn.com.omnimind.bot.agent.config

import android.content.Context

object AgentToolFeatureStore {
    private const val PREFS_NAME = "agent_tool_features"
    private const val KEY_OMNIFLOW_FUNCTION_RECALL = "oob_function_as_tool_enabled"
    private const val KEY_OMNIFLOW_FUNCTION_RECALL_USER_SET = "oob_function_as_tool_user_set"
    private const val DEFAULT_OMNIFLOW_FUNCTION_RECALL_ENABLED = false

    fun isOmniFlowFunctionRecallEnabled(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!isOmniFlowFunctionRecallUserSet(context)) {
            return DEFAULT_OMNIFLOW_FUNCTION_RECALL_ENABLED
        }
        return prefs.getBoolean(KEY_OMNIFLOW_FUNCTION_RECALL, DEFAULT_OMNIFLOW_FUNCTION_RECALL_ENABLED)
    }

    fun isOmniFlowFunctionRecallUserSet(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OMNIFLOW_FUNCTION_RECALL_USER_SET, false)
    }

    fun setOmniFlowFunctionRecallEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OMNIFLOW_FUNCTION_RECALL, enabled)
            .putBoolean(KEY_OMNIFLOW_FUNCTION_RECALL_USER_SET, true)
            .apply()
    }

    fun clearOmniFlowFunctionRecallEnabled(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_OMNIFLOW_FUNCTION_RECALL)
            .remove(KEY_OMNIFLOW_FUNCTION_RECALL_USER_SET)
            .apply()
    }

    fun getFeatures(context: Context): Map<String, Any?> = linkedMapOf(
        "oobFunctionAsToolEnabled" to isOmniFlowFunctionRecallEnabled(context),
        "oobFunctionAsToolDefaultEnabled" to DEFAULT_OMNIFLOW_FUNCTION_RECALL_ENABLED,
        "oobFunctionAsToolUserSet" to isOmniFlowFunctionRecallUserSet(context),
    )
}
