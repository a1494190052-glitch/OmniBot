// Generated from schemas/oob/oob_canonical_actions.v1.json.
// Do not edit action/tool names or argument fields here. Update the schema and
// run `python3 scripts/generate-oob-action-schema.py`.

enum OobActionArgType { string, number, integer, boolean, object, stringArray }

class OobLocalizedText {
  const OobLocalizedText({required this.zhCn, required this.enUs});

  final String zhCn;
  final String enUs;

  String text({bool useEnglish = false}) => useEnglish ? enUs : zhCn;
}

class OobActionArgSpec {
  const OobActionArgSpec({
    required this.name,
    required this.type,
    this.required = false,
    this.description = const OobLocalizedText(zhCn: '', enUs: ''),
    this.enumValues = const <String>[],
    this.minimum,
    this.maximum,
    this.additionalProperties = false,
  });

  final String name;
  final OobActionArgType type;
  final bool required;
  final OobLocalizedText description;
  final List<String> enumValues;
  final num? minimum;
  final num? maximum;
  final bool additionalProperties;
}

class OobActionToolSpec {
  const OobActionToolSpec({
    required this.name,
    required this.uiLabel,
    required this.description,
    required this.promptGuide,
    this.argsTemplate = const <String, Object?>{},
    this.args = const <OobActionArgSpec>[],
    this.modelVisible = true,
    this.replayable = true,
    this.editorVisible = true,
    this.recordable = false,
    this.coordinateAction = false,
    this.pointTargetAction = false,
    this.routeAction = false,
  });

  final String name;
  final OobLocalizedText uiLabel;
  final OobLocalizedText description;
  final OobLocalizedText promptGuide;
  final Map<String, Object?> argsTemplate;
  final List<OobActionArgSpec> args;
  final bool modelVisible;
  final bool replayable;
  final bool editorVisible;
  final bool recordable;
  final bool coordinateAction;
  final bool pointTargetAction;
  final bool routeAction;
}

class OobCanonicalActionSchema {
  const OobCanonicalActionSchema._();

  static const schemaVersion = "oob.canonical_actions.v1";
  static const rootTool = "tool";
  static const rootArgs = "args";

  static const toolClick = "click";
  static const toolLongPress = "long_press";
  static const toolInputText = "input_text";
  static const toolScroll = "scroll";
  static const toolOpenApp = "open_app";
  static const toolPressHome = "press_home";
  static const toolPressBack = "press_back";
  static const toolGetState = "get_state";
  static const toolOobFunctionRun = "oob_function_run";
  static const toolFinished = "finished";
  static const toolInfo = "info";
  static const toolFeedback = "feedback";
  static const toolAbort = "abort";
  static const toolRequireUserChoice = "require_user_choice";
  static const toolRequireUserConfirmation = "require_user_confirmation";

  static const argTargetDescription = "target_description";
  static const argElementIndex = "element_index";
  static const argNodeId = "node_id";
  static const argNodeResourceId = "node_resource_id";
  static const argX = "x";
  static const argY = "y";
  static const argDurationMs = "duration_ms";
  static const argText = "text";
  static const argScrollableIndex = "scrollable_index";
  static const argDirection = "direction";
  static const argX1 = "x1";
  static const argY1 = "y1";
  static const argX2 = "x2";
  static const argY2 = "y2";
  static const argPackageName = "package_name";
  static const argReason = "reason";
  static const argFunctionId = "function_id";
  static const argArguments = "arguments";
  static const argContent = "content";
  static const argValue = "value";
  static const argOptions = "options";
  static const argPrompt = "prompt";

  static const tools = <OobActionToolSpec>[
    OobActionToolSpec(
      name: "click",
      uiLabel: OobLocalizedText(zhCn: "点击", enUs: "Click"),
      description: OobLocalizedText(zhCn: "点击一个可见目标；有 indexed evidence 时优先给 element_index。", enUs: "Tap a visible target; when indexed evidence is available, prefer element_index."),
      promptGuide: OobLocalizedText(zhCn: "- click(target_description, element_index?, x, y): 点击可见目标；优先填写 indexed evidence 的 element_index，x/y 只是兜底。", enUs: "- click(target_description, element_index?, x, y): Tap a visible target; prefer indexed evidence element_index, x/y are fallback only."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "target_description", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "要点击的目标描述。", enUs: "Description of the target to tap.")),
        OobActionArgSpec(name: "element_index", type: OobActionArgType.integer, description: OobLocalizedText(zhCn: "优先填写。OOB indexed page evidence 中目标元素的 #index；提供后系统会用 live XML 中该元素中心覆盖坐标。", enUs: "Prefer this. The #index of the target element in OOB indexed page evidence; when provided, the runtime uses that live XML element center over raw coordinates."), minimum: 0),
        OobActionArgSpec(name: "node_id", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "可选。live XML 中已确认的无障碍节点 id。", enUs: "Optional. Confirmed accessibility node id from live XML.")),
        OobActionArgSpec(name: "node_resource_id", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs: "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer.")),
        OobActionArgSpec(name: "x", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "兜底点击位置的 X 坐标。", enUs: "Fallback X coordinate of the tap target."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "y", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "兜底点击位置的 Y 坐标。", enUs: "Fallback Y coordinate of the tap target."), minimum: 0, maximum: 1000),
      ],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: true,
      coordinateAction: true,
      pointTargetAction: true,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "long_press",
      uiLabel: OobLocalizedText(zhCn: "长按", enUs: "Long press"),
      description: OobLocalizedText(zhCn: "长按一个目标。", enUs: "Long-press a target."),
      promptGuide: OobLocalizedText(zhCn: "- long_press(target_description, element_index?, x, y): 长按目标；优先填写 element_index，x/y 只是兜底。", enUs: "- long_press(target_description, element_index?, x, y): Long-press a target; prefer element_index, x/y are fallback only."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "target_description", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "要长按的目标描述。", enUs: "Description of the target to long-press.")),
        OobActionArgSpec(name: "element_index", type: OobActionArgType.integer, description: OobLocalizedText(zhCn: "优先填写。OOB indexed page evidence 中目标元素的 #index；提供后系统会用 live XML 中该元素中心覆盖坐标。", enUs: "Prefer this. The #index of the target element in OOB indexed page evidence; when provided, the runtime uses that live XML element center over raw coordinates."), minimum: 0),
        OobActionArgSpec(name: "node_id", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "可选。live XML 中已确认的无障碍节点 id。", enUs: "Optional. Confirmed accessibility node id from live XML.")),
        OobActionArgSpec(name: "node_resource_id", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs: "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer.")),
        OobActionArgSpec(name: "x", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "长按位置的 X 坐标。", enUs: "X coordinate of the long press."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "y", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "长按位置的 Y 坐标。", enUs: "Y coordinate of the long press."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "duration_ms", type: OobActionArgType.integer, description: OobLocalizedText(zhCn: "长按时长，单位毫秒。", enUs: "Long-press duration in milliseconds."), minimum: 0),
      ],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: true,
      coordinateAction: true,
      pointTargetAction: true,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "input_text",
      uiLabel: OobLocalizedText(zhCn: "输入文本", enUs: "Input text"),
      description: OobLocalizedText(zhCn: "向一个可见输入目标输入文本；有 indexed evidence 时优先给 element_index。", enUs: "Type text into a visible input target; when indexed evidence is available, prefer element_index."),
      promptGuide: OobLocalizedText(zhCn: "- input_text(target_description, text, element_index?, x, y): 向输入框输入；优先填写 element_index，系统会先走 XML 节点输入，x/y 只是兜底。", enUs: "- input_text(target_description, text, element_index?, x, y): Type into an input field; prefer element_index so the runtime can use XML node input first, x/y are fallback only."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "target_description", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "要输入文本的目标输入框描述。", enUs: "Description of the input target.")),
        OobActionArgSpec(name: "text", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "要输入的文本内容。", enUs: "Text content to type.")),
        OobActionArgSpec(name: "element_index", type: OobActionArgType.integer, description: OobLocalizedText(zhCn: "优先填写。OOB indexed page evidence 中目标输入框的 #index；提供后系统会用 live XML 中该元素中心覆盖坐标。", enUs: "Prefer this. The #index of the target input field in OOB indexed page evidence; when provided, the runtime uses that live XML element center over raw coordinates."), minimum: 0),
        OobActionArgSpec(name: "node_id", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "可选。live XML 中已确认的无障碍节点 id。", enUs: "Optional. Confirmed accessibility node id from live XML.")),
        OobActionArgSpec(name: "node_resource_id", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "可选。录制或 XML grounding 得到的 Android resource-id，用于重放定位诊断和迁移。", enUs: "Optional. Android resource-id captured from recording or XML grounding, used for replay targeting diagnostics and transfer.")),
        OobActionArgSpec(name: "x", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "兜底目标输入框中心的 X 坐标。", enUs: "Fallback X coordinate of the input target center."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "y", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "兜底目标输入框中心的 Y 坐标。", enUs: "Fallback Y coordinate of the input target center."), minimum: 0, maximum: 1000),
      ],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: true,
      coordinateAction: true,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "scroll",
      uiLabel: OobLocalizedText(zhCn: "滚动", enUs: "Scroll"),
      description: OobLocalizedText(zhCn: "从起点滑动到终点。", enUs: "Swipe from the start point to the end point."),
      promptGuide: OobLocalizedText(zhCn: "- scroll(target_description, x1, y1, x2, y2, duration_ms?): 在屏幕上滑动。", enUs: "- scroll(target_description, x1, y1, x2, y2, duration_ms?): Swipe on the screen."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "target_description", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "本次滚动想浏览或定位的目标描述。", enUs: "Description of what this scroll action is trying to browse or locate.")),
        OobActionArgSpec(name: "scrollable_index", type: OobActionArgType.integer, description: OobLocalizedText(zhCn: "可选。OOB indexed page evidence 中 Scrollable regions 的 Sindex；提供后系统会在该区域内生成安全滑动坐标。", enUs: "Optional. The Sindex of the target Scrollable region in OOB indexed page evidence; when provided, the runtime generates safe swipe coordinates inside that region."), minimum: 0),
        OobActionArgSpec(name: "direction", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "配合 scrollable_index 使用的浏览方向。", enUs: "Browsing direction used with scrollable_index."), enumValues: <String>["up", "down", "left", "right"]),
        OobActionArgSpec(name: "x1", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "起点 X 坐标。", enUs: "Start X coordinate."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "y1", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "起点 Y 坐标。", enUs: "Start Y coordinate."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "x2", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "终点 X 坐标。", enUs: "End X coordinate."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "y2", type: OobActionArgType.number, required: true, description: OobLocalizedText(zhCn: "终点 Y 坐标。", enUs: "End Y coordinate."), minimum: 0, maximum: 1000),
        OobActionArgSpec(name: "duration_ms", type: OobActionArgType.integer, description: OobLocalizedText(zhCn: "滑动时长，单位毫秒。", enUs: "Swipe duration in milliseconds."), minimum: 0),
      ],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: true,
      coordinateAction: true,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "open_app",
      uiLabel: OobLocalizedText(zhCn: "打开应用", enUs: "Open app"),
      description: OobLocalizedText(zhCn: "打开指定应用。", enUs: "Open a specific app."),
      promptGuide: OobLocalizedText(zhCn: "- open_app(package_name): 打开指定应用。", enUs: "- open_app(package_name): Open a specific app."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "package_name", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "目标应用的 Android package name。", enUs: "Android package name of the target app.")),
      ],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: true,
    ),
    OobActionToolSpec(
      name: "press_home",
      uiLabel: OobLocalizedText(zhCn: "回到主页", enUs: "Home"),
      description: OobLocalizedText(zhCn: "回到桌面。", enUs: "Go to the home screen."),
      promptGuide: OobLocalizedText(zhCn: "- press_home(): 回到桌面。", enUs: "- press_home(): Go to the home screen."),
      argsTemplate: const <String, Object?>{},
      args: const <OobActionArgSpec>[],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: true,
    ),
    OobActionToolSpec(
      name: "press_back",
      uiLabel: OobLocalizedText(zhCn: "返回", enUs: "Back"),
      description: OobLocalizedText(zhCn: "返回上一级。", enUs: "Go back one level."),
      promptGuide: OobLocalizedText(zhCn: "- press_back(): 返回上一级。", enUs: "- press_back(): Go back one level."),
      argsTemplate: const <String, Object?>{},
      args: const <OobActionArgSpec>[],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: true,
    ),
    OobActionToolSpec(
      name: "get_state",
      uiLabel: OobLocalizedText(zhCn: "刷新状态", enUs: "Get state"),
      description: OobLocalizedText(zhCn: "不执行 UI 操作，只重新获取当前页面状态、包名和 Accessibility tree。", enUs: "Do not perform a UI action; refresh the current page state, package name, and Accessibility tree."),
      promptGuide: OobLocalizedText(zhCn: "- 内部状态刷新：运行时每轮自动读取当前页面状态；该动作不暴露给模型或前端，不点击、不滑动、不输入。", enUs: "- Internal state refresh: the runtime reads the current page state automatically each turn; this action is not exposed to the model or frontend and does not tap, swipe, or type."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "reason", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "为什么需要重新获取状态，例如上一步操作失败、页面无变化或当前页面不确定。", enUs: "Why state refresh is needed, such as previous action failed, page did not change, or current page is uncertain.")),
      ],
      modelVisible: false,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "oob_function_run",
      uiLabel: OobLocalizedText(zhCn: "运行复用指令", enUs: "Run function"),
      description: OobLocalizedText(zhCn: "执行本轮 OmniFlow recall 明确给出的 OOB 复用指令。调用后根据结果继续选择下一步。只能使用 recall context 里出现过的 function_id，并根据用户任务填写 arguments。", enUs: "Run one OOB reusable Function explicitly listed in the current-turn OmniFlow recall context. After the result, continue deciding the next step. Only use a function_id shown in recall context and fill arguments from the user task."),
      promptGuide: OobLocalizedText(zhCn: "- oob_function_run(function_id, arguments): 当本轮 OmniFlow recall 给出了高度匹配的复用指令时调用；不要发明 function_id，参数必须从用户任务中填写到 arguments；无参 Function 也必须输出 arguments: {}；调用后看结果，未完成目标时继续下一步。", enUs: "- oob_function_run(function_id, arguments): Use only when the current-turn OmniFlow recall context lists a matching reusable Function; do not invent function_id, fill arguments from the user task; no-argument Functions must still output arguments: {}; inspect the result and continue when the goal is not done."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "function_id", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "本轮 OmniFlow recall context 中给出的 Function id。", enUs: "Function id shown in the current-turn OmniFlow recall context.")),
        OobActionArgSpec(name: "arguments", type: OobActionArgType.object, required: true, description: OobLocalizedText(zhCn: "Function 业务参数；无参 Function 使用空对象。", enUs: "Business arguments for the Function; use an empty object for no-argument Functions."), additionalProperties: true),
      ],
      modelVisible: true,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "finished",
      uiLabel: OobLocalizedText(zhCn: "完成", enUs: "Finished"),
      description: OobLocalizedText(zhCn: "仅当当前页面或上一轮工具结果直接证明用户目标已经完成时结束。", enUs: "End only when the current page or previous tool result directly proves the user's goal is complete."),
      promptGuide: OobLocalizedText(zhCn: "- finished(content?): 仅在当前页面或上一轮工具结果直接证明目标完成时调用；不确定就继续执行下一步。", enUs: "- finished(content?): Call only when the current page or previous tool result directly proves completion; if uncertain, continue with the next action."),
      argsTemplate: const <String, Object?>{"content": "Done"},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "content", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "给用户的最终完成说明，可为空。", enUs: "Final completion note for the user. May be empty.")),
      ],
      modelVisible: true,
      replayable: true,
      editorVisible: true,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "info",
      uiLabel: OobLocalizedText(zhCn: "询问用户", enUs: "Info"),
      description: OobLocalizedText(zhCn: "向用户询问或请求手动协助。", enUs: "Ask the user a question or request manual help."),
      promptGuide: OobLocalizedText(zhCn: "- info(value): 询问用户或请求用户协助。", enUs: "- info(value): Ask the user for information or manual assistance."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "value", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "你要问用户的问题或需要用户执行的说明。", enUs: "Question to ask the user or instructions for the user to perform.")),
      ],
      modelVisible: true,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "feedback",
      uiLabel: OobLocalizedText(zhCn: "反馈", enUs: "Feedback"),
      description: OobLocalizedText(zhCn: "反馈当前上下文与目标不匹配。", enUs: "Report that the current context does not match the goal."),
      promptGuide: OobLocalizedText(zhCn: "- feedback(value): 请求上层重新规划。", enUs: "- feedback(value): Ask the upper layer to re-plan."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "value", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "反馈原因。", enUs: "Reason for the feedback.")),
      ],
      modelVisible: true,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "abort",
      uiLabel: OobLocalizedText(zhCn: "终止", enUs: "Abort"),
      description: OobLocalizedText(zhCn: "任务无法继续时终止。", enUs: "Abort when the task cannot continue."),
      promptGuide: OobLocalizedText(zhCn: "- abort(value?): 在任务无法继续时终止。", enUs: "- abort(value?): Abort when the task cannot continue."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "value", type: OobActionArgType.string, description: OobLocalizedText(zhCn: "终止任务的原因。", enUs: "Reason for aborting the task.")),
      ],
      modelVisible: true,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "require_user_choice",
      uiLabel: OobLocalizedText(zhCn: "用户选择", enUs: "User choice"),
      description: OobLocalizedText(zhCn: "让用户在若干选项中选择一个。", enUs: "Ask the user to choose one option from a list."),
      promptGuide: OobLocalizedText(zhCn: "- require_user_choice(options, prompt): 让用户做互斥选择。", enUs: "- require_user_choice(options, prompt): Ask the user to make a mutually exclusive choice."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "options", type: OobActionArgType.stringArray, required: true, description: OobLocalizedText(zhCn: "可供用户选择的选项列表。", enUs: "List of options the user can choose from.")),
        OobActionArgSpec(name: "prompt", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "要求用户做选择的提示文案。", enUs: "Prompt shown to the user when asking for a choice.")),
      ],
      modelVisible: true,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
    OobActionToolSpec(
      name: "require_user_confirmation",
      uiLabel: OobLocalizedText(zhCn: "用户确认", enUs: "User confirmation"),
      description: OobLocalizedText(zhCn: "让用户确认当前状态后继续。", enUs: "Ask the user to confirm the current state before continuing."),
      promptGuide: OobLocalizedText(zhCn: "- require_user_confirmation(prompt): 让用户确认后继续。", enUs: "- require_user_confirmation(prompt): Ask the user to confirm before continuing."),
      argsTemplate: const <String, Object?>{},
      args: <OobActionArgSpec>[
        OobActionArgSpec(name: "prompt", type: OobActionArgType.string, required: true, description: OobLocalizedText(zhCn: "要求用户确认的提示文案。", enUs: "Prompt asking the user for confirmation.")),
      ],
      modelVisible: true,
      replayable: false,
      editorVisible: false,
      recordable: false,
      coordinateAction: false,
      pointTargetAction: false,
      routeAction: false,
    ),
  ];

  static const replayableToolNames = <String>{"click", "long_press", "input_text", "scroll", "open_app", "press_home", "press_back", "finished"};
  static const recordableToolNames = <String>{"click", "long_press", "input_text", "scroll"};
  static const coordinateToolNames = <String>{"click", "long_press", "input_text", "scroll"};
  static const pointTargetToolNames = <String>{"click", "long_press"};
  static const routeToolNames = <String>{"open_app", "press_home", "press_back"};
  static const sourceContextArgNames = <String>{"target_description", "element_index", "node_id", "x", "y", "x1", "y1", "x2", "y2", "scrollable_index", "direction", "duration_ms", "text", "package_name", "selector", "node_resource_id", "bounds", "node_class", "clear"};
  static const actionAliases = <String, String>{};

  static List<OobActionToolSpec> get editorVisibleTools =>
      tools.where((tool) => tool.editorVisible).toList(growable: false);

  static String normalizeToolName(String raw) => raw.trim().toLowerCase();

  static String? canonicalToolName(String raw) {
    final normalized = normalizeToolName(raw);
    for (final tool in tools) {
      if (tool.name == normalized) return tool.name;
    }
    return null;
  }

  static OobActionToolSpec? tool(String raw) {
    final canonical = canonicalToolName(raw);
    if (canonical == null) return null;
    for (final tool in tools) {
      if (tool.name == canonical) return tool;
    }
    return null;
  }

  static Set<String> argNames(String toolName) =>
      tool(toolName)?.args.map((arg) => arg.name).toSet() ?? const <String>{};

  static List<String> requiredArgNames(String toolName) =>
      tool(toolName)
          ?.args
          .where((arg) => arg.required)
          .map((arg) => arg.name)
          .toList(growable: false) ??
      const <String>[];
}
