package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.controller.http.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniFlowAppPlatformTest {
    @Test
    fun `python preparation installs only the required runtime packages when missing`() {
        val command = buildOmniFlowPythonPrepareCommand("3.12")

        assertTrue(command.contains("if ! packages_ready; then"))
        assertTrue(command.contains("python3 -c 'import numpy'"))
        assertTrue(command.contains("apk add --no-cache python3 py3-pip py3-numpy libstdc++"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=repair_start package=py3-numpy"))
        assertTrue(command.contains("/etc/omnibot-python-environment"))
        assertTrue(command.trimEnd().endsWith("OMNIFLOW_PYTHON_STAGE=ready'"))
        assertFalse(command.contains("nodejs"))
    }

    @Test
    fun `json completion reads submit json native tool arguments`() {
        val response = SceneChatCompletionResponse(
            success = true,
            code = "200",
            message = "success",
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            toolCalls = listOf(
                AssistantToolCall(
                    id = "call-1",
                    function = AssistantToolCallFunction(
                        name = "submit_json",
                        arguments = """{"parameters":[]}""",
                    ),
                ),
            ),
        )

        assertEquals(
            """{"parameters":[]}""",
            resolveOmniFlowJsonCompletion(response),
        )
    }
}
