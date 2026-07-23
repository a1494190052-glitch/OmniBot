package cn.com.omnimind.bot.runlog

import cn.com.omnimind.assists.task.vlmserver.AccessibilityXml
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ReplayHelper {
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
