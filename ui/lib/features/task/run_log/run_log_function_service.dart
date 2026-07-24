import 'dart:async';

import 'package:ui/services/assists_core_service.dart';

class UtgManualRunResult {
  final bool success;
  final String goal;
  final String functionId;
  final String? errorCode;
  final String? errorMessage;
  final Map<String, dynamic> rawJson;

  const UtgManualRunResult({
    required this.success,
    required this.goal,
    required this.functionId,
    required this.errorCode,
    required this.errorMessage,
    required this.rawJson,
  });

  factory UtgManualRunResult.fromMap(Map<String, dynamic> map) {
    return UtgManualRunResult(
      success: map['success'] == true,
      goal: (map['goal'] ?? '').toString(),
      functionId: (map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }

  List<Map<String, dynamic>> get stepResults {
    final raw = rawJson['steps'];
    if (raw is! List) return const <Map<String, dynamic>>[];
    return raw
        .whereType<Map>()
        .toList(growable: false)
        .asMap()
        .entries
        .map((entry) {
          final step = _jsonSafeDynamicMap(entry.value);
          final action = _jsonSafeDynamicMap(step['action']);
          final result = _jsonSafeDynamicMap(step['result']);
          final metadata = _jsonSafeDynamicMap(step['metadata']);
          final transfer = _jsonSafeDynamicMap(metadata['transfer']);
          final tool = (action['tool'] ?? '').toString().trim();
          final error = (result['error'] ?? '').toString().trim();
          final summary = (metadata['summary'] ?? '').toString().trim();
          return <String, dynamic>{
            'step_id': (metadata['step_id'] ?? 'step_${entry.key + 1}')
                .toString(),
            'step_index': step['step_index'] ?? entry.key,
            'tool': tool,
            'args': _jsonSafeDynamicMap(action['args']),
            'executor': 'function',
            'model_free': true,
            'success': result['success'] == true,
            'summary': summary.isNotEmpty
                ? summary
                : error.isNotEmpty
                ? error
                : tool,
            if (error.isNotEmpty) 'error_code': error,
            'source_state_id': step['before_state_id'],
            'before_state_id': step['before_state_id'],
            'after_state_id': step['after_state_id'],
            if (transfer.isNotEmpty) 'transfer': transfer,
          };
        })
        .toList(growable: false);
  }

  int get stepCount => _intValue(rawJson['step_count']);

  int get successStepCount => _intValue(rawJson['success_step_count']);

  int? get failedStepIndex => _nullableIntValue(rawJson['failed_step_index']);

  int? get currentStepIndex =>
      _nullableIntValue(rawJson['current_step_index']) ?? failedStepIndex;

  int? get currentStepNumber {
    final explicit = _nullableIntValue(rawJson['current_step_number']);
    if (explicit != null && explicit > 0) return explicit;
    final index = currentStepIndex;
    if (index != null && index >= 0) return index + 1;
    return null;
  }

  String get executionStatus {
    return (rawJson['status'] ?? '').toString().trim();
  }

  bool get completedLocal =>
      executionStatus == 'completed_local' ||
      executionStatus == 'completed' ||
      executionStatus == 'succeeded';

  bool get completedVlmFallback =>
      executionStatus == 'completed_vlm_fallback' ||
      executionStatus == 'vlm_fallback_completed';

  bool get failed =>
      !success || executionStatus == 'failed' || executionStatus == 'error';

  static int _intValue(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value.trim()) ?? 0;
    return 0;
  }

  static int? _nullableIntValue(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value.trim());
    return null;
  }
}

class UtgFunctionMutationResult {
  final bool success;
  final String functionId;
  final String? errorCode;
  final String? errorMessage;
  final Map<String, dynamic> rawJson;

  const UtgFunctionMutationResult({
    required this.success,
    required this.functionId,
    required this.errorCode,
    required this.errorMessage,
    required this.rawJson,
  });

  factory UtgFunctionMutationResult.fromMap(Map<String, dynamic> map) {
    return UtgFunctionMutationResult(
      success: map['success'] == true,
      functionId: (map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }
}

class UtgRunLogSummary {
  final String runId;
  final String goal;
  final String status;
  final bool success;
  final String error;
  final int stepCount;
  final int? startedAtMs;
  final int? finishedAtMs;
  final Map<String, dynamic> diagnostics;

  const UtgRunLogSummary({
    required this.runId,
    required this.goal,
    required this.status,
    required this.success,
    required this.error,
    required this.stepCount,
    required this.startedAtMs,
    required this.finishedAtMs,
    required this.diagnostics,
  });

  factory UtgRunLogSummary.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgRunLogSummary(
      runId: (raw['run_id'] ?? '').toString(),
      goal: (raw['goal'] ?? '').toString(),
      status: (raw['status'] ?? '').toString(),
      success: raw['success'] == true,
      error: (raw['error'] ?? '').toString(),
      stepCount: raw['step_count'] is num
          ? (raw['step_count'] as num).toInt()
          : int.tryParse((raw['step_count'] ?? '0').toString()) ?? 0,
      startedAtMs: raw['started_at_ms'] is num
          ? (raw['started_at_ms'] as num).toInt()
          : int.tryParse((raw['started_at_ms'] ?? '').toString()),
      finishedAtMs: raw['finished_at_ms'] is num
          ? (raw['finished_at_ms'] as num).toInt()
          : int.tryParse((raw['finished_at_ms'] ?? '').toString()),
      diagnostics: Map<String, dynamic>.from(
        (raw['diagnostics'] as Map<dynamic, dynamic>? ?? const {}).map(
          (key, value) => MapEntry(key.toString(), value),
        ),
      ),
    );
  }

  bool get runFinished => status != 'running';

  num? get durationMs {
    final finished = finishedAtMs;
    final started = startedAtMs;
    if (finished == null || started == null) return null;
    return (finished - started).clamp(0, 1 << 62);
  }

  String get doneReason => (diagnostics['done_reason'] ?? '').toString();

  String get toolName => (diagnostics['tool_name'] ?? '').toString();

  String get source => (diagnostics['source'] ?? '').toString();

  Map<String, dynamic> get tokenUsage => Map<String, dynamic>.from(
    (diagnostics['token_usage'] as Map<dynamic, dynamic>? ?? const {}).map(
      (key, value) => MapEntry(key.toString(), value),
    ),
  );

  int? get tokenUsageTotal {
    final value = tokenUsage['total_tokens'];
    if (value is num) return value.toInt();
    return int.tryParse((value ?? '').toString());
  }

  List<String> get modelNames {
    final rawModels = tokenUsage['resolved_models'];
    if (rawModels is List) {
      return rawModels
          .map((item) => item.toString().trim())
          .where((item) => item.isNotEmpty)
          .toList(growable: false);
    }
    final model = (tokenUsage['resolved_model'] ?? '').toString().trim();
    return model.isEmpty ? const [] : [model];
  }
}

class UtgRunLogsSnapshot {
  final bool success;
  final int count;
  final int totalCount;
  final int limit;
  final int offset;
  final int nextOffset;
  final bool hasMore;
  final List<String> availableModels;
  final List<UtgRunLogSummary> runs;

  const UtgRunLogsSnapshot({
    required this.success,
    required this.count,
    required this.totalCount,
    required this.limit,
    required this.offset,
    required this.nextOffset,
    required this.hasMore,
    required this.availableModels,
    required this.runs,
  });

  factory UtgRunLogsSnapshot.fromMap(Map<String, dynamic> map) {
    return UtgRunLogsSnapshot(
      success: map['success'] == true,
      count: map['count'] is num
          ? (map['count'] as num).toInt()
          : int.tryParse((map['count'] ?? '0').toString()) ?? 0,
      totalCount: map['total_count'] is num
          ? (map['total_count'] as num).toInt()
          : int.tryParse(
                  (map['total_count'] ?? map['count'] ?? '0').toString(),
                ) ??
                0,
      limit: map['limit'] is num
          ? (map['limit'] as num).toInt()
          : int.tryParse((map['limit'] ?? '0').toString()) ?? 0,
      offset: map['offset'] is num
          ? (map['offset'] as num).toInt()
          : int.tryParse((map['offset'] ?? '0').toString()) ?? 0,
      nextOffset: map['next_offset'] is num
          ? (map['next_offset'] as num).toInt()
          : int.tryParse((map['next_offset'] ?? '0').toString()) ?? 0,
      hasMore: map['has_more'] == true,
      availableModels:
          (map['available_models'] as List<dynamic>?)
              ?.map((item) => item.toString().trim())
              .where((item) => item.isNotEmpty)
              .toList(growable: false) ??
          const <String>[],
      runs:
          (map['runs'] as List<dynamic>?)
              ?.map((item) => UtgRunLogSummary.fromMap(item as Map?))
              .toList() ??
          const <UtgRunLogSummary>[],
    );
  }
}

class UtgRunLogImportResult {
  final bool success;
  final String runId;
  final String functionId;
  final String? errorCode;
  final String? errorMessage;
  final Map<String, dynamic> rawJson;

  const UtgRunLogImportResult({
    required this.success,
    required this.runId,
    required this.functionId,
    required this.errorCode,
    required this.errorMessage,
    required this.rawJson,
  });

  factory UtgRunLogImportResult.fromMap(Map<String, dynamic> map) {
    return UtgRunLogImportResult(
      success: map['success'] == true,
      runId: (map['run_id'] ?? '').toString(),
      functionId: (map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }
}

class RunLogFunctionService {
  const RunLogFunctionService._();

  static Future<UtgRunLogsSnapshot> getInternalRunLogs({
    int limit = 50,
    int offset = 0,
    String source = '',
    String status = '',
    String model = '',
    String query = '',
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod('getInternalRunLogs', {
          'limit': limit,
          'offset': offset,
          if (source.trim().isNotEmpty) 'source': source.trim(),
          if (status.trim().isNotEmpty) 'status': status.trim(),
          if (model.trim().isNotEmpty) 'model': model.trim(),
          if (query.trim().isNotEmpty) 'query': query.trim(),
        });
    if (result is! Map) {
      throw Exception('内部 RunLog 响应格式错误');
    }
    return UtgRunLogsSnapshot.fromMap(_jsonSafeDynamicMap(result));
  }

  static Future<UtgRunLogsSnapshot> getRunLogsPreferInternal({
    String? baseUrl,
    int limit = 50,
  }) async {
    return getInternalRunLogs(limit: limit);
  }

  static Future<Map<String, dynamic>> getInternalRunLogTimeline({
    required String runId,
  }) async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'getInternalRunLogTimeline',
      {'run_id': runId.trim()},
    );
    if (result is! Map) {
      throw Exception('内部 RunLog 响应格式错误');
    }
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> getInternalRunLogState({
    required String stateId,
  }) async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'getInternalRunLogState',
      {'state_id': stateId.trim()},
    );
    if (result is! Map) {
      throw Exception('内部 RunLog 状态响应格式错误');
    }
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> getRunLogTimelinePreferInternal({
    required String runId,
    String? baseUrl,
  }) async {
    return getInternalRunLogTimeline(runId: runId);
  }

  static Future<UtgFunctionMutationResult> registerFunction({
    required Map<String, dynamic> function,
  }) async {
    final canonicalFunction = _jsonSafeMap(function);
    final functionId = (canonicalFunction['function_id'] ?? '')
        .toString()
        .trim();
    if (functionId.isEmpty) {
      throw Exception('function_id 为空，无法注册 Function');
    }

    final result = await AssistsMessageService.assistCore.invokeMethod(
      'registerFunction',
      {'function': canonicalFunction},
    );
    return UtgFunctionMutationResult.fromMap(_jsonSafeDynamicMap(result));
  }

  static Future<Map<String, dynamic>> updateFunction({
    required String functionId,
    String? runId,
    String mode = 'edit',
    Map<String, dynamic>? patch,
    bool dryRun = false,
  }) async {
    final normalizedFunctionId = functionId.trim();
    if (normalizedFunctionId.isEmpty) {
      throw Exception('function_id 为空，无法更新 Function');
    }
    final args = <String, dynamic>{
      'function_id': normalizedFunctionId,
      'mode': mode.trim().isEmpty ? 'edit' : mode.trim(),
      if (runId != null && runId.trim().isNotEmpty) 'run_id': runId.trim(),
      if (patch != null && patch.isNotEmpty) 'patch': _jsonSafeMap(patch),
      if (dryRun) 'dry_run': true,
    };
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'updateFunction',
      args,
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> enhanceFunction({
    required String functionId,
    String? runId,
  }) {
    return updateFunction(
      functionId: functionId,
      runId: runId,
      mode: 'enhance',
    );
  }

  static Future<Map<String, dynamic>> convertInternalRunLogToFunction({
    required String runId,
    bool register = true,
    bool agentVisible = false,
    String? functionId,
    String? name,
    String? description,
    bool enhance = true,
  }) async {
    final normalizedRunId = runId.trim();
    if (normalizedRunId.isEmpty) {
      throw Exception('runId 为空，无法转换 RunLog');
    }
    final result = await AssistsMessageService.assistCore
        .invokeMethod('convertInternalRunLogToFunction', {
          'run_id': normalizedRunId,
          'register': register,
          'agent_visible': agentVisible,
          'enhance': enhance,
          if (functionId != null && functionId.trim().isNotEmpty)
            'function_id': functionId.trim(),
          if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
          if (description != null && description.trim().isNotEmpty)
            'description': description.trim(),
        });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> startHumanTrajectoryLearning({
    String? name,
    String? description,
    bool enableDebugScreenshots = false,
  }) async {
    final normalizedName = name?.trim() ?? '';
    final result = await AssistsMessageService.assistCore
        .invokeMethod('startHumanTrajectoryLearning', {
          if (normalizedName.isNotEmpty) 'name': normalizedName,
          if (description != null && description.trim().isNotEmpty)
            'description': description.trim(),
          'enable_debug_screenshots': enableDebugScreenshots,
        });
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> pauseHumanTrajectoryLearning() async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'pauseHumanTrajectoryLearning',
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> resumeHumanTrajectoryLearning() async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'resumeHumanTrajectoryLearning',
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> getHumanTrajectoryLearningStatus() async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'getHumanTrajectoryLearningStatus',
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> listFunctions({
    int limit = 100,
    int offset = 0,
    bool includeHidden = false,
  }) async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'listFunctions',
      {'limit': limit, 'offset': offset, 'include_hidden': includeHidden},
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> deleteFunction(String functionId) async {
    final normalized = functionId.trim();
    if (normalized.isEmpty) {
      return {'success': false, 'error': 'functionId is empty'};
    }
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'deleteFunction',
      {'function_id': normalized},
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>?> getFunction(String functionId) async {
    final normalized = functionId.trim();
    if (normalized.isEmpty) {
      return null;
    }
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'getFunction',
      {'function_id': normalized},
    );
    if (result is! Map) {
      return null;
    }
    return _jsonSafeDynamicMap(result);
  }

  static Future<UtgManualRunResult> runFunction({
    required String functionId,
    Map<String, dynamic> arguments = const {},
    String? taskId,
  }) async {
    final args = <String, dynamic>{
      'function_id': functionId.trim(),
      'arguments': _jsonSafeMap(arguments),
    };
    if (taskId != null && taskId.trim().isNotEmpty) {
      args['frontend_task_id'] = taskId.trim();
    }
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'runFunction',
      {...args},
    );
    return UtgManualRunResult.fromMap(_jsonSafeDynamicMap(result));
  }
}

Map<String, dynamic> _jsonSafeMap(Map<String, dynamic> value) {
  final safe = _jsonSafeValue(value);
  if (safe is Map<String, dynamic>) {
    return safe;
  }
  if (safe is Map) {
    return safe.map((key, item) => MapEntry(key.toString(), item));
  }
  return <String, dynamic>{};
}

Map<String, dynamic> _jsonSafeDynamicMap(dynamic value) {
  if (value is Map<String, dynamic>) {
    return _jsonSafeMap(value);
  }
  if (value is Map) {
    return _jsonSafeMap(
      value.map((key, item) => MapEntry(key.toString(), item)),
    );
  }
  return <String, dynamic>{};
}

dynamic _jsonSafeValue(dynamic value) {
  if (value == null || value is String || value is num || value is bool) {
    return value;
  }
  if (value is Map) {
    return value.map(
      (key, item) => MapEntry(key.toString(), _jsonSafeValue(item)),
    );
  }
  if (value is Iterable) {
    return value.map(_jsonSafeValue).toList();
  }
  return value.toString();
}
