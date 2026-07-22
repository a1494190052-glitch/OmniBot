package cn.com.omnimind.assists.api.interfaces

import cn.com.omnimind.assists.api.bean.VlmTaskTerminalResult

data class VlmStepProgress(
    val runId: String,
    val stepIndex: Int,
    val status: String,
    val thinking: String = "",
    val summary: String = "",
    val action: Map<String, Any?>? = null,
    val result: Map<String, Any?>? = null,
    val error: String? = null,
)

interface OnMessagePushListener {
    /**
     * 大模型消息
     * @param taskID 任务ID
     * @param content 消息内容
     * @param type 消息类型（来自EventSource的type字段）
     */
    suspend fun onChatMessage(taskID:String,content: String, type: String?)

    /**
     * 大模型消息结束事件
     */
    suspend fun onChatMessageEnd(taskID:String)

    /**
     * 任务结束回调
     */
    fun onTaskFinish()

    /**
     * vlmTask执行结束
     */
    fun onVLMTaskFinish()

    /**
     * VLM任务请求用户输入（INFO动作）
     */
    fun onVLMRequestUserInput(question: String)

    /**
     * VLM任务终态/交互态结果
     */
    fun onVlmTaskResult(result: VlmTaskTerminalResult) {}

    /** Real-time canonical VLM step progress for execution cards. */
    suspend fun onVlmStepProgress(progress: VlmStepProgress) {}

}
