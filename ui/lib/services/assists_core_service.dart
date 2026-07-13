import 'package:flutter/foundation.dart';
import 'dart:async';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_schedule_bridge_service.dart';
import 'package:ui/services/app_state_service.dart';
import 'package:ui/services/codex_tool_call_parser.dart';

// 卡片推送
typedef CardPushCallback<T> = void Function(Map<String, dynamic> cardData);
//陪伴任务结束
typedef TaskFinishCallback = void Function();
//消息回执
typedef ChatTaskMessageCallBack =
    void Function(String taskID, String content, String? type);
//消息回执结束
typedef ChatTaskMessageEndCallBack =
    void Function(String taskID, {Map<String, dynamic>? turnUsage});
//VLM任务结束
typedef VLMTaskFinishEndCallBack = void Function(String? taskId);
//普通任务结束
typedef CommonTaskFinishEndCallBack = void Function();
//VLM请求用户输入（INFO动作）
typedef VLMRequestUserInputCallBack =
    void Function(String question, String? taskId);
//Dispatch流式数据回调
typedef DispatchStreamDataCallBack =
    void Function(String taskID, String data, String fullContent);
//Dispatch流式结束回调
typedef DispatchStreamEndCallBack =
    void Function(String taskID, String fullContent);
//Dispatch流式错误回调
typedef DispatchStreamErrorCallBack =
    void Function(
      String taskID,
      String error,
      String fullContent,
      bool isRateLimited,
    );

// Agent相关回调
typedef AgentPromptTokenUsageCallback =
    void Function(
      String taskId,
      int latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentContextCompactionStateCallback =
    void Function(
      String taskId,
      bool isCompacting,
      int? latestPromptTokens,
      int? promptTokenThreshold,
    );
typedef AgentStreamEventCallback = void Function(AgentStreamEvent event);
typedef ScheduledTaskCancelledCallBack = void Function(String taskId);
typedef ScheduledTaskExecuteNowCallBack = void Function(String taskId);

class ModelAvailabilityCheckResult {
  final bool available;
  final int? code;
  final String message;

  const ModelAvailabilityCheckResult({
    required this.available,
    required this.code,
    required this.message,
  });

  factory ModelAvailabilityCheckResult.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) {
      return const ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: '检测失败：返回为空',
      );
    }

    final codeValue = map['code'];
    int? code;
    if (codeValue is int) {
      code = codeValue;
    } else if (codeValue is String) {
      code = int.tryParse(codeValue);
    }

    return ModelAvailabilityCheckResult(
      available: map['available'] == true,
      code: code,
      message: (map['message'] ?? '').toString(),
    );
  }
}

class UtgBridgeConfig {
  final bool utgEnabled;
  final String omniflowBaseUrl;
  final String resolvedOmniflowBaseUrl;
  final bool providerAutoStartEnabled;
  final String providerStartCommand;
  final bool providerStartCommandConfigured;
  final String? providerWorkingDirectory;
  final bool providerHealthy;
  final String providerHealthStatus;
  final String runIndexPath;
  final String runStorageDir;
  final bool useEmbeddedProvider; // 是否使用内置 Provider
  final String providerConnectionMode;
  final OmniFlowPackageStatus devicePackageStatus;

  /// `/health` 响应中的 provider 状态，已随 config 一起返回，无需额外 HTTP 调用
  final OmniFlowStatus? providerStatus;

  const UtgBridgeConfig({
    required this.utgEnabled,
    required this.omniflowBaseUrl,
    required this.resolvedOmniflowBaseUrl,
    required this.providerAutoStartEnabled,
    required this.providerStartCommand,
    required this.providerStartCommandConfigured,
    required this.providerWorkingDirectory,
    required this.providerHealthy,
    required this.providerHealthStatus,
    required this.runIndexPath,
    required this.runStorageDir,
    required this.useEmbeddedProvider,
    required this.providerConnectionMode,
    required this.devicePackageStatus,
    this.providerStatus,
  });

  factory UtgBridgeConfig.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    final healthRaw = raw['providerHealth'];
    final health = (healthRaw is Map)
        ? Map<String, dynamic>.from(healthRaw)
        : <String, dynamic>{};
    return UtgBridgeConfig(
      utgEnabled: raw['utgEnabled'] != false,
      omniflowBaseUrl: (raw['omniflowBaseUrl'] ?? '').toString(),
      resolvedOmniflowBaseUrl: (raw['resolvedOmniflowBaseUrl'] ?? '')
          .toString(),
      providerAutoStartEnabled: raw['providerAutoStartEnabled'] == true,
      providerStartCommand: (raw['providerStartCommand'] ?? '').toString(),
      providerStartCommandConfigured:
          raw['providerStartCommandConfigured'] == true,
      providerWorkingDirectory: raw['providerWorkingDirectory']?.toString(),
      providerHealthy: raw['providerHealthy'] == true,
      providerHealthStatus:
          (raw['providerHealthStatus'] ?? health['status'] ?? '').toString(),
      runIndexPath: (raw['runIndexPath'] ?? '').toString(),
      runStorageDir: (raw['runStorageDir'] ?? '').toString(),
      useEmbeddedProvider: raw['useEmbeddedProvider'] == true,
      providerConnectionMode:
          (raw['providerConnectionMode'] ??
                  (raw['useEmbeddedProvider'] == true ? 'embedded' : 'bridge'))
              .toString(),
      devicePackageStatus: OmniFlowPackageStatus.fromMap(
        raw['devicePackageStatus'] is Map
            ? Map<String, dynamic>.from(raw['devicePackageStatus'] as Map)
            : const <String, dynamic>{},
      ),
      providerStatus: health.isNotEmpty
          ? OmniFlowStatus.fromHealth(health)
          : null,
    );
  }
}

class UtgProviderControlResult {
  final bool success;
  final String action;
  final String message;
  final UtgBridgeConfig config;
  final Map<String, dynamic> rawJson;

  const UtgProviderControlResult({
    required this.success,
    required this.action,
    required this.message,
    required this.config,
    required this.rawJson,
  });

  factory UtgProviderControlResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgProviderControlResult(
      success: raw['success'] == true,
      action: (raw['action'] ?? '').toString(),
      message: (raw['message'] ?? '').toString(),
      config: UtgBridgeConfig.fromMap(raw),
      rawJson: Map<String, dynamic>.from(
        raw.map((key, value) => MapEntry(key.toString(), value)),
      ),
    );
  }
}

/// 当前设备上的 OmniFlow 包状态（来自 native package manager）。
class OmniFlowPackageStatus {
  final bool installed;
  final String? installedVersion;
  final String? installedHash;
  final String? installSource;
  final bool externalWheelAvailable;

  const OmniFlowPackageStatus({
    required this.installed,
    this.installedVersion,
    this.installedHash,
    this.installSource,
    required this.externalWheelAvailable,
  });

  factory OmniFlowPackageStatus.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return OmniFlowPackageStatus(
      installed: raw['installed'] == true,
      installedVersion: raw['installedVersion']?.toString(),
      installedHash: raw['installedHash']?.toString(),
      installSource: raw['installSource']?.toString(),
      externalWheelAvailable: raw['externalWheelAvailable'] == true,
    );
  }

  String? get versionDisplay => installedVersion?.trim().isNotEmpty == true
      ? installedVersion!.trim()
      : installedHash?.trim();
}

/// Embedded Provider 状态
class EmbeddedProviderStatus {
  final bool installed;
  final String? installedVersion;
  final bool running;
  final int port;
  final String? binaryPath;
  final String latestVersion;
  final bool needsUpdate;

  const EmbeddedProviderStatus({
    required this.installed,
    required this.installedVersion,
    required this.running,
    required this.port,
    required this.binaryPath,
    required this.latestVersion,
    required this.needsUpdate,
  });

  factory EmbeddedProviderStatus.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return EmbeddedProviderStatus(
      installed: raw['installed'] == true,
      installedVersion: raw['installedVersion']?.toString(),
      running: raw['running'] == true,
      port: raw['port'] is num
          ? (raw['port'] as num).toInt()
          : int.tryParse((raw['port'] ?? '9417').toString()) ?? 9417,
      binaryPath: raw['binaryPath']?.toString(),
      latestVersion: (raw['latestVersion'] ?? '0.1.0').toString(),
      needsUpdate: raw['needsUpdate'] == true,
    );
  }
}

/// Embedded Provider 安装结果
class EmbeddedProviderInstallResult {
  final bool success;
  final String? version;
  final String? binaryPath;
  final String? error;

  const EmbeddedProviderInstallResult({
    required this.success,
    this.version,
    this.binaryPath,
    this.error,
  });

  factory EmbeddedProviderInstallResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return EmbeddedProviderInstallResult(
      success: raw['success'] == true,
      version: raw['version']?.toString(),
      binaryPath: raw['binaryPath']?.toString(),
      error: raw['error']?.toString(),
    );
  }
}

/// Provider 更新检查结果
class UtgUpdateCheckResult {
  final String currentVersion;
  final String? latestVersion;
  final String? latestCommit;
  final bool updateAvailable;
  final String wheelUrl;
  final int? wheelSizeBytes; // wheel 文件大小
  final bool localWheelExists;
  final String? localWheelHash;
  final String? error;

  const UtgUpdateCheckResult({
    required this.currentVersion,
    this.latestVersion,
    this.latestCommit,
    required this.updateAvailable,
    required this.wheelUrl,
    this.wheelSizeBytes,
    required this.localWheelExists,
    this.localWheelHash,
    this.error,
  });

  factory UtgUpdateCheckResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgUpdateCheckResult(
      currentVersion: (raw['current_version'] ?? 'unknown').toString(),
      latestVersion: raw['latest_version']?.toString(),
      latestCommit: raw['latest_commit']?.toString(),
      updateAvailable: raw['update_available'] == true,
      wheelUrl: (raw['wheel_url'] ?? '').toString(),
      wheelSizeBytes: raw['wheel_size_bytes'] is num
          ? (raw['wheel_size_bytes'] as num).toInt()
          : int.tryParse((raw['wheel_size_bytes'] ?? '').toString()),
      localWheelExists: raw['local_wheel_exists'] == true,
      localWheelHash: raw['local_wheel_hash']?.toString(),
      error: raw['error']?.toString(),
    );
  }

  /// 格式化 wheel 大小显示
  String get wheelSizeFormatted {
    if (wheelSizeBytes == null) return '';
    final mb = wheelSizeBytes! / (1024 * 1024);
    return '${mb.toStringAsFixed(1)} MB';
  }
}

/// Provider 更新应用结果
class UtgUpdateApplyResult {
  final bool success;
  final String? previousVersion;
  final String? installedVersion;
  final String? latestVersion;
  final bool restartRequired;
  final String? message;
  final String? error;
  final String? connectionMode;
  final bool providerRestarted;
  final String? hint; // 额外提示信息

  const UtgUpdateApplyResult({
    required this.success,
    this.previousVersion,
    this.installedVersion,
    this.latestVersion,
    required this.restartRequired,
    this.message,
    this.error,
    this.connectionMode,
    this.providerRestarted = false,
    this.hint,
  });

  factory UtgUpdateApplyResult.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgUpdateApplyResult(
      success: raw['success'] == true,
      previousVersion: raw['previous_version']?.toString(),
      installedVersion: raw['installed_version']?.toString(),
      latestVersion: raw['latest_version']?.toString(),
      restartRequired: raw['restart_required'] == true,
      message: raw['message']?.toString(),
      error: raw['error']?.toString(),
      connectionMode: raw['connection_mode']?.toString(),
      providerRestarted: raw['provider_restarted'] == true,
      hint: raw['hint']?.toString(),
    );
  }
}

/// Provider 本地 Store 信息（来自 /health 的 store 字段）
class OmniFlowStore {
  final String? path;
  final int functionCount;
  final int runLogCount;

  const OmniFlowStore({
    this.path,
    this.functionCount = 0,
    this.runLogCount = 0,
  });

  factory OmniFlowStore.fromJson(Map<String, dynamic>? json) {
    if (json == null) return const OmniFlowStore();
    return OmniFlowStore(
      path: json['path']?.toString(),
      functionCount: json['function_count'] is num
          ? (json['function_count'] as num).toInt()
          : int.tryParse((json['function_count'] ?? '0').toString()) ?? 0,
      runLogCount: json['run_log_count'] is num
          ? (json['run_log_count'] as num).toInt()
          : int.tryParse((json['run_log_count'] ?? '0').toString()) ?? 0,
    );
  }

  String get pathDisplay {
    if (path == null) return '未加载';
    final name = path!.split('/').last;
    return name.length > 24 ? '...${name.substring(name.length - 24)}' : name;
  }
}

/// Provider 连接状态（来自 /health 端点）
class OmniFlowStatus {
  final bool connected;
  final String version;
  final String buildType;
  final int port;
  final int embeddingDim;
  final OmniFlowStore store;

  const OmniFlowStatus({
    required this.connected,
    required this.version,
    required this.buildType,
    required this.port,
    required this.embeddingDim,
    required this.store,
  });

  factory OmniFlowStatus.fromHealth(Map<String, dynamic> json) {
    return OmniFlowStatus(
      connected: json['success'] == true,
      version: (json['version'] ?? 'unknown').toString(),
      buildType: (json['build_type'] ?? 'python').toString(),
      port: json['port'] is num
          ? (json['port'] as num).toInt()
          : int.tryParse((json['port'] ?? '9417').toString()) ?? 9417,
      embeddingDim: json['embedding_dim'] is num
          ? (json['embedding_dim'] as num).toInt()
          : int.tryParse((json['embedding_dim'] ?? '64').toString()) ?? 64,
      store: OmniFlowStore.fromJson(
        json['store'] is Map
            ? Map<String, dynamic>.from(json['store'] as Map)
            : null,
      ),
    );
  }

  String get versionDisplay {
    final suffix = buildType == 'cython' ? ' (Cython)' : '';
    return '$version$suffix';
  }
}

class UtgBridgeExecutionContext {
  final String bridgeBaseUrl;
  final String bridgeToken;
  final String resolvedOmniflowBaseUrl;
  final bool providerHealthy;
  final String providerMessage;

  const UtgBridgeExecutionContext({
    required this.bridgeBaseUrl,
    required this.bridgeToken,
    required this.resolvedOmniflowBaseUrl,
    required this.providerHealthy,
    required this.providerMessage,
  });

  factory UtgBridgeExecutionContext.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgBridgeExecutionContext(
      bridgeBaseUrl: (raw['bridgeBaseUrl'] ?? '').toString(),
      bridgeToken: (raw['bridgeToken'] ?? '').toString(),
      resolvedOmniflowBaseUrl: (raw['resolvedOmniflowBaseUrl'] ?? '')
          .toString(),
      providerHealthy: raw['providerHealthy'] == true,
      providerMessage: (raw['providerMessage'] ?? '').toString(),
    );
  }
}

class AgentToolEventData {
  final String taskId;
  final String cardId;
  final String toolCallId;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String toolType;
  final String uiStyle;
  final String? serverName;
  final String status;
  final String argsJson;
  final String progress;
  final String summary;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String terminalOutputDelta;
  final String? terminalSessionId;
  final String terminalStreamState;
  final Map<String, dynamic> raw;
  final String? workspaceId;
  final String? interruptedBy;
  final String? interruptionReason;
  final List<Map<String, dynamic>> artifacts;
  final List<Map<String, dynamic>> actions;
  final String subagentStatusText;
  final List<Map<String, dynamic>> subagentEvents;
  final bool success;

  const AgentToolEventData({
    required this.taskId,
    this.cardId = '',
    this.toolCallId = '',
    required this.toolName,
    required this.displayName,
    this.toolTitle = '',
    required this.toolType,
    this.uiStyle = '',
    this.serverName,
    this.status = '',
    this.argsJson = '',
    this.progress = '',
    this.summary = '',
    this.resultPreviewJson = '',
    this.rawResultJson = '',
    this.terminalOutput = '',
    this.terminalOutputDelta = '',
    this.terminalSessionId,
    this.terminalStreamState = '',
    this.raw = const <String, dynamic>{},
    this.workspaceId,
    this.interruptedBy,
    this.interruptionReason,
    this.artifacts = const [],
    this.actions = const [],
    this.subagentStatusText = '',
    this.subagentEvents = const [],
    this.success = true,
  });

  factory AgentToolEventData.fromMap(Map<dynamic, dynamic>? map) {
    final raw = Map<String, dynamic>.from(
      (map ?? const <dynamic, dynamic>{}).map(
        (key, value) => MapEntry(key.toString(), value),
      ),
    );
    final itemType = _asNonEmptyString(raw['type']);
    final isCodexTool = itemType != null && isCodexToolItemType(itemType);
    final normalized = isCodexTool
        ? normalizeCodexToolCall(
            raw,
            itemType: itemType,
            fallbackToolType: _asNonEmptyString(raw['toolType']) ?? 'builtin',
            fallbackTitle:
                _asNonEmptyString(raw['toolTitle']) ??
                _asNonEmptyString(raw['displayName']),
            fallbackStatus: _asNonEmptyString(raw['status']) ?? '',
          )
        : null;
    return AgentToolEventData(
      taskId: _firstString(raw, const ['taskId', 'task_id']),
      cardId: _firstString(raw, const ['cardId', 'card_id']),
      toolCallId: (raw['toolCallId'] ?? raw['tool_call_id'] ?? '').toString(),
      toolName:
          normalized?.toolName ??
          _firstString(raw, const ['toolName', 'tool_name']),
      displayName:
          normalized?.displayName ??
          _firstString(raw, const [
            'displayName',
            'display_name',
            'toolName',
            'tool_name',
          ]),
      toolTitle:
          normalized?.toolTitle ??
          _firstString(raw, const ['toolTitle', 'tool_title']),
      toolType:
          normalized?.toolType ??
          _firstString(raw, const [
            'toolType',
            'tool_type',
          ], fallback: 'builtin'),
      serverName:
          normalized?.serverName ??
          _firstNullableString(raw, const ['serverName', 'server_name']),
      status: normalized?.status ?? (raw['status'] ?? '').toString(),
      argsJson:
          normalized?.argsJson ??
          _firstString(raw, const ['argsJson', 'args_json', 'args']),
      progress: normalized?.progress ?? (raw['progress'] ?? '').toString(),
      summary: normalized?.summary ?? (raw['summary'] ?? '').toString(),
      resultPreviewJson:
          normalized?.resultPreviewJson ??
          _firstString(raw, const ['resultPreviewJson', 'result_preview_json']),
      rawResultJson:
          normalized?.rawResultJson ??
          _firstString(raw, const ['rawResultJson', 'raw_result_json']),
      terminalOutput:
          normalized?.terminalOutput ??
          _firstString(raw, const ['terminalOutput', 'terminal_output']),
      terminalOutputDelta: _firstString(raw, const [
        'terminalOutputDelta',
        'terminal_output_delta',
      ]),
      terminalSessionId: _firstNullableString(raw, const [
        'terminalSessionId',
        'terminal_session_id',
      ]),
      terminalStreamState: _firstString(raw, const [
        'terminalStreamState',
        'terminal_stream_state',
      ]),
      raw: raw,
      workspaceId: _firstNullableString(raw, const [
        'workspaceId',
        'workspace_id',
      ]),
      interruptedBy: _firstNullableString(raw, const [
        'interruptedBy',
        'interrupted_by',
      ]),
      interruptionReason: _firstNullableString(raw, const [
        'interruptionReason',
        'interruption_reason',
      ]),
      artifacts: ((raw['artifacts'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      actions: ((raw['actions'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      subagentStatusText: (raw['subagentStatusText'] ?? '').toString(),
      subagentEvents: _readSubagentEvents(
        raw['subagentEvents'] ?? raw['subagentEvent'],
      ),
      success: raw['success'] != false,
    );
  }

  static String? _asNonEmptyString(dynamic value) {
    final text = value?.toString().trim() ?? '';
    return text.isEmpty ? null : text;
  }

  static String _firstString(
    Map<String, dynamic> raw,
    List<String> keys, {
    String fallback = '',
  }) {
    return _firstNullableString(raw, keys) ?? fallback;
  }

  static String? _firstNullableString(
    Map<String, dynamic> raw,
    List<String> keys,
  ) {
    for (final key in keys) {
      final value = raw[key];
      if (value == null) {
        continue;
      }
      final text = value.toString();
      if (text.trim().isNotEmpty) {
        return text;
      }
    }
    return null;
  }

  static List<Map<String, dynamic>> _readSubagentEvents(dynamic value) {
    final rawEvents = value is List
        ? value
        : value is Map
        ? <dynamic>[value]
        : const <dynamic>[];
    return rawEvents
        .whereType<Map>()
        .map(
          (item) => item.map<String, dynamic>(
            (key, value) => MapEntry(key.toString(), value),
          ),
        )
        .toList(growable: false);
  }
}

class AgentAiConfigChangedEvent {
  final String source;
  final String path;

  const AgentAiConfigChangedEvent({required this.source, required this.path});

  factory AgentAiConfigChangedEvent.fromMap(Map<dynamic, dynamic>? map) {
    return AgentAiConfigChangedEvent(
      source: (map?['source'] ?? '').toString(),
      path: (map?['path'] ?? '').toString(),
    );
  }
}

class AssistsMessageService {
  static const MethodChannel assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  // 回调函数
  static CardPushCallback? _onCardPushCallback;
  static TaskFinishCallback? _onTaskFinishCallback;
  static ChatTaskMessageCallBack? _onChatTaskMessageCallBack;
  static ChatTaskMessageEndCallBack? _onChatTaskMessageEndCallBack;
  static VLMRequestUserInputCallBack? _onVLMRequestUserInputCallBack;
  static DispatchStreamDataCallBack? _onDispatchStreamDataCallBack;
  static DispatchStreamEndCallBack? _onDispatchStreamEndCallBack;
  static DispatchStreamErrorCallBack? _onDispatchStreamErrorCallBack;

  // Agent回调
  static AgentPromptTokenUsageCallback? _onAgentPromptTokenUsageCallback;
  static AgentContextCompactionStateCallback?
  _onAgentContextCompactionStateCallback;

  static ScheduledTaskCancelledCallBack? _onScheduledTaskCancelledCallBack;
  static ScheduledTaskExecuteNowCallBack? _onScheduledTaskExecuteNowCallBack;
  static final StreamController<AgentAiConfigChangedEvent>
  _agentAiConfigChangedController =
      StreamController<AgentAiConfigChangedEvent>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationListChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationMessagesChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _browserSessionSnapshotChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _workbenchProjectUpdatedController =
      StreamController<Map<String, dynamic>>.broadcast();
  // IM/WeChat/Telegram 等外部入口直推的用户消息：
  // 原生侧在写库后立刻 invokeMethod 发过来，runtime 直接插入气泡，
  // 不依赖 messagesChanged + DB reload 的事件链。
  static final List<void Function(Map<String, dynamic>)>
  _onExternalUserMessageAppendedCallbacks = [];

  // 改为回调列表，支持多个监听器
  static final List<ChatTaskMessageCallBack> _onChatTaskMessageCallBacks = [];
  static final List<ChatTaskMessageEndCallBack> _onChatTaskMessageEndCallBacks =
      [];
  static final List<AgentStreamEventCallback> _onAgentStreamEventCallbacks = [];
  static final List<VLMTaskFinishEndCallBack> _onVLMTaskFinishCallBacks = [];
  static final List<CommonTaskFinishEndCallBack> _onCommonTaskFinishCallBacks =
      [];

  static Stream<AgentAiConfigChangedEvent> get agentAiConfigChangedStream =>
      _agentAiConfigChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationListChangedStream =>
      _conversationListChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationMessagesChangedStream =>
      _conversationMessagesChangedController.stream;
  static Stream<Map<String, dynamic>> get browserSessionSnapshotChangedStream =>
      _browserSessionSnapshotChangedController.stream;
  static Stream<Map<String, dynamic>> get workbenchProjectUpdatedStream =>
      _workbenchProjectUpdatedController.stream;

  static void initialize() {
    assistCore.setMethodCallHandler(_handleMethod);
  }

  static void dispatchAgentAiConfigChanged(AgentAiConfigChangedEvent event) {
    _agentAiConfigChangedController.add(event);
  }

  static Future<dynamic> _handleMethod(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onCardPush':
          final Map<String, dynamic> cardData = Map<String, dynamic>.from(
            call.arguments,
          );
          _onCardPushCallback?.call(cardData['data']);
          break;

        case 'onTaskFinish':
          debugPrint('任务完成');
          _onTaskFinishCallback?.call();
          break;
        case 'onAgentAiConfigChanged':
          final data = Map<String, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          // Defer broadcast to the next event-loop turn so listeners can
          // safely invoke the same platform channel without re-entrancy.
          unawaited(
            Future<void>(() {
              dispatchAgentAiConfigChanged(
                AgentAiConfigChangedEvent.fromMap(data),
              );
            }),
          );
          break;
        case 'onConversationListChanged':
          _conversationListChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onConversationMessagesChanged':
          _conversationMessagesChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onExternalUserMessageAppended':
          final data = Map<String, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          for (final callback in List<void Function(Map<String, dynamic>)>.from(
            _onExternalUserMessageAppendedCallbacks,
          )) {
            try {
              callback(data);
            } catch (_) {}
          }
          break;
        case 'onBrowserSessionSnapshotUpdated':
          _browserSessionSnapshotChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'workbenchProjectUpdated':
          _workbenchProjectUpdatedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onChatMessage':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          debugPrint(
            'onChatMessage content: ${data['content']}, type: ${data['type']}',
          );
          _onChatTaskMessageCallBack?.call(
            data['taskID'],
            data['content'],
            data['type'],
          );
          for (final callback in _onChatTaskMessageCallBacks) {
            callback(data['taskID'], data['content'], data['type']);
          }
          break;
        case 'onChatMessageEnd':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final endTurnUsage = data['turnUsage'] != null
              ? Map<String, dynamic>.from(data['turnUsage'] as Map)
              : null;
          _onChatTaskMessageEndCallBack?.call(
            data['taskID'],
            turnUsage: endTurnUsage,
          );
          for (final callback in _onChatTaskMessageEndCallBacks) {
            callback(data['taskID'], turnUsage: endTurnUsage);
          }
          break;
        case 'onVLMRequestUserInput':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          debugPrint('onVLMRequestUserInput question: ${data['question']}');
          _onVLMRequestUserInputCallBack?.call(
            data['question'],
            data['taskId']?.toString(),
          );
          break;
        case 'onVLMTaskFinish':
          debugPrint('任务完成');
          // 通知所有注册的回调
          for (final callback in _onVLMTaskFinishCallBacks) {
            callback((call.arguments as Map?)?['taskId']?.toString());
          }
          break;
        case 'onCommonTaskFinish':
          debugPrint('任务完成');
          // 通知所有注册的回调
          for (final callback in _onCommonTaskFinishCallBacks) {
            callback();
          }
          break;
        case 'onDispatchStreamData':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamDataCallBack?.call(
            data['taskID'] ?? '',
            data['data'] ?? '',
            data['fullContent'] ?? '',
          );
          break;
        case 'onDispatchStreamEnd':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamEndCallBack?.call(
            data['taskID'] ?? '',
            data['fullContent'] ?? '',
          );
          break;
        case 'onDispatchStreamError':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onDispatchStreamErrorCallBack?.call(
            data['taskID'] ?? '',
            data['error'] ?? '',
            data['fullContent'] ?? '',
            data['isRateLimited'] == true,
          );
          break;
        case 'onAgentPromptTokenUsageChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          final latestPromptTokens = _asNullableInt(data['latestPromptTokens']);
          if (latestPromptTokens == null) {
            break;
          }
          _onAgentPromptTokenUsageCallback?.call(
            (data['taskId'] ?? '').toString(),
            latestPromptTokens,
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentContextCompactionStateChanged':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onAgentContextCompactionStateCallback?.call(
            (data['taskId'] ?? '').toString(),
            data['isCompacting'] == true,
            _asNullableInt(data['latestPromptTokens']),
            _asNullableInt(data['promptTokenThreshold']),
          );
          break;
        case 'onAgentStreamEvent':
          final event = AgentStreamEvent.fromMap(call.arguments as Map?);
          for (final callback in _onAgentStreamEventCallbacks) {
            callback(event);
          }
          break;
        case 'onScheduledTaskCancelled':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskCancelledCallBack?.call(data['taskId'] ?? '');
          break;
        case 'onScheduledTaskExecuteNow':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskExecuteNowCallBack?.call(data['taskId'] ?? '');
          break;
        case 'agentImagePick':
          final args = call.arguments is Map
              ? Map<String, dynamic>.from(call.arguments as Map)
              : <String, dynamic>{};
          final sourceStr = args['source']?.toString() ?? 'gallery';
          final source = sourceStr == 'camera'
              ? ImageSource.camera
              : ImageSource.gallery;
          final XFile? file = await ImagePicker().pickImage(
            source: source,
            imageQuality: 85,
          );
          return file == null ? null : {'path': file.path, 'name': file.name};

        case 'agentImagePickMultiple':
          final multiArgs = call.arguments is Map
              ? Map<String, dynamic>.from(call.arguments as Map)
              : <String, dynamic>{};
          final limit = (multiArgs['limit'] as num?)?.toInt() ?? 9;
          final files = await ImagePicker().pickMultiImage(
            imageQuality: 85,
            limit: limit,
          );
          return files.map((f) => {'path': f.path, 'name': f.name}).toList();

        case 'agentScheduleCreate':
          return await AgentScheduleBridgeService.createTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentScheduleList':
          return await AgentScheduleBridgeService.listTasks();
        case 'agentScheduleUpdate':
          return await AgentScheduleBridgeService.updateTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );
        case 'agentScheduleDelete':
          return await AgentScheduleBridgeService.deleteTask(
            Map<String, dynamic>.from(call.arguments as Map),
          );

        default:
          debugPrint('未处理的方法: ${call.method}');
      }
    } catch (e) {
      debugPrint('处理方法调用时出错: $e');
      rethrow;
    }
  }

  // 设置回调函数
  static void setOnCardPushCallback(CardPushCallback callback) {
    _onCardPushCallback = callback;
  }

  static void setOnTaskFinishCallback(TaskFinishCallback callback) {
    _onTaskFinishCallback = callback;
  }

  static void setOnChatTaskMessageCallBack(ChatTaskMessageCallBack callback) {
    _onChatTaskMessageCallBack = callback;
  }

  static void addOnChatTaskMessageCallBack(ChatTaskMessageCallBack? callback) {
    if (callback != null && !_onChatTaskMessageCallBacks.contains(callback)) {
      _onChatTaskMessageCallBacks.add(callback);
    }
  }

  static void removeOnChatTaskMessageCallBack(
    ChatTaskMessageCallBack? callback,
  ) {
    _onChatTaskMessageCallBacks.remove(callback);
  }

  static void setOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack callback,
  ) {
    _onChatTaskMessageEndCallBack = callback;
  }

  static void addOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack? callback,
  ) {
    if (callback != null &&
        !_onChatTaskMessageEndCallBacks.contains(callback)) {
      _onChatTaskMessageEndCallBacks.add(callback);
    }
  }

  static void removeOnChatTaskMessageEndCallBack(
    ChatTaskMessageEndCallBack? callback,
  ) {
    _onChatTaskMessageEndCallBacks.remove(callback);
  }

  static void setOnVLMRequestUserInputCallBack(
    VLMRequestUserInputCallBack callback,
  ) {
    _onVLMRequestUserInputCallBack = callback;
  }

  static void setOnVLMTaskFinishCallBack(VLMTaskFinishEndCallBack? callback) {
    if (callback != null && !_onVLMTaskFinishCallBacks.contains(callback)) {
      _onVLMTaskFinishCallBacks.add(callback);
    }
  }

  static void setOnCommonTaskFinishCallBack(
    CommonTaskFinishEndCallBack? callback,
  ) {
    if (callback != null && !_onCommonTaskFinishCallBacks.contains(callback)) {
      _onCommonTaskFinishCallBacks.add(callback);
    }
  }

  static void removeOnVLMTaskFinishCallBack(
    VLMTaskFinishEndCallBack? callback,
  ) {
    _onVLMTaskFinishCallBacks.remove(callback);
  }

  static void removeOnCommonTaskFinishCallBack(
    CommonTaskFinishEndCallBack? callback,
  ) {
    _onCommonTaskFinishCallBacks.remove(callback);
  }

  static void setOnDispatchStreamDataCallBack(
    DispatchStreamDataCallBack? callback,
  ) {
    _onDispatchStreamDataCallBack = callback;
  }

  static void setOnDispatchStreamEndCallBack(
    DispatchStreamEndCallBack? callback,
  ) {
    _onDispatchStreamEndCallBack = callback;
  }

  static void setOnDispatchStreamErrorCallBack(
    DispatchStreamErrorCallBack? callback,
  ) {
    _onDispatchStreamErrorCallBack = callback;
  }

  static void setOnScheduledTaskCancelledCallBack(
    ScheduledTaskCancelledCallBack? callback,
  ) {
    _onScheduledTaskCancelledCallBack = callback;
  }

  static void setOnScheduledTaskExecuteNowCallBack(
    ScheduledTaskExecuteNowCallBack? callback,
  ) {
    _onScheduledTaskExecuteNowCallBack = callback;
  }

  static void setOnAgentPromptTokenUsageCallback(
    AgentPromptTokenUsageCallback? callback,
  ) {
    _onAgentPromptTokenUsageCallback = callback;
  }

  static void setOnAgentContextCompactionStateCallback(
    AgentContextCompactionStateCallback? callback,
  ) {
    _onAgentContextCompactionStateCallback = callback;
  }

  static int? _asNullableInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw);
    return null;
  }

  static void setOnAgentStreamEventCallback(
    AgentStreamEventCallback? callback,
  ) {
    if (callback != null && !_onAgentStreamEventCallbacks.contains(callback)) {
      _onAgentStreamEventCallbacks.add(callback);
    }
  }

  static void removeOnAgentStreamEventCallback(
    AgentStreamEventCallback? callback,
  ) {
    _onAgentStreamEventCallbacks.remove(callback);
  }

  static void addOnExternalUserMessageAppendedCallback(
    void Function(Map<String, dynamic>) callback,
  ) {
    if (!_onExternalUserMessageAppendedCallbacks.contains(callback)) {
      _onExternalUserMessageAppendedCallbacks.add(callback);
    }
  }

  static void removeOnExternalUserMessageAppendedCallback(
    void Function(Map<String, dynamic>) callback,
  ) {
    _onExternalUserMessageAppendedCallbacks.remove(callback);
  }

  // 发送按钮点击事件到Android端
  static Future<bool> clickButton(
    String taskID,
    String btnId,
    String value, //需要保留.因为有多选数据比如选择app列表,具体协议再定义
    bool isNeedPermission, //是否需要检查权限
  ) async {
    try {
      var result = await assistCore.invokeMethod('clickButton', {
        'taskID': taskID,
        'id': btnId,
        'value': value,
        'isNeedPermission': isNeedPermission,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('发送按钮点击事件失败: ${e.message}');
      return false;
    }
  }

  // 创建陪伴任务
  static Future<bool> createCompanionTask() async {
    var result = await assistCore.invokeMethod('createCompanionTask');
    return result == "SUCCESS";
  }

  //取消陪伴任务
  static Future<bool> cancelTask() async {
    var result = await assistCore.invokeMethod('cancelTask');
    return result == "SUCCESS";
  }

  /// 取消正在运行的任务，不影响陪伴模式
  static Future<bool> cancelRunningTask({String? taskId}) async {
    try {
      var result = await assistCore.invokeMethod(
        'cancelRunningTask',
        taskId == null ? null : {'taskId': taskId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('取消运行中任务失败: ${e.message}');
      return false;
    }
  }

  /// 查询后端当前正在执行的 Agent 任务。
  static Future<List<Map<String, dynamic>>> listActiveAgentRuns() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentRunList',
      );
      final runs = (result?['runs'] as List?) ?? const [];
      return runs
          .whereType<Map>()
          .map(
            (item) => item.map((key, value) => MapEntry(key.toString(), value)),
          )
          .toList(growable: false);
    } on Exception catch (e) {
      final message = e is PlatformException ? e.message : e.toString();
      debugPrint('查询运行中 Agent 失败: $message');
      return const [];
    }
  }

  /// 停止当前 Agent 正在执行的工具调用，但不终止整轮 Agent 响应
  static Future<bool> stopAgentToolCall({
    required String taskId,
    required String cardId,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'stopAgentToolCall',
        <String, String>{'taskId': taskId, 'cardId': cardId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('停止工具调用失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> retryAgentTask({required String taskId}) async {
    try {
      final result = await assistCore.invokeMethod(
        'retryAgentTask',
        <String, String>{'taskId': taskId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('retryAgentTask failed: ${e.message}');
      return false;
    }
  }

  static Future<bool> continueAgentTask({required String taskId}) async {
    try {
      final result = await assistCore.invokeMethod(
        'continueAgentTask',
        <String, String>{'taskId': taskId},
      );
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('continueAgentTask failed: ${e.message}');
      return false;
    }
  }

  /// 取消陪伴任务的回到桌面操作
  /// 当用户在开启陪伴后离开主页时调用
  static Future<bool> cancelCompanionGoHome() async {
    try {
      var result = await assistCore.invokeMethod('cancelCompanionGoHome');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('取消回到桌面失败: ${e.message}');
      return false;
    }
  }

  /// Trigger the system Home action.
  static Future<bool> pressHome() async {
    try {
      var result = await assistCore.invokeMethod('pressHome');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('pressHome failed: ${e.message}');
      return false;
    }
  }

  // cancel chat task
  static Future<bool> cancelChatTask({String? taskId}) async {
    var result = await assistCore.invokeMethod(
      'cancelChatTask',
      taskId == null ? null : {'taskId': taskId},
    );
    return result == "SUCCESS";
  }

  static Future<UtgBridgeConfig> getUtgBridgeConfig() async {
    final result = await assistCore.invokeMethod('getUtgBridgeConfig');
    return UtgBridgeConfig.fromMap(result as Map?);
  }

  static Future<UtgBridgeConfig> saveUtgBridgeConfig({
    bool? utgEnabled,
    bool? providerAutoStartEnabled,
    String? omniflowBaseUrl,
    String? providerStartCommand,
    String? providerWorkingDirectory,
  }) async {
    final result = await assistCore.invokeMethod('saveUtgBridgeConfig', {
      if (utgEnabled != null) 'utgEnabled': utgEnabled,
      if (providerAutoStartEnabled != null)
        'providerAutoStartEnabled': providerAutoStartEnabled,
      if (omniflowBaseUrl != null) 'omniflowBaseUrl': omniflowBaseUrl,
      if (providerStartCommand != null)
        'providerStartCommand': providerStartCommand,
      if (providerWorkingDirectory != null)
        'providerWorkingDirectory': providerWorkingDirectory,
    });
    return UtgBridgeConfig.fromMap(result as Map?);
  }

  static Future<UtgProviderControlResult> controlUtgProvider({
    required String action,
  }) async {
    final result = await assistCore.invokeMethod('controlUtgProvider', {
      'action': action.trim(),
    });
    return UtgProviderControlResult.fromMap(result as Map?);
  }

  static Future<UtgBridgeExecutionContext>
  getUtgBridgeExecutionContext() async {
    final result = await assistCore.invokeMethod(
      'getUtgBridgeExecutionContext',
    );
    return UtgBridgeExecutionContext.fromMap(result as Map?);
  }

  static Future<Map<String, dynamic>> getAgentToolFeatures() async {
    final result = await assistCore.invokeMethod<Map>('getAgentToolFeatures');
    return Map<String, dynamic>.from(result ?? const {});
  }

  static Future<Map<String, dynamic>> setAgentToolFeatures({
    bool? functionRecallEnabled,
  }) async {
    final result = await assistCore.invokeMethod<Map>('setAgentToolFeatures', {
      if (functionRecallEnabled != null)
        'functionRecallEnabled': functionRecallEnabled,
    });
    return Map<String, dynamic>.from(result ?? const {});
  }

  static Future<bool> copyToClipboard(String text) async {
    try {
      var result = await assistCore.invokeMethod('copyToClipboard', {
        'text': text,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('复制到剪贴板失败: ${e.message}');
      return false;
    }
  }

  static Future<String?> getClipboardText() async {
    try {
      final result = await assistCore.invokeMethod<String>('getClipboardText');
      return result;
    } on PlatformException catch (e) {
      debugPrint('读取剪贴板失败: ${e.message}');
      return null;
    }
  }

  //开始聊天任务
  static Future<bool> createChatTask(
    String taskID,
    List<Map<String, dynamic>> content, {
    String? provider,
    Map<String, dynamic>? openClawConfig,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
    int? conversationId,
    String? conversationMode,
    String? userMessage,
    List<Map<String, dynamic>> userAttachments = const [],
  }) async {
    try {
      debugPrint('createChatTask taskID: $taskID content: $content');
      final args = {'taskID': taskID, 'content': content};
      if (provider != null) {
        args['provider'] = provider;
      }
      if (openClawConfig != null) {
        args['openClawConfig'] = openClawConfig;
      }
      if (modelOverride != null) {
        args['modelOverride'] = modelOverride;
      }
      if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty) {
        args['reasoningEffort'] = reasoningEffort.trim();
      }
      if (conversationId != null) {
        args['conversationId'] = conversationId;
      }
      if (conversationMode != null && conversationMode.trim().isNotEmpty) {
        args['conversationMode'] = conversationMode.trim();
      }
      if (userMessage != null) {
        args['userMessage'] = userMessage;
      }
      if (userAttachments.isNotEmpty) {
        args['userAttachments'] = userAttachments;
      }
      final result = await assistCore.invokeMethod('createChatTask', args);
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('createChatTask failed: ${e.message}');
      return false;
    }
  }

  //开始视觉模型任务
  static Future<bool> createVLMOperationTask(
    String goal, {
    String? taskId,
    String model = "scene.vlm.operation.primary",
    int maxSteps = 25,
    String? packageName,
    bool needSummary = false,
    bool skipGoHome = false, // 是否跳过回到主页，从当前页面开始执行
  }) async {
    debugPrint(
      'createVLMOperationTask goal: $goal model: $model  maxSteps: $maxSteps packageName: $packageName needSummary: $needSummary skipGoHome: $skipGoHome',
    );
    var result = await assistCore.invokeMethod('createVLMOperationTask', {
      'goal': goal,
      if (taskId != null) 'taskId': taskId,
      'model': model,
      'maxSteps': maxSteps,
      'packageName': packageName,
      'needSummary': needSummary,
      'skipGoHome': skipGoHome,
    });

    return result == "SUCCESS";
  }

  /// 向运行中的VLM任务提供用户输入（INFO动作）
  static Future<bool> provideUserInputToVLMTask(String userInput) async {
    try {
      final result = await assistCore.invokeMethod<bool>(
        'provideUserInputToVLMTask',
        {'userInput': userInput},
      );
      return result == true;
    } on PlatformException catch (e) {
      debugPrint('提供用户输入失败: ${e.message}');
      return false;
    }
  }

  static bool isVlmManualTakeoverPrompt(String? question) {
    final normalized = question?.trim().toLowerCase() ?? '';
    if (normalized.isEmpty) return false;
    return normalized.contains('已接管控制') ||
        normalized.contains('用户已接管') ||
        (normalized.contains('takeover') && normalized.contains('continue')) ||
        (normalized.contains('taken over') && normalized.contains('continue'));
  }

  static Future<bool> continueVLMTaskPrompt({
    required String? question,
    required String userInput,
  }) {
    if (isVlmManualTakeoverPrompt(question)) {
      return resumeVLMTask();
    }
    return provideUserInputToVLMTask(userInput);
  }

  static Future<bool> pauseVLMTask() async {
    try {
      final result = await assistCore.invokeMethod<bool>('pauseVLMTask');
      return result == true;
    } on PlatformException catch (e) {
      debugPrint('暂停VLM任务失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> resumeVLMTask() async {
    try {
      final result = await assistCore.invokeMethod<bool>('resumeVLMTask');
      return result == true;
    } on PlatformException catch (e) {
      debugPrint('恢复VLM任务失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> isCompanionTaskRunning() async {
    return await assistCore.invokeMethod('isCompanionTaskRunning', {});
  }

  /// 获取已安装应用（包含中文应用名和包名）
  static Future<List<Map<String, dynamic>>> getInstalledApplications() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplications',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      debugPrint('获取已安装应用失败: ${e.message}');
      return [];
    }
  }

  /// 获取已安装应用（附带图标更新）
  static Future<List<Map<String, dynamic>>>
  getInstalledApplicationsWithIconUpdate() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplicationsWithIconUpdate',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      debugPrint('获取已安装应用(附带图标更新)失败: ${e.message}');
      return [];
    }
  }

  /// 开源版不提供 suggestions
  static Future<List<Map<String, dynamic>>> getSuggestions() async {
    return [];
  }

  static Future<bool> isPackageAuthorized(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<bool>(
        'isPackageAuthorized',
        {'packageName': packageName},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('检查包名授权状态失败: ${e.message}');
      return false;
    }
  }

  // 开源版已移除学习模式

  /// 预约VLM操作任务
  static Future<String?> scheduleVLMOperationTask(
    String goal, //目标文本
    int times, { //预约时间
    String model = "scene.vlm.operation.primary", //模型(sceneId)
    int maxSteps = 25, //最大步数
    String? packageName, //执行任务包名
    String title = "", //任务标题
    String? subTitle, //子标题
    String? extraJson, //额外参数,获取info时会返回
  }) async {
    debugPrint(
      'scheduleVLMOperationTask goal: $goal, times: $times, model: $model, maxSteps: $maxSteps, packageName: $packageName',
    );
    try {
      final result = await assistCore
          .invokeMethod<String>('scheduleVLMOperationTask', {
            'goal': goal,
            'model': model,
            'maxSteps': maxSteps,
            'packageName': packageName,
            'times': times,
            'title': title,
            'subTitle': subTitle,
            'extraJson': extraJson,
          });
      return result;
    } on PlatformException catch (e) {
      debugPrint('预约VLM操作任务失败: ${e.message}');
      return null;
    }
  }

  /// 获取预约任务信息信息
  static Future<Map<String, dynamic>?> getScheduleTaskInfo() async {
    try {
      final result = await assistCore.invokeMethod<Map<Object?, Object?>>(
        'getScheduleInfo',
      );
      if (result != null) {
        return result.cast<String, dynamic>();
      }
      return null;
    } on PlatformException catch (e) {
      debugPrint('获取预约任务信息失败: ${e.message}');
      return null;
    }
  }

  /// 清除预约任务
  static Future<bool> clearScheduleTask() async {
    try {
      final result = await assistCore.invokeMethod('clearScheduleTask');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('清除预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 立即执行预约任务
  static Future<bool> doScheduleNow() async {
    try {
      final result = await assistCore.invokeMethod('doScheduleNow');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('立即执行预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 取消预约任务
  static Future<bool> cancelScheduleTask() async {
    try {
      final result = await assistCore.invokeMethod('cancelScheduleTask');
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('取消预约任务失败: ${e.message}');
      return false;
    }
  }

  /// 查询统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<List<Map<String, dynamic>>> listAgentExactAlarms() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'listAgentExactAlarms',
      );
      if (result == null) return [];
      return result.map((item) {
        if (item is Map) {
          return Map<String, dynamic>.from(item);
        }
        return <String, dynamic>{};
      }).toList();
    } on PlatformException catch (e) {
      debugPrint('查询应用内闹钟失败: ${e.message}');
      return [];
    }
  }

  /// 删除统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<bool> deleteAgentExactAlarm(String alarmId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteAgentExactAlarm',
        {'alarmId': alarmId},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      debugPrint('删除应用内闹钟失败: ${e.message}');
      return false;
    }
  }

  /// 停止并清空统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<bool> deleteAllAgentExactAlarms() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteAgentExactAlarm',
        {'alarmId': ''},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      debugPrint('清空应用内闹钟失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> getAlarmSettings() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'getAlarmSettings',
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      debugPrint('读取闹钟设置失败: ${e.message}');
      return {};
    }
  }

  static Future<Map<String, dynamic>> saveAlarmSettings({
    required String source,
    String? localPath,
    String? remoteUrl,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'saveAlarmSettings',
        {'source': source, 'localPath': localPath, 'remoteUrl': remoteUrl},
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      debugPrint('保存闹钟设置失败: ${e.message}');
      return {'success': false, 'message': e.message ?? '保存失败'};
    }
  }

  /// 获取当前 nanoTime（毫秒级，System.nanoTime() / 1_000_000）
  static Future<int?> getNanoTime() async {
    try {
      final result = await assistCore.invokeMethod<int>('getNanoTime');
      return result;
    } on PlatformException catch (e) {
      debugPrint('获取nanoTime失败: ${e.message}');
      return null;
    }
  }

  /// 执行首次任务
  static Future<bool> startFirstUse(String packageName) async {
    try {
      final result = await assistCore.invokeMethod('startFirstUse', {
        'packageName': packageName,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('执行首次任务失败: ${e.message}');
      return false;
    }
  }

  /// 初始化半屏引擎并启动首次体验
  static Future<void> initializeAndStartFirstUse(String packageName) async {
    debugPrint('🎯 [FirstUse] 开始初始化半屏引擎并启动首次体验');

    // 1. 首先初始化半屏引擎
    final initSuccess = await AppStateService.initHalfScreenEngine();
    if (initSuccess) {
      debugPrint('✅ [FirstUse] 半屏引擎初始化成功');
    } else {
      debugPrint('⚠️ [FirstUse] 半屏引擎初始化失败');
    }

    // 2. 延迟启动首次体验，确保引擎完全就绪
    await Future.delayed(const Duration(milliseconds: 300));

    // 3. 启动首次体验
    final startSuccess = await startFirstUse(packageName);
    if (startSuccess) {
      debugPrint('✅ [FirstUse] 首次体验启动成功');
    } else {
      debugPrint('⚠️ [FirstUse] 首次体验启动失败');
    }
  }

  /// 调用LLM chat接口（非流式）
  /// 用于修复JSON格式等场景
  static Future<String?> postLLMChat({
    required String text,
    String model = 'scene.dispatch.model',
    bool responseJsonObject = false,
  }) async {
    try {
      final result = await assistCore.invokeMethod<String>('postLLMChat', {
        'text': text,
        'model': model,
        'responseJsonObject': responseJsonObject,
      });
      return result;
    } on PlatformException catch (e) {
      debugPrint('调用LLM chat失败: ${e.message}');
      return null;
    }
  }

  /// 生成记忆中心问候语（原生端优先使用标准 tool_calls）
  static Future<String?> generateMemoryGreeting({
    required List<Map<String, String>> records,
    String model = 'scene.compactor.context',
  }) async {
    try {
      final payloadRecords = records
          .map(
            (item) => {
              'title': item['title'] ?? '',
              'description': item['description'] ?? '',
              'appName': item['appName'] ?? '',
            },
          )
          .toList();
      final result = await assistCore.invokeMethod<String>(
        'generateMemoryGreeting',
        {'model': model, 'records': payloadRecords},
      );
      return result;
    } on PlatformException catch (e) {
      debugPrint('生成记忆中心问候语失败: ${e.message}');
      return null;
    }
  }

  /// 创建 Agent 任务
  static Future<bool> createAgentTask({
    required String taskId,
    required String userMessage,
    List<Map<String, dynamic>> conversationHistory = const [],
    List<Map<String, dynamic>> attachments = const [],
    int? userMessageCreatedAtMillis,
    int? conversationId,
    String? conversationMode,
    String? scheduledTaskId,
    String? scheduledTaskTitle,
    bool? scheduleNotificationEnabled,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
    Map<String, String>? terminalEnvironment,
  }) async {
    try {
      final args = <String, dynamic>{
        'taskId': taskId,
        'userMessage': userMessage,
      };
      if (conversationHistory.isNotEmpty) {
        args['conversationHistory'] = conversationHistory;
      }
      if (conversationId != null) {
        args['conversationId'] = conversationId;
      }
      if (conversationMode != null && conversationMode.trim().isNotEmpty) {
        args['conversationMode'] = conversationMode.trim();
      }
      if (userMessageCreatedAtMillis != null &&
          userMessageCreatedAtMillis > 0) {
        args['userMessageCreatedAt'] = userMessageCreatedAtMillis;
      }
      if (scheduledTaskId != null && scheduledTaskId.trim().isNotEmpty) {
        args['scheduledTaskId'] = scheduledTaskId.trim();
      }
      if (scheduledTaskTitle != null && scheduledTaskTitle.trim().isNotEmpty) {
        args['scheduledTaskTitle'] = scheduledTaskTitle.trim();
      }
      if (scheduleNotificationEnabled != null) {
        args['scheduleNotificationEnabled'] = scheduleNotificationEnabled;
      }
      if (attachments.isNotEmpty) {
        args['attachments'] = attachments;
      }
      if (modelOverride != null) {
        args['modelOverride'] = modelOverride;
      }
      if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty) {
        args['reasoningEffort'] = reasoningEffort.trim();
      }
      if (terminalEnvironment != null && terminalEnvironment.isNotEmpty) {
        args['terminalEnvironment'] = terminalEnvironment;
      }
      final result = await assistCore.invokeMethod('createAgentTask', {
        ...args,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      debugPrint('创建 Agent 任务失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>?> captureWorkbenchAnnotationAttachment({
    required double canvasWidth,
    required double canvasHeight,
    required List<Map<String, dynamic>> drawingPaths,
    String source = 'xiaowan_floating_annotation_canvas',
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'captureWorkbenchAnnotationAttachment',
        {
          'canvasWidth': canvasWidth,
          'canvasHeight': canvasHeight,
          'drawingPaths': drawingPaths,
          'source': source,
        },
      );
      if (result == null) return null;
      return result.map((key, value) => MapEntry(key.toString(), value));
    } on PlatformException catch (e) {
      debugPrint('捕获 Workbench 标注截图失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>> compactConversationContext({
    required int conversationId,
    required String conversationMode,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<Map<dynamic, dynamic>>('compactConversationContext', {
            'conversationId': conversationId,
            'conversationMode': conversationMode,
            if (modelOverride != null) 'modelOverride': modelOverride,
            if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty)
              'reasoningEffort': reasoningEffort.trim(),
          });
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      debugPrint('手动压缩上下文失败: ${e.message}');
      return {
        'compacted': false,
        'reason': 'failed',
        'message': e.message ?? '手动压缩上下文失败',
      };
    }
  }

  static Future<Map<String, dynamic>?> upsertWorkspaceScheduledTask(
    Map<String, dynamic> task,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'upsertWorkspaceScheduledTask',
        {'task': task},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      debugPrint('更新原生定时任务失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteWorkspaceScheduledTask(String taskId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteWorkspaceScheduledTask',
        {'taskId': taskId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      debugPrint('删除原生定时任务失败: ${e.message}');
      return false;
    }
  }

  static Future<int> syncWorkspaceScheduledTasks(
    List<Map<String, dynamic>> tasks,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'syncWorkspaceScheduledTasks',
        {'tasks': tasks},
      );
      if (result == null) return 0;
      final count = result['count'];
      if (count is int) return count;
      if (count is String) return int.tryParse(count) ?? 0;
      return 0;
    } on PlatformException catch (e) {
      debugPrint('同步原生定时任务失败: ${e.message}');
      return 0;
    }
  }

  static Future<List<Map<String, dynamic>>> listAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'agentSkillList',
      );
      return (result ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList();
    } on PlatformException catch (e) {
      debugPrint('读取 Agent skills 失败: ${e.message}');
      return const [];
    }
  }

  static Future<Map<String, dynamic>?> installAgentSkill({
    required String sourcePath,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstall',
        {'sourcePath': sourcePath},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      debugPrint('安装 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> setAgentSkillEnabled({
    required String skillId,
    required bool enabled,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSetEnabled',
        {'skillId': skillId, 'enabled': enabled},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      debugPrint('切换 Agent skill 启用状态失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteAgentSkill({required String skillId}) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillDelete',
        {'skillId': skillId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      debugPrint('删除 Agent skill 失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>?> installBuiltinAgentSkill({
    required String skillId,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstallBuiltin',
        {'skillId': skillId},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      debugPrint('安装内置 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> syncOfficialAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSyncOfficialRepository',
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      debugPrint('同步官方 Agent skills 失败: ${e.message}');
      return null;
    }
  }

  /// 检测自定义 VLM 模型可用性（OpenAI-compatible）
  static Future<ModelAvailabilityCheckResult> checkVlmModelAvailability({
    required String model,
    required String apiBase,
    String apiKey = '',
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'checkVlmModelAvailability',
        {'model': model, 'apiBase': apiBase, 'apiKey': apiKey},
      );
      return ModelAvailabilityCheckResult.fromMap(result);
    } on PlatformException catch (e) {
      return ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: e.message ?? '检测失败',
      );
    } catch (e) {
      return ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: '检测失败: $e',
      );
    }
  }

  /// 打开应用市场
  static Future<String?> openAPPMarket(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<String>('openAPPMarket', {
        'packageName': packageName,
      });
      return result;
    } on PlatformException catch (e) {
      debugPrint('调用openAPPMarket失败: ${e.message}');
      return null;
    }
  }

  /// 检查是否在桌面
  static Future<bool> isDesktop() async {
    try {
      final result = await assistCore.invokeMethod<bool>('isDesktop');
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('检查是否在桌面失败: ${e.message}');
      return false;
    }
  }

  /// 获取桌面包名
  static Future<List<String>?> getDeskTopPackageName() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getDeskTopPackageName',
      );
      if (result != null) {
        return result.map((e) => e.toString()).toList();
      }
      return null;
    } on PlatformException catch (e) {
      debugPrint('获取桌面包名失败: ${e.message}');
      return null;
    }
  }

  /// 获取当前应用包名
  /// 用于从当前页面开始执行任务
  static Future<String?> getCurrentPackageName() async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'getCurrentPackageName',
      );
      return result;
    } on PlatformException catch (e) {
      debugPrint('获取当前应用包名失败: ${e.message}');
      return null;
    }
  }

  /// 同步“任务完成后自动回聊天”设置到原生层
  static Future<bool> setAutoBackToChatAfterTaskEnabled(bool enabled) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setAutoBackToChatAfterTaskEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('同步自动回聊天设置失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> setPreventScreenSleepDuringTasksEnabled(
    bool enabled,
  ) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setPreventScreenSleepDuringTasksEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('Failed to sync prevent sleep setting: ${e.message}');
      return false;
    }
  }

  static Future<bool> setTaskCompletionNotificationEnabled(bool enabled) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setTaskCompletionNotificationEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint(
        'Failed to sync task completion notification setting: ${e.message}',
      );
      return false;
    }
  }

  static Future<bool> setVisibleChatConversation({
    int? conversationId,
    String? conversationMode,
    bool visible = true,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<String>('setVisibleChatConversation', {
            'conversationId': conversationId ?? 0,
            'visible': visible,
            if (conversationMode != null) 'mode': conversationMode,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('Failed to sync visible chat conversation: ${e.message}');
      return false;
    }
  }

  static Future<bool> showTaskCompletionNotification({
    required String title,
    required String message,
    int? conversationId,
    String? conversationMode,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<String>('showTaskCompletionNotification', {
            'title': title,
            'message': message,
            if (conversationId != null) 'conversationId': conversationId,
            if (conversationMode != null) 'conversationMode': conversationMode,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('Failed to show task completion notification: ${e.message}');
      return false;
    }
  }

  /// 跳转到主引擎路由
  static Future<bool> navigateToMainEngineRoute(String route) async {
    try {
      final result = await assistCore.invokeMethod(
        'navigateToMainEngineRoute',
        {'route': route},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('跳转到主引擎路由失败: ${e.message}');
      return false;
    }
  }

  /// 显示定时任务倒计时提醒（原生浮层）
  static Future<bool> showScheduledTaskReminder({
    required String taskId,
    required String taskName,
    int countdownSeconds = 5,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'showScheduledTaskReminder',
        {
          'taskId': taskId,
          'taskName': taskName,
          'countdownSeconds': countdownSeconds,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('显示定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 隐藏定时任务倒计时提醒
  static Future<bool> hideScheduledTaskReminder() async {
    try {
      final result = await assistCore.invokeMethod('hideScheduledTaskReminder');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('隐藏定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 授权完成后重新打开ChatBot
  static Future<bool> reopenChatBotAfterAuth() async {
    try {
      final result = await assistCore.invokeMethod('reopenChatBotAfterAuth');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      debugPrint('重新打开ChatBot失败: ${e.message}');
      return false;
    }
  }
}
