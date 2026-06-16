// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get appName => '小万';

  @override
  String get brandName => '小万';

  @override
  String get brandNameEnglish => 'Omnibot';

  @override
  String get commonLoading => '加载中';

  @override
  String get homeDrawerSearchHint => '搜索全部对话';

  @override
  String get homeDrawerClearSearch => '清空搜索';

  @override
  String get themeModeTitle => '主题模式';

  @override
  String get themeModeSubtitle => '切换浅色、深色或跟随系统外观';

  @override
  String get themeModeLight => '浅色';

  @override
  String get themeModeDark => '深色';

  @override
  String get themeModeSystem => '系统';

  @override
  String get languageTitle => '语言';

  @override
  String get languageSubtitle => '设置应用界面、Agent 提示词与工具文案的显示语言';

  @override
  String get languageFollowSystem => '跟随系统';

  @override
  String get languageZhHans => '简体中文';

  @override
  String get languageEnglish => 'English';

  @override
  String get settingsTitle => '设置';

  @override
  String get settingsSectionModelMemory => '模型与记忆';

  @override
  String get settingsSectionServiceEnvironment => '服务与环境';

  @override
  String get settingsSectionExperienceAppearance => '体验与外观';

  @override
  String get settingsSectionPermissionInfo => '权限与信息';

  @override
  String get settingsModelProviderTitle => '模型提供商';

  @override
  String get settingsModelProviderSubtitle => '配置模型地址、密钥与模型列表';

  @override
  String get settingsSceneModelTitle => '场景模型配置';

  @override
  String get settingsSceneModelSubtitle => '按场景绑定模型，未绑定场景使用默认模型';

  @override
  String get settingsLocalModelsTitle => '本地模型服务';

  @override
  String get settingsLocalModelsSubtitle => '管理本地模型、推理、API 服务与语音模型';

  @override
  String get settingsWorkspaceMemoryTitle => 'Workspace 记忆配置';

  @override
  String get settingsWorkspaceMemoryLoading => '加载中...';

  @override
  String get settingsWorkspaceMemoryEnabled => '已启用 workspace 记忆（嵌入检索可用）';

  @override
  String get settingsWorkspaceMemoryLexical => '使用 workspace 记忆（当前为词法检索）';

  @override
  String get settingsMcpToolsTitle => 'MCP 工具';

  @override
  String get settingsMcpToolsSubtitle => '添加、启停和管理远端 MCP 服务';

  @override
  String get settingsLocalServiceTitle => '本机服务';

  @override
  String get settingsLocalServiceSubtitle => '在局域网内访问小万 MCP 和 webchat 服务';

  @override
  String get settingsAlpineTitle => 'Alpine 环境';

  @override
  String get settingsAlpineSubtitle => '查看与打开应用内 Alpine 终端环境';

  @override
  String get settingsHideRecentsTitle => '后台隐藏';

  @override
  String get settingsHideRecentsSubtitle => '开启后应用将从最近任务列表中隐藏';

  @override
  String get settingsAlarmTitle => '闹钟设置';

  @override
  String get settingsAlarmSubtitle => '配置默认铃声、本地 mp3 或 mp3 直链';

  @override
  String get settingsAppearanceTitle => '外观设置';

  @override
  String get settingsAppearanceSubtitle => '配置主题模式、语言、共享背景图、聊天字号和文本颜色';

  @override
  String get settingsVibrationTitle => '振动反馈';

  @override
  String get settingsVibrationSubtitle => '执行任务时，通过振动进行操作提醒';

  @override
  String get settingsIndependentSendButtonTitle => '使用独立的发送按钮';

  @override
  String get settingsIndependentSendButtonSubtitle =>
      '开启后，聊天页键盘回车为换行；关闭后，回车直接发送';

  @override
  String get settingsAutoBackTitle => '任务完成后自动回聊天';

  @override
  String get settingsAutoBackSubtitle => '关闭后，任务结束将停留在当前完成页面';

  @override
  String get settingsHabitualHandTitle => '惯用手';

  @override
  String get settingsHabitualHandSubtitle => '影响聊天历史记录的侧滑菜单方向';

  @override
  String get settingsHabitualHandLeft => '左手';

  @override
  String get settingsHabitualHandRight => '右手';

  @override
  String get settingsCompanionPermissionTitle => '陪伴权限授权';

  @override
  String get settingsCompanionPermissionSubtitle => '仅访问您授权的 App，隐私安全更有保障';

  @override
  String get settingsAboutTitle => '关于小万';

  @override
  String get settingsHideRecentsFailed => '设置后台隐藏失败';

  @override
  String get settingsSaveFailed => '设置失败';

  @override
  String get settingsAutoBackEnabledToast => '任务完成后将自动返回聊天';

  @override
  String get settingsAutoBackDisabledToast => '任务完成后将停留在当前页面';

  @override
  String settingsMcpEnabledToast(Object endpoint) {
    return 'MCP 已开启：$endpoint';
  }

  @override
  String get settingsMcpDisabledToast => 'MCP 已关闭';

  @override
  String get settingsMcpToggleFailed => 'MCP 开关失败';

  @override
  String get settingsCopiedAddress => '已复制访问地址';

  @override
  String get settingsCopiedToken => '已复制 Token';

  @override
  String get settingsTokenRefreshed => '已刷新 Token';

  @override
  String get settingsTokenRefreshFailed => '刷新 Token 失败';

  @override
  String get settingsMcpLocalService => '本机服务';

  @override
  String get settingsMcpAddress => '地址';

  @override
  String get settingsMcpToken => 'Token';

  @override
  String get settingsNotGenerated => '未生成';

  @override
  String get settingsCopyAddress => '复制地址';

  @override
  String get settingsCopyToken => '复制 Token';

  @override
  String get settingsRefreshToken => '刷新 Token';

  @override
  String get settingsMcpSecurityNotice =>
      '请在同一局域网内使用 Authorization: Bearer <Token> 调用 /mcp/v1/task/vlm，避免将地址或 Token 暴露到公网。';

  @override
  String get settingsInstalledAppsPermissionFailed => '请求应用列表权限失败';

  @override
  String get appearanceTitle => '外观设置';

  @override
  String get appearanceAutoSaving => '正在自动保存…';

  @override
  String get appearanceAutosaveHint => '更改会自动保存';

  @override
  String get appearanceBackgroundSource => '背景来源';

  @override
  String get appearancePreview => '效果预览';

  @override
  String get appearanceAdjustments => '效果调整';

  @override
  String get appearanceFontEffectsTitle => '字体效果';

  @override
  String get appearanceFontEffectsSubtitle => '为中文和英文界面加载更精细的字体组合';

  @override
  String get appearanceEnhanceFontEffects => '提升字体效果';

  @override
  String get appearanceEnhanceFontEffectsSubtitle =>
      '默认关闭；开启后会自动下载字体并缓存到本地，之后离线也可继续使用。';

  @override
  String get appearanceEnhanceFontEffectsLoading => '正在加载字体…';

  @override
  String get appearanceEnhanceFontEffectsFailed => '字体加载失败，请稍后重试';

  @override
  String get appearancePreviewChat => '聊天';

  @override
  String get appearancePreviewWorkspace => '工作区';

  @override
  String get appearanceEnableBackground => '启用背景图';

  @override
  String get appearanceEnableBackgroundSubtitle =>
      '同时作用于聊天页和 Workspace 页面，并自动保存';

  @override
  String get appearanceSourceLocal => '本地图片';

  @override
  String get appearanceSourceRemote => '图片直链';

  @override
  String get appearanceNoLocalImage => '尚未选择本地图片';

  @override
  String get appearancePickImage => '选择图片';

  @override
  String get appearanceRepickImage => '重新选择';

  @override
  String get appearanceRemoteImageUrl => '图片直链';

  @override
  String get appearanceRemoteImageUrlHint =>
      'https://example.com/background.jpg';

  @override
  String get appearanceBackgroundBlur => '背景柔化';

  @override
  String get appearanceBackgroundBlurSubtitle => '调节图片上方蒙版的柔化程度';

  @override
  String get appearanceOverlayIntensity => '蒙版强度';

  @override
  String get appearanceOverlayIntensitySubtitle => '增强统一蒙版，让页面元素更干净';

  @override
  String get appearanceOverlayBrightness => '蒙版明暗';

  @override
  String get appearanceOverlayBrightnessSubtitle => '提亮或压暗蒙版，不会直接修改原图';

  @override
  String get appearanceChatTextSize => '聊天文本大小';

  @override
  String get appearanceChatTextSizeSubtitle => '仅调整用户消息、AI 回复与思考区字号';

  @override
  String get appearanceTextColorTitle => '聊天文本颜色';

  @override
  String get appearanceTextColorSubtitle => '默认会自动跟随背景明暗，也可以改成固定颜色';

  @override
  String get appearanceTextColorAuto => '自动';

  @override
  String get appearanceCustomColorLabel => '自定义色号';

  @override
  String get appearanceCustomColorHint => '#FFFFFF 或 #FF112233';

  @override
  String get appearancePreviewTip => '图片可直接在上方预览里拖动和双指缩放，预览会尽量贴近实际效果。';

  @override
  String get appearanceColorWhite => '白';

  @override
  String get appearanceColorDarkGray => '深灰';

  @override
  String get appearanceColorLightBlue => '浅蓝';

  @override
  String get appearanceColorNavy => '藏蓝';

  @override
  String get appearanceColorTeal => '青绿';

  @override
  String get appearanceColorWarmYellow => '暖黄';

  @override
  String get appearanceInvalidHttpUrl => '请输入有效的 http(s) 图片直链';

  @override
  String get appearanceInvalidHexColor => '请输入 #RRGGBB 或 #AARRGGBB';

  @override
  String get appearanceInvalidHexColorFormat => '色号格式不正确';

  @override
  String appearancePickImageFailed(Object error) {
    return '选择图片失败：$error';
  }

  @override
  String get appearancePickLocalImageFirst => '请先选择本地图片';

  @override
  String get appearanceLocalImageMissing => '本地图片不存在，请重新选择';

  @override
  String appearanceAutosaveFailed(Object error) {
    return '自动保存失败：$error';
  }

  @override
  String get chatToolCalling => '正在调用工具';

  @override
  String get chatFallbackReply => '暂时无法生成回复，请重试。';

  @override
  String get chatPermissionRequired => '执行任务前需要先开启权限';

  @override
  String chatPermissionRequiredWithNames(Object names) {
    return '执行任务前，请先开启：$names';
  }

  @override
  String get chatRecentTerminalOutputNotice => '[只显示最近的部分终端输出]\n';

  @override
  String chatUserPrefix(Object text) {
    return '用户: $text\n';
  }

  @override
  String get permissionAccessibility => '无障碍权限';

  @override
  String get permissionOverlay => '悬浮窗权限';

  @override
  String get permissionInstalledApps => '应用列表读取权限';

  @override
  String get permissionPublicStorage => '公共文件访问';

  @override
  String get browserOverlayTitle => 'Agent Browser';

  @override
  String get browserOverlayClose => '关闭浏览器窗口';

  @override
  String get browserOverlayUnsupported => '当前平台暂不支持浏览器工具视图';

  @override
  String get networkErrorMessage => '抱歉，刚刚网络开小差了。再发一次试试？';

  @override
  String get rateLimitErrorMessage => '小万忙不过来了，等会儿再试试吧';

  @override
  String get chatHistoryArchivedTitle => '归档对话';

  @override
  String get chatHistoryTitle => '聊天记录';

  @override
  String get chatHistoryNoArchived => '暂无归档对话';

  @override
  String get chatHistoryEmpty => '暂无聊天记录';

  @override
  String get chatHistoryArchivedToast => '已归档';

  @override
  String get chatHistoryUnarchivedToast => '已移出归档';

  @override
  String get chatHistoryArchiveFailed => '归档对话失败';

  @override
  String get chatHistoryUnarchiveFailed => '移出归档失败';

  @override
  String get chatHistoryArchiveHint => '左滑对话即可归档';

  @override
  String get homeDrawerArchive => '归档对话';

  @override
  String get homeDrawerNewChat => '新对话';

  @override
  String get webchatNoChats => '开始一个新的对话吧';

  @override
  String get memoryCenterTitle => '记忆中心';

  @override
  String get memoryShortTermTitle => '短期记忆';

  @override
  String get memoryLongTermTitle => '长期记忆';

  @override
  String get memoryNoShortTerm => '还没有短期记忆';

  @override
  String get memoryNoShortTermDesc => '会话中的过程性信息会沉淀到短期记忆，并在后续整理后转入长期记忆。';

  @override
  String get memoryFilteredNoShortTerm => '当前筛选下还没有短期记忆';

  @override
  String get memoryFilteredNoShortTermDesc => '稍后再来看看，新的短期记忆会逐步出现。';

  @override
  String get memoryNoLongTerm => '长期记忆还未初始化';

  @override
  String get memoryNoLongTermDesc => '记忆能力启用后，你的跨会话长期记忆会在这里持续沉淀。';

  @override
  String get memoryDeleteConfirmTitle => '确定删除吗？';

  @override
  String get memoryDeleteWarning => '删除后该内容将不可找回';

  @override
  String get memoryEditDisabled => '短期记忆暂不支持编辑';

  @override
  String get memoryDeleteDisabled => '短期记忆暂不支持删除';

  @override
  String get memoryGreeting => '你好呀，\n欢迎回来，我们会在这里慢慢整理你的记忆。';

  @override
  String memorySelectedCount(Object n) {
    return '已选择$n项';
  }

  @override
  String get memoryDeselectAll => '全不选';

  @override
  String get memoryEditTitle => '编辑记忆';

  @override
  String get memoryIdLabel => '记忆 ID';

  @override
  String get memoryMatchScore => '匹配度';

  @override
  String get memoryAdditionalInfo => '附加信息';

  @override
  String get memoryAddLongTerm => '新增长期记忆';

  @override
  String get memorySaveToLongTerm => '保存到长期记忆';

  @override
  String get memoryLongTermAdded => '长期记忆已新增';

  @override
  String get memoryEditLongTerm => '编辑长期记忆';

  @override
  String get memorySaveChanges => '保存修改';

  @override
  String get memoryDeleteLongTermConfirm => '删除这条长期记忆？';

  @override
  String get memoryLongTermDeleted => '长期记忆已删除';

  @override
  String memoryLongTermFailed(Object error) {
    return '长期记忆操作失败：$error';
  }

  @override
  String get memoryNoMemories => '暂无记忆';

  @override
  String get memoryNoMemoriesDesc => '快去探索，添加喜欢的内容吧';

  @override
  String get skillStoreTitle => '技能仓库';

  @override
  String get skillBuiltin => '内置';

  @override
  String get skillOfficial => '官方';

  @override
  String get skillUser => '用户';

  @override
  String get skillInstalled => '已安装';

  @override
  String get skillNotInstalled => '未安装';

  @override
  String get skillEnabled => '启用中';

  @override
  String get skillDisabled => '已禁用';

  @override
  String get skillInstall => '安装';

  @override
  String get skillDelete => '删除';

  @override
  String get skillEmpty => '暂无已接入的技能';

  @override
  String get skillNoDescription => '暂无描述';

  @override
  String get skillBuiltinRemovedDesc => '该内置技能已从工作区移除，可随时重新安装。';

  @override
  String get skillDeleteTitle => '删除技能';

  @override
  String skillDeleteConfirmMsg(Object name) {
    return '确认删除\"$name\"？';
  }

  @override
  String get skillDeleted => '已删除';

  @override
  String get skillDeleteFailed => '删除失败';

  @override
  String skillInstalledMsg(Object name) {
    return '已安装 $name';
  }

  @override
  String get skillInstallFailed => '安装失败';

  @override
  String skillEnabledMsg(Object name) {
    return '已启用 $name';
  }

  @override
  String skillDisabledMsg(Object name) {
    return '已禁用 $name';
  }

  @override
  String get skillToggleFailed => '切换失败';

  @override
  String get skillSyncOfficialTooltip => '安装/更新官方 Skills';

  @override
  String skillSyncOfficialSuccess(Object count) {
    return '官方 Skills 已同步（$count 个）';
  }

  @override
  String get skillSyncOfficialFailed => '同步官方 Skills 失败';

  @override
  String get skillLoadFailed => '加载技能仓库失败';

  @override
  String get trajectoryTitle => '轨迹';

  @override
  String get trajectoryNoRecords => '暂无执行记录';

  @override
  String get trajectoryNoRecordsDesc => '小万为你执行的视觉任务，都会在此展示';

  @override
  String get trajectoryAll => '全部';

  @override
  String get trajectoryTaskRecords => '任务记录';

  @override
  String trajectorySelectedCount(Object n) {
    return '已选择$n项';
  }

  @override
  String get trajectoryUnknownDate => '未知日期';

  @override
  String get trajectoryThreeDaysAgo => '三天前';

  @override
  String get executionHistoryTitle => '执行历史';

  @override
  String get executionHistorySubtitle => '近3次任务执行历史';

  @override
  String get executionHistoryEmpty => '暂无执行历史';

  @override
  String executionHistoryTaskLabel(Object option) {
    return '$option任务';
  }

  @override
  String get modelProviderConfigTitle => 'Provider 配置';

  @override
  String get modelProviderConfigDesc => '新增、切换并维护模型服务提供商的名称、地址与密钥。';

  @override
  String get modelProviderName => 'Provider 名称';

  @override
  String get modelProviderNameHint => '例如：DeepSeek';

  @override
  String get modelProviderBaseUrlHint => '末尾加 # 可禁用自动补全请求路径';

  @override
  String get modelProviderApiKeyHint => '未填写 API Key 时，会以无鉴权方式请求 Provider。';

  @override
  String get modelListTitle => '模型列表';

  @override
  String get modelListDesc => '支持手动补充模型，也可从当前 Provider 拉取远端模型清单。';

  @override
  String modelListCount(Object count) {
    return '共 $count 个模型';
  }

  @override
  String get modelAddPrompt => '请添加模型！';

  @override
  String get modelBuiltinProvider => '内置 Provider';

  @override
  String get modelIdEmpty => '模型 ID 不能为空且不能以 scene. 开头';

  @override
  String get modelAlreadyExists => '模型已存在';

  @override
  String get modelAdded => '已添加模型';

  @override
  String get modelDeleted => '已删除模型';

  @override
  String get modelDeleteFailed => '删除模型失败';

  @override
  String get modelIdHint => '请输入模型 ID';

  @override
  String get modelAddProviderTitle => '新增 Provider';

  @override
  String get modelAddButton => '新增';

  @override
  String get modelProviderAdded => '已新增 Provider';

  @override
  String modelProviderAddFailed(Object error) {
    return '新增 Provider 失败：$error';
  }

  @override
  String get modelDeleteProviderTitle => '删除 Provider';

  @override
  String modelDeleteProviderMsg(Object name) {
    return '确定删除\"$name\"吗？场景绑定会保留，但需要重新选择可用 Provider。';
  }

  @override
  String get modelProviderDeleted => '已删除 Provider';

  @override
  String modelProviderDeleteFailed(Object error) {
    return '删除 Provider 失败：$error';
  }

  @override
  String get modelProviderLoadFailed => '加载模型提供商配置失败';

  @override
  String modelProviderSwitchFailed(Object error) {
    return '切换 Provider 失败：$error';
  }

  @override
  String get modelProviderBaseUrlRequired => '请先填写 Base URL';

  @override
  String get modelProviderInvalidBaseUrl => '请输入有效的 http(s) Base URL';

  @override
  String modelProviderFetchedModels(Object count) {
    return '已获取 $count 个模型';
  }

  @override
  String modelProviderFetchFailed(Object error) {
    return '拉取模型列表失败：$error';
  }

  @override
  String get sceneModelMapping => '场景映射';

  @override
  String get sceneModelMappingDesc => '按场景绑定 Provider 与模型，未绑定的场景会继续使用默认模型。';

  @override
  String get sceneModelRefreshList => '刷新模型列表';

  @override
  String get sceneModelSearchHint =>
      '点击右侧按钮后，可按 Provider 搜索、折叠并选择模型；顶部搜索框固定不随列表滚动。';

  @override
  String get sceneModelNoScenes => '暂无可配置场景';

  @override
  String get sceneModelLoadFailed => '加载场景模型配置失败';

  @override
  String sceneModelPartialUpdateFailed(Object profiles) {
    return '部分模型已更新，但这些 Provider 刷新失败：$profiles';
  }

  @override
  String sceneModelUpdatedModels(Object count) {
    return '已更新 $count 个模型';
  }

  @override
  String sceneModelRefreshFailed(Object error) {
    return '刷新模型列表失败：$error';
  }

  @override
  String get sceneModelInvalidModelId => '模型 ID 不能以 scene. 开头';

  @override
  String sceneModelBoundToast(Object scene, Object model) {
    return '已将 $scene 绑定到 $model';
  }

  @override
  String sceneModelSaveFailed(Object scene, Object error) {
    return '保存 $scene 配置失败：$error';
  }

  @override
  String sceneModelBindingCleared(Object scene) {
    return '已清除 $scene 的绑定';
  }

  @override
  String sceneModelDefaultRestored(Object scene) {
    return '$scene 已恢复为默认模型';
  }

  @override
  String sceneModelClearFailed(Object scene, Object error) {
    return '清除 $scene 配置失败：$error';
  }

  @override
  String sceneVoiceSaveFailed(Object error) {
    return '保存语音配置失败：$error';
  }

  @override
  String get localModelsTitle => '本地模型';

  @override
  String get localModelsAutoPreheat => '打开 App 时自动预热';

  @override
  String get localModelsAutoPreheatDesc => '进入应用后自动启动本地服务，并直接加载当前模型。';

  @override
  String get localModelsInstalled => '已安装模型';

  @override
  String get localModelsInstalledDesc => '搜索、切换默认模型或删除当前设备上的模型。';

  @override
  String get localModelsSearchHint => '搜索模型名称、ID 或标签';

  @override
  String get localModelsEmpty => '还没有可用的本地模型';

  @override
  String get localModelsEmptyDesc => '先去模型市场下载一个模型，或者手动放置 MNN 模型目录。';

  @override
  String get localModelsServiceControl => '服务控制';

  @override
  String get localModelsServiceControlDesc => '切换推理后端、当前模型和监听端口。';

  @override
  String get localModelsInferenceBackend => '推理后端';

  @override
  String get localModelsCurrentModel => '当前模型';

  @override
  String get localModelsCurrentModelHint => '启动服务时会加载这里选择的模型。';

  @override
  String get localModelsNoAvailableModels => '暂无可用模型';

  @override
  String get localModelsSelectModel => '选择一个模型';

  @override
  String get localModelsServicePort => '服务端口';

  @override
  String get localModelsServicePortHint => '请输入端口号';

  @override
  String get localModelsCurrentlyLoaded => '当前已加载';

  @override
  String get localModelsAutoPreheatSection => '自动预热';

  @override
  String get localModelsAutoPreheatSectionDesc => '打开 App 后自动启动本地服务并加载当前模型。';

  @override
  String get localModelsLocalInference => '本地推理模型';

  @override
  String get localModelsStopping => '停止中…';

  @override
  String get localModelsStarting => '启动中…';

  @override
  String get localModelsStopService => '停止服务';

  @override
  String get localModelsStartService => '启动服务';

  @override
  String get localModelsConfigLoadFailed => '无法加载本地模型配置';

  @override
  String get localModelsConfigLoadFailedDesc => '请稍后重试。';

  @override
  String get localModelsInstalledLoadFailed => '加载已安装模型失败';

  @override
  String get localModelsMarketLoadFailed => '加载模型市场失败';

  @override
  String get localModelsSwitchBackendFailed => '切换推理后端失败';

  @override
  String get localModelsActiveModelUpdated => '已更新当前模型';

  @override
  String get localModelsSetActiveFailed => '设置当前模型失败';

  @override
  String get localModelsPortInvalid => '端口号无效';

  @override
  String get localModelsPortUpdated => '已更新服务端口';

  @override
  String get localModelsPortSaveFailed => '保存端口失败';

  @override
  String get localModelsAutoPreheatSaveFailed => '保存自动预热设置失败';

  @override
  String get localModelsDownloadSourceSwitchFailed => '切换下载源失败';

  @override
  String get localModelsServiceStarted => '本地服务已启动';

  @override
  String get localModelsStartFailed => '启动服务失败';

  @override
  String get localModelsStopFailed => '停止服务失败';

  @override
  String get localModelsServiceStopped => '本地服务已停止';

  @override
  String get localModelsDownloadStartFailed => '启动下载失败';

  @override
  String get localModelsDownloadPauseFailed => '暂停下载失败';

  @override
  String localModelsDownloadStartedToast(String modelName) {
    return '开始下载：$modelName';
  }

  @override
  String localModelsDownloadPausedToast(String modelName) {
    return '下载已暂停：$modelName';
  }

  @override
  String localModelsDownloadCompletedToast(String modelName) {
    return '下载完成：$modelName';
  }

  @override
  String localModelsDownloadFailedToast(String modelName, String reason) {
    return '下载失败：$modelName — $reason';
  }

  @override
  String localModelsDownloadCancelledToast(String modelName, String reason) {
    return '下载已取消：$modelName — $reason';
  }

  @override
  String get localModelsDownloadErrorUnknown => '未知错误';

  @override
  String get localModelsFilterAndSource => '筛选与来源';

  @override
  String get localModelsFilterAndSourceDesc => '切换推理后端和下载源，影响当前市场列表。';

  @override
  String get localModelsDownloadSource => '下载源';

  @override
  String get localModelsSelectDownloadSource => '选择下载源';

  @override
  String get localModelsMarketModels => '市场模型';

  @override
  String get localModelsMarketModelsDesc => '搜索、下载、暂停或删除市场中的模型。';

  @override
  String get localModelsMarketSearchHint => '搜索市场模型名称、描述或标签';

  @override
  String get localModelsMarketEmpty => '模型市场暂时为空';

  @override
  String get localModelsMarketEmptyDesc => '请检查下载源，或者下拉刷新重试。';

  @override
  String get localModelsCurrentDefault => '当前默认';

  @override
  String get localModelsLoaded => '已加载';

  @override
  String get localModelsFileSize => '文件大小';

  @override
  String get localModelsModelDir => '模型目录';

  @override
  String get localModelsManualDir => '这是手动放置目录，App 内不提供删除。';

  @override
  String get localModelsOmniInferLoadable => '该模型可由 OmniInfer 直接加载。';

  @override
  String get localModelsSetAsCurrent => '设为当前';

  @override
  String get localModelsDelete => '删除';

  @override
  String get localModelsHasUpdate => '有更新';

  @override
  String get localModelsStage => '阶段';

  @override
  String get localModelsErrorInfo => '错误信息';

  @override
  String get localModelsResumeDownload => '继续下载';

  @override
  String get localModelsRetryDownload => '重新下载';

  @override
  String get localModelsDownloadModel => '下载模型';

  @override
  String get localModelsPause => '暂停';

  @override
  String get localModelsDeleteOldVersion => '删除旧版本';

  @override
  String get localModelsTabService => '服务';

  @override
  String get localModelsTabMarket => '市场';

  @override
  String get localModelsRefresh => '刷新';

  @override
  String get localModelsDownloadPreparing => '准备中';

  @override
  String get localModelsDownloading => '下载中';

  @override
  String get localModelsDownloadPaused => '已暂停';

  @override
  String get localModelsDownloadCompleted => '已完成';

  @override
  String get localModelsDownloadFailed => '下载失败';

  @override
  String get localModelsDownloadCancelled => '已取消';

  @override
  String get localModelsNotDownloaded => '未下载';

  @override
  String get localModelsImportFromDevice => '从设备导入';

  @override
  String get localModelsImportSuccess => '模型导入成功';

  @override
  String localModelsImportFailed(String reason) {
    return '导入失败：$reason';
  }

  @override
  String localModelsImporting(String modelId) {
    return '正在导入 $modelId...';
  }

  @override
  String get alarmSaved => '闹钟设置已保存';

  @override
  String get alarmRingtoneSource => '铃声来源';

  @override
  String get alarmSystemDefault => '系统默认铃声';

  @override
  String get alarmSystemDefaultDesc => '无需额外配置，兼容性最好';

  @override
  String get alarmLocalMp3 => '本地 mp3';

  @override
  String get alarmLocalMp3Desc => '选择手机内 mp3 作为闹钟铃声';

  @override
  String get alarmMp3Url => 'mp3 直链';

  @override
  String get alarmMp3UrlDesc => '使用 http(s) 直链播放在线 mp3';

  @override
  String get alarmAudioPermissionDenied => '读取音频权限未授予';

  @override
  String get alarmInvalidFilePath => '文件路径无效，请重新选择';

  @override
  String get alarmSelectLocalFirst => '请先选择本地 mp3 文件';

  @override
  String get alarmEnterHttpsUrl => '请输入 http(s) 开头的 mp3 直链';

  @override
  String get alarmLocalFile => '本地文件';

  @override
  String get alarmSelectMp3 => '选择 mp3 文件';

  @override
  String get authorizePageTitle => '应用权限授权';

  @override
  String get authorizeReceiveNotifications => '接收消息通知';

  @override
  String get authorizeNotificationsDesc => '打开后可以及时了解任务进展';

  @override
  String get companionPermissionManagement => '陪伴权限管理';

  @override
  String get companionPermissionDesc => '关闭对应的授权后，小万仍会显示，但不会展示任务执行内容';

  @override
  String get companionPermissionNote => '权限说明';

  @override
  String get companionAuthorizedApps => '授权应用';

  @override
  String get storageUsageTitle => '存储占用';

  @override
  String get storageUsageSubtitle => '查看空间占用明细，支持分项清理';

  @override
  String get storageAnalyzeFailed => '存储分析失败，请重试';

  @override
  String storageCategoryCleaned(Object name, Object size) {
    return '已清理$name，释放 $size';
  }

  @override
  String get storageCleanFailed => '清理失败，请稍后重试';

  @override
  String storageCleanCategory(Object name) {
    return '清理$name';
  }

  @override
  String get storageCleanConfirmMsg => '确认清理该分类数据吗？';

  @override
  String get storageCleanScope => '清理范围';

  @override
  String get storageCleanAll => '全部';

  @override
  String get storageClean7Days => '7天前';

  @override
  String get storageClean30Days => '30天前';

  @override
  String storageStrategyName(Object name) {
    return '执行策略：$name';
  }

  @override
  String storageStrategyDone(Object size) {
    return '策略执行完成，释放 $size';
  }

  @override
  String storageStrategyPartialDone(Object count, Object size) {
    return '策略完成，释放 $size，$count 项未完全成功';
  }

  @override
  String get storageStrategyFailed => '策略执行失败，请稍后重试';

  @override
  String get storageLoadFailed => '加载失败';

  @override
  String get storageReanalyze => '重新分析';

  @override
  String get storageTotalUsage => '总占用';

  @override
  String get storageAppSize => '应用大小';

  @override
  String get storageUserData => '用户数据';

  @override
  String get storageCleanable => '可清理';

  @override
  String storageStatsSource(Object source) {
    return '统计口径：$source';
  }

  @override
  String storagePackageName(Object name) {
    return '当前包名：$name';
  }

  @override
  String get storageTrendFirst => '这是首次分析，后续将展示占用变化趋势';

  @override
  String get storageSmartCleanup => '智能清理策略';

  @override
  String get storageExecute => '执行';

  @override
  String get storageUsageAnalysis => '占用分析';

  @override
  String get storageClean => '清理';

  @override
  String get storageRiskLow => '低风险';

  @override
  String get storageRiskCaution => '谨慎';

  @override
  String get storageRiskHigh => '高风险';

  @override
  String get storageReadOnly => '只读';

  @override
  String get storageSystemStats => '系统统计（与系统设置更接近）';

  @override
  String get storageDirectoryScan => '目录扫描估算';

  @override
  String get storageAdditionalInfo => '附加信息';

  @override
  String get storageCatAppBinary => '应用安装包';

  @override
  String get storageCatAppBinaryDesc => '应用安装文件占用（APK/AAB split）';

  @override
  String get storageCatCache => '缓存';

  @override
  String get storageCatCacheDesc => '临时文件与图片缓存，可安全清理';

  @override
  String get storageCatCacheHint => '清理后会在使用中自动重新生成';

  @override
  String get storageCatConversation => '会话历史';

  @override
  String get storageCatConversationDesc => '对话与工具执行历史（估算）';

  @override
  String get storageCatConversationHint => '会删除历史消息记录，且不可恢复';

  @override
  String get storageCatDatabaseOther => '数据库其他占用';

  @override
  String get storageCatDatabaseOtherDesc => '索引与系统表等数据库占用';

  @override
  String get storageCatWorkspaceBrowser => 'Workspace 浏览器产物';

  @override
  String get storageCatWorkspaceBrowserDesc => '浏览器截图、下载文件和中间产物';

  @override
  String get storageCatWorkspaceBrowserHint => '会删除浏览器工具相关的中间文件';

  @override
  String get storageCatWorkspaceOffloads => 'Workspace Offloads';

  @override
  String get storageCatWorkspaceOffloadsDesc => '工具离线输出与临时文件';

  @override
  String get storageCatWorkspaceOffloadsHint => '仅删除离线产物，不影响核心功能';

  @override
  String get storageCatWorkspaceAttachments => 'Workspace 附件';

  @override
  String get storageCatWorkspaceAttachmentsDesc => '历史任务使用的附件文件';

  @override
  String get storageCatWorkspaceAttachmentsHint => '可能影响历史任务对附件的回看';

  @override
  String get storageCatWorkspaceShared => 'Workspace 共享区';

  @override
  String get storageCatWorkspaceSharedDesc => '跨任务共享的工作区文件';

  @override
  String get storageCatWorkspaceSharedHint => '可能影响后续任务复用共享文件';

  @override
  String get storageCatWorkspaceMemory => 'Workspace 记忆数据';

  @override
  String get storageCatWorkspaceMemoryDesc => '长期/短期记忆与索引数据';

  @override
  String get storageCatWorkspaceUserFiles => 'Workspace 用户文件';

  @override
  String get storageCatWorkspaceUserFilesDesc => '用户主动保存到 workspace 的文件';

  @override
  String get storageCatLocalModelsFiles => '本地模型文件';

  @override
  String get storageCatLocalModelsFilesDesc => '.mnnmodels 下的模型文件';

  @override
  String get storageCatLocalModelsFilesHint => '会删除模型文件，后续需重新下载';

  @override
  String get storageCatLocalModelsCache => '模型推理缓存';

  @override
  String get storageCatLocalModelsCacheDesc => 'mmap 与本地推理临时目录';

  @override
  String get storageCatLocalModelsCacheHint => '清理后会在推理时重新生成';

  @override
  String get storageCatTerminalLocal => '终端运行时（local）';

  @override
  String get storageCatTerminalLocalDesc => 'Alpine 终端 local 运行目录';

  @override
  String get storageCatTerminalLocalHint => '会删除终端 local 目录，需重新初始化';

  @override
  String get storageCatTerminalBootstrap => '终端运行时（引导文件）';

  @override
  String get storageCatTerminalBootstrapDesc => 'proot/lib/alpine 引导文件';

  @override
  String get storageCatTerminalBootstrapHint => '会删除终端引导文件，需重新初始化';

  @override
  String get storageCatSharedDrafts => '共享草稿';

  @override
  String get storageCatSharedDraftsDesc => '外部分享导入的草稿缓存';

  @override
  String get storageCatSharedDraftsHint => '会删除未发送的草稿附件';

  @override
  String get storageCatMcpInbox => 'MCP 收件箱';

  @override
  String get storageCatMcpInboxDesc => 'MCP 文件传输接收目录';

  @override
  String get storageCatMcpInboxHint => '会删除 MCP 收件箱中的文件';

  @override
  String get storageCatLegacyWorkspace => '旧版遗留数据';

  @override
  String get storageCatLegacyWorkspaceDesc => '升级后可能残留的旧 workspace 目录';

  @override
  String get storageCatLegacyWorkspaceHint => '建议确认无用后再清理';

  @override
  String get storageCatOtherUserData => '其他数据';

  @override
  String get storageCatOtherUserDataDesc => '未命中分类规则的数据';

  @override
  String get storageStrategySafeQuick => '安全快速清理';

  @override
  String get storageStrategySafeQuickDesc => '优先清理低风险缓存与临时产物';

  @override
  String get storageStrategyBalanceDeep => '平衡深度清理';

  @override
  String get storageStrategyBalanceDeepDesc => '释放更多空间，保留核心模型与用户文件';

  @override
  String get storageStrategyFree1gb => '目标释放 1GB';

  @override
  String get storageStrategyFree1gbDesc => '按高收益顺序清理，尽量达到 1GB 释放目标';

  @override
  String get storageHintConversation => '如历史未释放，请重新进入页面执行「重新分析」';

  @override
  String get storageHintLocalModels => '模型被清理后，可在「本地模型服务」页面重新下载';

  @override
  String get storageHintTerminal => '终端运行时被清理后，可在 Alpine 环境页重新初始化';

  @override
  String get storageHintGeneral => '若清理失败，可稍后重试或重启应用后再次清理';

  @override
  String get storageHintNotCleanable => '该分类当前不可清理';

  @override
  String get storageHintSkipped => '该分类已跳过（可选项）';

  @override
  String storageCleanPartialFailed(Object hint) {
    return '部分清理失败：$hint';
  }

  @override
  String get storageCleanPartialFailedGeneric => '部分文件清理失败，请稍后重试';

  @override
  String storageTrendVsLast(Object cleanable, Object total) {
    return '对比上次分析：总计 $total，可清理 $cleanable';
  }

  @override
  String storageLastAnalyzed(Object time) {
    return '上次分析时间：$time';
  }

  @override
  String get aboutDescription =>
      '小万，是一款以智能对话为核心的手机AI助\n手，通过语义理解与持续学习能力，协助用户\n完成信息处理、决策辅助和日常管理。';

  @override
  String get aboutBetaProgramTitle => '加入 beta 测试';

  @override
  String get aboutBetaProgramDescription => '接收更快的四段版更新。';

  @override
  String get aboutBetaProgramToggleFailed => 'beta 测试设置更新失败';

  @override
  String get aboutPreferencesSectionTitle => '更新与测试';

  @override
  String get aboutApkSourceTitle => '安装包下载源';

  @override
  String get aboutApkSourceDescription => '选择安装更新时使用的下载源。';

  @override
  String get aboutApkSourceOptionCnb => 'Cloudflare R2';

  @override
  String get aboutApkSourceOptionCnbDescription => '通过更新 Worker 分发';

  @override
  String get aboutApkSourceOptionGithub => 'GitHub';

  @override
  String get aboutApkSourceOptionGithubDescription => '官方 Release';

  @override
  String get aboutApkSourceSwitchFailed => '安装包下载源切换失败';

  @override
  String get aboutUpdateHintDefault => '检查更新获取最新版本';

  @override
  String get workspaceMemoryLoadFailed => '加载 workspace 记忆配置失败';

  @override
  String get workspaceSoulSaved => 'SOUL.md 已保存';

  @override
  String get workspaceSoulSaveFailed => 'SOUL.md 保存失败';

  @override
  String get workspaceChatSaved => 'CHAT.md 已保存';

  @override
  String get workspaceChatSaveFailed => 'CHAT.md 保存失败';

  @override
  String get workspaceMemorySaved => 'MEMORY.md 已保存';

  @override
  String get workspaceMemorySaveFailed => 'MEMORY.md 保存失败';

  @override
  String get workspaceEmbeddingToggleFailed => '记忆嵌入开关更新失败';

  @override
  String get workspaceRollupToggleFailed => '夜间整理开关更新失败';

  @override
  String get workspaceRollupDone => '整理完成';

  @override
  String get workspaceRollupFailed => '立即整理失败';

  @override
  String get workspaceNone => '暂无';

  @override
  String get workspaceMemoryTitle => 'Workspace 记忆';

  @override
  String get workspaceMemoryCapability => '记忆能力';

  @override
  String get workspaceEmbeddingReady => '已配置，可使用向量检索';

  @override
  String get workspaceEmbeddingNotReady => '未配置，将自动降级为词法检索';

  @override
  String get workspaceGoToConfig => '去场景模型配置记忆嵌入模型';

  @override
  String get workspaceNightlyRollup => '夜间记忆整理（22:00）';

  @override
  String workspaceLastRun(Object time) {
    return '最近运行：$time';
  }

  @override
  String workspaceNextRun(Object time) {
    return '下次运行：$time';
  }

  @override
  String get workspaceRollupNow => '立即整理一次';

  @override
  String get workspaceDocContent => '文档内容';

  @override
  String get workspaceSoulMd => 'SOUL.md（Agent 灵魂）';

  @override
  String get workspaceChatMd => 'CHAT.md（纯聊天系统提示词）';

  @override
  String get workspaceMemoryMd => 'MEMORY.md（长期记忆）';

  @override
  String get alpineNodeJs => 'Node.js 运行时';

  @override
  String get alpineNpm => 'Node.js 包管理器';

  @override
  String get alpineGit => 'Git 版本控制';

  @override
  String get alpinePython => 'Python 解释器';

  @override
  String get alpinePip => 'Python 项目与包工具';

  @override
  String get alpinePipInstall => 'Python 包安装器';

  @override
  String get alpineCodex => 'OpenAI Codex CLI 与 app-server 桥接';

  @override
  String get alpineSshClient => 'SSH 客户端';

  @override
  String get alpineSshpass => 'SSH 密码辅助工具';

  @override
  String get alpineOpenSshServer => 'OpenSSH 服务器';

  @override
  String get alpineDetectFailed => '检测 Alpine 环境失败';

  @override
  String get alpineBootTasksLoadFailed => '读取自启动任务失败';

  @override
  String get alpineConfigOpenFailed => '打开终端环境配置失败';

  @override
  String get alpineBootTaskAdded => '已新增自启动任务';

  @override
  String get alpineBootTaskUpdated => '已更新自启动任务';

  @override
  String get alpineBootTaskSaveFailed => '保存自启动任务失败';

  @override
  String get alpineBootEnabled => '已开启应用启动时自启动';

  @override
  String get alpineBootDisabled => '已关闭自动启动';

  @override
  String get alpineBootTaskUpdateFailed => '更新任务失败';

  @override
  String get alpineDeleteBootTask => '删除自启动任务';

  @override
  String alpineDeleteBootTaskMsg(Object name) {
    return '确认删除\"$name\"吗？';
  }

  @override
  String get alpineBootTaskDeleted => '已删除自启动任务';

  @override
  String get alpineBootTaskDeleteFailed => '删除任务失败';

  @override
  String get alpineCommandSent => '启动命令已发送';

  @override
  String get alpineStartFailed => '启动任务失败';

  @override
  String get alpineDetecting => '正在检测环境';

  @override
  String alpineStartConfig(Object count) {
    return '开始配置（$count 项）';
  }

  @override
  String get alpineAllReady => '全部已就绪';

  @override
  String get alpineDetectingDesc => '正在后台检测 Alpine 内常见开发环境的版本信息。';

  @override
  String alpineReadyCount(Object ready, Object total) {
    return '已就绪 $ready/$total 项，可直接勾选缺失项并进入 ReTerminal 自动配置。';
  }

  @override
  String get alpineBootTasks => '自启动任务';

  @override
  String get alpineBootTasksDesc =>
      '打开 Omnibot 时会在后台检查已启用的任务，并在对应 ReTerminal 会话内启动命令，适合常驻服务。';

  @override
  String get alpineAddTask => '新增任务';

  @override
  String get alpineOpenTerminal => '打开终端';

  @override
  String get alpineNoTasksDesc =>
      '暂无任务。你可以添加例如 `python app.py`、`node server.js`、`./start.sh` 之类的常驻命令。';

  @override
  String get alpineBootOnAppOpen => '开机打开 app 后启动';

  @override
  String get alpineNotEnabled => '未启用';

  @override
  String get alpineRunning => '已在运行';

  @override
  String get alpineStartNow => '立即启动';

  @override
  String get alpineEdit => '编辑';

  @override
  String get alpineVersionDetected => '已检测到可用版本';

  @override
  String get alpineVersionNotFound => '未检测到';

  @override
  String get alpineTaskNameHint => '请输入任务名称';

  @override
  String get alpineCommandHint => '请输入启动命令';

  @override
  String get alpineEditBootTask => '编辑自启动任务';

  @override
  String get alpineAddBootTask => '新增自启动任务';

  @override
  String get alpineTaskName => '任务名称';

  @override
  String get alpineTaskNameExample => '例如：本地 API 服务';

  @override
  String get alpineStartCommand => '启动命令';

  @override
  String get alpineCommandExample => '例如：python app.py 或 pnpm start';

  @override
  String get alpineWorkDir => '工作目录';

  @override
  String get alpineBootAutoStart => '打开小万时自动启动';

  @override
  String get alpineDevEnv => '开发环境';

  @override
  String get alpineAiAgent => 'AI Agent';

  @override
  String get alpineEnvConfig => '环境配置';

  @override
  String alpineWorkDirValue(Object dir) {
    return '工作目录：$dir';
  }

  @override
  String get workspaceEmbeddingRetrieval => '记忆嵌入检索';

  @override
  String get chatHistoryStartConversation => '开始对话';

  @override
  String get homeDrawerSearching => '正在搜索对话内容…';

  @override
  String get homeDrawerNoResults => '没有找到相关对话';

  @override
  String get homeDrawerSearchHint2 => '试试更短的关键词，或换一种说法';

  @override
  String get homeDrawerSearchResults => '搜索结果';

  @override
  String get homeDrawerResultCount => '条';

  @override
  String get homeDrawerScheduled => '定时';

  @override
  String get homeDrawerScheduledTasks => '定时任务';

  @override
  String get homeDrawerPinnedConversations => '置顶会话';

  @override
  String get homeDrawerGreeting => '你好！';

  @override
  String get homeDrawerWelcome => '欢迎使用小万';

  @override
  String get homeDrawerDawnGreeting => '凌晨啦';

  @override
  String get homeDrawerDawnSub => '还没休息吗？';

  @override
  String get homeDrawerDawnGreeting2 => '天还没亮';

  @override
  String get homeDrawerDawnSub2 => '早起的你辛苦啦～';

  @override
  String get homeDrawerDawnGreeting3 => '深夜的时光很静';

  @override
  String get homeDrawerDawnSub3 => '但也要记得给身体留些休息呀～';

  @override
  String get homeDrawerMorningGreeting => '早安！';

  @override
  String get homeDrawerMorningSub => '开启元气一天';

  @override
  String get homeDrawerMorningGreeting2 => '早呀！';

  @override
  String get homeDrawerMorningSub2 => '新的一天开始啦';

  @override
  String get homeDrawerForenoonGreeting => '上午好！';

  @override
  String get homeDrawerForenoonSub => '再忙也别忘了活动下肩膀';

  @override
  String get homeDrawerForenoonGreeting2 => '上午的效率超棒！';

  @override
  String get homeDrawerForenoonSub2 => '继续加油';

  @override
  String get homeDrawerLunchGreeting => '午饭时间到！';

  @override
  String get homeDrawerLunchSub => '好好吃饭，别凑合';

  @override
  String get homeDrawerLunchGreeting2 => '午安～';

  @override
  String get homeDrawerLunchSub2 => '吃完记得歇会儿';

  @override
  String get homeDrawerLunchGreeting3 => '午餐不知道吃什么？';

  @override
  String get homeDrawerLunchSub3 => '让小万帮你推荐吧！';

  @override
  String get homeDrawerAfternoonGreeting => '喝杯茶提提神';

  @override
  String get homeDrawerAfternoonSub => '剩下的任务也能轻松搞定～';

  @override
  String get homeDrawerAfternoonGreeting2 => '工作间隙看看窗外';

  @override
  String get homeDrawerAfternoonSub2 => '让眼睛歇一歇～';

  @override
  String get homeDrawerEveningGreeting => '回家路上慢点';

  @override
  String get homeDrawerEveningSub => '今晚好好放松～';

  @override
  String get homeDrawerEveningGreeting2 => '傍晚了';

  @override
  String get homeDrawerEveningSub2 => '吹来的晚风很舒服呀！～';

  @override
  String get homeDrawerEveningGreeting3 => '忙了一天';

  @override
  String get homeDrawerEveningSub3 => '吃顿好的犒劳自己～';

  @override
  String get homeDrawerNightGreeting => '晚上好！';

  @override
  String get homeDrawerNightSub => '享受属于自己的时光吧～';

  @override
  String get homeDrawerNightGreeting2 => '夜色渐浓';

  @override
  String get homeDrawerNightSub2 => '准备下早点休息啦～';

  @override
  String get homeDrawerNightGreeting3 => '该休息了';

  @override
  String get homeDrawerNightSub3 => '让小万帮你定个闹钟吧！';

  @override
  String get homeDrawerLateNightGreeting => '放下手机早点睡';

  @override
  String get homeDrawerLateNightSub => '明天才能元气满满～';

  @override
  String get homeDrawerLateNightGreeting2 => '深夜了';

  @override
  String get homeDrawerLateNightSub2 => '好好和今天说晚安～';

  @override
  String get workbenchTitle => '工作台';

  @override
  String get workbenchWorkspaceTitle => '工作区';

  @override
  String get workbenchWorkspaceOpenWorkbench => '打开工作台';

  @override
  String get workbenchWorkspaceOpenProjectConsole => '进入管理';

  @override
  String get workbenchWorkspaceWorkMode => '文件';

  @override
  String get workbenchWorkspaceProjectMode => '项目';

  @override
  String get workbenchWorkspaceProjectFrontendsTitle => '项目窗口';

  @override
  String get workbenchWorkspaceProjectFrontendsSubtitle =>
      '开启项目模式后，这里像子窗口一样直接承载当前激活项目的 OOB 原生前端。';

  @override
  String get workbenchWorkspaceProjectFrontendsEmpty =>
      '暂无项目前端。回到对话里描述需求后，Agent 会通过工作台创建可显示的项目。';

  @override
  String get workbenchWorkspaceProjectOpenFailed => '打开项目前端失败';

  @override
  String get workbenchWorkspaceProjectUnsupportedDisplay =>
      '这个显示页暂不支持内嵌窗口显示，请用右上角打开为完整页面。';

  @override
  String get workbenchWorkspaceGuideTooltip => '查看项目工作台说明';

  @override
  String get workbenchWorkspaceGuideClose => '关闭说明';

  @override
  String get workbenchWorkspaceGuideTitle => '项目工作台怎么工作';

  @override
  String get workbenchWorkspaceGuideIntro =>
      '项目模式不是新的聊天页，而是 OOB 里用来承载 vibe project 的原生工作台。它把生成前端、项目工具、工作区文件、Skill 和持久化数据连成一个可继续编辑的单位。';

  @override
  String get workbenchWorkspaceGuideFlowTitle => '交互链路';

  @override
  String get workbenchWorkspaceGuideFlowPrompt => '提示词 + Skill 拆解需求';

  @override
  String get workbenchWorkspaceGuideFlowProject => '项目注册表记录容器';

  @override
  String get workbenchWorkspaceGuideFlowApi => '项目工具注册业务能力';

  @override
  String get workbenchWorkspaceGuideFlowDisplay => 'Flutter 显示页展示业务前端';

  @override
  String get workbenchWorkspaceGuideFlowPersist =>
      'data/ + logs/ 持久化 AI 与 UI 调用';

  @override
  String get workbenchWorkspaceGuideProjectTitle => '项目绑定什么';

  @override
  String get workbenchWorkspaceGuideProjectBody =>
      '一个项目会绑定目标、Skill、工作区文件、显示页列表、项目工具、数据和日志。它不是 MCP 工具列表，也不是随手生成的 HTML。';

  @override
  String get workbenchWorkspaceGuideFrontendTitle => '前端怎么显示';

  @override
  String get workbenchWorkspaceGuideFrontendBody =>
      '生成前端是 OOB 原生 Flutter 显示页。工作区切到项目后，不再显示大型管理列表，而是像浏览器子窗口一样直接承载当前激活项目的首页；一个项目可以有多个显示页，可用小菜单切换。';

  @override
  String get workbenchWorkspaceGuideBackendTitle => '后端怎么被调用';

  @override
  String get workbenchWorkspaceGuideBackendBody =>
      '后端能力注册为项目工具，例如 todo.add、todo.finish。AI 层和前端按钮都调用同一条 workbenchApiCall(projectId, toolId, inputs)，项目创建、导出、删除等控制接口不会混进业务工具。';

  @override
  String get workbenchWorkspaceGuideDataTitle => '数据怎么流';

  @override
  String get workbenchWorkspaceGuideDataBody =>
      '调用会经过 Flutter -> MethodChannel -> OOB native executor，然后写入项目的 data/ 和 logs/。前端刷新、AI 调用统计和重启后的状态都来自这份持久化数据。';

  @override
  String get workbenchWorkspaceGuideVibeTitle => '怎么继续改';

  @override
  String get workbenchWorkspaceGuideVibeBody =>
      '要继续 vibe coding，回到首页大输入框说需求。工作台 Skill 会判断是创建新项目、扩充项目工具、调整显示页，还是对当前项目做热更新。';

  @override
  String get workbenchWorkspaceGuideExtendTitle => '扩充后端工具';

  @override
  String get workbenchWorkspaceGuideExtendBody =>
      '新增能力时先定义 toolId、输入输出 schema、executorKind、持久化文件和前端触发位置，再通过工作台接口注册项目工具；不要手写 registry 文件。';

  @override
  String workbenchWorkspaceProjectApiStats(
    Object apiCount,
    Object executionCount,
  ) {
    return '$apiCount 个工具 · 已执行 $executionCount 次';
  }

  @override
  String get workbenchSubtitle => '一个 OOB 原生项目示例，用来验证项目工具注册、状态持久化和工作台内显示。';

  @override
  String get workbenchVibeSubtitle => '提示词生成的原生前端、项目工具和工作区文件在 OOB 内保持关联。';

  @override
  String get workbenchProjectDisplay => '项目显示';

  @override
  String get workbenchProjectSection => '项目';

  @override
  String get workbenchProjectIdLabel => '项目 ID';

  @override
  String get workbenchRouteLabel => '页面路径';

  @override
  String get workbenchSpacePathLabel => 'Space 路径';

  @override
  String get workbenchPageIdsLabel => '页面';

  @override
  String get workbenchDevelopmentMode => '开发模式';

  @override
  String get workbenchProjectRegistryPath => '项目注册表';

  @override
  String get workbenchApiRegistryPath => '工具注册表';

  @override
  String get workbenchProjectFilePath => '项目文件';

  @override
  String get workbenchDataFilePath => '数据文件';

  @override
  String get workbenchLogFilePath => '工具日志';

  @override
  String get workbenchBackendTools => '后端工具';

  @override
  String get workbenchFrontendBinding => '前后端绑定';

  @override
  String get workbenchCallApi => '调用工具';

  @override
  String get workbenchGeneratedFrontend => '生成的前端';

  @override
  String get workbenchGeneratedFrontendSubtitle =>
      '打开提示词生成页面应该挂载的 OOB 原生预览容器。它和 AI 层共用同一组项目工具与持久化数据。';

  @override
  String get workbenchOpenGeneratedFrontend => '打开生成前端';

  @override
  String get workbenchPreviewClose => '关闭预览';

  @override
  String get workbenchToolList => '项目工具';

  @override
  String get workbenchProjectControlSubtitle =>
      '这里只展示已注册的业务工具。项目创建和打开仍属于 OOB 工作台控制面。';

  @override
  String get workbenchOpenWorkspace => '打开工作区';

  @override
  String get workbenchApiEmpty => '暂无工具';

  @override
  String get workbenchToolListDefaultTodo => '项目工具点击了同一个后端';

  @override
  String workbenchToolExecutionCount(Object count) {
    return '已执行 $count 次';
  }

  @override
  String get workbenchProjectDefaultEntity => '条目';

  @override
  String workbenchProjectCreateTitle(Object entity) {
    return '新增 $entity';
  }

  @override
  String workbenchProjectInputHint(Object entity) {
    return '输入 $entity 名称';
  }

  @override
  String workbenchProjectItemsTitle(Object entity) {
    return '$entity 列表';
  }

  @override
  String workbenchProjectEmpty(Object entity) {
    return '暂无 $entity';
  }

  @override
  String get workbenchProjectActiveItems => '进行中';

  @override
  String get workbenchProjectArchivedItems => '已归档';

  @override
  String get workbenchProjectEditAction => '编辑';

  @override
  String get workbenchProjectEditTitle => '编辑条目';

  @override
  String get workbenchProjectArchiveAction => '归档';

  @override
  String get workbenchProjectMissingCreateApi => '这个项目没有可用的新增工具';

  @override
  String get workbenchProjectMissingUpdateApi => '这个项目没有可用的编辑工具';

  @override
  String get workbenchProjectMissingArchiveApi => '这个项目没有可用的归档工具';

  @override
  String workbenchProjectInputRequired(Object entity) {
    return '请先输入 $entity';
  }

  @override
  String workbenchProjectItemCreated(Object entity) {
    return '$entity 已新增';
  }

  @override
  String workbenchProjectItemUpdated(Object entity) {
    return '$entity 已保存';
  }

  @override
  String workbenchProjectItemArchived(Object entity) {
    return '$entity 已归档';
  }

  @override
  String get workbenchLoadFailed => '加载失败';

  @override
  String get workbenchUnknownTool => '工作台工具执行失败';

  @override
  String get workbenchStatusOpen => '等待处理';

  @override
  String get workbenchStatusFinished => '已归档';

  @override
  String get workbenchAssistantName => '小万';

  @override
  String get workbenchAssistantTooltip => '打开小万';

  @override
  String get workbenchAssistantPromptHint => '说出你想实时调整的地方';

  @override
  String get workbenchAssistantSend => '热更新当前项目';

  @override
  String get workbenchAssistantApplied => '项目已热更新';

  @override
  String get workbenchAssistantPromptRequired => '请先输入要调整的内容';

  @override
  String get workbenchAssistantNoProject => '请先选择一个项目';

  @override
  String get workbenchAssistantHotUpdateFailed => '项目热更新失败';

  @override
  String get workbenchProjectModeTitle => '项目';

  @override
  String get workbenchFlutterDisplay => 'Flutter 显示页';

  @override
  String get workbenchFlutterEvalTitle => 'Flutter 运行页';

  @override
  String get workbenchFlutterEvalNoSource =>
      '当前项目还没有可运行的 Flutter 源码。请在 frontend/flutter/lib/main.dart 定义 OobProjectWidget。';

  @override
  String get workbenchFlutterEvalRuntimeFailed =>
      'Flutter 源码暂不可运行，请回到输入框让小万修复这个页面。';

  @override
  String get workbenchProjectSwitcher => '切换项目';

  @override
  String get workbenchProjectGenerateTitle => '项目容器';

  @override
  String get workbenchProjectGenerateSubtitle =>
      '这里只选择和打开项目容器。创建、编辑和热更新继续回到首页大输入框，由当前激活的项目 toolbox 承接。';

  @override
  String get workbenchProjectPromptHint => '回到首页输入项目需求';

  @override
  String get workbenchProjectDefaultPrompt =>
      '我想创建一个简单的 todolist 管理系统，要求可以增加 todo，归档 todo';

  @override
  String get workbenchProjectGenerateButton => '回到首页继续';

  @override
  String get workbenchInputProjectTooltip => '打开项目工作台';

  @override
  String get workbenchGeneratedTodoProjectName => 'Todo List 工作台';

  @override
  String get workbenchPromptSeedAddTodo => '验证可以增加 todo';

  @override
  String get workbenchPromptSeedArchiveTodo => '验证可以归档 todo';

  @override
  String get workbenchProjectPlanTitle => '拆分计划';

  @override
  String get workbenchProjectPlanProject => '创建项目注册和可编辑工作区';

  @override
  String get workbenchProjectPlanFrontend => '生成 OOB 原生 Flutter 前端';

  @override
  String get workbenchProjectPlanApi => '注册 AI/UI 共用项目工具';

  @override
  String get workbenchProjectPlanData => '写入持久化数据和工具日志';

  @override
  String get workbenchUseMode => '使用模式';

  @override
  String get workbenchDebugMode => 'Debug 模式';

  @override
  String get workbenchDisplaysTitle => '页面';

  @override
  String workbenchDisplayCount(Object count) {
    return '$count 个前端';
  }

  @override
  String get workbenchUnnamedDisplay => '未命名前端';

  @override
  String get workbenchOpenDisplay => '打开这个前端';

  @override
  String get workbenchDebugDisplay => '调试这个前端';

  @override
  String get workbenchProjectCurrentTitle => '项目使用台';

  @override
  String get workbenchProjectCurrentSubtitle =>
      '默认打开前端会回到首页；调试打开会回到工作台。热更新通过首页大输入框和当前激活项目完成。';

  @override
  String get workbenchProjectModeCreateTitle => 'Vibe 项目入口';

  @override
  String get workbenchProjectModeSubtitle => '这里只显示项目和当前激活项。';

  @override
  String get workbenchProjectActiveTitle => '当前项目';

  @override
  String get workbenchProjectActiveEmpty => '尚未激活项目';

  @override
  String get workbenchProjectListTitle => '项目';

  @override
  String get workbenchProjectDetailTitle => '项目';

  @override
  String get workbenchProjectModeCreateButton => '去首页创建';

  @override
  String get workbenchProjectCreateFromHome => '回到首页输入框，直接说创建项目或描述你想做的页面。';

  @override
  String get workbenchProjectModeProjectsTitle => '当前工具';

  @override
  String get workbenchProjectApiForProject => '工具';

  @override
  String get workbenchProjectModeOpen => '打开项目';

  @override
  String get workbenchProjectModeEmpty => '暂无工作台项目';

  @override
  String get workbenchProjectModeLoadFailed => '项目模式加载失败';

  @override
  String get workbenchProjectPromptRequired => '请先输入项目需求';

  @override
  String get workbenchProjectGenerated => '项目已生成';

  @override
  String get workbenchDeleteProject => '删除项目';

  @override
  String get workbenchDeleteProjectTitle => '删除项目';

  @override
  String workbenchDeleteProjectMessage(Object projectId) {
    return '确定删除 $projectId？它会移除项目注册、业务工具注册和工作区项目文件。';
  }

  @override
  String get workbenchDeleteProjectCancel => '取消';

  @override
  String get workbenchDeleteProjectConfirm => '删除';

  @override
  String get workbenchDeleteProjectFailed => '项目删除失败';

  @override
  String get workbenchProjectDeleted => '项目已删除';

  @override
  String get workbenchProjectIdRequired => '请输入项目 ID';

  @override
  String get workbenchProjectCreated => '项目已创建';

  @override
  String get workbenchProjectInfoTitle => '项目信息';

  @override
  String get workbenchProjectInfoDisplayTitle => '显示入口';

  @override
  String get workbenchProjectInfoSourceTitle => '源码规格';

  @override
  String get workbenchProjectInfoSourceValue =>
      'README.md / frontend/page_spec.json / backend/api_spec.json';

  @override
  String get workbenchProjectInfoRuntimeTitle => '运行态';

  @override
  String get workbenchProjectInfoRuntimeValue =>
      'data/todos.json / logs/api_calls.jsonl';

  @override
  String get workbenchDebugToolsTitle => '调试工具';

  @override
  String get workbenchDebugHotUpdate => '悬浮小万实时修改当前项目';

  @override
  String get workbenchDebugHotUpdateHomeInput =>
      '回到首页大输入框描述修改，Agent 会带着当前项目 toolbox 执行热更新';

  @override
  String get workbenchDebugFloatingXiaowan =>
      '悬浮小万可以带上当前前端上下文，选择页面信息后调用 workbench_project_hot_update 迭代这个项目。';

  @override
  String get workbenchDebugVlmInput =>
      'VLM 输入也可以附带当前显示页、可见状态、选中控件或截图摘要，作为 frontendContext 交给项目 Skill。';

  @override
  String workbenchDebugContextProject(Object projectId) {
    return '项目 $projectId';
  }

  @override
  String workbenchDebugContextDisplay(Object displayId) {
    return '显示页 $displayId';
  }

  @override
  String workbenchDebugContextRoute(Object route) {
    return '页面路径 $route';
  }

  @override
  String get workbenchDebugVlmTest => '根据 VLM 模拟人类操作测试';

  @override
  String get workbenchDebugComingSoon => '待接入';

  @override
  String get workbenchAnnotationTitle => '标注画布';

  @override
  String get workbenchAnnotationDrawMode => '画笔';

  @override
  String get workbenchAnnotationBrowseMode => '浏览页面';

  @override
  String get workbenchAnnotationUndo => '撤销';

  @override
  String get workbenchAnnotationClear => '清空';

  @override
  String get workbenchAnnotationApply => '应用标注';

  @override
  String get workbenchAnnotationApplying => '应用中';

  @override
  String get workbenchAnnotationPromptHint => '补充修改说明，例如：把这里改成主按钮';

  @override
  String get workbenchAnnotationNoStrokes => '先在页面上画出要修改的区域';

  @override
  String get workbenchAnnotationNoShape => '未标注';

  @override
  String workbenchAnnotationShapeCount(Object count) {
    return '已标注 $count 笔';
  }

  @override
  String get workbenchAnnotationDefaultPrompt => '根据画布标注调整当前项目前端。';

  @override
  String get workbenchAnnotationHotUpdateSuccess => '已把标注应用到项目';

  @override
  String get workbenchAnnotationHotUpdateFailed => '标注热更新失败';

  @override
  String get workbenchExportProjectPackage => '导出分发包';

  @override
  String get workbenchProjectExportFailed => '项目导出失败';

  @override
  String workbenchProjectExported(Object packageName) {
    return '已导出 $packageName';
  }

  @override
  String workbenchProjectExportPath(Object path) {
    return '导出位置：$path';
  }

  @override
  String get workbenchAndroidAssetsTitle => '应用';

  @override
  String get workbenchAndroidSourceHint =>
      '输入 APK 或 Android 项目路径，例如 /workspace/apps/demo.apk';

  @override
  String get workbenchAndroidIngestButton => '导入到当前项目';

  @override
  String get workbenchAndroidSourceRequired => '请输入 Android 应用或项目路径';

  @override
  String get workbenchAndroidIngestFailed => 'Android 资产导入失败';

  @override
  String workbenchAndroidIngested(Object name) {
    return '已导入 $name';
  }

  @override
  String get workbenchAndroidAssetsEmpty => '暂无导入的 Android 应用或项目';

  @override
  String get workbenchProjectActivateFailed => '项目激活失败';

  @override
  String workbenchProjectActivated(Object projectName) {
    return '已激活 $projectName';
  }

  @override
  String get workbenchProjectDeactivateFailed => '项目取消激活失败';

  @override
  String get workbenchProjectDeactivated => '已取消激活项目';

  @override
  String get workbenchActivateProject => '激活项目';

  @override
  String get workbenchDeactivateProject => '取消激活';

  @override
  String get workbenchEditProjectLabels => '编辑名称';

  @override
  String get workbenchProjectNameLabel => '名称';

  @override
  String get workbenchProjectShortNameLabel => '简写';

  @override
  String get workbenchSaveProjectLabels => '保存';

  @override
  String get workbenchProjectNameRequired => '请输入名称';

  @override
  String get workbenchProjectLabelsUpdated => '已保存';

  @override
  String get workbenchProjectLabelsUpdateFailed => '保存失败';

  @override
  String get workbenchProjectMoreActions => '更多操作';

  @override
  String get workbenchActiveProject => '已激活';

  @override
  String get workbenchInactiveProject => '未激活';

  @override
  String get workbenchContinueInHome => '激活项目';

  @override
  String get workbenchProjectHelpTooltip => '项目工作台说明';

  @override
  String get workbenchProjectHelpTitle => '项目工作台';

  @override
  String get workbenchProjectHelpHomeInput => '创建、编辑和热更新都在首页大输入框里完成。';

  @override
  String get workbenchProjectHelpSelect => '这里选择一个项目，把它激活为 Agent 当前工作环境。';

  @override
  String get workbenchProjectHelpDisplays => '每个项目可以有多个 Flutter 前端显示页，从这里打开容器。';

  @override
  String get workbenchProjectHelpApis =>
      '项目工具是当前项目的业务 toolbox，和 MCP tools 分开管理。';

  @override
  String workbenchActiveProjectChip(Object projectName) {
    return '项目：$projectName';
  }

  @override
  String workbenchProjectSummaryGeneric(Object entityName) {
    return '管理 $entityName 记录，并保留状态和快捷操作。';
  }

  @override
  String workbenchAndroidAssetCount(Object count) {
    return '$count 个 Android 资产';
  }

  @override
  String workbenchProjectItemCount(Object activeCount, Object archivedCount) {
    return '$activeCount 条进行中 / $archivedCount 条归档';
  }

  @override
  String workbenchApiCount(Object count) {
    return '$count 个工具';
  }

  @override
  String get workbenchPhilosophyBadge => '了解工作台';

  @override
  String get workbenchPhilosophyClose => '关闭';

  @override
  String get workbenchPhilosophyTitle => 'AI 产品展示工作台';

  @override
  String get workbenchPhilosophyTagline => '让 AI 的结果立刻变成可看、可点、可继续修改的界面';

  @override
  String get workbenchPhilosophySubtitle =>
      'Workbench 不是模板生成器，而是 AI 产品的展示与运行层。Agent 产出的报告、数据、状态和操作会落到 Project 中，通过 HTML、Markdown 或 Flutter 显示，并通过 Project API 连接手机能力与持久化数据。';

  @override
  String get workbenchPhilosophyPillarsTitle => '当前核心闭环';

  @override
  String get workbenchPhilosophyComposable => '显示层';

  @override
  String get workbenchPhilosophyComposableDesc =>
      'HTML / Markdown / Flutter 都是 Project Display，用来承载 AI 输出';

  @override
  String get workbenchPhilosophyAIDriven => '交互层';

  @override
  String get workbenchPhilosophyAIDrivenDesc =>
      '用户点击、填写、选择后，通过 Project API 触发下一步 Agent 或工具';

  @override
  String get workbenchPhilosophyMobileNative => '能力层';

  @override
  String get workbenchPhilosophyMobileNativeDesc =>
      '需要操控手机、读屏、文件、脚本时，再走 OOB 原生能力';

  @override
  String get workbenchPhilosophyStrengthsTitle => '三件事';

  @override
  String get workbenchPhilosophyBackendTitle => 'Project API';

  @override
  String get workbenchPhilosophyBackendDesc =>
      '白名单工具、持久化数据、运行日志和手机能力统一挂到 Project 上';

  @override
  String get workbenchPhilosophyFrontendTitle => 'Display';

  @override
  String get workbenchPhilosophyFrontendDesc =>
      '普通交互 UI 默认 HTML；报告用 Markdown / HTML；Flutter 保留为容器和受限补充';

  @override
  String get workbenchPhilosophyRuntimeTitle => 'Hot update';

  @override
  String get workbenchPhilosophyRuntimeDesc =>
      '用户一句话或一次选区标注后，AI 只改必要的前端文件或 API，右侧立即刷新';

  @override
  String get workbenchPhilosophyHowToTitle => '使用方式';

  @override
  String get workbenchPhilosophyStep1Label => '生成';

  @override
  String get workbenchPhilosophyStep1Desc => 'Agent 创建 Project，写入 API 与显示文件';

  @override
  String get workbenchPhilosophyStep2Label => '查看';

  @override
  String get workbenchPhilosophyStep2Desc =>
      '右侧 Workspace 直接预览 HTML / Markdown / Flutter';

  @override
  String get workbenchPhilosophyStep3Label => '修改';

  @override
  String get workbenchPhilosophyStep3Desc => '用悬浮输入或标注提出修改，Project 热更新';

  @override
  String get workbenchPhilosophyActivateHint =>
      '激活项目后，右侧 Workspace 显示它的 Display；继续输入或标注会作为上下文传给 hot update。';
}
