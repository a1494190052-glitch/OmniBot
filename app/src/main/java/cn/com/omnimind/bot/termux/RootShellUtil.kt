package cn.com.omnimind.bot.termux

import android.content.Context
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真实 root（内核级提权）探测与 chroot 命令构造。
 *
 * 注意：内嵌 proot 环境内 `id` 显示 uid=0 只是 proot 的假 root（对 getuid 做了伪装），
 * 内核级 uid 仍是应用 uid。因此是否具备真实 root 以 Shizuku/Sui root 后端为准
 * （Shizuku.getUid() == 0 才表示真实 root），proot 内无法真提权执行内核 chroot。
 */
object RootShellUtil {

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "su"
    )

    /**
     * 探测当前是否具备真实 root 能力。
     *
     * 优先级：
     * 1. Shizuku/Sui root 后端（getUid() == 0）—— 首选，可直接执行内核 chroot。
     * 2. 传统 su 提权探测（`su -c 'cat /proc/self/status'` 中 Uid 行全为 0）—— 兜底。
     */
    fun isRealRootAvailable(context: Context): Boolean {
        val shizukuRoot = runCatching {
            val status = ShizukuCapabilityManager.get(context).getStatus()
            status.backend == ShizukuBackend.ROOT && status.permissionGranted
        }.getOrDefault(false)
        if (shizukuRoot) return true
        return hasTraditionalSu()
    }

    private fun hasTraditionalSu(): Boolean {
        val su = findSu() ?: return false
        return runCatching {
            val process = ProcessBuilder(su, "-c", "cat /proc/self/status")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            output.lineSequence().any { line ->
                line.startsWith("Uid:") && line.contains("\t0\t0\t0\t0")
            }
        }.getOrDefault(false)
    }

    private fun findSu(): String? =
        SU_PATHS.firstOrNull { path ->
            path == "su" || File(path).canExecute()
        }

    /**
     * 构造真实 root chroot 命令（在 root 后端 shell 中直接执行，无需 su 前缀）：
     * `chroot <rootfs> /bin/sh -c "<cmd>"`。
     * rootfsAndroidPath 必须是 Android 侧绝对路径（如 /data/user/0/... 或 /data/local/tmp/...）。
     */
    fun buildRealChrootCommand(
        rootfsAndroidPath: String,
        command: String,
        workdir: String?
    ): String {
        val inner = if (workdir.isNullOrBlank()) {
            command
        } else {
            "cd '${escapeSingleQuoted(workdir)}' && $command"
        }
        val escaped = escapeDoubleQuoted(inner)
        return "chroot $rootfsAndroidPath /bin/sh -c \"$escaped\""
    }

    /** 构造 proot 模拟 chroot 命令（在既有 Alpine proot 环境内执行，无需真实 root）。 */
    fun buildProotChrootCommand(
        rootfsShellPath: String,
        command: String,
        workdir: String?
    ): String {
        val inner = if (workdir.isNullOrBlank()) {
            command
        } else {
            "cd '${escapeSingleQuoted(workdir)}' && $command"
        }
        val escaped = escapeSingleQuoted(inner)
        return "chroot '$rootfsShellPath' /bin/sh -c '$escaped'"
    }

    /** 单引号包裹的 shell 片段转义（用于外层单引号内）。 */
    private fun escapeSingleQuoted(value: String): String =
        value.replace("'", "'\\''")

    /** 双引号包裹的 shell 片段转义（用于内层 sh -c 双引号内）。 */
    private fun escapeDoubleQuoted(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
}
