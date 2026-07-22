package cn.com.omnimind.assists.task.vlmserver

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ManualTimedXmlCapture(
    val xml: String?,
    val durationMs: Long,
)

internal data class ManualObservationStats(
    val screenshotsActive: Boolean,
    val screenshotStoredCount: Int,
    val screenshotFailedCount: Int,
    val screenshotSkippedCount: Int,
    val xmlCaptureCount: Int,
    val xmlCaptureSuccessCount: Int,
    val xmlCaptureEmptyCount: Int,
    val xmlCaptureTotalMs: Long,
    val xmlCaptureMaxMs: Long,
    val xmlCaptureLastMs: Long,
    val xmlCaptureLastReason: String,
)

internal data class ManualScreenshotAnnotation(
    val actionName: String,
    val x: Float,
    val y: Float,
    val endX: Float? = null,
    val endY: Float? = null,
) {
    fun asMap(appliedScale: Float): Map<String, Any?> = linkedMapOf<String, Any?>(
        "kind" to "actual_touch_position",
        "action_name" to actionName,
    ).apply {
        if (endX != null && endY != null) {
            put("x1", x)
            put("y1", y)
            put("x2", endX)
            put("y2", endY)
        } else {
            put("x", x)
            put("y", y)
        }
        put("applied_scale", appliedScale)
    }

    companion object {
        fun point(actionName: String, x: Float, y: Float): ManualScreenshotAnnotation =
            ManualScreenshotAnnotation(actionName = actionName, x = x, y = y)

    }
}

internal class ManualObservationCapture(
    context: Context? = null,
    private val sessionLabel: String,
    private val debugScreenshotsRequested: Boolean,
    private val xmlProvider: () -> String? = ::captureAccessibilityXml,
    private val elapsedRealtimeMs: () -> Long = SystemClock::uptimeMillis,
    private val filesDirProvider: () -> File = {
        val requiredContext = requireNotNull(context) { "Context is required for screenshot capture" }
        (requiredContext.applicationContext ?: requiredContext).filesDir
    },
) {
    private val lock = Any()
    private var screenshotSkippedCount = 0
    private var screenshotStoredCount = 0
    private var screenshotFailedCount = 0
    private var screenshotDisabledReason: String? = null
    private var screenshotLastErrorType: String? = null
    private var screenshotSequence = 0
    private var xmlCaptureCount = 0
    private var xmlCaptureSuccessCount = 0
    private var xmlCaptureEmptyCount = 0
    private var xmlCaptureTotalMs = 0L
    private var xmlCaptureMaxMs = 0L
    private var xmlCaptureLastMs = 0L
    private var xmlCaptureLastReason = ""

    fun captureXml(reason: String): ManualTimedXmlCapture {
        val startedAtMs = elapsedRealtimeMs()
        val xml = runBlocking {
            withContext(Dispatchers.IO) { xmlProvider() }
        }?.takeIf { AccessibilityXml.health(it).isUsable }
        val durationMs = (elapsedRealtimeMs() - startedAtMs).coerceAtLeast(0L)
        synchronized(lock) {
            xmlCaptureCount += 1
            if (xml == null) xmlCaptureEmptyCount += 1 else xmlCaptureSuccessCount += 1
            xmlCaptureTotalMs += durationMs
            xmlCaptureMaxMs = maxOf(xmlCaptureMaxMs, durationMs)
            xmlCaptureLastMs = durationMs
            xmlCaptureLastReason = reason
        }
        return ManualTimedXmlCapture(xml = xml, durationMs = durationMs)
    }

    fun captureScreenshot(
        stage: String,
        annotation: ManualScreenshotAnnotation? = null,
    ): ManualVlmScreenshotRef? {
        if (!screenshotsActive()) {
            synchronized(lock) { screenshotSkippedCount += 1 }
            return null
        }
        return try {
            val sequence = synchronized(lock) {
                screenshotSequence += 1
                screenshotSequence
            }
            val capturedAtMs = System.currentTimeMillis()
            val capture = runBlocking {
                withTimeoutOrNull(SCREENSHOT_CAPTURE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        AccessibilityController.captureScreenshotImage(
                            isBitmap = true,
                            isBase64 = false,
                            isFile = false,
                            isFilterOverlay = true,
                            compressQuality = SCREENSHOT_QUALITY,
                        )
                    }
                }
            }
            val bitmap = capture?.imageBitmap
            if (capture?.isSuccess != true || bitmap == null) {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                synchronized(lock) {
                    screenshotFailedCount += 1
                    screenshotLastErrorType = "capture_unsuccessful"
                }
                OmniLog.w(TAG, "manual debug screenshot capture failed stage=$stage")
                return null
            }
            var outputBitmap: Bitmap? = null
            try {
                val preparedBitmap = prepareBitmap(bitmap)
                outputBitmap = preparedBitmap
                val filesDir = filesDirProvider()
                val screenshotDir = File(filesDir, screenshotRootRelativePath()).apply {
                    mkdirs()
                }
                val file = File(
                    screenshotDir,
                    "${sequence.toString().padStart(4, '0')}_${safePathSegment(stage)}.jpg",
                )
                val bytes = ByteArrayOutputStream().use { output ->
                    preparedBitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_JPEG_QUALITY, output)
                    output.toByteArray()
                }
                file.writeBytes(bytes)
                synchronized(lock) { screenshotStoredCount += 1 }
                ManualVlmScreenshotRef(
                    path = file.absolutePath,
                    relativePath = file.relativeTo(filesDir).path,
                    mimeType = "image/jpeg",
                    width = preparedBitmap.width,
                    height = preparedBitmap.height,
                    bytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    capturedAtMs = capturedAtMs,
                    captureStage = stage,
                    annotation = annotation?.asMap(capture.appliedScale).orEmpty(),
                )
            } finally {
                val preparedBitmap = outputBitmap
                if (preparedBitmap != null && !preparedBitmap.isRecycled) preparedBitmap.recycle()
                if (preparedBitmap !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            }
        } catch (error: OutOfMemoryError) {
            recordScreenshotFailure(
                stage = stage,
                error = error,
                disableForSession = true,
                disabledReason = "disabled_after_oom",
            )
        } catch (error: Throwable) {
            recordScreenshotFailure(stage, error)
        }
    }

    fun screenshotsActive(): Boolean = synchronized(lock) {
        debugScreenshotsRequested && screenshotDisabledReason == null
    }

    fun stats(): ManualObservationStats = synchronized(lock) {
        ManualObservationStats(
            screenshotsActive = debugScreenshotsRequested && screenshotDisabledReason == null,
            screenshotStoredCount = screenshotStoredCount,
            screenshotFailedCount = screenshotFailedCount,
            screenshotSkippedCount = screenshotSkippedCount,
            xmlCaptureCount = xmlCaptureCount,
            xmlCaptureSuccessCount = xmlCaptureSuccessCount,
            xmlCaptureEmptyCount = xmlCaptureEmptyCount,
            xmlCaptureTotalMs = xmlCaptureTotalMs,
            xmlCaptureMaxMs = xmlCaptureMaxMs,
            xmlCaptureLastMs = xmlCaptureLastMs,
            xmlCaptureLastReason = xmlCaptureLastReason,
        )
    }

    fun screenshotDiagnostics(): Map<String, Any?> {
        val stats = stats()
        val disabledReason = synchronized(lock) { screenshotDisabledReason }
        val lastErrorType = synchronized(lock) { screenshotLastErrorType }
        return linkedMapOf(
            "schema_version" to "oob.runlog.screenshot_refs.v1",
            "requested" to debugScreenshotsRequested,
            "enabled" to stats.screenshotsActive,
            "mode" to if (stats.screenshotsActive) "debug_marked_click_positions" else "disabled",
            "disabled_reason" to (
                disabledReason ?: if (debugScreenshotsRequested) null
                else "manual_recording_uses_real_touch_and_before_xml"
            ),
            "last_error_type" to lastErrorType,
            "storage" to "app_private_files",
            "reference_style" to "path_only",
            "stored_count" to stats.screenshotStoredCount,
            "failed_count" to stats.screenshotFailedCount,
            "skipped_count" to stats.screenshotSkippedCount,
            "root_relative_path" to screenshotRootRelativePath(),
            "annotation" to "actual_touch_position".takeIf { stats.screenshotsActive },
        ).filterValues { it != null }
    }

    fun xmlDiagnostics(): Map<String, Any?> {
        val stats = stats()
        return linkedMapOf(
            "schema_version" to "oob.manual_recording.xml_capture_timing.v1",
            "capture_count" to stats.xmlCaptureCount,
            "success_count" to stats.xmlCaptureSuccessCount,
            "empty_count" to stats.xmlCaptureEmptyCount,
            "total_ms" to stats.xmlCaptureTotalMs,
            "avg_ms" to if (stats.xmlCaptureCount > 0) {
                stats.xmlCaptureTotalMs / stats.xmlCaptureCount
            } else {
                0L
            },
            "max_ms" to stats.xmlCaptureMaxMs,
            "last_ms" to stats.xmlCaptureLastMs,
            "last_reason" to stats.xmlCaptureLastReason.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private fun recordScreenshotFailure(
        stage: String,
        error: Throwable,
        disableForSession: Boolean = false,
        disabledReason: String? = null,
    ): ManualVlmScreenshotRef? {
        synchronized(lock) {
            screenshotFailedCount += 1
            screenshotLastErrorType = error.javaClass.name
            if (disableForSession) screenshotDisabledReason = disabledReason ?: error.javaClass.name
        }
        OmniLog.w(
            TAG,
            "manual debug screenshot capture failed stage=$stage type=${error.javaClass.name}: ${error.message}",
        )
        return null
    }

    private fun prepareBitmap(bitmap: Bitmap): Bitmap =
        if (bitmap.isMutable && bitmap.config != Bitmap.Config.HARDWARE) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

    private fun screenshotRootRelativePath(): String =
        "oob_runlog_artifacts/${safePathSegment(sessionLabel)}/screenshots"

    private fun safePathSegment(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "manual_trace" }
        .take(96)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val TAG = "ManualObservationCapture"
        private const val SCREENSHOT_CAPTURE_TIMEOUT_MS = 2_800L
        private const val SCREENSHOT_JPEG_QUALITY = 90
        private val SCREENSHOT_QUALITY = ImageQuality.MEDIUM

        fun captureAccessibilityXml(): String? = try {
            AccessibilityController.initController()
            AccessibilityController.getCaptureScreenShotXml(false)?.takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            OmniLog.w(TAG, "manual trace xml capture failed: ${error.message}")
            null
        }
    }
}
