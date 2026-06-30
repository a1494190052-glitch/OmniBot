package cn.com.omnimind.bot.omniflow

/**
 * Canonical in-app agent tool names for OmniFlow Function and RunLog lifecycle.
 *
 * Legacy external `omniflow.*` MCP names are adapter compatibility and stay in
 * the MCP layer. Replay-step tool taxonomy stays in RunLogReplayPolicy.
 */
object OobFunctionToolNames {
    const val FUNCTION_LIST = "oob_function_list"
    const val FUNCTION_GET = "oob_function_get"
    const val FUNCTION_REGISTER = "oob_function_register"
    const val FUNCTION_UPDATE = "update_function"
    const val FUNCTION_DELETE = "oob_function_delete"
    const val FUNCTION_CLEAR = "oob_function_clear"

    const val RUN_LOG_LIST = "oob_run_log_list"
    const val RUN_LOG_GET = "oob_run_log_get"
    const val RUN_LOG_CONVERT = "oob_run_log_convert"

    val functionLifecycleTools: Set<String> = setOf(
        FUNCTION_LIST,
        FUNCTION_GET,
        FUNCTION_REGISTER,
        FUNCTION_UPDATE,
        FUNCTION_DELETE,
        FUNCTION_CLEAR,
    )

    val runLogTools: Set<String> = setOf(
        RUN_LOG_LIST,
        RUN_LOG_GET,
        RUN_LOG_CONVERT,
    )

    val profileTools: Set<String> = functionLifecycleTools + runLogTools

    val mcpLifecycleTools: Set<String> = functionLifecycleTools + runLogTools
}
