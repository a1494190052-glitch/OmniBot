package cn.com.omnimind.bot.agent

/**
 * Canonical in-app agent tool names shared by tool definitions, handlers, MCP
 * adapters, and RunLog classification.
 *
 * Function and RunLog lifecycle tool names live in
 * [cn.com.omnimind.bot.function.FunctionApi]. Internal
 * canonical actions such as `call_tool` live in
 * [cn.com.omnimind.baselib.runlog.OobActionSchema].
 */
object AgentToolNames {
    const val VLM_TASK = "vlm_task"
    const val WEB_SEARCH = "web_search"
    const val BROWSER_USE = "browser_use"
    const val ANDROID_PRIVILEGED_ACTION = "android_privileged_action"
}
