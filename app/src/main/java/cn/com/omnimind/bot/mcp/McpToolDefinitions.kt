package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.agent.AgentToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionSchemaExport
import cn.com.omnimind.bot.omniflow.OobFunctionToolNames
import cn.com.omnimind.bot.omniflow.OobFunctionUpdateToolSchema

/**
 * MCP 工具定义
 */
object McpToolDefinitions {
    private fun brandName(): String = AppLocaleManager.brandName()
    private val canonicalReplayTools: String =
        OobCanonicalActionSchema.replayableToolNames.joinToString(", ")
    
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
- Online VLM observes the current page, and the local runtime recalls and executes high-confidence saved Functions before ordinary VLM actions.
- Parameterized Functions are valid matches. Runtime resolve and Function replay stay inside the local runtime.
- The outer Agent should not call hidden Function replay tools. Checker handling, action transfer, and replay safety stay inside the local runner.
- If replay cannot continue locally, runtime resolve may ask the VLM for only one normal UI action for the current step; it should not choose a saved Function itself.

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
                "disableOmniFlowRecall" to mapOf(
                    "type" to "boolean",
                    "default" to false,
                    "description" to "Optional: set true to disable local OmniFlow Function recall for this run. This is mainly for first-run capture, diagnostics, and A/B testing."
                ),
                "allowOmniFlowFunctionAutoExecute" to mapOf(
                    "type" to "boolean",
                    "default" to true,
                    "description" to "Optional: when true, a high-confidence local OmniFlow recall hit may be replayed by the native runtime before ordinary VLM actions. Set false to observe recall guidance without automatic Function replay."
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

Returns the foreground package/activity, live Accessibility XML, screenshot metadata, and optionally a JPEG screenshot data URI plus OOB indexed page evidence. This is a read-only state capture tool for external testing and GUI agents; it avoids slow host-side adb uiautomator/screencap capture.
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
                    "description" to "Include OOB indexed page evidence rendered from XML for element grounding. Default: true."
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
                ),
                "max_xml_chars" to mapOf(
                    "type" to "integer",
                    "description" to "Optional XML truncation limit for the returned xml field. Omit or set <=0 for full XML."
                )
            )
        )
    )

    val actTool = mapOf(
        "name" to "act",
        "description" to """Execute one canonical Android UI action through OOB's on-device runtime.

Use this for external evaluators that make their own next-step decision but want OOB to provide the physical device operation. This is a single-step executor, not a planner: pass one action such as click, input_text, swipe, open_app, press_key, long_press, or finished. Coordinates are absolute screen pixels by default; set coordinate_space=relative_0_1000 only when x/y fields are normalized 0..1000 values.
""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "object",
                    "description" to "Canonical action object, for example {tool:'click', args:{x:100,y:200}}."
                ),
                "tool" to mapOf(
                    "type" to "string",
                    "description" to "Action name when action is not supplied."
                ),
                "args" to mapOf(
                    "type" to "object",
                    "description" to "Action arguments when action is not supplied."
                ),
                "coordinate_space" to mapOf(
                    "type" to "string",
                    "enum" to listOf("absolute", "relative_0_1000"),
                    "description" to "Coordinate space for x/y fields. Default absolute."
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
                    "enum" to listOf("function_management"),
                    "description" to "Optional focused tool exposure profile. Use function_management when the Agent only needs to list, inspect, register, convert, update, or delete OmniFlow Functions; this keeps regular Agent behavior unchanged while reducing tool-schema tokens."
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

    val omniflowRecallTool = mapOf(
        "name" to "omniflow.recall",
        "description" to """Recall by the UDEG path: page match -> UDEG node -> node skill-like decision context. The result is candidate context for inspection and diagnostics. Online execution should use vlm_task; saved Function execution is selected by the local runtime, not by a direct model tool call.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "Natural-language task goal."),
                "current_package" to mapOf("type" to "string", "description" to "Optional foreground Android package for scope matching."),
                "current_node_id" to mapOf("type" to "string", "description" to "Optional current page/node id for future OmniFlow compatibility."),
                "current_xml" to mapOf("type" to "string", "description" to "Optional live accessibility XML. When omitted, OmniFlow captures the foreground page and page-matches it to a UDEG node."),
                "k" to mapOf("type" to "integer", "description" to "Maximum candidates to return. Default 8."),
                "include_debug" to mapOf(
                    "type" to "boolean",
                    "default" to false,
                    "description" to "Default false returns an agent-compact payload without timing, full node skill body, page vectors, or artifacts. Set true only for tests/debugging."
                )
            ),
            "required" to listOf("goal")
        )
    )

    val omniflowIngestRunLogTool = mapOf(
        "name" to "omniflow.ingest_run_log",
        "description" to """Convert a successful OmniFlow RunLog into a local manual Function asset. By default this returns or saves an agent-hidden manual Function; set register=true only when explicitly publishing it for runtime recall.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "Existing OmniFlow RunLog id."),
                "run_log" to mapOf("type" to "object", "description" to "Optional inline canonical run log."),
                "register" to mapOf("type" to "boolean", "description" to "Persist the converted manual Function. Default false."),
                "agent_visible" to mapOf("type" to "boolean", "description" to "Compatibility flag for older callers. Function recall/replay is runtime-owned and not exposed as model-callable tools."),
                "auto_enrich" to mapOf("type" to "boolean", "description" to "Accepted for compatibility; OmniFlow simple mode does deterministic local import.")
            )
        )
    )

    val omniflowExploreReplayTool = mapOf(
        "name" to "omniflow.explore_replay",
        "description" to """Run OmniFlow-native exploratory UI crawling, persist the path as a UTG-backed RunLog, convert it into a reusable Function, then optionally replay that Function through the existing local runner.""".trimIndent(),
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "Natural-language objective used to rank safe clickable UI nodes."),
                "package_name" to mapOf("type" to "string", "description" to "Optional Android package to launch before exploration."),
                "max_steps" to mapOf("type" to "integer", "description" to "Maximum exploration clicks. Default 3, capped at 8."),
                "settle_delay_ms" to mapOf("type" to "integer", "description" to "Delay after launch/click before capturing XML. Default 800ms."),
                "stop_text" to mapOf("type" to "string", "description" to "Optional text/content/resource substring that stops exploration once seen in captured XML."),
                "allow_risky_actions" to mapOf("type" to "boolean", "description" to "Allow labels such as delete, pay, submit, or logout. Default false."),
                "function_id" to mapOf("type" to "string", "description" to "Optional stable Function id for the generated path."),
                "replay" to mapOf("type" to "boolean", "description" to "Whether to replay after registration. Default true."),
                "reset_before_replay" to mapOf("type" to "boolean", "description" to "Optionally press Back and relaunch package before replay."),
                "reset_back_steps" to mapOf("type" to "integer", "description" to "Back presses used when reset_before_replay=true. Default 1."),
                "arguments" to mapOf("type" to "object", "description" to "Function arguments for replay; generated UTG functions are usually argument-free.")
            ),
            "required" to listOf("goal")
        )
    )

    val oobFunctionListTool = mapOf(
        "name" to OobFunctionToolNames.FUNCTION_LIST,
        "description" to "List registered OmniFlow Functions available for runtime recall and replay.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Maximum number of Functions to return. Default: 100.")
            )
        )
    )

    val oobFunctionGetTool = mapOf(
        "name" to OobFunctionToolNames.FUNCTION_GET,
        "description" to "Read one registered OmniFlow Function by id.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Function id to read.")
            ),
            "required" to listOf("function_id")
        )
    )

    val oobFunctionRegisterTool = mapOf(
        "name" to OobFunctionToolNames.FUNCTION_REGISTER,
        "description" to "Register or update one OmniFlow Function. Prefer the simple shape {function_id,name,description,steps,source_page}; pass function_spec only when you already have a full oob.reusable_function.v1 spec. Registration never auto-executes the Function.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Optional stable Function id. Generated from name when omitted."),
                "name" to mapOf("type" to "string", "description" to "User-readable Function name."),
                "description" to mapOf("type" to "string", "description" to "One-sentence description of when to reuse this Function."),
                "package_name" to mapOf("type" to "string", "description" to "Optional target/source app package for page-scoped recall."),
                "source_page" to mapOf("type" to "object", "description" to "Optional source page context, for example {xml, package_name, activity_name}."),
                "parameters" to mapOf("type" to "array", "description" to "Optional Function parameter descriptors with name/type/required/default/bindings."),
                "steps" to mapOf(
                    "type" to "array",
                    "description" to "Simple canonical step list. Each item must use {tool,args,title?}. Supported tool values are $canonicalReplayTools. input_text uses args.text; finished uses args.content.",
                    "items" to mapOf("type" to "object")
                ),
                "function_spec" to mapOf("type" to "object", "description" to "Optional full oob.reusable_function.v1 spec object.")
            )
        )
    )

    val updateFunctionTool = mapOf(
        "name" to OobFunctionToolNames.FUNCTION_UPDATE,
        "description" to "Update one saved OmniFlow Function from a structured patch, user correction, or RunLog evidence. Passing run_id without analysis/patch returns analysis_context and agent_prompt; saving RunLog evidence uses analysis plus an optional patch.",
        "inputSchema" to OobFunctionUpdateToolSchema.inputSchema(includeCamelCaseAliases = false)
    )

    val oobFunctionDeleteTool = mapOf(
        "name" to OobFunctionToolNames.FUNCTION_DELETE,
        "description" to "Delete one registered OmniFlow Function from Workspace, local registry, and UDEG node references.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "function_id" to mapOf("type" to "string", "description" to "Function id to delete.")
            ),
            "required" to listOf("function_id")
        )
    )

    val oobFunctionClearTool = mapOf(
        "name" to OobFunctionToolNames.FUNCTION_CLEAR,
        "description" to "Clear all registered OmniFlow Functions and detach all Function references from UDEG node skills. Requires confirm=true.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "confirm" to mapOf("type" to "boolean", "description" to "Must be true to clear all Functions.")
            ),
            "required" to listOf("confirm")
        )
    )

    val oobRunLogListTool = mapOf(
        "name" to OobFunctionToolNames.RUN_LOG_LIST,
        "description" to "List recent OmniFlow RunLogs that can be inspected or converted to Functions.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "limit" to mapOf("type" to "integer", "description" to "Maximum number of RunLogs to return. Default: 50.")
            )
        )
    )

    val oobRunLogGetTool = mapOf(
        "name" to OobFunctionToolNames.RUN_LOG_GET,
        "description" to "Read one OmniFlow RunLog timeline payload by id.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "RunLog id to read.")
            ),
            "required" to listOf("run_id")
        )
    )

    val oobRunLogConvertTool = mapOf(
        "name" to OobFunctionToolNames.RUN_LOG_CONVERT,
        "description" to "Convert one successful OmniFlow RunLog into a reusable Function and optionally register it.",
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to mapOf(
                "run_id" to mapOf("type" to "string", "description" to "RunLog id to convert."),
                "register" to mapOf("type" to "boolean", "description" to "Register the converted Function. Default follows service policy."),
                "function_id" to mapOf("type" to "string", "description" to "Optional Function id override."),
                "name" to mapOf("type" to "string", "description" to "Optional Function name override."),
                "description" to mapOf("type" to "string", "description" to "Optional Function description override.")
            ),
            "required" to listOf("run_id")
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
            omniflowRecallTool,
            omniflowIngestRunLogTool,
            omniflowExploreReplayTool,
            oobFunctionListTool,
            oobFunctionGetTool,
            oobFunctionRegisterTool,
            updateFunctionTool,
            oobFunctionDeleteTool,
            oobFunctionClearTool,
            oobRunLogListTool,
            oobRunLogGetTool,
            oobRunLogConvertTool
        )

    val fixedToolNames: Set<String>
        get() = fixedTools.mapNotNull { it["name"]?.toString() }.toSet()

    val schemaExportResource: Map<String, Any?>
        get() = mapOf(
            "uri" to OobFunctionSchemaExport.RESOURCE_URI,
            "name" to "OmniFlow Function Management Schemas",
            "description" to "Exported JSON schema bundle for OmniFlow Function, update_function, enhancement reports, replay policy, and MCP tool inputs.",
            "mimeType" to "application/json",
        )

    val schemaExportBundle: Map<String, Any?>
        get() = OobFunctionSchemaExport.bundle(fixedTools)

    val allTools
        get() = fixedTools
}
