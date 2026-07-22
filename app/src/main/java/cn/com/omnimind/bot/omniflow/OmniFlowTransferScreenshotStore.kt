package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.util.Base64
import cn.com.omnimind.assists.task.vlmserver.DeviceOperator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object OmniFlowTransferScreenshotStore {
    suspend fun capture(
        context: Context,
        deviceOperator: DeviceOperator,
    ): Map<String, Any?> {
        val captured = deviceOperator.captureScreenshot()
        val encoded = if (captured.startsWith("data:")) {
            captured.substringAfter(',')
        } else {
            captured
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "transfer_screenshot_empty" }
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            val target = File(directory, "transfer_${System.currentTimeMillis()}.jpg")
            val temporary = File(directory, "${target.name}.tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.writeBytes(bytes)
                temporary.delete()
            }
            prune(directory)
            target
        }
        val width = deviceOperator.getDisplayWidth().takeIf { it > 0 }
            ?: deviceOperator.getLastScreenshotWidth().takeIf { it > 0 }
        val height = deviceOperator.getDisplayHeight().takeIf { it > 0 }
            ?: deviceOperator.getLastScreenshotHeight().takeIf { it > 0 }
        return linkedMapOf<String, Any?>("screenshot_path" to file.absolutePath).apply {
            if (width != null && height != null) {
                put("display", linkedMapOf("width" to width, "height" to height))
            }
        }
    }

    private fun prune(directory: File) {
        val files = directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            retainedBytes += file.length()
            if (index >= MAX_FILES || retainedBytes > MAX_BYTES) file.delete()
        }
    }

    private const val DIRECTORY = "omniflow_transfer_diagnostics"
    private const val MAX_FILES = 24
    private const val MAX_BYTES = 64L * 1024L * 1024L
}
