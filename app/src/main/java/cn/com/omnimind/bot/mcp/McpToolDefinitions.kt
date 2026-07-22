package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.function.FunctionApi

/**
 * MCP 工具定义
 */
object McpToolDefinitions {
    private fun brandName(): String = AppLocaleManager.brandName()
    
    val vlmTaskTool = mapOf(
        "name" to AgentToolNames.VLM_TASK,
        "description" to """Execute an autonomous VLM (Visual Language Model) agent task on an Android device.

This tool enables AI-driven device automation by using a visual language model to understand screen content and perform actions. The agent will:
1. Analyze the current screen state using screenshots
2. Reason about the next best action to achieve the goal
3. Execute UI actions (tap, scroll, input text, etc.)
4. Iterate until the goal is achieved or intervention is needed

Do not use this tool for uploaded image, screenshot, or photo recognition, OCR, explanation, summary, or comparison. Uploaded images are already part of the multimodal conversation; this tool is only for the current Android device screen and real UI automation.

Use cases:
- Automate repetitive mobile tasks (ordering food, sending messages, etc.)
- Navigate complex app workflows autonomously
- Extract information from mobile applications
- Perform multi-step operations across different apps

OMNIFLOW FUNCTION REUSE:
- Ordinary vlm_task calls use the normal online VLM flow by default.
- The outer Agent should not call hidden Function replay tools. Replay stays inside the local runtime.

IMPORTANT FOR SUMMARY TASKS:
- If the user's goal is to summarize, extract key points, or produce a report (e.g., "总结/汇总/整理/概括/提炼" or "summary/recap"),
  you MUST set needSummary=true to get the summary back in the tool result.
- When needSummary=true, the final response will include a Summary section and a `summary` field.

BEHAVIOR:
- This tool BLOCKS and waits for the task to complete or require input (up to 2 minutes)
- If the agent needs clarification, the response will include the agent's question
- When you receive a WAITING_INPUT response, use 'task_reply' to answer the agent
- After replying, the tool will again wait for completion or next interaction
- Provide clear, specific goals for best results

WORKFLOW:
1. Call vlm_task with your goal
2. If response shows WAITING_INPUT with a question, call task_reply with your answer
3. Repeat step 2 if the agent asks more questions
4. Task completes when you receive a FINISHED status
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf(
                    "type" to "string",
                    "description" to "The task goal in natural language. Be specific and clear. Example: 'Open WeChat and send a message saying Hello to contact John'"
                ),
                "model" to mapOf(
                    "type" to "string",
                    "description" to "Optional: AI model identifier to use for vision reasoning. Leave empty for default."
                ),
                "packageName" to mapOf(
                    "type" to "string",
                    "description" to "Optional: Target app package name (e.g., 'com.tencent.mm' for WeChat). If not specified, the agent will start from the current screen."
                ),
                "maxSteps" to mapOf(
                    "type" to "integer",
                    "default" to 12,
                    "description" to "Optional maximum execution steps. Defaults to 12 and is capped at 64. If the model does not explicitly finish before the limit, the task reports incomplete or max-step failure."
                ),
                "timeoutMs" to mapOf(
                    "type" to "integer",
                    "description" to "Optional control-plane wait timeout in milliseconds. If it expires, OOB stops the on-device VLM task instead of leaving it running."
                ),
                "startFromCurrent" to mapOf(
                    "type" to "boolean",
                    "description" to "Optional: set true to keep the current app/page and skip launching packageName."
                ),
                "needSummary" to mapOf(
                    "type" to "boolean",
                    "description" to "Optional: Set true for summarization/report tasks so the summary is generated and returned in the tool result. Default: false."
                ),
                "disableFunctionRecall" to mapOf(
                    "type" to "boolean",
                    "default" to false,
                    "description" to "Optional: set true to disable local Function recall for this run. This is mainly for first-run capture, diagnostics, and A/B testing."
                )
            ),
            "required" to listOf("goal")
        )
    )

    val taskStatusTool = mapOf(
        "name" to "task_status",
        "description" to """Query the current status of a VLM task (for long-running tasks that timed out).

This is a backup tool - normally vlm_task and task_reply will wait and return the final status.
Only use this if a previous call timed out but the task is still running.

Returns the task state including:
- status: RUNNING, WAITING_INPUT, USER_PAUSED, FINISHED, ERROR, CANCELLED
- message: Status message or error description
- waitingQuestion: When status is WAITING_INPUT, contains the question the agent is asking
- chatMessages: Recent agent reasoning/action messages
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID returned from vlm_task execution."
                )
            ),
            "required" to listOf("taskId")
        )
    )

    val taskReplyTool = mapOf(
        "name" to "task_reply",
        "description" to """Provide user input to a VLM task that is waiting for input.

WHEN TO USE:
When vlm_task returns with status WAITING_INPUT, the agent is asking a question.
Use this tool to answer the question and the task will continue.

BEHAVIOR:
- This tool BLOCKS and waits for the task to complete or require more input (up to 2 minutes)
- After providing your reply, the agent will resume and this tool returns the next status
- If the agent asks another question, you'll receive another WAITING_INPUT response
- Continue the conversation until the task completes (FINISHED status)

Common scenarios:
- Agent asks for verification code: reply with the code
- Agent asks which song to play: reply with the song name
- Agent asks for confirmation: reply '确认' or specific instructions
- Agent needs manual intervention: reply '已完成操作，继续执行' after completing the action
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID of the waiting task."
                ),
                "reply" to mapOf(
                    "type" to "string",
                    "description" to "The user's reply or input to provide to the agent."
                )
            ),
            "required" to listOf("taskId", "reply")
        )
    )

    val taskWaitUnlockTool = mapOf(
        "name" to "task_wait_unlock",
        "description" to """Wait for the device screen to be unlocked and resume/start a paused VLM task.

WHEN TO USE:
When you receive a SCREEN_LOCKED status, ask the user to unlock their phone,
then call this tool to wait for unlock and automatically resume the task.

BEHAVIOR:
- This tool BLOCKS and waits for the screen to be unlocked (up to 2 minutes)
- Once unlocked, if this is a new task it will start execution
- If this is a paused task, it will resume from where it left off
- Returns the next task status (FINISHED, WAITING_INPUT, etc.)
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "The task ID of the screen-locked task."
                )
            ),
            "required" to listOf("taskId")
        )
    )

    val getStateTool = mapOf(
        "name" to "get_state",
        "description" to """Capture the current Android device state from OOB's on-device Accessibility runtime.

Returns one canonical `state` object with the foreground package/activity and live Accessibility XML. Screenshot media and optional indexed context are returned beside it. This is a read-only capture tool for external testing and GUI agents; it avoids slow host-side adb uiautomator/screencap capture.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "include_xml" to mapOf(
                    "type" to "boolean",
                    "default" to true,
                    "description" to "Include the full live Accessibility XML string. Default: true."
                ),
                "include_screenshot" to mapOf(
                    "type" to "boolean",
                    "default" to true,
                    "description" to "Include a JPEG screenshot data URI under screenshot.data_uri. Default: true."
                ),
                "include_indexed_context" to mapOf(
                    "type" to "boolean",
                    "default" to true,
                    "description" to "Include indexed state context rendered from XML for element grounding. Default: true."
                ),
                "include_marked_screenshot" to mapOf(
                    "type" to "boolean",
                    "default" to false,
                    "description" to "Include a marked screenshot with element indexes. This duplicates image payload size. Default: false."
                ),
                "include_image_content" to mapOf(
                    "type" to "boolean",
                    "default" to false,
                    "description" to "Also attach screenshot as MCP image content. Default false because screenshot.data_uri already contains it."
                ),
                "filter_overlay" to mapOf(
                    "type" to "boolean",
                    "default" to true,
                    "description" to "Try to hide/filter OOB overlays during screenshot capture. Default: true."
                ),
                "image_quality" to mapOf(
                    "type" to "string",
                    "enum" to listOf("original", "high", "medium", "low", "summary"),
                    "default" to "medium",
                    "description" to "Screenshot compression level. Default: medium."
                )
            )
        )
    )

    val actTool = mapOf(
        "name" to "act",
        "description" to """Execute one canonical Android UI action through OOB's on-device runtime.

Use this for external evaluators that make their own next-step decision but want OOB to provide the physical device operation. This is a single-step executor, not a planner. Pass exactly one canonical {tool,args} action. Coordinate fields always use 0..1000 relative values and are converted to device pixels once by the Android action executor.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("action"),
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("tool", "args"),
                    "properties" to mapOf(
                        "tool" to mapOf(
                            "type" to "string",
                            "enum" to OobActionSchema.replayableToolNames.toList(),
                        ),
                        "args" to mapOf("type" to "object"),
                    ),
                    "description" to "Canonical {tool,args} action. Coordinate args always use 0..1000 relative values."
                ),
                "settle_delay_ms" to mapOf(
                    "type" to "integer",
                    "description" to "Optional fixed wait after the action. Default 1000ms."
                )
            )
        )
    )

    val fileTransferTool
        get() = mapOf(
        "name" to "file_transfer",
        "description" to """Retrieve files shared to the ${brandName()} app on the Android device.

WORKFLOW:
1. Use vlm_task to navigate to the file and choose "Open with" or "Share" -> 小万.
2. Call this tool to fetch file metadata and a short-lived download URL.
3. Download the file from the returned URL (valid for about 15 minutes).

ACTIONS:
- latest (default): return the most recently received file
- wait: block until a new file arrives (timeoutMs, default 120000)
- list: list recent received files
- get: fetch a file by fileId
- clear: delete one file (fileId) or all files

NOTES:
- Files are stored temporarily on the device (about 2 hours).
- Download URLs are only reachable on the same LAN.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "latest | wait | list | get | clear. Default: latest."
                ),
                "fileId" to mapOf(
                    "type" to "string",
                    "description" to "Target file ID (required for action=get; optional for action=clear)."
                ),
                "afterFileId" to mapOf(
                    "type" to "string",
                    "description" to "For action=wait, only return a file newer than this ID."
                ),
                "timeoutMs" to mapOf(
                    "type" to "integer",
                    "description" to "For action=wait, max wait time in milliseconds (default 120000)."
                ),
                "limit" to mapOf(
                    "type" to "integer",
                    "description" to "For action=list, max number of items to return."
                )
            )
        )
    )

    val agentRunTool = mapOf(
        "name" to "agent_run",
        "description" to """Submit a prompt into the normal in-app ${brandName()} Agent runtime.

Use this when you need OOB itself to run a normal Agent task, call internal Agent tools, or validate a workflow without relying on visual typing into the Flutter Home input.

BEHAVIOR:
- Returns once the Agent run is accepted.
- Use WebChat events, task logs, or returned artifacts to verify completion.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "userMessage" to mapOf(
                    "type" to "string",
                    "description" to "The user prompt to submit to the normal OOB Agent runtime."
                ),
                "conversationId" to mapOf(
                    "type" to "integer",
                    "description" to "Optional existing OOB conversation id. If omitted, a new conversation is created."
                ),
                "conversationMode" to mapOf(
                    "type" to "string",
                    "description" to "Optional conversation mode. Defaults to normal."
                ),
                "title" to mapOf(
                    "type" to "string",
                    "description" to "Optional title when creating a new conversation."
                ),
                "taskId" to mapOf(
                    "type" to "string",
                    "description" to "Optional stable task id for correlation. If omitted, the runtime generates one."
                ),
                "attachments" to mapOf(
                    "type" to "array",
                    "description" to "Optional image/file attachments in the same shape accepted by WebChat."
                ),
                "modelOverride" to mapOf(
                    "type" to "object",
                    "description" to "Optional providerProfileId/modelId override in the same shape accepted by WebChat."
                ),
                "toolProfile" to mapOf(
                    "type" to "string",
                    "enum" to listOf(FunctionApi.PROFILE),
                    "description" to "Optional focused tool exposure profile. Use function when the Agent only needs to list, inspect, register, convert, run, update, or delete Functions; this keeps regular Agent behavior unchanged while reducing tool-schema tokens. Legacy omniflow and function_management inputs are still accepted and normalized."
                ),
                "allowedTools" to mapOf(
                    "type" to "array",
                    "description" to "Optional explicit model tool allowlist for this Agent run. When set, only these tool schemas are exposed to the model.",
                    "items" to mapOf("type" to "string")
                )
            ),
            "required" to listOf("userMessage")
        )
    )

    val fixedTools
        get() = listOf(
            vlmTaskTool,
            taskStatusTool,
            taskReplyTool,
            taskWaitUnlockTool,
            getStateTool,
            actTool,
            fileTransferTool,
            agentRunTool,
        ) + FunctionApi.mcpToolDefinitions

    val fixedToolNames: Set<String>
        get() = fixedTools.mapNotNull { it["name"]?.toString() }.toSet()

    val schemaExportResource: Map<String, Any?>
        get() = mapOf(
            "uri" to FunctionApi.SCHEMA_RESOURCE_URI,
            "name" to "Function Management Schemas",
            "description" to "Exported JSON schema bundle for Function, update_function, enhancement reports, function schemas and MCP tool inputs.",
            "mimeType" to "application/json",
        )

    val schemaExportBundle: Map<String, Any?>
        get() = FunctionApi.schemaBundle(fixedTools)

    val allTools
        get() = fixedTools
}
