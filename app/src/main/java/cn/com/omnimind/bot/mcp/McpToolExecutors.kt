package cn.com.omnimind.bot.mcp
import cn.com.omnimind.bot.runlog.resolveActionName
import cn.com.omnimind.baselib.runlog.OobActionSchema

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.ActionExecutor
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.assists.task.vlmserver.UIContextManager
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.vlmserver.VLMIndexedPageContext
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.ImageQuality
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionRun
import cn.com.omnimind.bot.vlm.VlmToolCoordinator
import cn.com.omnimind.bot.vlm.VlmToolOutcome
import cn.com.omnimind.bot.vlm.VlmToolOutcomeStatus
import cn.com.omnimind.bot.omniflow.function.OmniFlowFunctionService
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.webchat.AgentRunRequestNormalizer
import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * MCP 工具执行器
 */
object McpToolExecutors {
    private const val TAG = "[McpToolExecutors]"
    private const val XML_STABILITY_DEFAULT_ATTEMPTS = 5
    private const val XML_STABILITY_MAX_ATTEMPTS_LIMIT = 8
    private const val XML_STABILITY_DEFAULT_INTERVAL_MS = 250L
    private const val XML_STABILITY_MIN_INTERVAL_MS = 50L
    private const val XML_STABILITY_MAX_INTERVAL_MS = 1_000L
    private const val XML_STABILITY_BOUNDS_TOLERANCE_PX = 8
    private const val XML_STABILITY_NODE_DELTA_RATIO = 0.05f
    private const val XML_DISPLAY_BOUNDS_MIN_TOLERANCE_PX = 80
    private val XML_BOUNDS_REGEX = Regex("""bounds="\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]"""")
    private val XML_PACKAGE_REGEX = Regex("""package="([^"]+)"""")
    private fun brandName(): String = AppLocaleManager.brandName()

    private data class NormalizedActRequest(
        val tool: String,
        val args: Map<String, Any?>,
        val sourceActionType: String,
    )
    
    /**
     * 执行 VLM 任务（阻塞等待完成）
     */
    suspend fun executeVlmTask(
        context: Context,
        args: Map<String, Any?>?,
        scope: CoroutineScope
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val requestArgs = args ?: emptyMap()
        val goal = requestArgs["goal"] as? String
        if (goal.isNullOrBlank()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing goal")
        }

        val needSummaryArg = requestArgs["needSummary"] as? Boolean
        val shouldSummary = shouldEnableSummary(goal, needSummaryArg)
        val startFromCurrent = boolArg(requestArgs, "startFromCurrent", "start_from_current", "skipGoHome", "skip_go_home")
        val parseOnly = boolArg(requestArgs, "parseOnly", "parse_only", "dryRun", "dry_run")
        val disableOmniFlowRecall = boolArgOrDefault(
            requestArgs,
            default = false,
            "disableOmniFlowRecall",
            "disable_omniflow_recall",
            "disableRecall",
            "disable_recall",
        )
        val request = VlmTaskRequest(
            goal = goal,
            model = requestArgs["model"] as? String,
            maxSteps = intArg(requestArgs, "maxSteps", "max_steps"),
            waitTimeoutMs = waitTimeoutMsArg(requestArgs),
            packageName = if (startFromCurrent) null else firstString(requestArgs, "packageName", "package_name"),
            needSummary = shouldSummary,
            skipGoHome = startFromCurrent,
            disableOmniFlowRecall = disableOmniFlowRecall,
        )

        try {
            if (parseOnly) {
                if (!AssistsUtil.Core.isAccessibilityServiceEnabled()) {
                    return@withContext McpResponseBuilder.buildErrorText("Accessibility service is not enabled")
                }
                return@withContext VlmToolCoordinator.parseOnlyNextAction(
                    context = context,
                    request = request,
                    scope = scope
                ).toPayload()
            }
            val outcome = VlmToolCoordinator.executeNewTask(
                context = context,
                request = request,
                scope = scope
            )
            return@withContext outcomeToMcpResponse(outcome)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error executing VLM task: ${e.message}")
            return@withContext McpResponseBuilder.buildErrorText("VLM task failed: ${e.message}")
        }
    }

    /**
     * Captures the current phone state through OOB's on-device Accessibility runtime.
     *
     * This is intentionally read-only and avoids host-side adb dump/screencap latency.
     */
    suspend fun executeGetState(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!AssistsUtil.Core.isAccessibilityServiceEnabled()) {
            return@withContext McpResponseBuilder.buildErrorText("Accessibility service is not enabled")
        }

        val includeXml = boolArgOrDefault(args, true, "include_xml", "includeXml")
        val includeScreenshot = boolArgOrDefault(args, true, "include_screenshot", "includeScreenshot")
        val includeIndexedContext = boolArgOrDefault(
            args,
            true,
            "include_indexed_context",
            "includeIndexedContext",
            "include_indexed_page_evidence",
            "includeIndexedPageEvidence"
        )
        val includeMarkedScreenshot = boolArgOrDefault(
            args,
            false,
            "include_marked_screenshot",
            "includeMarkedScreenshot"
        )
        val includeImageContent = boolArgOrDefault(
            args,
            false,
            "include_image_content",
            "includeImageContent"
        )
        val filterOverlay = boolArgOrDefault(args, true, "filter_overlay", "filterOverlay")
        val maxXmlChars = intArg(args, "max_xml_chars", "maxXmlChars") ?: 0
        val imageQuality = imageQualityArg(args)

        val capturedAtMs = System.currentTimeMillis()
        val packageName = runCatching { AccessibilityController.getPackageName().orEmpty() }
            .getOrDefault("")
        val activityName = runCatching { AccessibilityController.getCurrentActivity().orEmpty() }
            .getOrDefault("")

        var xmlStability: StableXmlCapture? = null
        val xmlCapture = if (includeXml || includeIndexedContext || includeMarkedScreenshot) {
            runCatching {
                captureStableXml(context, args).also { xmlStability = it }.xml
            }
        } else {
            Result.success("")
        }
        val rawXml = xmlCapture.getOrDefault("")
        val returnedXml = truncateXml(rawXml, maxXmlChars)

        val screenshotCapture = if (includeScreenshot || includeMarkedScreenshot || includeImageContent) {
            runCatching {
                AccessibilityController.captureScreenshotImage(
                    isBitmap = false,
                    isBase64 = true,
                    isFile = false,
                    isFilterOverlay = filterOverlay,
                    compressQuality = imageQuality
                )
            }
        } else {
            null
        }
        val screenshotPayload = screenshotCapture?.getOrNull()
        val screenshotDataUri = screenshotPayload
            ?.imageBase64
            ?.takeIf { screenshotPayload.isSuccess && it.isNotBlank() }
            ?.let(::ensureJpegDataUri)

        val displayWidth = maxOf(
            screenshotPayload?.originalWidth ?: 0,
            context.resources.displayMetrics.widthPixels
        ).coerceAtLeast(1)
        val displayHeight = maxOf(
            screenshotPayload?.originalHeight ?: 0,
            context.resources.displayMetrics.heightPixels
        ).coerceAtLeast(1)
        val indexedEvidence = if (includeIndexedContext && rawXml.isNotBlank()) {
            runCatching {
                VLMIndexedPageContext.render(
                    currentXml = rawXml,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
            }.getOrDefault("")
        } else {
            ""
        }
        val markedScreenshot = if (includeMarkedScreenshot && screenshotDataUri != null && rawXml.isNotBlank()) {
            runCatching {
                VLMIndexedPageContext.renderMarkedScreenshot(
                    screenshotBase64 = screenshotDataUri,
                    currentXml = rawXml,
                    displayWidth = displayWidth,
                    displayHeight = displayHeight
                )
            }.getOrNull()
        } else {
            null
        }

        val text = buildString {
            appendLine("get_state captured current device state.")
            appendLine("package_name: ${packageName.ifBlank { "unknown" }}")
            appendLine("activity_name: ${activityName.ifBlank { "unknown" }}")
            appendLine("xml_chars: ${rawXml.length}")
            appendLine("xml_node_count: ${xmlNodeCount(rawXml)}")
            xmlStability?.let {
                appendLine("xml_stable: ${it.stable}")
                appendLine("xml_capture_attempts: ${it.attempts}")
                appendLine("xml_stability_reason: ${it.reason}")
            }
            appendLine("screenshot_present: ${screenshotDataUri != null}")
            if (screenshotPayload != null) {
                appendLine("screenshot_size: ${screenshotPayload.compressedWidth}x${screenshotPayload.compressedHeight}")
                appendLine("screenshot_original_size: ${screenshotPayload.originalWidth}x${screenshotPayload.originalHeight}")
            }
            if (indexedEvidence.isNotBlank()) {
                appendLine("indexed_page_evidence: present")
            }
            if (xmlCapture.isFailure) {
                appendLine("xml_error: ${xmlCapture.exceptionOrNull()?.message.orEmpty()}")
            }
            if (screenshotCapture?.isFailure == true) {
                appendLine("screenshot_error: ${screenshotCapture.exceptionOrNull()?.message.orEmpty()}")
            }
        }.trim()

        val content = mutableListOf<Map<String, Any?>>(
            mapOf("type" to "text", "text" to text)
        )
        if (includeImageContent && screenshotDataUri != null) {
            content += mapOf(
                "type" to "image",
                "data" to jpegBase64Payload(screenshotDataUri),
                "mimeType" to "image/jpeg"
            )
        }

        return@withContext linkedMapOf<String, Any?>(
            "content" to content,
            "success" to true,
            "schema_version" to "oob.get_state.v1",
            "captured_at_ms" to capturedAtMs,
            "package_name" to packageName,
            "activity_name" to activityName,
            "xml" to returnedXml.takeIf { includeXml },
            "xml_chars" to rawXml.length,
            "xml_node_count" to xmlNodeCount(rawXml),
            "xml_truncated" to (includeXml && returnedXml.length < rawXml.length),
            "xml_error" to (xmlCapture.exceptionOrNull()?.message ?: xmlStability?.errorMessage),
            "xml_stable" to xmlStability?.stable,
            "xml_capture_attempts" to xmlStability?.attempts,
            "xml_stability_reason" to xmlStability?.reason,
            "xml_stability_wait_ms" to xmlStability?.waitMs,
            "xml_display_width" to xmlStability?.displayWidth,
            "xml_display_height" to xmlStability?.displayHeight,
            "xml_max_right" to xmlStability?.maxRight,
            "xml_max_bottom" to xmlStability?.maxBottom,
            "indexed_page_evidence" to indexedEvidence.takeIf { it.isNotBlank() },
            "screenshot" to screenshotPayload?.let { payload ->
                linkedMapOf<String, Any?>(
                    "present" to (screenshotDataUri != null),
                    "mime_type" to "image/jpeg",
                    "data_uri" to screenshotDataUri,
                    "original_width" to payload.originalWidth,
                    "original_height" to payload.originalHeight,
                    "width" to payload.compressedWidth,
                    "height" to payload.compressedHeight,
                    "applied_scale" to payload.appliedScale,
                    "filter_overlay" to payload.isFilterOverlay,
                    "quality" to imageQuality.name.lowercase(),
                    "is_lot_of_single_color" to payload.isLotOfSingleColor,
                    "is_mostly_light_background" to payload.isMostlyLightBackground,
                    "is_side_region_mostly_single_color" to payload.isSideRegionMostlySingleColor,
                )
            },
            "screenshot_error" to screenshotCapture?.exceptionOrNull()?.message,
            "marked_screenshot" to markedScreenshot?.let {
                linkedMapOf(
                    "mime_type" to "image/jpeg",
                    "data_uri" to it
                )
            },
            "source" to "oob_accessibility_runtime"
        ).filterValues { it != null }
    }

    suspend fun executeAct(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val request = args ?: emptyMap()
        val normalized = normalizeActRequest(context, request)
        val startedAtMs = System.currentTimeMillis()
        val settleDelayMs = (longArg(request, "settle_delay_ms", "settleDelayMs") ?: 1_000L)
            .coerceIn(0L, 10_000L)
        ActionExecutor(
            AndroidDeviceOperator(null, context),
            UIContextManager(),
        ).act(
            action = normalized.tool,
            args = normalized.args,
            source = "mcp_act",
            diagnostics = mapOf("source_action_type" to normalized.sourceActionType),
        )
        if (settleDelayMs > 0L && normalized.tool != "finished") {
            delay(settleDelayMs)
        }
        val packageName = runCatching { AccessibilityController.getPackageName().orEmpty() }
            .getOrDefault("")
        val activityName = runCatching { AccessibilityController.getCurrentActivity().orEmpty() }
            .getOrDefault("")
        linkedMapOf<String, Any?>(
            "content" to listOf(
                mapOf(
                    "type" to "text",
                    "text" to "act executed ${normalized.tool}"
                )
            ),
            "success" to true,
            "schema_version" to "oob.act.v1",
            "source" to "oob_accessibility_runtime",
            "source_action_type" to normalized.sourceActionType,
            "tool" to normalized.tool,
            "args" to normalized.args,
            "settle_delay_ms" to settleDelayMs,
            "duration_ms" to (System.currentTimeMillis() - startedAtMs),
            "package_name" to packageName.takeIf { it.isNotBlank() },
            "activity_name" to activityName.takeIf { it.isNotBlank() },
        ).filterValues { it != null }
    }

    private data class StableXmlCapture(
        val xml: String,
        val stable: Boolean,
        val attempts: Int,
        val waitMs: Long,
        val reason: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val maxRight: Int,
        val maxBottom: Int,
        val errorMessage: String? = null,
    )

    private data class XmlSnapshot(
        val xml: String,
        val nodeCount: Int,
        val rootBounds: XmlBounds?,
        val maxRight: Int,
        val maxBottom: Int,
        val packageName: String,
        val withinDisplay: Boolean,
        val errorMessage: String? = null,
    )

    private data class XmlBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private suspend fun captureStableXml(
        context: Context,
        args: Map<String, Any?>?,
    ): StableXmlCapture {
        val stableEnabled = boolArgOrDefault(
            args,
            true,
            "stable_xml",
            "stableXml",
            "wait_xml_stable",
            "waitXmlStable",
        )
        val maxAttempts = intArg(args, "xml_stability_attempts", "xmlStabilityAttempts")
            ?.coerceIn(1, XML_STABILITY_MAX_ATTEMPTS_LIMIT)
            ?: XML_STABILITY_DEFAULT_ATTEMPTS
        val intervalMs = longArg(args, "xml_stability_interval_ms", "xmlStabilityIntervalMs")
            ?.coerceIn(XML_STABILITY_MIN_INTERVAL_MS, XML_STABILITY_MAX_INTERVAL_MS)
            ?: XML_STABILITY_DEFAULT_INTERVAL_MS
        val (displayWidth, displayHeight) = currentDisplaySize(context)
        val startedAtMs = System.currentTimeMillis()

        if (!stableEnabled || maxAttempts <= 1) {
            val snapshot = captureXmlSnapshot(displayWidth, displayHeight)
            return stableXmlResult(
                snapshot = snapshot,
                stable = stableEnabled,
                attempts = 1,
                startedAtMs = startedAtMs,
                reason = if (stableEnabled) "single_capture" else "stable_xml_disabled",
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }

        var previousValid: XmlSnapshot? = null
        var lastSnapshot: XmlSnapshot? = null
        var lastValid: XmlSnapshot? = null

        repeat(maxAttempts) { index ->
            val snapshot = captureXmlSnapshot(displayWidth, displayHeight)
            lastSnapshot = snapshot
            if (snapshot.xml.isNotBlank() && snapshot.withinDisplay) {
                val previous = previousValid
                if (previous != null && snapshotsStable(previous, snapshot)) {
                    return stableXmlResult(
                        snapshot = snapshot,
                        stable = true,
                        attempts = index + 1,
                        startedAtMs = startedAtMs,
                        reason = "consecutive_stable_xml",
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                    )
                }
                previousValid = snapshot
                lastValid = snapshot
            } else {
                previousValid = null
            }
            if (index < maxAttempts - 1) {
                delay(intervalMs)
            }
        }

        val fallback = lastValid ?: lastSnapshot ?: XmlSnapshot(
            xml = "",
            nodeCount = 0,
            rootBounds = null,
            maxRight = 0,
            maxBottom = 0,
            packageName = "",
            withinDisplay = false,
            errorMessage = "xml_capture_unavailable",
        )
        val reason = when {
            lastValid != null -> "timeout_using_last_display_valid_xml"
            fallback.errorMessage != null -> "timeout_with_capture_error"
            fallback.xml.isBlank() -> "timeout_with_empty_xml"
            !fallback.withinDisplay -> "timeout_with_out_of_display_xml"
            else -> "timeout_using_last_xml"
        }
        return stableXmlResult(
            snapshot = fallback,
            stable = false,
            attempts = maxAttempts,
            startedAtMs = startedAtMs,
            reason = reason,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
        )
    }

    private fun stableXmlResult(
        snapshot: XmlSnapshot,
        stable: Boolean,
        attempts: Int,
        startedAtMs: Long,
        reason: String,
        displayWidth: Int,
        displayHeight: Int,
    ): StableXmlCapture =
        StableXmlCapture(
            xml = snapshot.xml,
            stable = stable,
            attempts = attempts,
            waitMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L),
            reason = reason,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            maxRight = snapshot.maxRight,
            maxBottom = snapshot.maxBottom,
            errorMessage = snapshot.errorMessage,
        )

    private fun captureXmlSnapshot(displayWidth: Int, displayHeight: Int): XmlSnapshot {
        var errorMessage: String? = null
        val xml = runCatching {
            AccessibilityController.getCaptureScreenShotXml(true).orEmpty()
        }.getOrElse { error ->
            errorMessage = error.message.orEmpty()
            ""
        }
        return xmlSnapshot(xml, displayWidth, displayHeight, errorMessage)
    }

    private fun xmlSnapshot(
        xml: String,
        displayWidth: Int,
        displayHeight: Int,
        errorMessage: String?,
    ): XmlSnapshot {
        val bounds = XML_BOUNDS_REGEX.findAll(xml)
            .mapNotNull { match ->
                val left = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val top = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val right = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                val bottom = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                XmlBounds(left, top, right, bottom)
            }
            .toList()
        val rootBounds = bounds.firstOrNull()
        val maxRight = bounds.maxOfOrNull { it.right } ?: 0
        val maxBottom = bounds.maxOfOrNull { it.bottom } ?: 0
        return XmlSnapshot(
            xml = xml,
            nodeCount = xmlNodeCount(xml),
            rootBounds = rootBounds,
            maxRight = maxRight,
            maxBottom = maxBottom,
            packageName = XML_PACKAGE_REGEX.find(xml)?.groupValues?.getOrNull(1).orEmpty(),
            withinDisplay = xml.isNotBlank() &&
                rootBounds != null &&
                xmlBoundsWithinDisplay(rootBounds, maxRight, maxBottom, displayWidth, displayHeight),
            errorMessage = errorMessage,
        )
    }

    private fun snapshotsStable(previous: XmlSnapshot, current: XmlSnapshot): Boolean {
        if (previous.nodeCount <= 0 || current.nodeCount <= 0) return false
        val previousRoot = previous.rootBounds ?: return false
        val currentRoot = current.rootBounds ?: return false
        if (previous.packageName.isNotBlank() &&
            current.packageName.isNotBlank() &&
            previous.packageName != current.packageName
        ) {
            return false
        }
        if (!boundsSimilar(previousRoot, currentRoot, XML_STABILITY_BOUNDS_TOLERANCE_PX)) {
            return false
        }
        if (abs(previous.maxRight - current.maxRight) > XML_STABILITY_BOUNDS_TOLERANCE_PX) {
            return false
        }
        if (abs(previous.maxBottom - current.maxBottom) > XML_STABILITY_BOUNDS_TOLERANCE_PX) {
            return false
        }
        val nodeBase = max(previous.nodeCount, current.nodeCount).coerceAtLeast(1)
        val nodeDeltaRatio = abs(previous.nodeCount - current.nodeCount).toFloat() / nodeBase
        return nodeDeltaRatio <= XML_STABILITY_NODE_DELTA_RATIO
    }

    private fun boundsSimilar(first: XmlBounds, second: XmlBounds, tolerancePx: Int): Boolean =
        abs(first.left - second.left) <= tolerancePx &&
            abs(first.top - second.top) <= tolerancePx &&
            abs(first.right - second.right) <= tolerancePx &&
            abs(first.bottom - second.bottom) <= tolerancePx

    private fun xmlBoundsWithinDisplay(
        rootBounds: XmlBounds,
        maxRight: Int,
        maxBottom: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Boolean {
        val widthTolerance = max(XML_DISPLAY_BOUNDS_MIN_TOLERANCE_PX, displayWidth / 10)
        val heightTolerance = max(XML_DISPLAY_BOUNDS_MIN_TOLERANCE_PX, displayHeight / 10)
        val rightLimit = displayWidth + widthTolerance
        val bottomLimit = displayHeight + heightTolerance
        return rootBounds.left >= -widthTolerance &&
            rootBounds.top >= -heightTolerance &&
            rootBounds.right <= rightLimit &&
            rootBounds.bottom <= bottomLimit &&
            maxRight <= rightLimit &&
            maxBottom <= bottomLimit
    }

    private fun currentDisplaySize(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics().apply { setTo(context.resources.displayMetrics) }
        runCatching {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            @Suppress("DEPRECATION")
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        }
        return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
    }

    private fun normalizeActRequest(
        context: Context,
        request: Map<String, Any?>,
    ): NormalizedActRequest {
        val actionPayload = mapArg(request["action"]).ifEmpty { request }
        val nestedArgs = mapArg(actionPayload["args"])
            .ifEmpty { mapArg(actionPayload["params"]) }
            .ifEmpty { mapArg(request["args"]) }
            .ifEmpty { mapArg(request["params"]) }
        val rawType = firstNonBlank(
            actionPayload["tool"],
            actionPayload["name"],
            actionPayload["action_type"],
            actionPayload["type"],
            request["tool"],
            request["action_type"],
            request["type"],
        ).trim().lowercase()
        if (rawType.isBlank()) {
            throw IllegalArgumentException("act requires action.tool or action_type")
        }
        val tool = when (rawType) {
            "click", "tap", "double_tap" -> OobActionSchema.TOOL_CLICK
            "long_press", "long-click", "long_click", "longpress" -> OobActionSchema.TOOL_LONG_PRESS
            "input_text", "type", "text" -> OobActionSchema.TOOL_INPUT_TEXT
            "scroll", "swipe" -> OobActionSchema.TOOL_SWIPE
            "open_app" -> OobActionSchema.TOOL_OPEN_APP
            "navigate_back", "press_back" -> OobActionSchema.TOOL_PRESS_KEY
            "navigate_home", "press_home" -> OobActionSchema.TOOL_PRESS_KEY
            "keyboard_enter", "press_enter" -> OobActionSchema.TOOL_PRESS_KEY
            "press_key" -> OobActionSchema.TOOL_PRESS_KEY
            "status", "answer", "finished", "finish" -> OobActionSchema.TOOL_FINISHED
            "wait" -> OobActionSchema.TOOL_WAIT
            else -> resolveActionName(rawType)
                ?: throw IllegalArgumentException("Unsupported act action_type: $rawType")
        }

        val args = LinkedHashMap<String, Any?>()
        args.putAll(nestedArgs)
        for (key in ANDROIDWORLD_ACTION_ARG_KEYS) {
            if (!args.containsKey(key) && actionPayload.containsKey(key)) {
                args[key] = actionPayload[key]
            }
            if (!args.containsKey(key) && request.containsKey(key)) {
                args[key] = request[key]
            }
        }
        when (rawType) {
            "navigate_back", "press_back" -> args["key"] = "back"
            "navigate_home", "press_home" -> args["key"] = "home"
            "keyboard_enter", "press_enter" -> args["key"] = "enter"
        }
        if (tool == OobActionSchema.TOOL_OPEN_APP && !args.containsKey("package_name")) {
            args["package_name"] = firstNonBlank(args["app_name"], actionPayload["app_name"], request["app_name"])
        }
        if (tool == OobActionSchema.TOOL_FINISHED && !args.containsKey("content")) {
            args["content"] = firstNonBlank(args["goal_status"], args["text"], actionPayload["goal_status"])
        }
        if (tool == OobActionSchema.TOOL_SWIPE && rawType == "scroll") {
            fillDefaultScrollArgs(context, args)
        }
        decodeRelativeCoordinatesIfRequested(context, request, actionPayload, args)
        return NormalizedActRequest(
            tool = tool,
            args = args.filterValues { it != null },
            sourceActionType = rawType,
        )
    }

    private fun fillDefaultScrollArgs(context: Context, args: MutableMap<String, Any?>) {
        val (displayWidth, displayHeight) = currentDisplaySize(context)
        val direction = firstNonBlank(args["direction"]).lowercase().ifBlank { "down" }
        val centerX = displayWidth / 2f
        val centerY = displayHeight / 2f
        val distance = (minOf(displayWidth, displayHeight) * 0.55f).coerceAtLeast(240f)
        args["direction"] = direction
        args.putIfAbsent("x", centerX)
        args.putIfAbsent("y", centerY)
        args.putIfAbsent("distance", distance)
        args.putIfAbsent("duration_ms", 300L)
    }


    private fun decodeRelativeCoordinatesIfRequested(
        context: Context,
        request: Map<String, Any?>,
        actionPayload: Map<String, Any?>,
        args: MutableMap<String, Any?>,
    ) {
        val coordinateSpace = firstNonBlank(
            args["coordinate_space"],
            actionPayload["coordinate_space"],
            request["coordinate_space"],
            actionPayload["coordinateSpace"],
            request["coordinateSpace"],
        ).trim().lowercase()
        if (coordinateSpace != "relative_0_1000") return
        val (displayWidth, displayHeight) = currentDisplaySize(context)
        for (key in listOf("x", "x1", "x2")) {
            val value = numericArg(args, key) ?: continue
            args[key] = (value.coerceIn(0.0, 1000.0) / 1000.0) * displayWidth
        }
        for (key in listOf("y", "y1", "y2")) {
            val value = numericArg(args, key) ?: continue
            args[key] = (value.coerceIn(0.0, 1000.0) / 1000.0) * displayHeight
        }
    }

    private fun numericArg(args: Map<String, Any?>, key: String): Double? {
        val raw = args[key] ?: return null
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> raw.toString().trim().toDoubleOrNull()
        }
    }

    private val ANDROIDWORLD_ACTION_ARG_KEYS = setOf(
        "x", "y", "x1", "y1", "x2", "y2",
        "index", "node_id", "node_resource_id",
        "text", "direction", "app_name", "package_name", "goal_status",
        "key", "keycode", "clear_text", "target_description",
        "duration_ms", "time_ms", "time_s", "distance", "coordinate_space",
    )

    private fun firstString(args: Map<String, Any?>?, vararg keys: String): String? {
        if (args == null) return null
        return keys.asSequence()
            .mapNotNull { key -> args[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
    }

    private fun intArg(args: Map<String, Any?>?, vararg keys: String): Int? {
        if (args == null) return null
        for (key in keys) {
            val raw = args[key] ?: continue
            val parsed = when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: raw.trim().toDoubleOrNull()?.toInt()
                else -> raw.toString().trim().toIntOrNull()
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun waitTimeoutMsArg(args: Map<String, Any?>?): Long? {
        val explicitMs = longArg(args, "waitTimeoutMs", "wait_timeout_ms", "timeoutMs", "timeout_ms")
        if (explicitMs != null) return explicitMs
        return longArg(args, "timeoutSeconds", "timeout_seconds")?.times(1000L)
    }

    private fun longArg(args: Map<String, Any?>?, vararg keys: String): Long? {
        if (args == null) return null
        for (key in keys) {
            val raw = args[key] ?: continue
            val parsed = when (raw) {
                is Number -> raw.toLong()
                is String -> raw.trim().toLongOrNull() ?: raw.trim().toDoubleOrNull()?.toLong()
                else -> raw.toString().trim().toLongOrNull()
            }
            if (parsed != null) return parsed
        }
        return null
    }

    private fun boolArg(args: Map<String, Any?>?, vararg keys: String): Boolean {
        if (args == null) return false
        for (key in keys) {
            val raw = args[key] ?: continue
            when (raw) {
                is Boolean -> return raw
                is String -> raw.trim().toBooleanStrictOrNull()?.let { return it }
                is Number -> return raw.toInt() != 0
            }
        }
        return false
    }

    private fun boolArgOrDefault(
        args: Map<String, Any?>?,
        default: Boolean,
        vararg keys: String
    ): Boolean {
        if (args == null) return default
        for (key in keys) {
            val raw = args[key] ?: continue
            when (raw) {
                is Boolean -> return raw
                is String -> raw.trim().toBooleanStrictOrNull()?.let { return it }
                is Number -> return raw.toInt() != 0
            }
        }
        return default
    }

    private fun imageQualityArg(args: Map<String, Any?>?): ImageQuality {
        val raw = firstString(args, "image_quality", "imageQuality", "quality")
            ?.uppercase()
            ?.replace("-", "_")
            ?: return ImageQuality.MEDIUM
        return ImageQuality.values().firstOrNull { it.name == raw } ?: ImageQuality.MEDIUM
    }

    private fun ensureJpegDataUri(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            trimmed
        } else {
            "data:image/jpeg;base64,$trimmed"
        }
    }

    private fun jpegBase64Payload(dataUri: String): String =
        dataUri
            .substringAfter("base64,", dataUri)
            .replace(Regex("\\s+"), "")

    private fun truncateXml(xml: String, maxXmlChars: Int): String {
        if (maxXmlChars <= 0 || xml.length <= maxXmlChars) return xml
        return xml.take(maxXmlChars)
    }

    private fun xmlNodeCount(xml: String): Int {
        if (xml.isBlank()) return 0
        return Regex("<node\\b").findAll(xml).count()
    }
    
    /**
     * 执行任务回复
     */
    suspend fun executeTaskReply(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val taskId = args?.get("taskId") as? String
        val reply = args?.get("reply") as? String
        
        if (taskId.isNullOrBlank() || reply.isNullOrBlank()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing taskId or reply")
        }
        
        val taskState = McpTaskManager.getTask(taskId)
            ?: return@withContext McpResponseBuilder.buildErrorText("Task not found: $taskId")
        
        if (taskState.status != TaskStatus.WAITING_INPUT) {
            return@withContext McpResponseBuilder.buildErrorText(
                "Task is not waiting for input. Current status: ${taskState.status}"
            )
        }
        
        OmniLog.d(TAG, "Sending reply to task $taskId: $reply")

        val success = AssistsUtil.Core.provideUserInputToVLMTask(reply)
        if (!success) {
            return@withContext McpResponseBuilder.buildErrorText("Failed to send reply to task")
        }
        
        // 更新状态并等待下一个状态变更
        taskState.status = TaskStatus.RUNNING
        taskState.waitingQuestion = null
        taskState.message = if (AppLocaleManager.isEnglish()) "Resuming execution" else "继续执行中"
        taskState.addChatMessage("User replied: $reply")
        taskState.markStateChanged()
        
        // 阻塞等待任务完成或再次需要输入
        val outcome = VlmToolCoordinator.waitForTask(taskId, taskState.goal)
        return@withContext outcomeToMcpResponse(outcome)
    }
    
    /**
     * 执行任务状态查询
     */
    fun executeTaskStatus(args: Map<String, Any?>?): Map<String, Any?> {
        val taskId = args?.get("taskId") as? String
        
        if (taskId.isNullOrBlank()) {
            return McpResponseBuilder.buildErrorText("Missing taskId")
        }
        
        val state = McpTaskManager.getTask(taskId)
            ?: return McpResponseBuilder.buildErrorText("Task not found: $taskId")
        
        return McpResponseBuilder.buildTaskStatusResponse(state)
    }
    
    /**
     * 执行等待屏幕解锁
     */
    suspend fun executeTaskWaitUnlock(
        context: Context,
        args: Map<String, Any?>?,
        scope: CoroutineScope
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val taskId = args?.get("taskId") as? String
        
        if (taskId.isNullOrBlank()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing taskId")
        }
        
        val taskState = McpTaskManager.getTask(taskId)
            ?: return@withContext McpResponseBuilder.buildErrorText("Task not found: $taskId")
        
        if (taskState.status != TaskStatus.SCREEN_LOCKED) {
            // 如果已经不是锁屏状态，直接返回当前状态
            return@withContext when (taskState.status) {
                TaskStatus.FINISHED -> McpResponseBuilder.buildFinishedResponse(taskState)
                TaskStatus.ERROR -> McpResponseBuilder.buildErrorResponse(taskState)
                TaskStatus.WAITING_INPUT -> McpResponseBuilder.buildWaitingInputResponse(taskState)
                TaskStatus.RUNNING -> outcomeToMcpResponse(
                    VlmToolCoordinator.waitForTask(taskId, taskState.goal)
                )
                else -> McpResponseBuilder.buildTextResponse("Task status: ${taskState.status}")
            }
        }
        
        OmniLog.d(TAG, "Waiting for screen unlock for task $taskId")

        val outcome = VlmToolCoordinator.resumeAfterUnlock(
            context = context,
            taskId = taskId,
            taskState = taskState,
            scope = scope
        )
        return@withContext if (outcome.status == VlmToolOutcomeStatus.TIMEOUT) {
            McpResponseBuilder.buildUnlockTimeoutResponse(taskId, taskState.goal)
        } else {
            outcomeToMcpResponse(outcome)
        }
    }

    /**
     * 执行文件传输工具
     */
    suspend fun executeFileTransfer(
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val action = (args?.get("action") as? String)?.trim()?.lowercase() ?: "latest"
        val fileId = args?.get("fileId") as? String
        val afterFileId = args?.get("afterFileId") as? String
        val limit = (args?.get("limit") as? Number)?.toInt()
        val timeoutMs = (args?.get("timeoutMs") as? Number)?.toLong()
            ?.coerceIn(1_000L, McpTaskManager.MAX_WAIT_TIME_MS)
            ?: McpTaskManager.MAX_WAIT_TIME_MS

        when (action) {
            "latest" -> {
                val record = McpFileInbox.latest()
                    ?: return@withContext McpResponseBuilder.buildTextResponse(
                        "No files in inbox. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                    )
                return@withContext buildFileTransferResponse(record)
            }
            "get" -> {
                if (fileId.isNullOrBlank()) {
                    return@withContext McpResponseBuilder.buildErrorText("Missing fileId")
                }
                val record = McpFileInbox.getFile(fileId)
                    ?: return@withContext McpResponseBuilder.buildErrorText("File not found: $fileId")
                return@withContext buildFileTransferResponse(record)
            }
            "list" -> {
                val records = McpFileInbox.list(limit)
                if (records.isEmpty()) {
                    return@withContext McpResponseBuilder.buildTextResponse(
                        "No files in inbox. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                    )
                }
                val itemsText = records.joinToString("\n") { record ->
                    "- id=${record.id}, name=${record.fileName}, size=${record.sizeBytes}, receivedAt=${record.createdAt}"
                }
                return@withContext mapOf(
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Received files:\n$itemsText"
                        )
                    ),
                    "files" to records.map { record ->
                        mapOf(
                            "id" to record.id,
                            "name" to record.fileName,
                            "mimeType" to record.mimeType,
                            "sizeBytes" to record.sizeBytes,
                            "receivedAt" to record.createdAt,
                        )
                    }
                )
            }
            "clear" -> {
                val cleared = if (!fileId.isNullOrBlank()) {
                    if (McpFileInbox.removeFile(fileId)) 1 else 0
                } else {
                    McpFileInbox.clearAll()
                }
                return@withContext McpResponseBuilder.buildTextResponse("Cleared $cleared file(s) from inbox.")
            }
            "wait" -> {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val record = McpFileInbox.latest()
                    if (record != null && (afterFileId == null || record.id != afterFileId)) {
                        return@withContext buildFileTransferResponse(record)
                    }
                    kotlinx.coroutines.delay(McpTaskManager.POLL_INTERVAL_MS)
                }
                return@withContext McpResponseBuilder.buildTextResponse(
                    "No file received within timeout. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                )
            }
            else -> {
                return@withContext McpResponseBuilder.buildErrorText("Unknown action: $action")
            }
        }
    }

    /**
     * Starts a normal in-app Agent run through the same service used by WebChat/Home.
     *
     * @param context Android application context used to access conversation storage and the Agent manager.
     * @param args MCP JSON arguments. `userMessage` is the natural-language request; `conversationId`
     * optionally reuses an existing conversation, while omitted or invalid ids create a fresh one.
     * @return MCP-compatible response with the accepted task id and conversation id. Completion must be
     * verified through Agent/WebChat events or the runtime files produced by the requested tools.
     */
    suspend fun executeAgentRun(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val requestArgs = args ?: emptyMap()
        val normalizedPayload = AgentRunRequestNormalizer.normalize(requestArgs)
        if (normalizedPayload.userMessage.isBlank() && normalizedPayload.attachments.isEmpty()) {
            return@withContext McpResponseBuilder.buildErrorText("Missing userMessage")
        }

        val appContext = context.applicationContext
        val conversationService = ConversationDomainService(appContext)
        val requestedMode = requestArgs["conversationMode"]?.toString()?.trim()?.ifEmpty { null }
            ?: "normal"
        val requestedConversationId = when (val raw = requestArgs["conversationId"]) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }?.takeIf { it > 0L }

        val conversation = if (requestedConversationId != null) {
            conversationService.getConversationPayload(requestedConversationId)
                ?: return@withContext McpResponseBuilder.buildErrorText(
                    "Conversation not found: $requestedConversationId"
                )
        } else {
            conversationService.createConversation(
                title = requestArgs["title"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "MCP Agent Run",
                mode = requestedMode
            )
        }
        val conversationId = (conversation["id"] as? Number)?.toLong()
            ?: conversation["id"]?.toString()?.toLongOrNull()
            ?: return@withContext McpResponseBuilder.buildErrorText("Conversation id is invalid")

        val runRequest = linkedMapOf<String, Any?>()
        runRequest.putAll(requestArgs)
        runRequest["conversationMode"] = requestedMode
        runRequest["userMessage"] = normalizedPayload.userMessage
        runRequest["attachments"] = normalizedPayload.attachments

        return@withContext runCatching {
            val accepted = AgentRunService(appContext).startConversationRun(
                conversationId = conversationId,
                request = runRequest
            )
            val taskId = accepted["taskId"]?.toString().orEmpty()
            val status = accepted["status"]?.toString().orEmpty().ifEmpty { "accepted" }
            val text = buildString {
                appendLine("Agent run accepted.")
                appendLine("")
                appendLine("Task ID: $taskId")
                appendLine("Conversation ID: $conversationId")
                appendLine("Status: $status")
                appendLine("")
                appendLine("This uses the normal in-app OOB Agent runtime. Verify completion through WebChat events or Project runtime files.")
            }
            linkedMapOf<String, Any?>(
                "content" to listOf(mapOf("type" to "text", "text" to text)),
                "taskId" to taskId,
                "status" to status,
                "conversationId" to conversationId,
                "conversation" to conversation
            )
        }.getOrElse { error ->
            McpResponseBuilder.buildErrorText("Agent run failed: ${error.message}")
        }
    }


    /**
     * Submits a generic OOB tool call request through the normal Agent runtime.
     *
     * @param context Android application context used by [executeAgentRun].
     * @param args MCP arguments containing `toolName`, optional `arguments`, and optional `goal`.
     * @return Accepted Agent task response. Completion is observed through existing Agent channels.
     */
    suspend fun executeOobToolCall(
        context: Context,
        args: Map<String, Any?>?
    ): Map<String, Any?> {
        val requestArgs = args ?: emptyMap()
        val toolArgs = mapArg(requestArgs["arguments"])
        val toolName = firstNonBlank(
            requestArgs["toolName"],
            requestArgs["tool_name"],
            requestArgs["targetTool"],
            requestArgs["target_tool"],
            requestArgs["tool"],
        )
        val functionId = firstNonBlank(
            requestArgs["function_id"],
            if (RunLogReplayPolicy.isOmniflowToolCallTool(toolName)) toolArgs["function_id"] else null,
        )
        if (functionId.isNotEmpty()) {
            val frontendRunId = firstNonBlank(
                requestArgs["frontend_run_id"],
                requestArgs["frontendRunId"],
                requestArgs["run_id"],
                requestArgs["runId"],
                requestArgs["taskId"],
                requestArgs["task_id"],
            )
            val frontendTaskId = firstNonBlank(
                requestArgs["frontend_task_id"],
                requestArgs["frontendTaskId"],
                requestArgs["taskId"],
                requestArgs["task_id"],
                requestArgs["run_id"],
                requestArgs["runId"],
            )
            val frontendParent = firstNonBlank(
                requestArgs["frontend_parent"],
                requestArgs["frontendParent"],
                requestArgs["parent"],
            ).ifBlank {
                if (frontendRunId.isNotEmpty() || frontendTaskId.isNotEmpty()) "vlm_task" else ""
            }
            return OmniFlowFunctionRun(context).runFunction(
                linkedMapOf(
                    "function_id" to functionId,
                    "arguments" to toolArgs,
                    "goal" to requestArgs["goal"],
                    "frontend_run_id" to frontendRunId.takeIf { it.isNotEmpty() },
                    "frontend_task_id" to frontendTaskId.takeIf { it.isNotEmpty() },
                    "frontend_parent" to frontendParent.takeIf { it.isNotEmpty() },
                )
            )
        }
        if (toolName.isEmpty()) {
            return McpResponseBuilder.buildErrorText("Missing toolName or function_id")
        }
        return McpResponseBuilder.buildErrorText(
            "Unsupported call_tool target: $toolName. call_tool only supports OmniFlow function_id replay."
        )
    }

    private fun outcomeToMcpResponse(outcome: VlmToolOutcome): Map<String, Any?> {
        val state = McpTaskManager.getTask(outcome.taskId)
        return when (outcome.status) {
            VlmToolOutcomeStatus.FINISHED -> {
                state?.let(McpResponseBuilder::buildFinishedResponse)
                    ?: McpResponseBuilder.buildTextResponse("Task completed: ${outcome.message}")
            }
            VlmToolOutcomeStatus.WAITING_INPUT -> {
                state?.let(McpResponseBuilder::buildWaitingInputResponse)
                    ?: McpResponseBuilder.buildTextResponse(outcome.message)
            }
            VlmToolOutcomeStatus.SCREEN_LOCKED -> {
                state?.let { McpResponseBuilder.buildScreenLockedResponse(it, isInitial = false) }
                    ?: McpResponseBuilder.buildTextResponse(outcome.message)
            }
            VlmToolOutcomeStatus.ERROR, VlmToolOutcomeStatus.CANCELLED -> {
                state?.let(McpResponseBuilder::buildErrorResponse)
                    ?: McpResponseBuilder.buildErrorText(outcome.errorMessage ?: outcome.message)
            }
            VlmToolOutcomeStatus.TIMEOUT -> {
                McpResponseBuilder.buildTimeoutResponse(outcome.taskId, outcome.goal, state)
            }
        }
    }

    private fun buildFileTransferResponse(record: McpFileRecord): Map<String, Any?> {
        val issued = McpFileInbox.issueDownloadToken(record)
        val state = McpServerManager.currentState()
        val host = state.host ?: McpNetworkUtils.currentLanIp()
        if (host.isNullOrBlank()) {
            return McpResponseBuilder.buildErrorText("LAN IP not available. Please ensure the device is on a LAN-accessible network.")
        }
        val url = "http://$host:${state.port}/mcp/file/${issued.id}?token=${issued.downloadToken}"
        val text = buildString {
            appendLine("File ready for download.")
            appendLine("")
            appendLine("File ID: ${issued.id}")
            appendLine("Name: ${issued.fileName}")
            appendLine("Size: ${issued.sizeBytes} bytes")
            appendLine("MIME: ${issued.mimeType ?: "unknown"}")
            appendLine("ReceivedAt: ${issued.createdAt}")
            appendLine("")
            appendLine("Download URL (valid ~15 minutes):")
            appendLine(url)
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "file" to mapOf(
                "id" to issued.id,
                "name" to issued.fileName,
                "mimeType" to issued.mimeType,
                "sizeBytes" to issued.sizeBytes,
                "receivedAt" to issued.createdAt,
                "downloadUrl" to url,
                "tokenExpiresAt" to issued.tokenExpiresAt,
            )
        )
    }

    private fun shouldEnableSummary(goal: String, needSummaryArg: Boolean?): Boolean {
        return (needSummaryArg == true) || hasSummaryIntent(goal)
    }

    private fun hasSummaryIntent(goal: String): Boolean {
        if (goal.isBlank()) return false
        val keywords = listOf(
            "总结", "汇总", "整理", "要点", "概括", "归纳", "提炼", "总结一下",
            "summary", "summarize", "recap", "tl;dr", "tl;dr."
        )
        return keywords.any { goal.contains(it, ignoreCase = true) }
    }

}
