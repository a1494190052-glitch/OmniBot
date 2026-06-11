package cn.com.omnimind.bot.runlog

import BaseApplication
import android.app.Activity
import android.content.Intent
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.exception.PrivacyBlockedException
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

interface OmniflowActionBackend {
    fun isReady(): Boolean

    suspend fun click(x: Float, y: Float)

    suspend fun click(
        x: Float,
        y: Float,
        targetDescription: String,
        nodeResourceId: String,
    ) {
        click(x, y)
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long)

    suspend fun longPress(
        x: Float,
        y: Float,
        durationMs: Long,
        targetDescription: String,
        nodeResourceId: String,
    ) {
        longPress(x, y, durationMs)
    }

    suspend fun scroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
    )

    suspend fun scrollWithContext(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
        targetDescription: String,
    ) {
        scroll(x, y, direction, distance, durationMs)
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        targetDescription: String,
    ) {
        val dx = endX - startX
        val dy = endY - startY
        val direction = if (kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
            if (dy > 0) ScrollDirection.DOWN else ScrollDirection.UP
        } else {
            if (dx > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT
        }
        scrollWithContext(
            x = startX,
            y = startY,
            direction = direction,
            distance = kotlin.math.hypot(dx, dy),
            durationMs = durationMs,
            targetDescription = targetDescription,
        )
    }

    suspend fun inputTextToFocusedNode(text: String)

    suspend fun inputText(
        text: String,
        targetDescription: String = "",
        x: Float? = null,
        y: Float? = null,
        nodeResourceId: String = "",
    ) {
        inputTextToFocusedNode(text)
    }

    suspend fun inputTextByTyping(
        text: String,
        targetDescription: String = "",
        x: Float? = null,
        y: Float? = null,
        nodeResourceId: String = "",
    ) {
        inputText(
            text = text,
            targetDescription = targetDescription,
            x = x,
            y = y,
            nodeResourceId = nodeResourceId,
        )
    }

    suspend fun launchApplication(packageName: String)

    suspend fun launchApplication(packageName: String, resetTask: Boolean) {
        launchApplication(packageName)
    }

    suspend fun pressHotKey(key: String)

    suspend fun hideKeyboard() = Unit

    fun currentXml(): String?

    fun currentPackageName(): String?

    fun currentActivityName(): String?
}

object OmniflowActionRuntime {
    @Volatile
    private var backendOverride: OmniflowActionBackend? = null

    val backend: OmniflowActionBackend
        get() = backendOverride ?: AccessibilityOmniflowActionBackend

    internal val isUsingBackendForTesting: Boolean
        get() = backendOverride != null

    fun useBackendForTesting(backend: OmniflowActionBackend): AutoCloseable {
        backendOverride = backend
        return AutoCloseable {
            if (backendOverride === backend) {
                backendOverride = null
            }
        }
    }
}

private object AccessibilityOmniflowActionBackend : OmniflowActionBackend {
    private const val TAG = "OmniflowActionBackend"
    private const val CLICK_EFFECT_CHECK_DELAY_MS = 180L
    private const val PRIVILEGED_SWIPE_EFFECT_CHECK_DELAY_MS = 300L

    override fun isReady(): Boolean = AccessibilityController.initController()

    override suspend fun click(x: Float, y: Float) {
        val clickError = runCatching {
            AccessibilityController.clickCoordinate(x, y)
        }.exceptionOrNull()
        if (clickError == null) {
            return
        }
        OmniLog.w(
            TAG,
            "accessibility coordinate click failed, fallback to privileged tap: ${clickError.message}"
        )
        if (!tryPrivilegedTap(x, y)) {
            AccessibilityController.clickCoordinate(x, y)
        }
    }

    override suspend fun click(
        x: Float,
        y: Float,
        targetDescription: String,
        nodeResourceId: String,
    ) {
        // Replay click coordinates are already transferred to the live page.
        click(x, y)
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
        val beforeXml = currentXml().orEmpty()
        val longPressError = runCatching {
            AccessibilityController.longClickCoordinate(x, y, durationMs)
        }.exceptionOrNull()
        if (longPressError == null) {
            delay(CLICK_EFFECT_CHECK_DELAY_MS)
            val afterXml = currentXml().orEmpty()
            if (xmlChanged(beforeXml, afterXml)) {
                return
            }
            OmniLog.w(
                TAG,
                "accessibility coordinate long press had no visible effect, fallback to privileged swipe: x=${x.toInt()} y=${y.toInt()}"
            )
        } else {
            OmniLog.w(
                TAG,
                "accessibility coordinate long press failed, fallback to privileged swipe: ${longPressError.message}"
            )
        }
        if (!tryPrivilegedSwipe(x, y, x, y, durationMs)) {
            AccessibilityController.longClickCoordinate(x, y, durationMs)
        }
    }

    override suspend fun longPress(
        x: Float,
        y: Float,
        durationMs: Long,
        targetDescription: String,
        nodeResourceId: String,
    ) {
        // Replay long-press coordinates are already transferred to the live page.
        longPress(x, y, durationMs)
    }

    override suspend fun scroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
    ) {
        performScroll(
            x = x,
            y = y,
            direction = direction,
            distance = distance,
            durationMs = durationMs,
            targetDescription = "",
        )
    }

    private suspend fun performScroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
        targetDescription: String,
    ) {
        val beforeXml = currentXml().orEmpty()
        val endpoints = scrollEndpoints(x, y, direction, distance)
        if (trySemanticSwipe(
                x1 = endpoints.x1,
                y1 = endpoints.y1,
                x2 = endpoints.x2,
                y2 = endpoints.y2,
                targetDescription = targetDescription,
            )
        ) {
            delay(PRIVILEGED_SWIPE_EFFECT_CHECK_DELAY_MS)
            return
        }
        val scrollError = runCatching {
            AccessibilityController.scrollCoordinate(x, y, direction, distance, durationMs)
        }.exceptionOrNull()
        if (scrollError == null) {
            delay(CLICK_EFFECT_CHECK_DELAY_MS)
            val afterXml = currentXml().orEmpty()
            if (xmlChanged(beforeXml, afterXml)) {
                return
            }
            OmniLog.w(
                TAG,
                "accessibility scroll had no visible effect, fallback to privileged swipe: direction=$direction"
            )
        } else {
            OmniLog.w(
                TAG,
                "accessibility scroll failed, fallback to privileged swipe: ${scrollError.message}"
            )
        }
        if (!tryPrivilegedSwipeUntilChanged(beforeXml, endpoints.x1, endpoints.y1, endpoints.x2, endpoints.y2, durationMs)) {
            AccessibilityController.scrollCoordinate(x, y, direction, distance, durationMs)
        }
    }

    override suspend fun scrollWithContext(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
        targetDescription: String,
    ) {
        performScroll(
            x = x,
            y = y,
            direction = direction,
            distance = distance,
            durationMs = durationMs,
            targetDescription = targetDescription,
        )
    }

    override suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        targetDescription: String,
    ) {
        val beforeXml = currentXml().orEmpty()
        if (trySemanticSwipe(
                x1 = startX,
                y1 = startY,
                x2 = endX,
                y2 = endY,
                targetDescription = targetDescription,
            )
        ) {
            delay(PRIVILEGED_SWIPE_EFFECT_CHECK_DELAY_MS)
            return
        }
        val swipeError = runCatching {
            AccessibilityController.swipeCoordinate(startX, startY, endX, endY, durationMs)
        }.exceptionOrNull()
        if (swipeError == null) {
            delay(CLICK_EFFECT_CHECK_DELAY_MS)
            val afterXml = currentXml().orEmpty()
            if (xmlChanged(beforeXml, afterXml)) {
                return
            }
            OmniLog.w(
                TAG,
                "accessibility swipe had no visible effect, fallback to privileged swipe"
            )
        } else {
            OmniLog.w(
                TAG,
                "accessibility swipe failed, fallback to privileged swipe: ${swipeError.message}"
            )
        }
        if (!tryPrivilegedSwipeUntilChanged(beforeXml, startX, startY, endX, endY, durationMs)) {
            AccessibilityController.swipeCoordinate(startX, startY, endX, endY, durationMs)
        }
    }

    private suspend fun trySemanticSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        targetDescription: String,
    ): Boolean {
        val sliderHandled = runCatching {
            AccessibilityController.setSliderProgressFromGesture(
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                targetDescription = targetDescription,
            )
        }.getOrElse { error ->
            OmniLog.w(TAG, "semantic slider gesture failed: ${error.message}")
            false
        }
        if (sliderHandled) {
            return true
        }
        return runCatching {
            AccessibilityController.scrollScrollableNodeFromGesture(
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                targetDescription = targetDescription,
            )
        }.getOrElse { error ->
            OmniLog.w(TAG, "semantic scroll gesture failed: ${error.message}")
            false
        }
    }

    override suspend fun inputTextToFocusedNode(text: String) {
        AccessibilityController.inputTextToFocusedNode(text)
    }

    override suspend fun inputText(
        text: String,
        targetDescription: String,
        x: Float?,
        y: Float?,
        nodeResourceId: String,
    ) {
        try {
            AccessibilityController.inputTextToBestNode(
                text = text,
                targetDescription = targetDescription,
                x = x,
                y = y,
                nodeResourceId = nodeResourceId,
            )
        } catch (error: Exception) {
            if (x == null || y == null || !shouldFallbackInputTextByTyping(error)) {
                throw error
            }
            OmniLog.w(TAG, "accessibility inputText failed, fallback to typed shell input: ${error.message}")
            inputTextByTyping(
                text = text,
                targetDescription = targetDescription,
                x = x,
                y = y,
                nodeResourceId = nodeResourceId,
            )
        }
    }

    override suspend fun inputTextByTyping(
        text: String,
        targetDescription: String,
        x: Float?,
        y: Float?,
        nodeResourceId: String,
    ) {
        if (x != null && y != null) {
            click(x, y)
            delay(250)
        }
        if (tryPrivilegedInputText(text)) {
            return
        }
        inputTextViaShell(text)
    }

    override suspend fun launchApplication(packageName: String) {
        launchApplication(packageName = packageName, resetTask = false)
    }

    override suspend fun launchApplication(packageName: String, resetTask: Boolean) {
        if (!resetTask && AccessibilityController.initController()) {
            runCatching {
                AccessibilityController.launchApplication(packageName) { x, y ->
                    AccessibilityController.clickCoordinate(x, y)
                }
            }.onSuccess {
                return
            }.onFailure { error ->
                if (error is PrivacyBlockedException) throw error
                OmniLog.w(TAG, "accessibility launchApplication failed: ${error.message}")
            }
        }
        launchApplicationByForegroundIntent(packageName, resetTask)
    }

    override suspend fun pressHotKey(key: String) {
        AccessibilityController.pressHotKey(key)
    }

    override suspend fun hideKeyboard() {
        AccessibilityController.hideKeyboard()
        delay(250)
    }

    private suspend fun inputTextViaShell(text: String) = withContext(Dispatchers.IO) {
        val escapedText = text
            .replace("\\", "\\\\")
            .replace(" ", "%s")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("&", "\\&")
            .replace("<", "\\<")
            .replace(">", "\\>")
            .replace("|", "\\|")
            .replace(";", "\\;")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("\n", "%n")
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text '$escapedText'"))
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException("shell input text failed: exit_code=$exitCode")
        }
    }

    private suspend fun tryPrivilegedTap(x: Float, y: Float): Boolean {
        val privileged = runCatching {
            ShizukuCapabilityManager.get(BaseApplication.instance).tap(x, y)
        }.getOrNull()
        if (privileged?.success == true) {
            return true
        }
        if (privileged != null) {
            OmniLog.w(
                TAG,
                "privileged tap unavailable: code=${privileged.code} message=${privileged.message}"
            )
        }
        return false
    }

    private fun shouldFallbackInputTextByTyping(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("input text") ||
            message.contains("focused input") ||
            message.contains("editable input") ||
            message.contains("focused node") ||
            message.contains("action_set_text")
    }

    private suspend fun tryPrivilegedSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean {
        val privileged = runCatching {
            ShizukuCapabilityManager.get(BaseApplication.instance).swipe(x1, y1, x2, y2, durationMs)
        }.getOrNull()
        if (privileged?.success == true) {
            return true
        }
        if (privileged != null) {
            OmniLog.w(
                TAG,
                "privileged swipe unavailable: code=${privileged.code} message=${privileged.message}"
            )
        }
        return false
    }

    private suspend fun tryPrivilegedSwipeUntilChanged(
        beforeXml: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean {
        repeat(2) { attempt ->
            if (!tryPrivilegedSwipe(x1, y1, x2, y2, durationMs)) {
                return@repeat
            }
            delay(PRIVILEGED_SWIPE_EFFECT_CHECK_DELAY_MS)
            val afterXml = currentXml().orEmpty()
            if (xmlChanged(beforeXml, afterXml)) {
                return true
            }
            OmniLog.w(
                TAG,
                "privileged swipe had no visible effect: attempt=${attempt + 1} x1=${x1.toInt()} y1=${y1.toInt()} x2=${x2.toInt()} y2=${y2.toInt()}"
            )
        }
        return false
    }

    private suspend fun tryPrivilegedInputText(text: String): Boolean {
        val privileged = runCatching {
            ShizukuCapabilityManager.get(BaseApplication.instance).inputText(text)
        }.getOrNull()
        if (privileged?.success == true) {
            return true
        }
        if (privileged != null) {
            OmniLog.w(
                TAG,
                "privileged input text unavailable: code=${privileged.code} message=${privileged.message}"
            )
        }
        return false
    }

    private data class SwipeEndpoints(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )

    private fun scrollEndpoints(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
    ): SwipeEndpoints {
        val half = (distance / 2f).coerceAtLeast(1f)
        return when (direction) {
            ScrollDirection.UP -> SwipeEndpoints(x, y + half, x, y - half)
            ScrollDirection.DOWN -> SwipeEndpoints(x, y - half, x, y + half)
            ScrollDirection.LEFT -> SwipeEndpoints(x + half, y, x - half, y)
            ScrollDirection.RIGHT -> SwipeEndpoints(x - half, y, x + half, y)
        }
    }

    private fun xmlChanged(beforeXml: String, afterXml: String): Boolean {
        if (beforeXml.isBlank() || afterXml.isBlank()) return false
        if (beforeXml == afterXml) return false
        return beforeXml.hashCode() != afterXml.hashCode()
    }

    override fun currentXml(): String? =
        if (AccessibilityController.initController()) {
            AccessibilityController.getCaptureScreenShotXml(true)
        } else {
            null
        }

    override fun currentPackageName(): String? =
        if (AccessibilityController.initController()) {
            AccessibilityController.getPackageName()
        } else {
            null
        }

    override fun currentActivityName(): String? =
        if (AccessibilityController.initController()) {
            AccessibilityController.getCurrentActivity()
        } else {
            null
        }

    private suspend fun launchApplicationByForegroundIntent(
        packageName: String,
        resetTask: Boolean = false,
    ) {
        if (!APPPackageUtil.isPackageAuthorized(packageName)) {
            val appName = APPPackageUtil.getAppName(BaseApplication.instance, packageName)
                .takeIf { it.isNotBlank() }
                ?: packageName
            throw PrivacyBlockedException("应用 $appName 未授权，已被隐私设置限制")
        }
        val started = withContext(Dispatchers.Main) {
            val appContext = BaseApplication.instance
            val startContext = BaseApplication.foregroundActivity ?: appContext
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException(
                    "Application with package name $packageName not found"
                )
            if (resetTask) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } else {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            if (startContext !is Activity) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startContext.startActivity(launchIntent)
            true
        }
        if (started) {
            delay(800)
        }
    }
}
