package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object VLMFunctionRunCompletionPolicy {
    fun shouldFinishAfterSuccessfulFunction(step: UIStep): Boolean {
        if (step.action !is FunctionRunAction) return false
        val data = step.actionResultData as? JsonObject ?: return false
        val result = data.objectValue("result")
        val success = data.booleanValue("success") ?: result?.booleanValue("success") ?: false
        if (!success) return false
        if (data.booleanValue("model_required") == true || result?.booleanValue("model_required") == true) {
            return false
        }
        if (data.booleanValue("needs_confirmation") == true || result?.booleanValue("needs_confirmation") == true) {
            return false
        }
        val stepCount = data.intValue("step_count") ?: result?.intValue("step_count")
        val currentStepNumber = data.intValue("current_step_number") ?: result?.intValue("current_step_number")
        if (stepCount != null && currentStepNumber != null && currentStepNumber < stepCount) {
            return false
        }
        return true
    }

    private fun JsonObject.objectValue(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.booleanValue(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull
}
