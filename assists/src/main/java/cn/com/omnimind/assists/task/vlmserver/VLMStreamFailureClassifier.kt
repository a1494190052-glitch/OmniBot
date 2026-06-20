package cn.com.omnimind.assists.task.vlmserver

internal data class VLMStreamFailure(
    val kind: String,
    val message: String,
    val diagnostics: Map<String, String>
)

internal object VLMStreamFailureClassifier {
    const val KIND_AUTH_OR_CONFIG = "provider_auth_or_configuration_failed"
    const val KIND_NETWORK = "provider_network_failed"
    const val KIND_MODEL_OR_ENDPOINT_NOT_FOUND = "provider_model_or_endpoint_not_found"
    const val KIND_TOOL_SCHEMA_REJECTED = "provider_tool_schema_rejected"
    const val KIND_STREAMING_FAILED = "provider_streaming_failed"
    const val KIND_REQUEST_FAILED = "provider_request_failed"

    fun classify(error: Exception): VLMStreamFailure {
        val requestError = error as? VLMStreamRequestException
        val rawMessage = error.message?.trim().orEmpty()
        val reason = requestError?.reason?.trim().orEmpty()
        val combined = listOf(reason, rawMessage)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .lowercase()
        val kind = classifyKind(
            statusCode = requestError?.statusCode,
            normalizedMessage = combined
        )
        val detail = reason.ifBlank { rawMessage }.ifBlank { "unknown stream failure" }
        val diagnostics = linkedMapOf(
            "vlm_provider_failure_kind" to kind,
            "vlm_provider_failure_message" to detail.take(1000)
        )
        requestError?.statusCode?.let {
            diagnostics["vlm_provider_failure_status_code"] = it.toString()
        }
        requestError?.requestVariant?.takeIf { it.isNotBlank() }?.let {
            diagnostics["vlm_stream_request_variant"] = it
        }
        requestError?.responseBody?.takeIf { it.isNotBlank() }?.let {
            diagnostics["vlm_provider_failure_response_preview"] = it.take(1000)
        }
        return VLMStreamFailure(
            kind = kind,
            message = "$kind: $detail",
            diagnostics = diagnostics
        )
    }

    private fun classifyKind(
        statusCode: Int?,
        normalizedMessage: String
    ): String {
        if (
            statusCode == 401 ||
            statusCode == 403 ||
            normalizedMessage.contains("authentication") ||
            normalizedMessage.contains("unauthorized") ||
            normalizedMessage.contains("invalid proxy server token") ||
            normalizedMessage.contains("unable to find token") ||
            normalizedMessage.contains("api key")
        ) {
            return KIND_AUTH_OR_CONFIG
        }
        if (
            normalizedMessage.contains("unable to resolve host") ||
            normalizedMessage.contains("no address associated with hostname") ||
            normalizedMessage.contains("unknownhostexception") ||
            normalizedMessage.contains("failed to connect") ||
            normalizedMessage.contains("connection refused") ||
            normalizedMessage.contains("connection reset") ||
            normalizedMessage.contains("connect timed out") ||
            normalizedMessage.contains("read timed out") ||
            normalizedMessage.contains("sslhandshakeexception") ||
            normalizedMessage.contains("certificate") ||
            normalizedMessage.contains("tls")
        ) {
            return KIND_NETWORK
        }
        if (statusCode == 404) {
            return KIND_MODEL_OR_ENDPOINT_NOT_FOUND
        }
        if (
            statusCode == 400 &&
            (
                normalizedMessage.contains("tool") ||
                    normalizedMessage.contains("function") ||
                    normalizedMessage.contains("schema") ||
                    normalizedMessage.contains("tool_choice")
                )
        ) {
            return KIND_TOOL_SCHEMA_REJECTED
        }
        if (
            normalizedMessage.contains("event-stream") ||
            normalizedMessage.contains("sse")
        ) {
            return KIND_STREAMING_FAILED
        }
        return KIND_REQUEST_FAILED
    }
}
