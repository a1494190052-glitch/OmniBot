package cn.com.omnimind.androidgui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class AndroidGuiActionResult(
    val success: Boolean,
    val message: String,
    val diagnostics: Map<String, String> = emptyMap(),
)

data class AndroidGuiScreenSnapshot(
    val packageName: String,
    val activityName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val screenshotJpeg: ByteArray?,
)

enum class AndroidGuiAccessibilityStatus {
    DISABLED,
    CONNECTING,
    READY,
}

class AndroidGuiEnvironment internal constructor(
    private val appContext: Context?,
    private val platform: AndroidGuiPlatform,
) {
    constructor(context: Context) : this(
        appContext = context.applicationContext ?: context,
        platform = AccessibilityAndroidGuiPlatform(context.applicationContext ?: context),
    )

    fun accessibilityStatus(): AndroidGuiAccessibilityStatus = when {
        !platform.isAccessibilityEnabled() -> AndroidGuiAccessibilityStatus.DISABLED
        platform.isReady() -> AndroidGuiAccessibilityStatus.READY
        else -> AndroidGuiAccessibilityStatus.CONNECTING
    }

    fun isAccessibilityEnabled(): Boolean =
        accessibilityStatus() != AndroidGuiAccessibilityStatus.DISABLED

    fun isReady(): Boolean = accessibilityStatus() == AndroidGuiAccessibilityStatus.READY

    suspend fun awaitReady(timeoutMs: Long = 5_000L): Boolean {
        if (!isAccessibilityEnabled()) return false
        return withTimeoutOrNull(timeoutMs) {
            while (!platform.isReady()) delay(50L)
            true
        } ?: false
    }

    fun openAccessibilitySettings() {
        val context = checkNotNull(appContext) { "android_gui_context_required" }
        val component = ComponentName(context, AssistsService::class.java)
        val detailsIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val detailsActivity = detailsIntent.resolveActivityInfo(context.packageManager, 0)
        val detailsPermissionGranted = detailsActivity?.permission
            ?.let { permission ->
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
            ?: true
        val openedDetails = detailsActivity != null && detailsPermissionGranted &&
            runCatching { context.startActivity(detailsIntent) }.isSuccess
        if (!openedDetails) {
            context.startActivity(fallbackIntent)
        }
    }

    fun displaySize(): Pair<Int, Int> = platform.displaySize()

    fun screenshotExcludesOverlays(): Boolean = platform.screenshotExcludesOverlays()

    suspend fun observe(captureScreenshot: Boolean = true): State {
        check(awaitReady()) { "android_gui_accessibility_not_ready" }
        val context = checkNotNull(appContext) { "android_gui_context_required" }
        val observed = platform.observe(captureScreenshot)
        val state = State.create(
            packageName = observed.packageName,
            activityName = observed.activityName,
            displayWidth = observed.displayWidth,
            displayHeight = observed.displayHeight,
            xml = observed.xml,
        )
        return InternalRunLogStore.persistState(context, state, observed.screenshotJpeg)
    }

    /** Capture a transient preview without writing a RunLog state or image to disk. */
    suspend fun captureScreenSnapshot(): AndroidGuiScreenSnapshot {
        check(awaitReady()) { "android_gui_accessibility_not_ready" }
        val observed = platform.observe(captureScreenshot = true)
        return AndroidGuiScreenSnapshot(
            packageName = observed.packageName,
            activityName = observed.activityName,
            displayWidth = observed.displayWidth,
            displayHeight = observed.displayHeight,
            screenshotJpeg = observed.screenshotJpeg,
        )
    }

    suspend fun act(action: Action): AndroidGuiActionResult {
        if (!awaitReady()) {
            return AndroidGuiActionResult(
                success = false,
                message = "android_gui_accessibility_not_ready",
            )
        }
        return try {
            val result = platform.dispatch(canonicalForDisplay(action))
            if (!result.success) return result
            result.copy(
                diagnostics = result.diagnostics + mapOf(
                    "state_stabilization" to "runtime_delegated",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AndroidGuiActionResult(
                success = false,
                message = error.message ?: "android_gui_action_failed",
            )
        }
    }

    suspend fun inputTarget(x: Float? = null, y: Float? = null): AndroidGuiInputTarget? =
        if (awaitReady()) platform.inputTarget(x, y) else null

    suspend fun installedApplications(): Map<String, String> = platform.installedApplications()

    fun inputMethodTop(): Int? = runCatching(platform::inputMethodTop).getOrNull()

    private suspend fun canonicalForDisplay(action: Action): Action {
        if (action.tool !in OobActionSchema.coordinateToolNames) return action
        val display = platform.displaySize()
        val args = ActionCoordinateCodec.toScreenPixels(
            args = action.args,
            displaySize = ActionCoordinateCodec.DisplaySize(
                width = display.first.toDouble(),
                height = display.second.toDouble(),
            ),
        )
        return action.copy(args = args)
    }
}

private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
    "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
