package cn.com.omnimind.assists.task.vlmserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VLMStreamFailureClassifierTest {
    @Test
    fun `401 stream failure is auth configuration blocker not tool-call contract`() {
        val failure = VLMStreamFailureClassifier.classify(
            VLMStreamRequestException(
                statusCode = 401,
                reason = "Authentication Error, Invalid proxy server token passed",
                responseBody = """{"error":{"message":"Invalid proxy server token"}}""",
                requestVariant = "default"
            )
        )

        assertEquals(VLMStreamFailureClassifier.KIND_AUTH_OR_CONFIG, failure.kind)
        assertTrue(failure.message.startsWith("provider_auth_or_configuration_failed:"))
        assertEquals("401", failure.diagnostics["vlm_provider_failure_status_code"])
        assertEquals("default", failure.diagnostics["vlm_stream_request_variant"])
        assertEquals(
            VLMStreamFailureClassifier.KIND_AUTH_OR_CONFIG,
            failure.diagnostics["vlm_provider_failure_kind"]
        )
    }

    @Test
    fun `400 tool schema rejection is separate from auth and native response contract`() {
        val failure = VLMStreamFailureClassifier.classify(
            VLMStreamRequestException(
                statusCode = 400,
                reason = "Unsupported tool_choice required with strict schema",
                responseBody = null,
                requestVariant = "default"
            )
        )

        assertEquals(VLMStreamFailureClassifier.KIND_TOOL_SCHEMA_REJECTED, failure.kind)
    }

    @Test
    fun `network failures are classified before generic stream failure`() {
        val failure = VLMStreamFailureClassifier.classify(
            IllegalStateException("Unable to resolve host llmapi.example.com")
        )

        assertEquals(VLMStreamFailureClassifier.KIND_NETWORK, failure.kind)
    }
}
