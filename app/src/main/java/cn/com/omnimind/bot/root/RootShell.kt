package cn.com.omnimind.bot.root

import android.os.SystemClock
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 直接 Root 能力层（二改：OmniBot-Eta）。
 *
 * 不依赖 Shizuku，直接探测 Magisk / KernelSU / APatch 提供的 `su` 并执行命令，
 * 与 Eta（Mangi-11/Eta）的 Root Shell 方案对齐：
 *  - `android` 原生 shell（root 会话自动发现 Magisk/KernelSU/APatch 的 BusyBox）
 *  - 一次性命令走 [exec]，需要保留 cwd/环境变量的持久会话走 [RootShellSession]
 *
 * 注意：KernelSU 用户需先在 KernelSU 管理器中将本应用设为「直接授权」，
 * 否则 `su` 会等待授权弹窗（或直接拒绝）。
 */
object RootShell {

    enum class RootBackend(val label: String) {
        MAGISK("Magisk"),
        KERNELSU("KernelSU"),
        APATCH("APatch"),
        UNKNOWN("su")
    }

    data class RootStatus(
        val available: Boolean,
        val backend: RootBackend? = null,
        val version: String? = null,
        val suPath: String? = null,
        val busybox: Boolean = false,
        val detail: String = ""
    )

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    ) {
        val success: Boolean get() = exitCode == 0 && !timedOut
    }

    private const val TAG = "OmniBotEta.RootShell"

    private val suCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/bin/.su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su"
    )

    private val busyboxCandidates = listOf(
        "/data/adb/magisk/busybox",
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox"
    )

    @Volatile
    private var cachedStatus: RootStatus? = null

    /** 探测 root 状态；默认使用缓存，[forceRefresh] 为 true 时强制重新探测。 */
    fun detect(forceRefresh: Boolean = false): RootStatus {
        if (!forceRefresh) {
            cachedStatus?.let { return it }
        }
        val status = runCatching { probeRoot() }.getOrElse { err ->
            RootStatus(available = false, detail = err.message ?: "probe failed")
        }
        cachedStatus = status
        return status
    }

    fun invalidateCache() {
        cachedStatus = null
    }

    /** 以 root 身份执行一次性命令（`su -c <command>`）。 */
    suspend fun exec(
        command: String,
        timeoutSeconds: Int = 30,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap()
    ): ExecResult = withContext(Dispatchers.IO) {
        val status = detect()
        if (!status.available) {
            return@withContext ExecResult(-1, "", "root unavailable: ${status.detail}")
        }
        val suPath = status.suPath ?: "su"
        val builder = ProcessBuilder(suPath, "-c", command)
        workingDirectory?.let { builder.directory(File(it)) }
        environment.forEach { (k, v) -> builder.environment()[k] = v }
        val process = try {
            builder.start()
        } catch (e: Exception) {
            OmniLog.e(TAG, "failed to start su process", e)
            return@withContext ExecResult(-1, "", "failed to start su process: ${e.message}")
        }
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        val outThread = Thread { process.inputStream.copyTo(stdout) }.apply { start() }
        val errThread = Thread { process.errorStream.copyTo(stderr) }.apply { start() }
        val finished = try {
            process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            false
        }
        if (!finished) {
            process.destroyForcibly()
            outThread.join(1000)
            errThread.join(1000)
            ExecResult(
                exitCode = -1,
                stdout = stdout.toString(Charsets.UTF_8),
                stderr = stderr.toString(Charsets.UTF_8),
                timedOut = true
            )
        } else {
            outThread.join(1000)
            errThread.join(1000)
            ExecResult(
                exitCode = process.exitValue(),
                stdout = stdout.toString(Charsets.UTF_8),
                stderr = stderr.toString(Charsets.UTF_8)
            )
        }
    }

    private fun probeRoot(): RootStatus {
        val suPath = discoverSuPath()
        if (suPath == null) {
            return RootStatus(available = false, suPath = null, detail = "no su binary found")
        }
        val versionResult = runBlocking(listOf(suPath, "-v"), timeoutSeconds = 3)
        val versionText = versionResult.stdout.trim().ifEmpty { versionResult.stderr.trim() }
        val backend = detectBackend(versionText)
        val busybox = probeBusybox()
        return RootStatus(
            available = true,
            backend = backend,
            version = versionText.ifEmpty { null },
            suPath = suPath,
            busybox = busybox,
            detail = "su=$suPath backend=$backend busybox=$busybox"
        )
    }

    private fun discoverSuPath(): String? {
        suCandidates.forEach { path ->
            if (File(path).canExecute()) {
                return path
            }
        }
        // 兜底：PATH 查找
        val which = runBlocking(listOf("sh", "-c", "command -v su || which su"), timeoutSeconds = 3)
        val found = which.stdout.trim().ifEmpty { which.stderr.trim() }
        if (found.isNotBlank() && !found.contains("not found") && !found.contains("no su")) {
            return found.lineSequence().firstOrNull()
        }
        return null
    }

    private fun detectBackend(versionText: String): RootBackend? {
        val t = versionText.lowercase().replace(" ", "")
        return when {
            "magisk" in t -> RootBackend.MAGISK
            "kernelsu" in t -> RootBackend.KERNELSU
            "apatch" in t -> RootBackend.APATCH
            else -> RootBackend.UNKNOWN
        }
    }

    private fun probeBusybox(): Boolean {
        if (busyboxCandidates.any { File(it).canExecute() }) {
            return true
        }
        val which = runBlocking(listOf("sh", "-c", "command -v busybox"), timeoutSeconds = 2)
        return which.stdout.isNotBlank() || which.stderr.isNotBlank()
    }

    /** 阻塞式命令执行（仅用于探测，输出量小）。 */
    private fun runBlocking(command: List<String>, timeoutSeconds: Int): ExecResult {
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(false).start()
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ExecResult(-1, "", "timeout", timedOut = true)
            }
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            ExecResult(process.exitValue(), out, err)
        } catch (e: Exception) {
            ExecResult(-1, "", e.message ?: "error")
        }
    }
}

/**
 * 持久 root shell 会话：保留 cwd 与环境变量，适合多步诊断/脚本任务。
 * 与 Eta 的会话式 shell（async task + 分段读取）对齐。
 */
class RootShellSession internal constructor(
    private val process: Process,
    private val writer: PrintWriter
) {
    private val lock = Any()
    private val buffer = StringBuilder()

    @Volatile
    var closed: Boolean = false
        private set

    fun write(command: String) {
        if (closed) return
        synchronized(lock) {
            writer.println(command)
            writer.flush()
        }
    }

    /** 读取自上次读取以来累积的输出。 */
    fun readAvailable(): String {
        synchronized(lock) {
            val text = buffer.toString()
            buffer.setLength(0)
            return text
        }
    }

    fun close() {
        closed = true
        runCatching { writer.close() }
        runCatching { process.destroy() }
    }

    companion object {
        fun start(): RootShellSession? {
            val status = RootShell.detect()
            if (!status.available) {
                return null
            }
            val process = try {
                ProcessBuilder(status.suPath ?: "su").start()
            } catch (e: Exception) {
                OmniLog.e(TAG_ROOT_SESSION, "failed to start root session", e)
                return null
            }
            val session = RootShellSession(process, PrintWriter(process.outputStream, true))
            Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        session.appendLine(line)
                    }
                }
            }.apply { isDaemon = true }.start()
            Thread {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        session.appendLine(line)
                    }
                }
            }.apply { isDaemon = true }.start()
            return session
        }

        private const val TAG_ROOT_SESSION = "OmniBotEta.RootSession"
    }

    private fun appendLine(line: String) {
        if (closed) return
        synchronized(lock) {
            buffer.appendLine(line)
        }
    }
}

/** root 会话管理器：按会话 ID 持有/回收。 */
object RootShellSessionManager {

    private val sessions = ConcurrentHashMap<String, RootShellSession>()

    fun startSession(): String? {
        val session = RootShellSession.start() ?: return null
        val id = UUID.randomUUID().toString().take(8)
        sessions[id] = session
        return id
    }

    fun get(sessionId: String): RootShellSession? = sessions[sessionId]

    fun stopSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        session.close()
        return true
    }

    fun stopAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    /**
     * 在会话中执行命令，用随机 token 作为结束哨兵，避免依赖提示符判断。
     * 返回去掉哨兵后的输出。
     */
    suspend fun execInSession(
        sessionId: String,
        command: String,
        timeoutSeconds: Int = 60
    ): RootShell.ExecResult = withContext(Dispatchers.IO) {
        val session = sessions[sessionId]
        if (session == null) {
            return@withContext RootShell.ExecResult(-1, "", "session not found: $sessionId")
        }
        val token = "ETA_DONE_${UUID.randomUUID().toString().take(8)}"
        session.write("$command; echo $token")
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L
        val out = StringBuilder()
        var sawToken = false
        while (SystemClock.elapsedRealtime() < deadline && !sawToken) {
            val chunk = session.readAvailable()
            if (chunk.isNotEmpty()) {
                out.append(chunk)
                sawToken = out.contains(token)
            }
            if (!sawToken) {
                Thread.sleep(50)
            }
        }
        if (!sawToken) {
            // 超时：再读一次可能已经到达的尾巴
            out.append(session.readAvailable())
        }
        val text = out.toString()
        val body = text.substringBefore(token).trimEnd()
        RootShell.ExecResult(
            exitCode = if (sawToken) 0 else -1,
            stdout = body,
            stderr = "",
            timedOut = !sawToken
        )
    }
}
