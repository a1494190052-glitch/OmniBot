import 'dart:async';

import 'package:ui/services/assists_core_service.dart';

class UtgManualRunResult {
  final bool success;
  final String goal;
  final String functionId;
  final String? errorCode;
  final String? errorMessage;
  final Map<String, dynamic> terminalState;
  final String runIndexPath;
  final String runStorageDir;
  final String runFilePath;
  final Map<String, dynamic> rawJson;

  const UtgManualRunResult({
    required this.success,
    required this.goal,
    required this.functionId,
    required this.errorCode,
    required this.errorMessage,
    required this.terminalState,
    required this.runIndexPath,
    required this.runStorageDir,
    required this.runFilePath,
    required this.rawJson,
  });

  factory UtgManualRunResult.fromMap(Map<String, dynamic> map) {
    return UtgManualRunResult(
      success: map['success'] == true,
      goal: (map['goal'] ?? '').toString(),
      functionId: (map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      terminalState:
          (map['terminal_state'] as Map<dynamic, dynamic>?)?.map(
            (key, value) => MapEntry(key.toString(), value),
          ) ??
          const <String, dynamic>{},
      runIndexPath: (map['run_index_path'] ?? '').toString(),
      runStorageDir: (map['run_storage_dir'] ?? '').toString(),
      runFilePath: (map['run_file_path'] ?? '').toString(),
      rawJson: Map<String, dynamic>.from(map),
    );
  }

  Map<String, dynamic> get context {
    final raw = rawJson['context'];
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return raw.map((key, value) => MapEntry(key.toString(), value));
    }
    return const <String, dynamic>{};
  }

  List<Map<String, dynamic>> get stepResults {
    final raw =
        context['step_results'] ??
        terminalState['step_results'] ??
        rawJson['step_results'];
    if (raw is! List) return const <Map<String, dynamic>>[];
    return raw
        .whereType<Map>()
        .map(
          (item) => item.map((key, value) => MapEntry(key.toString(), value)),
        )
        .toList(growable: false);
  }

  bool get modelRequired => _truthy(
    terminalState['model_required'] ??
        rawJson['model_required'] ??
        context['model_required'],
  );

  bool get fallbackAvailable => _truthy(
    terminalState['fallback_available'] ??
        rawJson['fallback_available'] ??
        context['fallback_available'],
  );

  bool get canContinueWithAgent => false;

  bool get canContinueWithVlm => canContinueWithAgent;

  bool get delegatedToolUsed => _truthy(
    terminalState['delegated_tool_used'] ??
        rawJson['delegated_tool_used'] ??
        context['delegated_tool_used'],
  );

  int get stepCount => _intValue(
    terminalState['step_count'] ??
        rawJson['step_count'] ??
        context['step_count'],
  );

  int get successStepCount => _intValue(
    terminalState['success_step_count'] ??
        rawJson['success_step_count'] ??
        context['success_step_count'],
  );

  int? get activeStepCount => _nullableIntValue(
    _firstPresent([
      terminalState['active_step_count'],
      rawJson['active_step_count'],
      context['active_step_count'],
    ]),
  );

  int? get completedStepCount => _nullableIntValue(
    _firstPresent([
      terminalState['completed_step_count'],
      rawJson['completed_step_count'],
      context['completed_step_count'],
    ]),
  );

  int? get resumeFromStep => _nullableIntValue(
    _firstPresent([
      terminalState['resume_from_step'],
      rawJson['resume_from_step'],
      context['resume_from_step'],
    ]),
  );

  int? get failedStepIndex => _nullableIntValue(
    _firstPresent([
      terminalState['failed_step_index'],
      rawJson['failed_step_index'],
      context['failed_step_index'],
    ]),
  );

  int? get currentStepIndex => _nullableIntValue(
    _firstPresent([
      terminalState['current_step_index'],
      rawJson['current_step_index'],
      context['current_step_index'],
      failedStepIndex,
    ]),
  );

  int? get currentStepNumber {
    final explicit = _nullableIntValue(
      _firstPresent([
        terminalState['current_step_number'],
        rawJson['current_step_number'],
        context['current_step_number'],
      ]),
    );
    if (explicit != null && explicit > 0) return explicit;
    final index = currentStepIndex;
    if (index != null && index >= 0) return index + 1;
    return null;
  }

  String get runner =>
      (terminalState['runner'] ?? rawJson['runner'] ?? context['runner'] ?? '')
          .toString()
          .trim();

  String get executionStatus {
    return (terminalState['execution_status'] ??
            terminalState['executionStatus'] ??
            rawJson['execution_status'] ??
            rawJson['executionStatus'] ??
            context['execution_status'] ??
            context['executionStatus'] ??
            terminalState['status'] ??
            '')
        .toString()
        .trim();
  }

  String get taskId =>
      (terminalState['taskId'] ??
              terminalState['task_id'] ??
              terminalState['agent_task_id'] ??
              rawJson['taskId'] ??
              rawJson['task_id'] ??
              '')
          .toString()
          .trim();

  bool get completedLocal =>
      executionStatus == 'completed_local' ||
      executionStatus == 'completed' ||
      terminalState['status'] == 'completed';

  bool get completedVlmFallback =>
      executionStatus == 'completed_vlm_fallback' ||
      executionStatus == 'vlm_fallback_completed';

  bool get failed =>
      !success ||
      executionStatus == 'failed' ||
      executionStatus == 'error' ||
      terminalState['status'] == 'error';

  int get startedAtMs => _intValue(
    _firstPresent([
      rawJson['started_at_ms'],
      rawJson['startedAtMs'],
      terminalState['started_at_ms'],
      terminalState['startedAtMs'],
      _timing['started_at_ms'],
      _timing['startedAtMs'],
    ]),
  );

  int get finishedAtMs => _intValue(
    _firstPresent([
      rawJson['finished_at_ms'],
      rawJson['finishedAtMs'],
      terminalState['finished_at_ms'],
      terminalState['finishedAtMs'],
      _timing['finished_at_ms'],
      _timing['finishedAtMs'],
    ]),
  );

  int get durationMs {
    final explicit = _intValue(
      _firstPresent([
        rawJson['duration_ms'],
        rawJson['durationMs'],
        terminalState['duration_ms'],
        terminalState['durationMs'],
        _timing['duration_ms'],
        _timing['durationMs'],
        _timing['runner_duration_ms'],
        _timing['runnerDurationMs'],
      ]),
    );
    if (explicit > 0) return explicit;
    final started = startedAtMs;
    final finished = finishedAtMs;
    if (started > 0 && finished >= started) return finished - started;
    return 0;
  }

  Map<String, dynamic> get phaseMs {
    final raw =
        rawJson['phase_ms'] ??
        rawJson['phaseMs'] ??
        terminalState['phase_ms'] ??
        terminalState['phaseMs'] ??
        _timing['phase_ms'] ??
        _timing['phaseMs'];
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return raw.map((key, value) => MapEntry(key.toString(), value));
    }
    return const <String, dynamic>{};
  }

  Map<String, dynamic> get _timing {
    final raw =
        rawJson['timing'] ?? terminalState['timing'] ?? context['timing'];
    if (raw is Map<String, dynamic>) return raw;
    if (raw is Map) {
      return raw.map((key, value) => MapEntry(key.toString(), value));
    }
    return const <String, dynamic>{};
  }

  static dynamic _firstPresent(Iterable<dynamic> values) {
    for (final value in values) {
      if (value != null) return value;
    }
    return null;
  }

  static bool _truthy(dynamic value) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      final normalized = value.trim().toLowerCase();
      return normalized == 'true' || normalized == '1' || normalized == 'yes';
    }
    return false;
  }

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
  final String createdFunctionId;
  final String? errorCode;
  final String? errorMessage;
  final bool deleted;
  final bool imported;
  final bool alreadyExists;
  final int count;
  final String? cloudBaseUrl;
  final String assetKind;
  final String assetState;
  final String derivedFromRawFunctionId;
  final Map<String, dynamic> rawJson;
  final bool isUpdate;
  final double? similarity;
  final String? enrichedGoal;
  final String? originalGoal;
  final List<String> sourceRunIds;

  const UtgFunctionMutationResult({
    required this.success,
    required this.functionId,
    required this.createdFunctionId,
    required this.errorCode,
    required this.errorMessage,
    required this.deleted,
    required this.imported,
    required this.alreadyExists,
    required this.count,
    required this.cloudBaseUrl,
    required this.assetKind,
    required this.assetState,
    required this.derivedFromRawFunctionId,
    required this.rawJson,
    this.isUpdate = false,
    this.similarity,
    this.enrichedGoal,
    this.originalGoal,
    this.sourceRunIds = const [],
  });

  factory UtgFunctionMutationResult.fromMap(Map<String, dynamic> map) {
    List<String> parseSourceRunIds() {
      final raw = map['source_run_ids'];
      if (raw is List) {
        return raw.map((value) => value.toString()).toList();
      }
      return const [];
    }

    return UtgFunctionMutationResult(
      success: map['success'] == true,
      functionId: (map['function_id'] ?? '').toString(),
      createdFunctionId:
          (map['created_function_id'] ?? map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      deleted: map['deleted'] == true,
      imported: map['imported'] == true,
      alreadyExists: map['already_exists'] == true,
      count: map['count'] is num
          ? (map['count'] as num).toInt()
          : int.tryParse((map['count'] ?? '0').toString()) ?? 0,
      cloudBaseUrl: map['cloud_base_url']?.toString(),
      assetKind: (map['function_kind'] ?? map['asset_kind'] ?? '').toString(),
      assetState: (map['asset_state'] ?? '').toString(),
      derivedFromRawFunctionId: (map['derived_from_raw_function_id'] ?? '')
          .toString(),
      rawJson: Map<String, dynamic>.from(map),
      isUpdate: map['is_update'] == true,
      similarity: map['similarity'] is num
          ? (map['similarity'] as num).toDouble()
          : null,
      enrichedGoal: map['enriched_goal']?.toString(),
      originalGoal: map['original_goal']?.toString(),
      sourceRunIds: parseSourceRunIds(),
    );
  }
}

class UtgRunLogSummary {
  final String runId;
  final String goal;
  final bool success;
  final bool runFinished;
  final bool? runSuccess;
  final String runStatus;
  final String doneReason;
  final int stepCount;
  final int? startedAtMs;
  final int? finishedAtMs;
  final String startedAt;
  final String finishedAt;
  final num? durationMs;
  final String toolName;
  final String executionStatus;
  final String executionFunctionId;
  final String executionMode;
  final String actFunctionId;
  final String source;
  final String executionSummary;
  final String operationDescription;
  final String selectorLabel;
  final String selectorReason;
  final String errorMessage;
  final String finalPackageName;
  final int? tokenUsageTotal;
  final Map<String, dynamic> tokenUsage;
  final bool registeredAsFunction;
  final String registeredFunctionId;
  final int registeredFunctionCount;
  final List<String> registeredFunctionIds;
  final Map<String, dynamic> rawJson;

  const UtgRunLogSummary({
    required this.runId,
    required this.goal,
    required this.success,
    required this.runFinished,
    required this.runSuccess,
    required this.runStatus,
    required this.doneReason,
    required this.stepCount,
    required this.startedAtMs,
    required this.finishedAtMs,
    required this.startedAt,
    required this.finishedAt,
    required this.durationMs,
    required this.toolName,
    required this.executionStatus,
    required this.executionFunctionId,
    required this.executionMode,
    required this.actFunctionId,
    required this.source,
    required this.executionSummary,
    required this.operationDescription,
    required this.selectorLabel,
    required this.selectorReason,
    required this.errorMessage,
    required this.finalPackageName,
    required this.tokenUsageTotal,
    required this.tokenUsage,
    required this.registeredAsFunction,
    required this.registeredFunctionId,
    required this.registeredFunctionCount,
    required this.registeredFunctionIds,
    required this.rawJson,
  });

  factory UtgRunLogSummary.fromMap(Map<dynamic, dynamic>? map) {
    final raw = map ?? const {};
    return UtgRunLogSummary(
      runId: (raw['run_id'] ?? '').toString(),
      goal: (raw['goal'] ?? '').toString(),
      success: raw['success'] == true,
      runFinished:
          _parseBool(raw['run_finished'] ?? raw['runFinished']) ??
          ((raw['finished_at'] ?? raw['finishedAt'] ?? '')
                  .toString()
                  .trim()
                  .isNotEmpty ||
              raw['finished_at_ms'] != null ||
              raw['finishedAtMs'] != null),
      runSuccess: _parseBool(raw['run_success'] ?? raw['runSuccess']),
      runStatus: (raw['run_status'] ?? raw['runStatus'] ?? '').toString(),
      doneReason: (raw['done_reason'] ?? '').toString(),
      stepCount: raw['step_count'] is num
          ? (raw['step_count'] as num).toInt()
          : int.tryParse((raw['step_count'] ?? '0').toString()) ?? 0,
      startedAtMs: raw['started_at_ms'] is num
          ? (raw['started_at_ms'] as num).toInt()
          : int.tryParse((raw['started_at_ms'] ?? '').toString()),
      finishedAtMs: raw['finished_at_ms'] is num
          ? (raw['finished_at_ms'] as num).toInt()
          : int.tryParse((raw['finished_at_ms'] ?? '').toString()),
      startedAt: (raw['started_at'] ?? '').toString(),
      finishedAt: (raw['finished_at'] ?? '').toString(),
      durationMs: raw['duration_ms'] as num?,
      toolName: (raw['tool_name'] ?? '').toString(),
      executionStatus:
          (raw['execution_status'] ??
                  raw['recall_status'] ??
                  raw['compile_status'] ??
                  '')
              .toString(),
      executionFunctionId:
          (raw['execution_function_id'] ??
                  raw['recall_function_id'] ??
                  raw['compile_function_id'] ??
                  '')
              .toString(),
      executionMode: (raw['execution_mode'] ?? raw['compile_mode'] ?? '')
          .toString(),
      actFunctionId: (raw['act_function_id'] ?? '').toString(),
      source: (raw['source'] ?? '').toString(),
      executionSummary: _userVisibleExecutionText(
        (raw['execution_summary'] ??
                raw['recall_summary'] ??
                raw['compile_summary'] ??
                '')
            .toString(),
      ),
      operationDescription: (raw['operation_description'] ?? '').toString(),
      selectorLabel: (raw['selector_label'] ?? '').toString(),
      selectorReason: (raw['selector_reason'] ?? '').toString(),
      errorMessage: (raw['error_message'] ?? '').toString(),
      finalPackageName: (raw['final_package_name'] ?? '').toString(),
      tokenUsageTotal: raw['token_usage_total'] is num
          ? (raw['token_usage_total'] as num).toInt()
          : int.tryParse((raw['token_usage_total'] ?? '').toString()),
      tokenUsage: Map<String, dynamic>.from(
        (raw['token_usage'] as Map<dynamic, dynamic>? ?? const {}).map(
          (key, value) => MapEntry(key.toString(), value),
        ),
      ),
      registeredAsFunction:
          _parseBool(
            raw['registered_as_function'] ??
                raw['registeredAsFunction'] ??
                raw['is_registered_function'] ??
                raw['isRegisteredFunction'],
          ) ??
          false,
      registeredFunctionId:
          (raw['registered_function_id'] ?? raw['registeredFunctionId'] ?? '')
              .toString(),
      registeredFunctionCount: raw['registered_function_count'] is num
          ? (raw['registered_function_count'] as num).toInt()
          : int.tryParse(
                  (raw['registered_function_count'] ??
                          raw['registeredFunctionCount'] ??
                          '0')
                      .toString(),
                ) ??
                0,
      registeredFunctionIds:
          ((raw['registered_function_ids'] ?? raw['registeredFunctionIds'])
                  as List<dynamic>?)
              ?.map((value) => value.toString())
              .where((value) => value.trim().isNotEmpty)
              .toList() ??
          const <String>[],
      rawJson: Map<String, dynamic>.from(
        (raw['raw_run'] as Map<dynamic, dynamic>? ?? const {}).map(
          (key, value) => MapEntry(key.toString(), value),
        ),
      ),
    );
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
  final List<UtgRunLogSummary> runs;
  final String runIndexPath;
  final String runStorageDir;
  final String provider;

  const UtgRunLogsSnapshot({
    required this.success,
    required this.count,
    required this.totalCount,
    required this.limit,
    required this.offset,
    required this.nextOffset,
    required this.hasMore,
    required this.runs,
    required this.runIndexPath,
    required this.runStorageDir,
    required this.provider,
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
      hasMore: map['has_more'] == true || map['hasMore'] == true,
      runs:
          (map['runs'] as List<dynamic>?)
              ?.map((item) => UtgRunLogSummary.fromMap(item as Map?))
              .toList() ??
          const <UtgRunLogSummary>[],
      runIndexPath: (map['run_index_path'] ?? '').toString(),
      runStorageDir: (map['run_storage_dir'] ?? '').toString(),
      provider: (map['provider'] ?? '').toString(),
    );
  }
}

class FunctionLastRunLog {
  final bool success;
  final String functionId;
  final String runId;
  final bool? runSuccess;
  final String createdAt;
  final String errorMessage;
  final Map<String, dynamic> rawJson;

  const FunctionLastRunLog({
    required this.success,
    required this.functionId,
    required this.runId,
    required this.runSuccess,
    required this.createdAt,
    required this.errorMessage,
    required this.rawJson,
  });

  factory FunctionLastRunLog.fromFunctionSpec({
    required String functionId,
    Map<String, dynamic>? spec,
  }) {
    final raw = spec ?? const <String, dynamic>{};
    final effectiveSpec = _firstMap([
      raw['function_spec'],
      raw['functionSpec'],
      raw['spec'],
      raw,
    ]);
    final registry = _firstMap([
      effectiveSpec['_oob_registry'],
      effectiveSpec['registry'],
      raw['_oob_registry'],
      raw['registry'],
    ]);
    final runStats = _firstMap([
      effectiveSpec['run_stats'],
      effectiveSpec['runStats'],
      registry['run_stats'],
      registry['runStats'],
      raw['run_stats'],
      raw['runStats'],
    ]);
    final lastRun = _firstMap([
      effectiveSpec['last_run'],
      effectiveSpec['lastRun'],
      runStats['last_run'],
      runStats['lastRun'],
      raw['last_run'],
      raw['lastRun'],
    ]);
    final runId = _firstNonBlank([
      lastRun['run_id'],
      lastRun['runId'],
      runStats['last_run_id'],
      runStats['lastRunId'],
      effectiveSpec['last_run_id'],
      effectiveSpec['lastRunId'],
      raw['run_id'],
      raw['runId'],
      raw['last_run_id'],
      raw['lastRunId'],
    ]);
    final createdAt = _firstNonBlank([
      lastRun['created_at'],
      lastRun['createdAt'],
      runStats['last_run_at'],
      runStats['lastRunAt'],
      effectiveSpec['last_run_at'],
      effectiveSpec['lastRunAt'],
      raw['created_at'],
      raw['createdAt'],
      raw['last_run_at'],
      raw['lastRunAt'],
    ]);
    final errorMessage = _firstNonBlank([
      lastRun['error_message'],
      lastRun['errorMessage'],
      raw['error_message'],
      raw['errorMessage'],
    ]);
    return FunctionLastRunLog(
      success: runId.isNotEmpty,
      functionId: functionId.trim(),
      runId: runId,
      runSuccess: _boolValue(
        lastRun['success'] ??
            lastRun['run_success'] ??
            lastRun['runSuccess'] ??
            runStats['last_success'] ??
            runStats['lastSuccess'] ??
            raw['last_success'] ??
            raw['lastSuccess'],
      ),
      createdAt: createdAt,
      errorMessage: errorMessage,
      rawJson: raw,
    );
  }

  bool get hasRunLog => runId.trim().isNotEmpty;

  static Map<String, dynamic> _firstMap(Iterable<dynamic> values) {
    for (final value in values) {
      final mapped = _asMap(value);
      if (mapped.isNotEmpty) return mapped;
    }
    return const <String, dynamic>{};
  }

  static Map<String, dynamic> _asMap(dynamic value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return value.map((key, item) => MapEntry(key.toString(), item));
    }
    return const <String, dynamic>{};
  }

  static String _firstNonBlank(Iterable<dynamic> values) {
    for (final value in values) {
      final text = value?.toString().trim() ?? '';
      if (text.isNotEmpty) return text;
    }
    return '';
  }

  static bool? _boolValue(dynamic value) {
    if (value == null) return null;
    if (value is bool) return value;
    if (value is num) return value != 0;
    final normalized = value.toString().trim().toLowerCase();
    if (normalized.isEmpty) return null;
    if (normalized == 'true' || normalized == '1' || normalized == 'yes') {
      return true;
    }
    if (normalized == 'false' || normalized == '0' || normalized == 'no') {
      return false;
    }
    return null;
  }
}

class UtgRunLogImportResult {
  final bool success;
  final String runId;
  final String createdFunctionId;
  final String? errorCode;
  final String? errorMessage;
  final int pathsCreated;
  final int nodesCreated;
  final int nodesUpdated;
  final int functionsCreated;
  final List<String> warnings;
  final String runFilePath;
  final String assetKind;
  final String assetState;
  final Map<String, dynamic> rawJson;
  final List<String> hitFunctionIds;
  final int missActionCount;

  const UtgRunLogImportResult({
    required this.success,
    required this.runId,
    required this.createdFunctionId,
    required this.errorCode,
    required this.errorMessage,
    required this.pathsCreated,
    required this.nodesCreated,
    required this.nodesUpdated,
    required this.functionsCreated,
    required this.warnings,
    required this.runFilePath,
    required this.assetKind,
    required this.assetState,
    required this.rawJson,
    this.hitFunctionIds = const [],
    this.missActionCount = 0,
  });

  factory UtgRunLogImportResult.fromMap(Map<String, dynamic> map) {
    return UtgRunLogImportResult(
      success: map['success'] == true,
      runId: (map['run_id'] ?? '').toString(),
      createdFunctionId:
          (map['created_function_id'] ?? map['function_id'] ?? '').toString(),
      errorCode: map['error_code']?.toString(),
      errorMessage: map['error_message']?.toString(),
      pathsCreated: map['paths_created'] is num
          ? (map['paths_created'] as num).toInt()
          : int.tryParse((map['paths_created'] ?? '0').toString()) ?? 0,
      nodesCreated: map['nodes_created'] is num
          ? (map['nodes_created'] as num).toInt()
          : int.tryParse((map['nodes_created'] ?? '0').toString()) ?? 0,
      nodesUpdated: map['nodes_updated'] is num
          ? (map['nodes_updated'] as num).toInt()
          : int.tryParse((map['nodes_updated'] ?? '0').toString()) ?? 0,
      functionsCreated: map['functions_created'] is num
          ? (map['functions_created'] as num).toInt()
          : int.tryParse((map['functions_created'] ?? '0').toString()) ?? 0,
      warnings:
          (map['warnings'] as List<dynamic>?)
              ?.map((value) => value.toString())
              .toList() ??
          const <String>[],
      runFilePath: (map['run_file_path'] ?? '').toString(),
      assetKind: (map['function_kind'] ?? map['asset_kind'] ?? '').toString(),
      assetState: (map['asset_state'] ?? '').toString(),
      rawJson: Map<String, dynamic>.from(map),
      hitFunctionIds:
          (map['hit_function_ids'] as List<dynamic>?)
              ?.map((value) => value.toString())
              .toList() ??
          const <String>[],
      missActionCount: map['miss_action_count'] is num
          ? (map['miss_action_count'] as num).toInt()
          : int.tryParse((map['miss_action_count'] ?? '0').toString()) ?? 0,
    );
  }
}

class RunLogFunctionService {
  const RunLogFunctionService._();

  static Future<UtgRunLogsSnapshot> getInternalRunLogs({
    int limit = 50,
    int offset = 0,
  }) async {
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'getInternalRunLogs',
      {'limit': limit, 'offset': offset},
    );
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

  static Future<Map<String, dynamic>> getRunLogTimelinePreferInternal({
    required String runId,
    String? baseUrl,
  }) async {
    return getInternalRunLogTimeline(runId: runId);
  }

  static Future<Map<String, dynamic>> getVlmTaskRunLog({
    required String taskId,
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Map<Object?, Object?>>('getVlmTaskRunLog', {
          'taskId': taskId.trim(),
        });
    if (result == null) {
      return <String, dynamic>{
        'success': false,
        'task_id': taskId.trim(),
        'error_message': 'Run log not found',
      };
    }
    return result.map((key, value) => MapEntry(key.toString(), value));
  }

  static Future<UtgFunctionMutationResult> registerFunction({
    required Map<String, dynamic> functionSpec,
  }) async {
    final spec = _jsonSafeMap(functionSpec);
    final functionId = (spec['function_id'] ?? '').toString().trim();
    if (functionId.isEmpty) {
      throw Exception('function_id 为空，无法注册 Function');
    }

    final result = await AssistsMessageService.assistCore.invokeMethod(
      'registerFunction',
      {'function_spec': spec},
    );
    return UtgFunctionMutationResult.fromMap(_jsonSafeDynamicMap(result));
  }

  static Future<Map<String, dynamic>> updateFunction({
    required String functionId,
    String? runId,
    String mode = 'enhance',
    Map<String, dynamic>? analysis,
    Map<String, dynamic>? patch,
    Map<String, dynamic> extraArgs = const <String, dynamic>{},
    bool autoAnalyzeWithModel = false,
  }) async {
    final normalizedFunctionId = functionId.trim();
    if (normalizedFunctionId.isEmpty) {
      throw Exception('function_id 为空，无法更新 Function');
    }
    final args = <String, dynamic>{
      ..._jsonSafeMap(extraArgs),
      'function_id': normalizedFunctionId,
      'mode': mode.trim().isEmpty ? 'enhance' : mode.trim(),
      'auto_analyze_with_model': autoAnalyzeWithModel,
      if (runId != null && runId.trim().isNotEmpty) 'run_id': runId.trim(),
      if (analysis != null && analysis.isNotEmpty)
        'analysis': _jsonSafeMap(analysis),
      if (patch != null && patch.isNotEmpty) 'patch': _jsonSafeMap(patch),
    };
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'updateFunction',
      args,
    );
    return _jsonSafeDynamicMap(result);
  }

  static Future<Map<String, dynamic>> convertInternalRunLogToFunction({
    required String runId,
    bool register = true,
    bool agentVisible = false,
    String? functionId,
    String? name,
    String? description,
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
          'enableDebugScreenshots': enableDebugScreenshots,
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
    final result = await AssistsMessageService.assistCore
        .invokeMethod('listFunctions', {
          'limit': limit,
          'offset': offset,
          'includeHidden': includeHidden,
          'include_hidden': includeHidden,
        });
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

  static Future<FunctionLastRunLog> getFunctionLastRunLog(
    String functionId,
  ) async {
    final normalized = functionId.trim();
    if (normalized.isEmpty) {
      return const FunctionLastRunLog(
        success: false,
        functionId: '',
        runId: '',
        runSuccess: null,
        createdAt: '',
        errorMessage: 'function_id is empty',
        rawJson: <String, dynamic>{},
      );
    }
    final spec = await getFunction(normalized);
    if (spec == null || spec.isEmpty) {
      return FunctionLastRunLog(
        success: false,
        functionId: normalized,
        runId: '',
        runSuccess: null,
        createdAt: '',
        errorMessage: 'function not found',
        rawJson: const <String, dynamic>{},
      );
    }
    return FunctionLastRunLog.fromFunctionSpec(
      functionId: normalized,
      spec: spec,
    );
  }

  static Future<UtgManualRunResult> runFunction({
    required String functionId,
    Map<String, dynamic> arguments = const {},
    int? conversationId,
    String? conversationMode,
    Map<String, dynamic>? localReplayResult,
    String? taskId,
    String? frontendRunId,
  }) async {
    final args = <String, dynamic>{
      'function_id': functionId.trim(),
      'arguments': _jsonSafeMap(arguments),
    };
    if (conversationId != null && conversationId > 0) {
      args['conversationId'] = conversationId;
    }
    if (conversationMode != null && conversationMode.trim().isNotEmpty) {
      args['conversationMode'] = conversationMode.trim();
    }
    if (localReplayResult != null && localReplayResult.isNotEmpty) {
      args['localReplayResult'] = _jsonSafeMap(localReplayResult);
    }
    if (taskId != null && taskId.trim().isNotEmpty) {
      args['taskId'] = taskId.trim();
    }
    if (frontendRunId != null && frontendRunId.trim().isNotEmpty) {
      args['frontendRunId'] = frontendRunId.trim();
    }
    final result = await AssistsMessageService.assistCore.invokeMethod(
      'runFunction',
      {...args},
    );
    return UtgManualRunResult.fromMap(_jsonSafeDynamicMap(result));
  }
}

bool? _parseBool(dynamic value) {
  if (value is bool) return value;
  final text = value?.toString().trim().toLowerCase();
  if (text == 'true') return true;
  if (text == 'false') return false;
  return null;
}

String _userVisibleExecutionText(String value) {
  final trimmed = value.trim();
  if (trimmed.isEmpty) return '';
  return trimmed
      .replaceAll(RegExp(r'\bcompiled\b', caseSensitive: false), 'executed')
      .replaceAll(RegExp(r'\bcompiler\b', caseSensitive: false), 'runner')
      .replaceAll(RegExp(r'\bcompilation\b', caseSensitive: false), 'execution')
      .replaceAll(RegExp(r'\bcompile\b', caseSensitive: false), 'execute')
      .replaceAll('编译', '执行');
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
