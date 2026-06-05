package cn.com.omnimind.baselib.runlog

/**
 * Generated from schemas/oob/oob_canonical_actions.v1.json.
 *
 * Do not edit action/tool names or argument fields here. Update the schema and
 * run `python3 scripts/generate-oob-action-schema.py`.
 */
object OobCanonicalActionSchema {
    const val SCHEMA_VERSION = "oob.canonical_actions.v1"
    const val ROOT_TOOL = "tool"
    const val ROOT_ARGS = "args"

    const val TOOL_CLICK = "click"
    const val TOOL_LONG_PRESS = "long_press"
    const val TOOL_INPUT_TEXT = "input_text"
    const val TOOL_SCROLL = "scroll"
    const val TOOL_OPEN_APP = "open_app"
    const val TOOL_PRESS_HOME = "press_home"
    const val TOOL_PRESS_BACK = "press_back"
    const val TOOL_GET_STATE = "get_state"
    const val TOOL_OOB_FUNCTION_RUN = "oob_function_run"
    const val TOOL_FINISHED = "finished"
    const val TOOL_INFO = "info"
    const val TOOL_FEEDBACK = "feedback"
    const val TOOL_ABORT = "abort"
    const val TOOL_REQUIRE_USER_CHOICE = "require_user_choice"
    const val TOOL_REQUIRE_USER_CONFIRMATION = "require_user_confirmation"

    const val ARG_TARGET_DESCRIPTION = "target_description"
    const val ARG_ELEMENT_INDEX = "element_index"
    const val ARG_NODE_ID = "node_id"
    const val ARG_NODE_RESOURCE_ID = "node_resource_id"
    const val ARG_X = "x"
    const val ARG_Y = "y"
    const val ARG_DURATION_MS = "duration_ms"
    const val ARG_TEXT = "text"
    const val ARG_SCROLLABLE_INDEX = "scrollable_index"
    const val ARG_DIRECTION = "direction"
    const val ARG_X1 = "x1"
    const val ARG_Y1 = "y1"
    const val ARG_X2 = "x2"
    const val ARG_Y2 = "y2"
    const val ARG_PACKAGE_NAME = "package_name"
    const val ARG_REASON = "reason"
    const val ARG_FUNCTION_ID = "function_id"
    const val ARG_ARGUMENTS = "arguments"
    const val ARG_CONTENT = "content"
    const val ARG_VALUE = "value"
    const val ARG_OPTIONS = "options"
    const val ARG_PROMPT = "prompt"

    enum class Type {
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN,
        OBJECT,
        STRING_ARRAY,
    }

    data class LocalizedText(
        val zhCn: String,
        val enUs: String,
    )

    data class ArgSpec(
        val name: String,
        val type: Type,
        val required: Boolean = false,
        val description: LocalizedText = LocalizedText("", ""),
        val enumValues: List<String> = emptyList(),
        val minimum: Number? = null,
        val maximum: Number? = null,
        val additionalProperties: Boolean = false,
    )

    data class ToolSpec(
        val name: String,
        val uiLabel: LocalizedText,
        val description: LocalizedText,
        val promptGuide: LocalizedText,
        val argsTemplate: Map<String, Any?> = emptyMap(),
        val args: List<ArgSpec> = emptyList(),
        val modelVisible: Boolean = true,
        val replayable: Boolean = true,
        val editorVisible: Boolean = true,
        val recordable: Boolean = false,
        val coordinateAction: Boolean = false,
        val pointTargetAction: Boolean = false,
        val routeAction: Boolean = false,
    )

    val tools: List<ToolSpec> = listOf(
        ToolSpec(
            name = "click",
            uiLabel = LocalizedText(zhCn = "点击", enUs = "Click"),
            description = LocalizedText(zhCn = "点击一个可见目标；有 indexed evidence 时优先给 element_index。", enUs = "Tap a visible target; when indexed evidence is available, prefer element_index."),
            promptGuide = LocalizedText(zhCn = "- click(target_description, element_index?, x, y): 点击可见目标；优先填写 indexed evidence 的 element_index，x/y 只是兜底。", enUs = "- click(target_description, element_index?, x, y): Tap a visible target; prefer indexed evidence element_index, x/y are fallback only."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要点击的目标描述。", enUs = "Description of the target to tap."),
                ),
ArgSpec(
                    name = "element_index",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "优先填写。OOB indexed page evidence 中目标元素的 #index；提供后系统会用 live XML 中该元素中心覆盖坐标。", enUs = "Prefer this. The #index of the target element in OOB indexed page evidence; when provided, the runtime uses that live XML element center over raw coordinates."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "node_id",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "可选。live XML 中已确认的无障碍节点 id。", enUs = "Optional. Confirmed accessibility node id from live XML."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs = "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "兜底点击位置的屏幕绝对像素 X 坐标。", enUs = "Fallback absolute screen-pixel X coordinate of the tap target."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "兜底点击位置的屏幕绝对像素 Y 坐标。", enUs = "Fallback absolute screen-pixel Y coordinate of the tap target."),
                    minimum = 0,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = true,
            routeAction = false,
        ),
        ToolSpec(
            name = "long_press",
            uiLabel = LocalizedText(zhCn = "长按", enUs = "Long press"),
            description = LocalizedText(zhCn = "长按一个目标。", enUs = "Long-press a target."),
            promptGuide = LocalizedText(zhCn = "- long_press(target_description, element_index?, x, y): 长按目标；优先填写 element_index，x/y 只是兜底。", enUs = "- long_press(target_description, element_index?, x, y): Long-press a target; prefer element_index, x/y are fallback only."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要长按的目标描述。", enUs = "Description of the target to long-press."),
                ),
ArgSpec(
                    name = "element_index",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "优先填写。OOB indexed page evidence 中目标元素的 #index；提供后系统会用 live XML 中该元素中心覆盖坐标。", enUs = "Prefer this. The #index of the target element in OOB indexed page evidence; when provided, the runtime uses that live XML element center over raw coordinates."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "node_id",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "可选。live XML 中已确认的无障碍节点 id。", enUs = "Optional. Confirmed accessibility node id from live XML."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs = "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "长按位置的屏幕绝对像素 X 坐标。", enUs = "Absolute screen-pixel X coordinate of the long press."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "长按位置的屏幕绝对像素 Y 坐标。", enUs = "Absolute screen-pixel Y coordinate of the long press."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "duration_ms",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "长按时长，单位毫秒。", enUs = "Long-press duration in milliseconds."),
                    minimum = 0,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = true,
            routeAction = false,
        ),
        ToolSpec(
            name = "input_text",
            uiLabel = LocalizedText(zhCn = "输入文本", enUs = "Input text"),
            description = LocalizedText(zhCn = "向一个可见输入目标输入文本；有 indexed evidence 时优先给 element_index。", enUs = "Type text into a visible input target; when indexed evidence is available, prefer element_index."),
            promptGuide = LocalizedText(zhCn = "- input_text(target_description, text, element_index?, x, y): 向输入框输入；优先填写 element_index，系统会先走 XML 节点输入，x/y 只是兜底。", enUs = "- input_text(target_description, text, element_index?, x, y): Type into an input field; prefer element_index so the runtime can use XML node input first, x/y are fallback only."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要输入文本的目标输入框描述。", enUs = "Description of the input target."),
                ),
ArgSpec(
                    name = "text",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要输入的文本内容。", enUs = "Text content to type."),
                ),
ArgSpec(
                    name = "element_index",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "优先填写。OOB indexed page evidence 中目标输入框的 #index；提供后系统会用 live XML 中该元素中心覆盖坐标。", enUs = "Prefer this. The #index of the target input field in OOB indexed page evidence; when provided, the runtime uses that live XML element center over raw coordinates."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "node_id",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "可选。live XML 中已确认的无障碍节点 id。", enUs = "Optional. Confirmed accessibility node id from live XML."),
                ),
ArgSpec(
                    name = "node_resource_id",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs = "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer."),
                ),
ArgSpec(
                    name = "x",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "兜底目标输入框中心的屏幕绝对像素 X 坐标。", enUs = "Fallback absolute screen-pixel X coordinate of the input target center."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "y",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "兜底目标输入框中心的屏幕绝对像素 Y 坐标。", enUs = "Fallback absolute screen-pixel Y coordinate of the input target center."),
                    minimum = 0,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "scroll",
            uiLabel = LocalizedText(zhCn = "滚动", enUs = "Scroll"),
            description = LocalizedText(zhCn = "从起点滑动到终点。", enUs = "Swipe from the start point to the end point."),
            promptGuide = LocalizedText(zhCn = "- scroll(target_description, x1, y1, x2, y2, duration_ms?): 在屏幕上滑动。", enUs = "- scroll(target_description, x1, y1, x2, y2, duration_ms?): Swipe on the screen."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "target_description",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "本次滚动想浏览或定位的目标描述。", enUs = "Description of what this scroll action is trying to browse or locate."),
                ),
ArgSpec(
                    name = "scrollable_index",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "可选。OOB indexed page evidence 中 Scrollable regions 的 Sindex；提供后系统会在该区域内生成安全滑动坐标。", enUs = "Optional. The Sindex of the target Scrollable region in OOB indexed page evidence; when provided, the runtime generates safe swipe coordinates inside that region."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "direction",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "配合 scrollable_index 使用的浏览方向。", enUs = "Browsing direction used with scrollable_index."),
                    enumValues = listOf("up", "down", "left", "right"),
                ),
ArgSpec(
                    name = "x1",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "起点屏幕绝对像素 X 坐标。", enUs = "Start absolute screen-pixel X coordinate."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "y1",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "起点屏幕绝对像素 Y 坐标。", enUs = "Start absolute screen-pixel Y coordinate."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "x2",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "终点屏幕绝对像素 X 坐标。", enUs = "End absolute screen-pixel X coordinate."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "y2",
                    type = Type.NUMBER,
                    required = true,
                    description = LocalizedText(zhCn = "终点屏幕绝对像素 Y 坐标。", enUs = "End absolute screen-pixel Y coordinate."),
                    minimum = 0,
                ),
ArgSpec(
                    name = "duration_ms",
                    type = Type.INTEGER,
                    description = LocalizedText(zhCn = "滑动时长，单位毫秒。", enUs = "Swipe duration in milliseconds."),
                    minimum = 0,
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = true,
            coordinateAction = true,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "open_app",
            uiLabel = LocalizedText(zhCn = "打开应用", enUs = "Open app"),
            description = LocalizedText(zhCn = "打开指定应用。", enUs = "Open a specific app."),
            promptGuide = LocalizedText(zhCn = "- open_app(package_name): 打开指定应用。", enUs = "- open_app(package_name): Open a specific app."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "package_name",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "目标应用的 Android package name。", enUs = "Android package name of the target app."),
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = true,
        ),
        ToolSpec(
            name = "press_home",
            uiLabel = LocalizedText(zhCn = "回到主页", enUs = "Home"),
            description = LocalizedText(zhCn = "回到桌面。", enUs = "Go to the home screen."),
            promptGuide = LocalizedText(zhCn = "- press_home(): 回到桌面。", enUs = "- press_home(): Go to the home screen."),
            argsTemplate = emptyMap(),
            args = emptyList(),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = true,
        ),
        ToolSpec(
            name = "press_back",
            uiLabel = LocalizedText(zhCn = "返回", enUs = "Back"),
            description = LocalizedText(zhCn = "返回上一级。", enUs = "Go back one level."),
            promptGuide = LocalizedText(zhCn = "- press_back(): 返回上一级。", enUs = "- press_back(): Go back one level."),
            argsTemplate = emptyMap(),
            args = emptyList(),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = true,
        ),
        ToolSpec(
            name = "get_state",
            uiLabel = LocalizedText(zhCn = "刷新状态", enUs = "Get state"),
            description = LocalizedText(zhCn = "不执行 UI 操作，只重新获取当前页面状态、包名和 Accessibility tree。", enUs = "Do not perform a UI action; refresh the current page state, package name, and Accessibility tree."),
            promptGuide = LocalizedText(zhCn = "- 内部状态刷新：运行时每轮自动读取当前页面状态；该动作不暴露给模型或前端，不点击、不滑动、不输入。", enUs = "- Internal state refresh: the runtime reads the current page state automatically each turn; this action is not exposed to the model or frontend and does not tap, swipe, or type."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "reason",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "为什么需要重新获取状态，例如上一步操作失败、页面无变化或当前页面不确定。", enUs = "Why state refresh is needed, such as previous action failed, page did not change, or current page is uncertain."),
                ),
            ),
            modelVisible = false,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "oob_function_run",
            uiLabel = LocalizedText(zhCn = "运行复用指令", enUs = "Run function"),
            description = LocalizedText(zhCn = "执行本轮 OmniFlow recall 明确给出的 OOB 复用指令。调用后根据结果继续选择下一步。只能使用 recall context 里出现过的 function_id，并根据用户任务填写 arguments。", enUs = "Run one OOB reusable Function explicitly listed in the current-turn OmniFlow recall context. After the result, continue deciding the next step. Only use a function_id shown in recall context and fill arguments from the user task."),
            promptGuide = LocalizedText(zhCn = "- oob_function_run(function_id, arguments): 当本轮 OmniFlow recall 给出了高度匹配的复用指令时调用；不要发明 function_id，参数必须从用户任务中填写到 arguments；无参 Function 也必须输出 arguments: {}；调用后看结果，未完成目标时继续下一步。", enUs = "- oob_function_run(function_id, arguments): Use only when the current-turn OmniFlow recall context lists a matching reusable Function; do not invent function_id, fill arguments from the user task; no-argument Functions must still output arguments: {}; inspect the result and continue when the goal is not done."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "function_id",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "本轮 OmniFlow recall context 中给出的 Function id。", enUs = "Function id shown in the current-turn OmniFlow recall context."),
                ),
ArgSpec(
                    name = "arguments",
                    type = Type.OBJECT,
                    required = true,
                    description = LocalizedText(zhCn = "Function 业务参数；无参 Function 使用空对象。", enUs = "Business arguments for the Function; use an empty object for no-argument Functions."),
                    additionalProperties = true,
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "finished",
            uiLabel = LocalizedText(zhCn = "完成", enUs = "Finished"),
            description = LocalizedText(zhCn = "仅当当前页面或上一轮工具结果直接证明用户目标已经完成时结束。", enUs = "End only when the current page or previous tool result directly proves the user's goal is complete."),
            promptGuide = LocalizedText(zhCn = "- finished(content?): 仅在当前页面或上一轮工具结果直接证明目标完成时调用；不确定就继续执行下一步。", enUs = "- finished(content?): Call only when the current page or previous tool result directly proves completion; if uncertain, continue with the next action."),
            argsTemplate = mapOf("content" to "Done"),
            args = listOf(
ArgSpec(
                    name = "content",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "给用户的最终完成说明，可为空。", enUs = "Final completion note for the user. May be empty."),
                ),
            ),
            modelVisible = true,
            replayable = true,
            editorVisible = true,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "info",
            uiLabel = LocalizedText(zhCn = "询问用户", enUs = "Info"),
            description = LocalizedText(zhCn = "向用户询问或请求手动协助。", enUs = "Ask the user a question or request manual help."),
            promptGuide = LocalizedText(zhCn = "- info(value): 询问用户或请求用户协助。", enUs = "- info(value): Ask the user for information or manual assistance."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "value",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "你要问用户的问题或需要用户执行的说明。", enUs = "Question to ask the user or instructions for the user to perform."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "feedback",
            uiLabel = LocalizedText(zhCn = "反馈", enUs = "Feedback"),
            description = LocalizedText(zhCn = "反馈当前上下文与目标不匹配。", enUs = "Report that the current context does not match the goal."),
            promptGuide = LocalizedText(zhCn = "- feedback(value): 请求上层重新规划。", enUs = "- feedback(value): Ask the upper layer to re-plan."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "value",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "反馈原因。", enUs = "Reason for the feedback."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "abort",
            uiLabel = LocalizedText(zhCn = "终止", enUs = "Abort"),
            description = LocalizedText(zhCn = "任务无法继续时终止。", enUs = "Abort when the task cannot continue."),
            promptGuide = LocalizedText(zhCn = "- abort(value?): 在任务无法继续时终止。", enUs = "- abort(value?): Abort when the task cannot continue."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "value",
                    type = Type.STRING,
                    description = LocalizedText(zhCn = "终止任务的原因。", enUs = "Reason for aborting the task."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "require_user_choice",
            uiLabel = LocalizedText(zhCn = "用户选择", enUs = "User choice"),
            description = LocalizedText(zhCn = "让用户在若干选项中选择一个。", enUs = "Ask the user to choose one option from a list."),
            promptGuide = LocalizedText(zhCn = "- require_user_choice(options, prompt): 让用户做互斥选择。", enUs = "- require_user_choice(options, prompt): Ask the user to make a mutually exclusive choice."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "options",
                    type = Type.STRING_ARRAY,
                    required = true,
                    description = LocalizedText(zhCn = "可供用户选择的选项列表。", enUs = "List of options the user can choose from."),
                ),
ArgSpec(
                    name = "prompt",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要求用户做选择的提示文案。", enUs = "Prompt shown to the user when asking for a choice."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
        ToolSpec(
            name = "require_user_confirmation",
            uiLabel = LocalizedText(zhCn = "用户确认", enUs = "User confirmation"),
            description = LocalizedText(zhCn = "让用户确认当前状态后继续。", enUs = "Ask the user to confirm the current state before continuing."),
            promptGuide = LocalizedText(zhCn = "- require_user_confirmation(prompt): 让用户确认后继续。", enUs = "- require_user_confirmation(prompt): Ask the user to confirm before continuing."),
            argsTemplate = emptyMap(),
            args = listOf(
ArgSpec(
                    name = "prompt",
                    type = Type.STRING,
                    required = true,
                    description = LocalizedText(zhCn = "要求用户确认的提示文案。", enUs = "Prompt asking the user for confirmation."),
                ),
            ),
            modelVisible = true,
            replayable = false,
            editorVisible = false,
            recordable = false,
            coordinateAction = false,
            pointTargetAction = false,
            routeAction = false,
        ),
    )

    val modelVisibleTools: List<ToolSpec> = tools.filter { it.modelVisible }
    val replayableToolNames: Set<String> = tools.filter { it.replayable }.mapTo(linkedSetOf()) { it.name }
    val editorVisibleTools: List<ToolSpec> = tools.filter { it.editorVisible }
    val recordableToolNames: Set<String> = tools.filter { it.recordable }.mapTo(linkedSetOf()) { it.name }
    val coordinateToolNames: Set<String> = tools.filter { it.coordinateAction }.mapTo(linkedSetOf()) { it.name }
    val pointTargetToolNames: Set<String> = tools.filter { it.pointTargetAction }.mapTo(linkedSetOf()) { it.name }
    val routeToolNames: Set<String> = tools.filter { it.routeAction }.mapTo(linkedSetOf()) { it.name }
    val sourceContextArgNames: Set<String> = linkedSetOf("target_description", "element_index", "node_id", "x", "y", "x1", "y1", "x2", "y2", "scrollable_index", "direction", "duration_ms", "text", "package_name", "selector", "node_resource_id", "bounds", "node_class", "clear")

    private val toolsByName: Map<String, ToolSpec> = tools.associateBy { it.name }

    fun tool(name: String): ToolSpec? = toolsByName[normalizeToolName(name)]

    fun normalizeToolName(raw: String): String =
        raw.trim().lowercase()

    fun canonicalToolName(raw: String): String? {
        val normalized = normalizeToolName(raw)
        return normalized.takeIf { toolsByName.containsKey(it) }
    }

    fun argNames(toolName: String): Set<String> =
        tool(toolName)?.args?.mapTo(linkedSetOf()) { it.name } ?: emptySet()

    fun requiredArgNames(toolName: String): List<String> =
        tool(toolName)?.args?.filter { it.required }?.map { it.name } ?: emptyList()

    fun argsTemplate(toolName: String): Map<String, Any?> =
        tool(toolName)?.argsTemplate ?: emptyMap()

    fun supportsAdditionalProperties(toolName: String): Boolean =
        tool(toolName)?.args?.any { it.additionalProperties } == true
}
