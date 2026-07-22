package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.AccessibilityXml
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ReplayHelper {
    class ExecutionException(
        val errorCode: String,
        message: String,
        val diagnostics: Map<String, Any?> = emptyMap(),
    ) : IllegalStateException(message)

    fun isUIStep(step: Map<String, Any?>): Boolean =
        actionNameForStep(step) in OobActionSchema.replayableToolNames

    fun actionNameForStep(step: Map<String, Any?>): String {
        val raw = step[OobActionSchema.ROOT_TOOL] as? String ?: ""
        return resolveActionName(raw)
            ?: OobActionSchema.normalizeToolName(raw).ifBlank { "unknown" }
    }

    fun normalizeArgsMap(rawArgs: Any?): Map<String, Any?> = mapArg(rawArgs)

    internal data class BackendSnapshot(
        val xml: String,
        val rawPackage: String,
        val activityName: String,
    )

    internal suspend fun readBackendSnapshot(
        deviceOperator: DeviceOperator,
    ): BackendSnapshot {
        var bestSnapshot = BackendSnapshot("", "", "")
        var bestHealth = AccessibilityXml.health(null)
        repeat(OBSERVATION_ATTEMPTS) { attempt ->
            val snapshot = readBackendSnapshotOnce(deviceOperator)
            val health = AccessibilityXml.health(snapshot.xml)
            if (
                health.semanticNodeCount > bestHealth.semanticNodeCount ||
                health.nodeCount > bestHealth.nodeCount ||
                health.charCount > bestHealth.charCount
            ) {
                bestSnapshot = snapshot
                bestHealth = health
            }
            if (health.isUsable) return snapshot
            if (attempt < OBSERVATION_ATTEMPTS - 1) {
                delay(OBSERVATION_RETRY_DELAY_MS)
            }
        }
        return bestSnapshot
    }

    private suspend fun readBackendSnapshotOnce(
        deviceOperator: DeviceOperator,
    ): BackendSnapshot = runCatching {
        withContext(Dispatchers.Main.immediate) {
            readBackendSnapshotDirect(deviceOperator)
        }
    }.getOrElse { readBackendSnapshotDirect(deviceOperator) }

    private fun readBackendSnapshotDirect(deviceOperator: DeviceOperator): BackendSnapshot {
        runCatching { deviceOperator.isReady() }
        return BackendSnapshot(
            xml = runCatching { deviceOperator.currentXml()?.trim().orEmpty() }.getOrDefault(""),
            rawPackage = runCatching {
                deviceOperator.currentPackageName()?.trim().orEmpty()
            }.getOrDefault(""),
            activityName = runCatching {
                deviceOperator.currentActivityName()?.trim().orEmpty()
            }.getOrDefault(""),
        )
    }

    private const val OBSERVATION_ATTEMPTS = 6
    private const val OBSERVATION_RETRY_DELAY_MS = 100L
}
