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
    fun `python preparation only requires system numpy`() {
        val command = buildOmniFlowPythonPrepareCommand("3.12")

        assertTrue(command.contains("if ! base_packages_ready; then"))
        assertTrue(command.contains("apk --wait 300 add --no-cache python3 py3-pip py3-numpy"))
        assertTrue(command.contains("python3 -c 'import numpy'"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=repair_start package=python-numpy"))
        assertTrue(command.contains("OMNIFLOW_PYTHON_STAGE=probe_ready source=environment"))
        assertTrue(command.contains("/etc/omnibot-python-environment"))
        assertTrue(command.contains("alpine-3.21-python3.12-numpy2.1.3-v3"))
        assertFalse(command.contains("grep -qx 'alpine-3.21-python3.12-numpy2.1.3-v3'"))
        assertFalse(command.contains("printf '%s\\\\n'"))
        assertFalse(command.contains("command -v uv"))
        assertFalse(command.contains("uv sync"))
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
