import 'package:ui/features/home/pages/chat/chat_page_models.dart';

enum AgentStreamEventKind {
  thinkingStarted('thinking_started'),
  thinkingSnapshot('thinking_snapshot'),
  retrying('retrying'),
  textSnapshot('text_snapshot'),
  todoSnapshot('todo_snapshot'),
  toolStarted('tool_started'),
  toolProgress('tool_progress'),
  toolCompleted('tool_completed'),
  completed('completed'),
  error('error'),
  permissionRequired('permission_required'),
  clarifyRequired('clarify_required');

  const AgentStreamEventKind(this.value);

  final String value;

  static AgentStreamEventKind? fromValue(String raw) {
    final normalized = raw.trim().toLowerCase();
    for (final kind in AgentStreamEventKind.values) {
      if (kind.value == normalized) {
        return kind;
      }
    }
    return null;
  }
}

enum AgentStreamPhase {
  idle,
  thinking,
  tool,
  output,
  retrying,
  completed,
  error,
  clarify,
  permissionRequired,
}

enum AgentTodoStatus {
  pending('pending'),
  inProgress('in_progress'),
  completed('completed');

  const AgentTodoStatus(this.value);

  final String value;

  static AgentTodoStatus fromValue(String? raw) {
    final normalized = raw?.trim().toLowerCase() ?? '';
    for (final status in AgentTodoStatus.values) {
      if (status.value == normalized) {
        return status;
      }
    }
    return AgentTodoStatus.pending;
  }
}

class AgentTodoItem {
  const AgentTodoItem({
    required this.content,
    required this.activeForm,
    required this.status,
  });

  final String content;
  final String activeForm;
  final AgentTodoStatus status;

  bool get isInProgress => status == AgentTodoStatus.inProgress;
  bool get isCompleted => status == AgentTodoStatus.completed;

  factory AgentTodoItem.fromMap(Map<dynamic, dynamic> map) {
    final raw = Map<String, dynamic>.from(
      map.map((key, value) => MapEntry(key.toString(), value)),
    );
    return AgentTodoItem(
      content: (raw['content'] ?? '').toString(),
      activeForm: (raw['activeForm'] ?? raw['active_form'] ?? '').toString(),
      status: AgentTodoStatus.fromValue(raw['status']?.toString()),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'content': content,
      'activeForm': activeForm,
      'status': status.value,
    };
  }
}

class AgentStreamEvent {
  const AgentStreamEvent({
    required this.taskId,
    required this.seq,
    required this.kind,
    required this.createdAtMs,
    this.entryId,
    this.roundIndex = 0,
    this.isFinal = false,
    this.text = '',
    this.thinking = '',
    this.stage = 1,
    this.prefillTokensPerSecond,
    this.decodeTokensPerSecond,
    this.success = true,
    this.outputKind = 'none',
    this.hasUserVisibleOutput = false,
    this.latestPromptTokens,
    this.promptTokenThreshold,
    this.errorMessage = '',
    this.willRetry = false,
    this.retryable = false,
    this.retryCount = 0,
    this.maxRetries = 0,
    this.retryDelayMs = 0,
    this.retryReason = '',
    this.question = '',
    this.missingFields = const <String>[],
    this.missingPermissions = const <String>[],
    this.todos = const <AgentTodoItem>[],
    this.oldTodos = const <AgentTodoItem>[],
    this.browserSnapshot,
    this.raw = const <String, dynamic>{},
  });

  final String taskId;
  final int seq;
  final AgentStreamEventKind kind;
  final int createdAtMs;
  final String? entryId;
  final int roundIndex;
  final bool isFinal;
  final String text;
  final String thinking;
  final int stage;
  final double? prefillTokensPerSecond;
  final double? decodeTokensPerSecond;
  final bool success;
  final String outputKind;
  final bool hasUserVisibleOutput;
  final int? latestPromptTokens;
  final int? promptTokenThreshold;
  final String errorMessage;
  final bool willRetry;
  final bool retryable;
  final int retryCount;
  final int maxRetries;
  final int retryDelayMs;
  final String retryReason;
  final String question;
  final List<String> missingFields;
  final List<String> missingPermissions;
  final List<AgentTodoItem> todos;
  final List<AgentTodoItem> oldTodos;
  final ChatBrowserSessionSnapshot? browserSnapshot;
  final Map<String, dynamic> raw;

  factory AgentStreamEvent.fromMap(Map<dynamic, dynamic>? map) {
    final raw = Map<String, dynamic>.from(
      (map ?? const <String, dynamic>{}).map(
        (key, value) => MapEntry(key.toString(), value),
      ),
    );
    final kind = AgentStreamEventKind.fromValue((raw['kind'] ?? '').toString());
    if (kind == null) {
      throw ArgumentError('Unknown agent stream event kind: ${raw['kind']}');
    }
    final taskId = (raw['taskId'] ?? '').toString();
    if (taskId.trim().isEmpty) {
      throw ArgumentError('Agent stream event missing taskId');
    }
    final workspaceId = (raw['workspaceId'] ?? '').toString().trim();
    final browserSnapshot =
        kind == AgentStreamEventKind.toolCompleted &&
            (raw['toolType'] ?? '').toString().trim() == 'browser' &&
            workspaceId.isNotEmpty
        ? (ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
                rawJson: (raw['rawResultJson'] ?? '').toString(),
                workspaceId: workspaceId,
              ) ??
              ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
                rawJson: (raw['resultPreviewJson'] ?? '').toString(),
                workspaceId: workspaceId,
              ))
        : null;
    return AgentStreamEvent(
      taskId: taskId,
      seq: _asInt(raw['seq']) ?? 0,
      kind: kind,
      createdAtMs:
          _asInt(raw['createdAt']) ?? DateTime.now().millisecondsSinceEpoch,
      entryId: raw['entryId']?.toString(),
      roundIndex: _asInt(raw['roundIndex']) ?? 0,
      isFinal: raw['isFinal'] == true,
      text: (raw['text'] ?? raw['message'] ?? '').toString(),
      thinking: (raw['thinking'] ?? raw['reasoning_content'] ?? '').toString(),
      stage: _asInt(raw['stage']) ?? 1,
      prefillTokensPerSecond: _asDouble(raw['prefillTokensPerSecond']),
      decodeTokensPerSecond: _asDouble(raw['decodeTokensPerSecond']),
      success: raw['success'] != false,
      outputKind: (raw['outputKind'] ?? 'none').toString(),
      hasUserVisibleOutput: raw['hasUserVisibleOutput'] == true,
      latestPromptTokens: _asInt(raw['latestPromptTokens']),
      promptTokenThreshold: _asInt(raw['promptTokenThreshold']),
      errorMessage: (raw['error'] ?? '').toString(),
      willRetry: raw['willRetry'] == true,
      retryable: raw['retryable'] == true,
      retryCount: _asInt(raw['retryCount']) ?? 0,
      maxRetries: _asInt(raw['maxRetries']) ?? 0,
      retryDelayMs: _asInt(raw['retryDelayMs']) ?? 0,
      retryReason: (raw['retryReason'] ?? '').toString(),
      question: (raw['question'] ?? '').toString(),
      missingFields:
          (raw['missingFields'] as List<dynamic>?)
              ?.map((item) => item.toString())
              .toList(growable: false) ??
          const <String>[],
      missingPermissions:
          (raw['missing'] as List<dynamic>?)
              ?.map((item) => item.toString())
              .toList(growable: false) ??
          const <String>[],
      todos: _parseTodos(raw['todos']),
      oldTodos: _parseTodos(raw['oldTodos']),
      browserSnapshot: browserSnapshot,
      raw: raw,
    );
  }

  static List<AgentTodoItem> _parseTodos(dynamic raw) {
    if (raw is! List) {
      return const <AgentTodoItem>[];
    }
    return raw
        .map((item) {
          if (item is Map) {
            return AgentTodoItem.fromMap(item);
          }
          return null;
        })
        .whereType<AgentTodoItem>()
        .toList(growable: false);
  }

  static int? _asInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is num) return raw.toInt();
    if (raw is String) return int.tryParse(raw.trim());
    return null;
  }

  static double? _asDouble(dynamic raw) {
    if (raw is double) return raw;
    if (raw is num) return raw.toDouble();
    if (raw is String) return double.tryParse(raw.trim());
    return null;
  }
}
