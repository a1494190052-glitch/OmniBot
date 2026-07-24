package cn.com.omnimind.assists.task.vlmserver

internal data class TaskExecutionReport(
    val success: Boolean,
    val executionTrace: List<UIStep>,
    val error: String?,
    val summaryScreenshotList: List<String>? = null,
    val doneReason: String? = null,
    val finalStateId: String? = null,
)
