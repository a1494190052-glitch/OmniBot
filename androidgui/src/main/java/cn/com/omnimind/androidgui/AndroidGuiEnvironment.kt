package cn.com.omnimind.androidgui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
        val component = ComponentName(context, AndroidGuiAccessibilityService::class.java)
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

    suspend fun observe(captureScreenshot: Boolean = true): State {
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
        return try {
            val result = platform.dispatch(canonicalForDisplay(action))
            if (!result.success) return result
            result.copy(diagnostics = result.diagnostics + awaitStateStabilization(action))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AndroidGuiActionResult(
                success = false,
                message = error.message ?: "android_gui_action_failed",
            )
        }
    }

    private suspend fun awaitStateStabilization(action: Action): Map<String, String> {
        return try {
            val expectedPackage = action.args["package_name"]
                ?.toString()
                ?.trim()
                .orEmpty()
                .takeIf { action.tool == OobActionSchema.TOOL_OPEN_APP && it.isNotEmpty() }
            delay(STATE_STABILIZATION_INITIAL_DELAY_MS)
            var previous = platform.observe(captureScreenshot = false)
            var observations = 1
            repeat(STATE_STABILIZATION_MAX_OBSERVATIONS - 1) {
                delay(STATE_STABILIZATION_POLL_DELAY_MS)
                val current = platform.observe(captureScreenshot = false)
                observations += 1
                if (
                    current.sameScreenAs(previous) &&
                    (expectedPackage == null || current.packageName == expectedPackage)
                ) {
                    return mapOf(
                        "state_stabilization" to "stable",
                        "state_stabilization_observations" to observations.toString(),
                    )
                }
                previous = current
            }
            mapOf(
                "state_stabilization" to "timeout",
                "state_stabilization_observations" to observations.toString(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mapOf(
                "state_stabilization" to "error",
                "state_stabilization_error" to
                    (error.message ?: "android_gui_state_stabilization_failed"),
            )
        }
    }

    suspend fun inputTarget(x: Float? = null, y: Float? = null): AndroidGuiInputTarget? =
        platform.inputTarget(x, y)

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

private fun AndroidGuiPlatformState.sameScreenAs(other: AndroidGuiPlatformState): Boolean =
    packageName == other.packageName &&
        activityName == other.activityName &&
        xml == other.xml

private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
    "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
private const val STATE_STABILIZATION_INITIAL_DELAY_MS = 250L
private const val STATE_STABILIZATION_POLL_DELAY_MS = 200L
private const val STATE_STABILIZATION_MAX_OBSERVATIONS = 5
