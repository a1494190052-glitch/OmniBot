package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest

interface OmniFlowPlatform {
    suspend fun startProcess(
        context: Context,
        command: String,
        environment: Map<String, String>,
    ): Process

    suspend fun ensurePython(context: Context, expectedVersion: String)

    suspend fun completeJson(request: ChatCompletionRequest): String
}

internal object OmniFlowPlatformRegistry {
    @Volatile
    private var platform: OmniFlowPlatform? = null

    fun configure(value: OmniFlowPlatform) {
        platform = value
    }

    fun require(): OmniFlowPlatform = requireNotNull(platform) {
        "omniflow_platform_not_configured"
    }
}
