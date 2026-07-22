package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object VLMFunctionRunCompletionPolicy {
    fun shouldFinishAfterSuccessfulFunction(step: UIStep): Boolean {
        if (step.action !is FunctionInvocation) return false
        val payload = step.actionResultData as? JsonObject ?: return false
        if (payload.bool("success") != true) return false
        val nestedResult = payload["result"] as? JsonObject
        if (payload.bool("model_required") == true || nestedResult?.bool("model_required") == true) {
            return false
        }
        val stepCount = payload.int("step_count") ?: nestedResult?.int("step_count") ?: return false
        val currentStep = payload.int("current_step_number")
            ?: nestedResult?.int("current_step_number")
            ?: payload.int("completed_step_count")
            ?: nestedResult?.int("completed_step_count")
            ?: return false
        return stepCount > 0 && currentStep >= stepCount
    }

    private fun JsonObject.bool(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull
}
