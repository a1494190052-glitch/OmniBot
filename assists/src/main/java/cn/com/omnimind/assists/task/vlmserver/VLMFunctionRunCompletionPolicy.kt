package cn.com.omnimind.assists.task.vlmserver

object VLMFunctionRunCompletionPolicy {
    // Functions are segments within the VLM loop, not task terminators.
    // After a function executes, the loop continues so VLM can observe the
    // new screen state and decide whether to call finished or take more actions.
    fun shouldFinishAfterSuccessfulFunction(step: UIStep): Boolean = false
}
