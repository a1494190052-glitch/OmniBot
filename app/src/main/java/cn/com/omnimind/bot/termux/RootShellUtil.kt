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
     *
     * 注意：外层 shell 的 PATH 是 Android 的（/system/bin 等），chroot 后这些目录不存在，
     * 因此在内层 sh 中显式注入 Alpine 默认 PATH，否则 cat/ls 等 busybox applet 会报 not found。
     */
    fun buildRealChrootCommand(
        rootfsAndroidPath: String,
        command: String,
        workdir: String?
    ): String {
        val inner = withChrootEnvironment {
            if (workdir.isNullOrBlank()) {
                command
            } else {
                "cd '${escapeSingleQuoted(workdir)}' && $command"
            }
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
        val inner = withChrootEnvironment {
            if (workdir.isNullOrBlank()) {
                command
            } else {
                "cd '${escapeSingleQuoted(workdir)}' && $command"
            }
        }
        val escaped = escapeSingleQuoted(inner)
        return "chroot '$rootfsShellPath' /bin/sh -c '$escaped'"
    }

    /**
     * 探测默认 rootfs（Android 侧绝对路径）：优先 workspace 下 rootfs/alpine，
     * 其次 rootfs/debian、rootfs、workspace/alpine。rootfs 内需包含可执行的 /bin/sh。
     */
    fun findDefaultRootfsAndroidPath(workspaceAndroidRoot: String): String? {
        val candidates = listOf(
            "$workspaceAndroidRoot/rootfs/alpine",
            "$workspaceAndroidRoot/rootfs/debian",
            "$workspaceAndroidRoot/rootfs",
            "$workspaceAndroidRoot/alpine"
        )
        // 注意：rootfs 内的 /bin/sh 通常是指向 /bin/busybox 的绝对符号链接，而 busybox 只存在于
        // chroot 内（Android 宿主侧无 /bin/busybox），因此 File.exists()/canExecute() 都会返回
        // false（符号链接目标在宿主侧不存在）。改为检查 bin/ 目录存在且非空即可判定为 rootfs。
        return candidates.firstOrNull { path ->
            val root = File(path)
            val binDir = File(root, "bin")
            root.isDirectory && binDir.isDirectory &&
                (binDir.listFiles()?.isNotEmpty() == true)
        }
    }

    /**
     * 构造默认终端使用的真实 chroot 命令：先把 Android workspace bind mount 到
     * rootfs 内的 /workspace（保证 chroot 内 /workspace 与 Agent workspace 一致），
     * 再执行内核 chroot。命令结束后卸载 bind 挂载点。
     *
     * 由于 workspace 被 bind 到 rootfs 的 /workspace，chroot 内的 cwd 语义与
     * proot 命名空间一致（/workspace 即 workspace 根），因此 workdir 直接使用
     * shell 侧路径即可。
     */
    fun buildWorkspaceChrootCommand(
        rootfsAndroidPath: String,
        workspaceAndroidPath: String,
        command: String,
        workdir: String?
    ): String {
        val workspaceMount = "$rootfsAndroidPath/workspace"
        val inner = withChrootEnvironment {
            if (workdir.isNullOrBlank()) {
                command
            } else {
                "cd '${escapeSingleQuoted(workdir)}' && $command"
            }
        }
        val escaped = escapeDoubleQuoted(inner)
        val qRootfs = escapeSingleQuoted(rootfsAndroidPath)
        val qWorkspace = escapeSingleQuoted(workspaceAndroidPath)
        val qMount = escapeSingleQuoted(workspaceMount)
        val qProc = escapeSingleQuoted("$rootfsAndroidPath/proc")
        val qDev = escapeSingleQuoted("$rootfsAndroidPath/dev")
        val qSys = escapeSingleQuoted("$rootfsAndroidPath/sys")
        val setup =
            "mkdir -p '$qProc' '$qDev' '$qSys' '$qMount' 2>/dev/null || true; " +
                "umount '$qProc' 2>/dev/null || true; " +
                "umount '$qSys' 2>/dev/null || true; " +
                "umount '$qDev' 2>/dev/null || true; " +
                "mount -t proc proc '$qProc' 2>/dev/null || true; " +
                "mount --bind /dev '$qDev' 2>/dev/null || true; " +
                "mount -t sysfs sysfs '$qSys' 2>/dev/null || true; " +
                "mount --bind '$qWorkspace' '$qMount' 2>/dev/null || true; "
        val teardown =
            "umount '$qProc' 2>/dev/null || true; " +
                "umount '$qSys' 2>/dev/null || true; " +
                "umount '$qDev' 2>/dev/null || true; " +
                "umount '$qMount' 2>/dev/null || true; "
        return (
            setup +
                "chroot '$qRootfs' /bin/sh -c \"$escaped\"; " +
                "rc=\$?; " +
                teardown +
                "exit \$rc"
        )
    }

    /** 在 chroot 内执行的 shell 片段前缀：注入常见 Linux 发行版默认 PATH。 */
    private inline fun withChrootEnvironment(block: () -> String): String {
        val pathExport = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        return "$pathExport; ${block()}"
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
