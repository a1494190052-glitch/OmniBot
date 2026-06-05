package cn.com.omnimind.bot.workbench

data class WorkbenchProjectRecord(
    val projectId: String,
    val name: String,
    val route: String,
    val spacePath: String,
    val apiIds: List<String>,
    val createdAt: String,
    val updatedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "projectId" to projectId,
        "name" to name,
        "route" to route,
        "spacePath" to spacePath,
        "apiIds" to apiIds,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    )
}

data class WorkbenchApiRecord(
    val apiId: String,
    val projectId: String,
    val toolId: String,
    val displayName: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
    val executorKind: String,
    val run: Map<String, Any?>? = null
) {
    fun toPayload(executionCount: Int = 0): Map<String, Any?> =
        WorkbenchToolboxBuilder.apiContract(this, executionCount)
}

data class WorkbenchProjectItemRecord(
    val id: String,
    val title: String,
    val status: String,
    val fields: Map<String, Any?> = emptyMap(),
    val createdAt: String,
    val archivedAt: String? = null
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "status" to status,
        "fields" to fields,
        "createdAt" to createdAt,
        "archivedAt" to archivedAt
    )
}

internal data class WorkbenchApiCallSnapshot(
    val record: WorkbenchProjectRecord,
    val api: WorkbenchApiRecord?
)

internal data class WorkbenchApiCallPostState(
    val project: Map<String, Any?>,
    val updatedItems: List<Map<String, Any?>>?
)

data class WorkbenchAndroidAsset(
    val assetId: String,
    val projectId: String,
    val sourceKind: String,
    val displayName: String,
    val originalPath: String,
    val projectPath: String,
    val shellPath: String,
    val entryPath: String,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val sizeBytes: Long = 0,
    val fileCount: Int = 0,
    val importedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "assetId" to assetId,
        "projectId" to projectId,
        "sourceKind" to sourceKind,
        "displayName" to displayName,
        "originalPath" to originalPath,
        "projectPath" to projectPath,
        "shellPath" to shellPath,
        "entryPath" to entryPath,
        "packageName" to packageName,
        "versionName" to versionName,
        "versionCode" to versionCode,
        "sizeBytes" to sizeBytes,
        "fileCount" to fileCount,
        "importedAt" to importedAt
    )
}

data class WorkbenchOssSourceAsset(
    val sourceId: String,
    val projectId: String,
    val sourceKind: String,
    val displayName: String,
    val sourceUrl: String? = null,
    val ref: String? = null,
    val originalPath: String? = null,
    val projectPath: String,
    val shellPath: String,
    val entryPath: String,
    val requiresFetch: Boolean = false,
    val fetchHint: String? = null,
    val detectedStack: List<String> = emptyList(),
    val packageFiles: List<Map<String, Any?>> = emptyList(),
    val entrypoints: List<Map<String, Any?>> = emptyList(),
    val sizeBytes: Long = 0,
    val fileCount: Int = 0,
    val importedAt: String
) {
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "sourceId" to sourceId,
        "projectId" to projectId,
        "sourceKind" to sourceKind,
        "displayName" to displayName,
        "sourceUrl" to sourceUrl,
        "ref" to ref,
        "originalPath" to originalPath,
        "projectPath" to projectPath,
        "shellPath" to shellPath,
        "entryPath" to entryPath,
        "requiresFetch" to requiresFetch,
        "fetchHint" to fetchHint,
        "detectedStack" to detectedStack,
        "packageFiles" to packageFiles,
        "entrypoints" to entrypoints,
        "sizeBytes" to sizeBytes,
        "fileCount" to fileCount,
        "importedAt" to importedAt
    )
}
