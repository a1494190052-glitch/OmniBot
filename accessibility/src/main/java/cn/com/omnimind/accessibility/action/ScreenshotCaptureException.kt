package cn.com.omnimind.accessibility.action

class ScreenshotCaptureException(
    val errorCode: Int? = null,
    message: String = errorCode?.let { "Screenshot failed with error code: $it" }
        ?: "Screenshot failed",
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal object ScreenshotCaptureRetryPolicy {
    private val retryDelaysMs = longArrayOf(250L, 500L)

    fun isRecoverable(errorCode: Int?): Boolean = errorCode == 1 || errorCode == 3

    fun retryDelayMs(errorCode: Int?, retriesCompleted: Int): Long? {
        if (!isRecoverable(errorCode)) return null
        return retryDelaysMs.getOrNull(retriesCompleted)
    }
}
