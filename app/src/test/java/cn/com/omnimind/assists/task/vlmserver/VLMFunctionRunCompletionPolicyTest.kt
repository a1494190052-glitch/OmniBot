package cn.com.omnimind.assists.task.vlmserver

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMFunctionRunCompletionPolicyTest {
    @Test
    fun `finishes after successful function that does not require model continuation`() {
        val step = functionStep(
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("step_count", JsonPrimitive(4))
                put("current_step_number", JsonPrimitive(4))
                put("result", buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("model_required", JsonPrimitive(false))
                })
            }
        )

        assertTrue(VLMFunctionRunCompletionPolicy.shouldFinishAfterSuccessfulFunction(step))
    }

    @Test
    fun `does not finish when function requires model continuation`() {
        val step = functionStep(
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("result", buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("model_required", JsonPrimitive(true))
                })
            }
        )

        assertFalse(VLMFunctionRunCompletionPolicy.shouldFinishAfterSuccessfulFunction(step))
    }

    @Test
    fun `does not finish when function has not reached final step`() {
        val step = functionStep(
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("step_count", JsonPrimitive(4))
                put("current_step_number", JsonPrimitive(2))
                put("result", buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("model_required", JsonPrimitive(false))
                })
            }
        )

        assertFalse(VLMFunctionRunCompletionPolicy.shouldFinishAfterSuccessfulFunction(step))
    }

    private fun functionStep(actionResultData: kotlinx.serialization.json.JsonObject): UIStep =
        UIStep(
            observation = "Settings",
            thought = "Call reusable function",
            action = FunctionRunAction(functionId = "open_bluetooth"),
            result = "复用指令执行完成",
            actionResultData = actionResultData,
        )
}
