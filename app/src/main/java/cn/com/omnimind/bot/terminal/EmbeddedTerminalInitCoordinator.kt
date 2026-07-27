package cn.com.omnimind.bot.terminal

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import cn.com.omnimind.bot.termux.TermuxLiveEnvironmentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object EmbeddedTerminalInitCoordinator {
    private const val TAG = "EmbeddedTerminalInit"
    private const val MAX_INIT_LOG_LINES = 160

    private val BASE_PACKAGE_NAMES = listOf(
        "bash",
        "ca-certificates",
        "curl",
        "git",
        "gcompat",
        "glib",
        "nodejs",
        "npm",
        "python3",
        "py3-pip",
        "py3-virtualenv",
        "ripgrep",
        "tmux",
        "xz"
    )

    private data class EmbeddedTerminalInitState(
        val running: Boolean = false,
        val completed: Boolean = false,
        val success: Boolean? = null,
        val progress: Double = 0.0,
        val stage: String = "",
        val logLines: List<String> = emptyList(),
        val startedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val completedAt: Long? = null,
        val seenBasePackages: Set<String> = emptySet()
    )

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val listenerLock = Any()
    private val listeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

    private var embeddedTerminalInitState = EmbeddedTerminalInitState()
    private var activeRun: CompletableDeferred<TermuxLiveEnvironmentResult>? = null

    fun addListener(listener: (Map<String, Any?>) -> Unit) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Map<String, Any?>) -> Unit) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    fun buildSnapshot(): Map<String, Any?> {
        val snapshot = synchronized(stateLock) {
            embeddedTerminalInitState
        }
        return mapOf(
            "running" to snapshot.running,
            "completed" to snapshot.completed,
            "success" to snapshot.success,
            "progress" to snapshot.progress,
            "stage" to snapshot.stage,
            "logLines" to snapshot.logLines,
            "startedAt" to snapshot.startedAt.takeIf { it > 0L },
            "updatedAt" to snapshot.updatedAt.takeIf { it > 0L },
            "completedAt" to snapshot.completedAt
        )
    }

    fun startInBackground(context: Context): Boolean {
        val deferred = synchronized(stateLock) {
            val current = activeRun?.takeIf { !it.isCompleted }
            if (current != null) {
                return false
            }
            CompletableDeferred<TermuxLiveEnvironmentResult>().also {
                activeRun = it
                resetEmbeddedTerminalInitStateLocked()
            }
        }
        val appContext = context.applicationContext
        workerScope.launch {
            runPreparation(appContext, deferred)
        }
        return true
    }

    suspend fun prepare(context: Context): TermuxLiveEnvironmentResult {
        val appContext = context.applicationContext
        val deferred: CompletableDeferred<TermuxLiveEnvironmentResult>
        val shouldStartNow: Boolean
        synchronized(stateLock) {
            val current = activeRun?.takeIf { !it.isCompleted }
            if (current != null) {
                deferred = current
                shouldStartNow = false
            } else {
                deferred = CompletableDeferred()
                activeRun = deferred
                resetEmbeddedTerminalInitStateLocked()
                shouldStartNow = true
            }
        }
        if (shouldStartNow) {
            runPreparation(appContext, deferred)
        }
        return deferred.await()
    }

    private suspend fun runPreparation(
        context: Context,
        deferred: CompletableDeferred<TermuxLiveEnvironmentResult>
    ) {
        try {
            emitEmbeddedTerminalInitProgress(
                kind = "status",
                message = "开始准备内嵌终端环境"
            )
            val status = TermuxCommandRunner.prepareLiveEnvironment(context) { progress ->
                emitEmbeddedTerminalInitProgress(
                    kind = progress.kind.name.lowercase(),
                    message = progress.message
                )
            }
            emitEmbeddedTerminalInitProgress(
                kind = if (status.success) "status" else "error",
                message = status.message
            )
            markEmbeddedTerminalInitCompleted(
                success = status.success,
                finalMessage = status.message
            )
            deferred.complete(status)
        } catch (error: Exception) {
            OmniLog.e(TAG, "Failed to prepare embedded terminal runtime", error)
            val failureMessage = error.message ?: "检查内嵌终端环境失败"
            emitEmbeddedTerminalInitProgress(
                kind = "error",
                message = failureMessage
            )
            markEmbeddedTerminalInitCompleted(
                success = false,
                finalMessage = failureMessage
            )
            deferred.completeExceptionally(error)
        } finally {
            synchronized(stateLock) {
                if (activeRun === deferred) {
                    activeRun = null
                }
            }
        }
    }

    private fun emitEmbeddedTerminalInitProgress(
        kind: String,
        message: String
    ) {
        if (message.isBlank()) {
            return
        }
        updateEmbeddedTerminalInitState(kind, message)
        val payload = mapOf(
            "kind" to kind,
            "message" to message,
            "timestamp" to System.currentTimeMillis()
        )
        val currentListeners = synchronized(listenerLock) {
            listeners.toList()
        }
        if (currentListeners.isEmpty()) {
            return
        }
        mainScope.launch {
            currentListeners.forEach { listener ->
                runCatching {
                    listener(payload)
                }
            }
        }
    }

    private fun resetEmbeddedTerminalInitStateLocked() {
        val now = System.currentTimeMillis()
        embeddedTerminalInitState = EmbeddedTerminalInitState(
            running = true,
            completed = false,
            success = null,
            progress = 0.02,
            stage = "准备开始",
            logLines = listOf("[系统] 正在启动内嵌终端环境初始化..."),
            startedAt = now,
            updatedAt = now
        )
    }

    private fun updateEmbeddedTerminalInitState(
        kind: String,
        message: String
    ) {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isBlank()) {
            return
        }

        val normalizedLines = normalizeEmbeddedTerminalInitLines(normalizedMessage)
        if (normalizedLines.isEmpty()) {
            return
        }

        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val current =
                if (embeddedTerminalInitState.startedAt == 0L) {
                    EmbeddedTerminalInitState(
                        running = true,
                        startedAt = now,
                        updatedAt = now
                    )
                } else {
                    embeddedTerminalInitState
                }

            val nextSeenBasePackages =
                if (kind == "output") {
                    current.seenBasePackages + extractSeenBasePackages(normalizedLines)
                } else {
                    current.seenBasePackages
                }

            val derivedProgress = deriveEmbeddedTerminalInitProgress(
                kind = kind,
                message = normalizedMessage,
                seenBasePackages = nextSeenBasePackages,
                currentProgress = current.progress
            )

            embeddedTerminalInitState = current.copy(
                running = true,
                completed = false,
                success = null,
                progress = maxOf(current.progress, derivedProgress).coerceAtMost(0.99),
                stage = if (kind == "output") current.stage else normalizedMessage,
                logLines = mergeEmbeddedTerminalInitLogLines(
                    current.logLines,
                    formatEmbeddedTerminalInitLogLines(kind, normalizedLines)
                ),
                updatedAt = now,
                seenBasePackages = nextSeenBasePackages
            )
        }
    }

    private fun markEmbeddedTerminalInitCompleted(
        success: Boolean,
        finalMessage: String
    ) {
        val normalizedMessage = finalMessage.trim().ifBlank {
            if (success) {
                "内嵌终端环境和基础 Agent CLI 包均已就绪。"
            } else {
                "检查内嵌终端环境失败"
            }
        }
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val current = embeddedTerminalInitState
            embeddedTerminalInitState = current.copy(
                running = false,
                completed = true,
                success = success,
                progress = if (success) 1.0 else current.progress.coerceAtLeast(0.02),
                stage = normalizedMessage,
                updatedAt = now,
                completedAt = now
            )
        }
    }

    private fun normalizeEmbeddedTerminalInitLines(message: String): List<String> {
        return message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
    }

    private fun formatEmbeddedTerminalInitLogLines(
        kind: String,
        lines: List<String>
    ): List<String> {
        val prefix =
            when (kind) {
                "error" -> "[错误] "
                "output" -> ""
                else -> "[阶段] "
            }
        return lines.map { line -> "$prefix$line" }
    }

    private fun mergeEmbeddedTerminalInitLogLines(
        currentLines: List<String>,
        appendedLines: List<String>
    ): List<String> {
        if (appendedLines.isEmpty()) {
            return currentLines
        }
        val merged = currentLines + appendedLines
        return if (merged.size > MAX_INIT_LOG_LINES) {
            merged.takeLast(MAX_INIT_LOG_LINES)
        } else {
            merged
        }
    }

    private fun extractSeenBasePackages(lines: List<String>): Set<String> {
        val lowerCaseLines = lines.map { it.lowercase() }
        return BASE_PACKAGE_NAMES.filter { packageName ->
            val lowerPackageName = packageName.lowercase()
            lowerCaseLines.any { line ->
                line.contains(lowerPackageName) &&
                    (
                        line.contains("fetch ") ||
                            line.contains("installing ") ||
                            line.contains("upgrading ") ||
                            line.contains("get:") ||
                            line.contains("selecting previously") ||
                            line.contains("unpacking") ||
                            line.contains("setting up") ||
                            line.contains("preparing to unpack")
                        )
            }
        }.toSet()
    }

    private fun deriveEmbeddedTerminalInitProgress(
        kind: String,
        message: String,
        seenBasePackages: Set<String>,
        currentProgress: Double
    ): Double {
        val normalizedMessage = message.trim()
        val stageProgress =
            when {
                normalizedMessage.contains("开始准备内嵌终端环境") -> 0.04
                normalizedMessage.contains("正在准备 workspace 和运行目录") -> 0.10
                normalizedMessage.contains("正在初始化宿主终端运行时") -> 0.14
                normalizedMessage.contains("正在校验终端环境运行资源") -> 0.24
                normalizedMessage.contains("正在安装终端环境运行资源") -> 0.42
                normalizedMessage.contains("宿主终端环境校验完成") -> 0.60
                normalizedMessage.contains("正在检查基础 Agent CLI 包") -> 0.68
                normalizedMessage.contains("基础 Agent CLI 包已就绪") -> 0.96
                normalizedMessage.contains("正在安装基础 Agent CLI 包") -> 0.72
                normalizedMessage.contains("基础 Agent CLI 包安装完成") -> 0.98
                normalizedMessage.contains("均已就绪") -> 1.0
                else -> null
            }
        if (stageProgress != null) {
            return stageProgress
        }

        if (kind == "output" && seenBasePackages.isNotEmpty()) {
            val packageRatio = seenBasePackages.size.toDouble() / BASE_PACKAGE_NAMES.size.toDouble()
            val outputProgress = 0.72 + packageRatio * 0.22
            return maxOf(currentProgress, outputProgress)
        }

        return currentProgress
    }
}
