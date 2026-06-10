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

    override fun isReady(): Boolean = AccessibilityController.initController()

    override suspend fun click(x: Float, y: Float) {
        try {
            AccessibilityController.clickCoordinate(x, y)
            return
        } catch (error: Exception) {
            OmniLog.w(
                TAG,
                "accessibility coordinate click failed, fallback to privileged tap: ${error.message}"
            )
        }
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
        if (nodeResourceId.isNotBlank()) {
            val beforeXml = currentXml().orEmpty()
            runCatching {
                AccessibilityController.clickNodeById(nodeResourceId, targetDescription)
            }.onSuccess {
                delay(180)
                val afterXml = currentXml().orEmpty()
                if (xmlChanged(beforeXml, afterXml)) {
                    return
                }
                OmniLog.w(
                    TAG,
                    "accessibility node click had no visible effect, fallback to tap: node=$nodeResourceId"
                )
            }.onFailure { error ->
                OmniLog.w(
                    TAG,
                    "accessibility node click failed, fallback to tap: node=$nodeResourceId message=${error.message}"
                )
            }
        }
        // Replay click coordinates are already transferred to the live page.
        click(x, y)
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) {
        AccessibilityController.longClickCoordinate(x, y, durationMs)
    }

    override suspend fun scroll(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
    ) {
        AccessibilityController.scrollCoordinate(x, y, direction, distance, durationMs)
    }

    override suspend fun scrollWithContext(
        x: Float,
        y: Float,
        direction: ScrollDirection,
        distance: Float,
        durationMs: Long,
        targetDescription: String,
    ) {
        scroll(x, y, direction, distance, durationMs)
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

    private fun shouldFallbackInputTextByTyping(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("input text") ||
            message.contains("focused input") ||
            message.contains("editable input") ||
            message.contains("focused node") ||
            message.contains("action_set_text")
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
