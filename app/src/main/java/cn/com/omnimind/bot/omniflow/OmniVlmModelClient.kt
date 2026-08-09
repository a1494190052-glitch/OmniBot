package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.bot.agent.AgentLlmClient

internal fun AgentLlmClient.asOmniFlowModelClient(): OmniFlowModelClient =
    object : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn = this@asOmniFlowModelClient.streamTurn(
            request = request,
            onReasoningUpdate = onReasoningUpdate,
        )
    }
