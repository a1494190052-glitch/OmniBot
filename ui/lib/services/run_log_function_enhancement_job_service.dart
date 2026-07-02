import 'dart:async';
import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/task/run_log/function_spec.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';

enum RunLogFunctionEnhancementJobPhase {
  queued,
  enhancing,
  saving,
  completed,
  failed,
}

extension RunLogFunctionEnhancementJobPhaseX
    on RunLogFunctionEnhancementJobPhase {
  String get wireName => name;

  bool get isRunning =>
      this == RunLogFunctionEnhancementJobPhase.queued ||
      this == RunLogFunctionEnhancementJobPhase.enhancing ||
      this == RunLogFunctionEnhancementJobPhase.saving;
}

class RunLogFunctionEnhancementJob {
  const RunLogFunctionEnhancementJob({
    required this.jobId,
    required this.runId,
    required this.functionId,
    required this.inputFunctionJson,
    required this.phase,
    required this.enhancementStatus,
    required this.message,
    required this.useEnglish,
    required this.createdAt,
    required this.updatedAt,
    this.enhancedFunctionJson,
    this.agentPrompt,
    this.rawAiText,
    this.warning,
    this.enhancementReport,
    this.registrationResult,
    this.error,
    this.conversationId,
    this.taskId,
  });

  final String jobId;
  final String runId;
  final String functionId;
  final Map<String, dynamic> inputFunctionJson;
  final RunLogFunctionEnhancementJobPhase phase;
  final FunctionEnhancementStatus enhancementStatus;
  final String message;
  final bool useEnglish;
  final DateTime createdAt;
  final DateTime updatedAt;
  final Map<String, dynamic>? enhancedFunctionJson;
  final String? agentPrompt;
  final String? rawAiText;
  final String? warning;
  final Map<String, dynamic>? enhancementReport;
  final Map<String, dynamic>? registrationResult;
  final String? error;
  final int? conversationId;
  final String? taskId;

  bool get isRunning => phase.isRunning;
  bool get isCompleted =>
      phase == RunLogFunctionEnhancementJobPhase.completed &&
      enhancementStatus.isTerminal;
  bool get isSaved =>
      isCompleted &&
      registrationResult != null &&
      registrationResult?['success'] == true &&
      (registrationResult?['saved'] == true ||
          registrationResult?['imported'] == true ||
          registrationResult?['registered'] == true ||
          registrationResult?['changed'] == false ||
          registrationResult?['already_exists'] == true);

  FunctionSpec? get savedSpec {
    final json = enhancedFunctionJson;
    if (json == null || !isSaved) {
      return null;
    }
    return FunctionSpec(
      json: json,
      agentPrompt: agentPrompt ?? functionAgentPrompt(json),
      aiEnhanced: enhancementStatus.isApplied,
      rawAiText: rawAiText,
      warning: warning,
      enhancementStatus: enhancementStatus,
      enhancementMessage: message,
      enhancementReport: enhancementReport,
    );
  }

  RunLogFunctionEnhancementJob copyWith({
    RunLogFunctionEnhancementJobPhase? phase,
    FunctionEnhancementStatus? enhancementStatus,
    String? message,
    DateTime? updatedAt,
    Map<String, dynamic>? enhancedFunctionJson,
    String? agentPrompt,
    String? rawAiText,
    String? warning,
    Map<String, dynamic>? enhancementReport,
    Map<String, dynamic>? registrationResult,
    String? error,
    int? conversationId,
    String? taskId,
  }) {
    return RunLogFunctionEnhancementJob(
      jobId: jobId,
      runId: runId,
      functionId: functionId,
      inputFunctionJson: inputFunctionJson,
      phase: phase ?? this.phase,
      enhancementStatus: enhancementStatus ?? this.enhancementStatus,
      message: message ?? this.message,
      useEnglish: useEnglish,
      createdAt: createdAt,
      updatedAt: updatedAt ?? DateTime.now().toUtc(),
      enhancedFunctionJson: enhancedFunctionJson ?? this.enhancedFunctionJson,
      agentPrompt: agentPrompt ?? this.agentPrompt,
      rawAiText: rawAiText ?? this.rawAiText,
      warning: warning ?? this.warning,
      enhancementReport: enhancementReport ?? this.enhancementReport,
      registrationResult: registrationResult ?? this.registrationResult,
      error: error,
      conversationId: conversationId ?? this.conversationId,
      taskId: taskId ?? this.taskId,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'job_id': jobId,
      'run_id': runId,
      'function_id': functionId,
      'input_function_json': inputFunctionJson,
      'phase': phase.wireName,
      'enhancement_status': enhancementStatus.wireName,
      'message': message,
      'use_english': useEnglish,
      'created_at': createdAt.toIso8601String(),
      'updated_at': updatedAt.toIso8601String(),
      if (enhancedFunctionJson != null)
        'enhanced_function_json': enhancedFunctionJson,
      if (agentPrompt != null) 'agent_prompt': agentPrompt,
      if (rawAiText != null) 'raw_ai_text': rawAiText,
      if (warning != null) 'warning': warning,
      if (enhancementReport != null) 'enhancement_report': enhancementReport,
      if (registrationResult != null) 'registration_result': registrationResult,
      if (error != null) 'error': error,
      if (conversationId != null) 'conversation_id': conversationId,
      if (taskId != null) 'task_id': taskId,
    };
  }

  factory RunLogFunctionEnhancementJob.fromJson(Map<String, dynamic> json) {
    final createdAt = DateTime.tryParse((json['created_at'] ?? '').toString());
    final updatedAt = DateTime.tryParse((json['updated_at'] ?? '').toString());
    return RunLogFunctionEnhancementJob(
      jobId: (json['job_id'] ?? '').toString(),
      runId: (json['run_id'] ?? '').toString(),
      functionId: (json['function_id'] ?? '').toString(),
      inputFunctionJson: _stringKeyMap(json['input_function_json']),
      phase: _phaseFromWire(json['phase']),
      enhancementStatus: _enhancementStatusFromWire(json['enhancement_status']),
      message: (json['message'] ?? '').toString(),
      useEnglish: json['use_english'] == true,
      createdAt: createdAt ?? DateTime.now().toUtc(),
      updatedAt: updatedAt ?? DateTime.now().toUtc(),
      enhancedFunctionJson: _nullableStringKeyMap(
        json['enhanced_function_json'],
      ),
      agentPrompt: json['agent_prompt']?.toString(),
      rawAiText: json['raw_ai_text']?.toString(),
      warning: json['warning']?.toString(),
      enhancementReport: _nullableStringKeyMap(json['enhancement_report']),
      registrationResult: _nullableStringKeyMap(json['registration_result']),
      error: json['error']?.toString(),
      conversationId: _nullableInt(json['conversation_id']),
      taskId: json['task_id']?.toString(),
    );
  }
}

class RunLogFunctionEnhancementJobService {
  const RunLogFunctionEnhancementJobService._();

  static const String _jobsKey = 'run_log_function_enhancement_jobs_v1';
  static final StreamController<RunLogFunctionEnhancementJob>
  _jobChangedController =
      StreamController<RunLogFunctionEnhancementJob>.broadcast();
  static final Map<String, Future<void>> _runningJobs = {};
  static final Set<String> _canceledJobIds = <String>{};
  static int _jobSequence = 0;
  static const Duration _agentPollInterval = Duration(milliseconds: 800);
  static const Duration _agentEnhancementTimeout = Duration(minutes: 5);

  static Stream<RunLogFunctionEnhancementJob> watchJob(String jobId) {
    return _jobChangedController.stream.where((job) => job.jobId == jobId);
  }

  static Future<RunLogFunctionEnhancementJob?> latestFor({
    required String runId,
    required String functionId,
  }) async {
    final normalizedRunId = runId.trim();
    final normalizedFunctionId = functionId.trim();
    final jobs = await _loadJobs();
    RunLogFunctionEnhancementJob? latest;
    for (final job in jobs) {
      if (job.runId != normalizedRunId ||
          job.functionId != normalizedFunctionId) {
        continue;
      }
      if (latest == null || job.updatedAt.isAfter(latest.updatedAt)) {
        latest = job;
      }
    }
    return latest;
  }

  static Future<RunLogFunctionEnhancementJob> enqueue({
    required String runId,
    required Map<String, dynamic> functionJson,
    required bool useEnglish,
  }) async {
    final normalizedRunId = runId.trim();
    final normalizedFunctionJson = _stringKeyMap(functionJson);
    final functionId = (normalizedFunctionJson['function_id'] ?? '')
        .toString()
        .trim();
    if (functionId.isEmpty) {
      throw Exception('function_id 为空，无法增强复用指令');
    }
    final now = DateTime.now().toUtc();
    final sequence = _jobSequence++;
    final job = RunLogFunctionEnhancementJob(
      jobId:
          'enhance_${_safeId(functionId)}_${now.microsecondsSinceEpoch}_${sequence.toString()}',
      runId: normalizedRunId,
      functionId: functionId,
      inputFunctionJson: normalizedFunctionJson,
      phase: RunLogFunctionEnhancementJobPhase.queued,
      enhancementStatus: FunctionEnhancementStatus.enhancing,
      message: useEnglish
          ? 'Agent queued background enhancement for this Function.'
          : 'Agent 已将这个复用指令加入后台增强队列。',
      useEnglish: useEnglish,
      createdAt: now,
      updatedAt: now,
    );
    await _upsertJob(job);
    _startJob(job.jobId);
    return job;
  }

  static Future<void> resumePendingJobs() async {
    final jobs = await _loadJobs();
    for (final job in jobs) {
      if (job.phase.isRunning) {
        _startJob(job.jobId);
      }
    }
  }

  static Future<void> clearForTesting() async {
    _canceledJobIds.addAll(_runningJobs.keys);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_jobsKey);
    _runningJobs.clear();
  }

  static void _startJob(String jobId) {
    if (_runningJobs.containsKey(jobId)) {
      return;
    }
    _canceledJobIds.remove(jobId);
    final future = _runJob(jobId);
    _runningJobs[jobId] = future;
    future.whenComplete(() => _runningJobs.remove(jobId));
  }

  static Future<void> _runJob(String jobId) async {
    var job = await _findJob(jobId);
    if (job == null || _canceledJobIds.contains(jobId)) {
      return;
    }
    try {
      job = await _transition(
        job.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.enhancing,
          enhancementStatus: FunctionEnhancementStatus.enhancing,
          message: job.useEnglish
              ? 'Agent is enhancing this Function in a background conversation.'
              : 'Agent 正在后台对话中增强这个 Function。',
        ),
      );
      if (_canceledJobIds.contains(jobId)) return;

      final baseFunction = await _loadFunctionForEnhancement(job);
      if (_canceledJobIds.contains(jobId)) return;
      if (baseFunction == null) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: FunctionEnhancementStatus.failed,
            message: job.useEnglish
                ? 'This Function could not be loaded before enhancement.'
                : '增强前无法读取这个复用指令。',
            error: job.useEnglish ? 'function load failed' : 'Function 读取失败',
          ),
        );
        return;
      }

      final prompt = _buildFunctionEnhancementAgentPrompt(
        functionJson: baseFunction,
        useEnglish: job.useEnglish,
      );
      final conversationId = await ConversationService.createConversation(
        title: _enhancementConversationTitle(baseFunction, job.useEnglish),
        mode: ConversationMode.normal,
      );
      if (_canceledJobIds.contains(jobId)) return;
      if (conversationId == null) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: FunctionEnhancementStatus.failed,
            message: job.useEnglish
                ? 'Failed to create a background Agent conversation.'
                : '无法创建后台 Agent 对话。',
            agentPrompt: prompt,
            error: job.useEnglish ? 'conversation create failed' : '对话创建失败',
          ),
        );
        return;
      }

      final taskId =
          'function_enhance_${_safeId(job.functionId)}_${DateTime.now().microsecondsSinceEpoch}';
      job = await _transition(
        job.copyWith(
          conversationId: conversationId,
          taskId: taskId,
          agentPrompt: prompt,
          message: job.useEnglish
              ? 'Agent conversation started. Waiting for the revised Function JSON.'
              : 'Agent 对话已启动，正在等待完整 Function JSON。',
        ),
      );
      final started = await AssistsMessageService.createAgentTask(
        taskId: taskId,
        userMessage: prompt,
        conversationId: conversationId,
        conversationMode: ConversationMode.normal.storageValue,
        allowedTools: const <String>['__no_tools__'],
      );
      if (_canceledJobIds.contains(jobId)) return;
      if (!started) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: FunctionEnhancementStatus.failed,
            message: job.useEnglish
                ? 'Failed to start the Agent enhancement task.'
                : '无法启动 Agent 增强任务。',
            error: job.useEnglish ? 'agent task start failed' : 'Agent 任务启动失败',
          ),
        );
        return;
      }

      final agentResult = await _waitForAgentFunctionJson(
        conversationId: conversationId,
        functionId: job.functionId,
        timeout: _agentEnhancementTimeout,
      );
      if (_canceledJobIds.contains(jobId)) return;
      if (!agentResult.success || agentResult.functionJson == null) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: FunctionEnhancementStatus.failed,
            message: job.useEnglish
                ? 'Agent did not return a valid complete Function JSON. The current Function is unchanged.'
                : 'Agent 没有返回有效的完整 Function JSON；当前复用指令保持原样。',
            rawAiText: agentResult.rawText,
            error:
                agentResult.errorMessage ??
                (job.useEnglish ? 'invalid function json' : 'Function JSON 无效'),
          ),
        );
        return;
      }

      job = await _transition(
        job.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.saving,
          rawAiText: agentResult.rawText,
          message: job.useEnglish
              ? 'Saving the revised Function JSON.'
              : '正在保存 Agent 返回的 Function JSON。',
        ),
      );
      final rewrittenFunction = _agentVisibleFunctionJson(
        agentResult.functionJson!,
      );
      final saveJson = await AssistsMessageService.updateFunction(
        functionSpec: rewrittenFunction,
        runId: job.runId,
        mode: 'enhance',
        extraArgs: <String, dynamic>{
          'source': 'run_log_function_enhancement_agent_conversation',
          'offline_job': true,
          'background_enhancement': true,
          'agent_conversation_id': conversationId,
          'agent_task_id': taskId,
        },
      );
      if (_canceledJobIds.contains(jobId)) return;
      final updateSuccess = saveJson['success'] == true;
      final changed = saveJson['changed'] == true;
      final saved = saveJson['saved'] == true;
      final noSafeChange = updateSuccess && !changed;
      if (!updateSuccess || (changed && !saved)) {
        await _transition(
          job.copyWith(
            phase: RunLogFunctionEnhancementJobPhase.failed,
            enhancementStatus: FunctionEnhancementStatus.failed,
            message: job.useEnglish
                ? 'Agent returned a revised Function, but saving it failed. The current Function is unchanged.'
                : 'Agent 已返回增强结果，但保存失败；当前复用指令保持原样。',
            enhancedFunctionJson: rewrittenFunction,
            registrationResult: saveJson,
            error:
                _updateFunctionErrorMessage(saveJson) ??
                (job.useEnglish
                    ? 'update_function save failed'
                    : 'update_function 保存失败'),
          ),
        );
        return;
      }

      final savedFunction =
          _nullableStringKeyMap(saveJson['updated_function']) ??
          _nullableStringKeyMap(saveJson['function']) ??
          rewrittenFunction;
      final visibleSavedFunction = _agentVisibleFunctionJson(savedFunction);
      final finalStatus = noSafeChange
          ? FunctionEnhancementStatus.unchanged
          : FunctionEnhancementStatus.enhanced;
      await _transition(
        job.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.completed,
          enhancementStatus: finalStatus,
          message: _statusMessage(finalStatus, job.useEnglish),
          enhancedFunctionJson: visibleSavedFunction,
          agentPrompt: _firstNonBlank([
            visibleSavedFunction['agent_prompt'],
            visibleSavedFunction['display_prompt'],
            saveJson['agent_prompt'],
            saveJson['display_prompt'],
          ]),
          rawAiText: agentResult.rawText,
          registrationResult: saveJson,
          error: null,
        ),
      );
    } catch (error) {
      final current = await _findJob(jobId) ?? job;
      if (current == null || _canceledJobIds.contains(jobId)) {
        return;
      }
      await _transition(
        current.copyWith(
          phase: RunLogFunctionEnhancementJobPhase.failed,
          enhancementStatus: FunctionEnhancementStatus.failed,
          message: current.useEnglish
              ? 'Agent enhancement failed. Keeping the current Function.'
              : 'Agent 增强失败，当前复用指令保持原样。',
          error: error.toString(),
        ),
      );
    }
  }

  static Future<Map<String, dynamic>?> _loadFunctionForEnhancement(
    RunLogFunctionEnhancementJob job,
  ) async {
    final existing = await AssistsMessageService.getFunction(job.functionId);
    final existingFunction = _functionJsonFromToolResult(existing);
    if (existingFunction != null) {
      return existingFunction;
    }
    return _looksLikeFunctionSpec(job.inputFunctionJson)
        ? job.inputFunctionJson
        : null;
  }

  static String _enhancementConversationTitle(
    Map<String, dynamic> functionJson,
    bool useEnglish,
  ) {
    final name =
        _firstNonBlank([
          functionJson['name'],
          functionJson['description'],
          functionJson['function_id'],
        ]) ??
        '';
    final prefix = useEnglish ? 'Enhance Function' : '增强复用指令';
    return name.isEmpty ? prefix : '$prefix: $name';
  }

  static String _buildFunctionEnhancementAgentPrompt({
    required Map<String, dynamic> functionJson,
    required bool useEnglish,
  }) {
    final prettyFunctionJson = const JsonEncoder.withIndent(
      '  ',
    ).convert(_jsonSafe(functionJson));
    if (useEnglish) {
      return '''
You are improving a saved OpenOmniBot Function so it can be reused like a small API.

Return exactly one JSON object and no prose. The object must be the complete revised Function spec.

Rules:
- Preserve the same function_id.
- Preserve execution.steps and source_context. Do not invent coordinates, XML, screenshots, or new UI actions.
- Improve name and description so another agent can decide when to call this Function.
- If an obvious literal user value exists, expose it as a parameter and bind the step argument to that parameter.
- Keep deterministic replay data unchanged unless it is only text/name/description/parameter metadata.
- If unsure, return the original Function unchanged.

Function JSON:
```json
$prettyFunctionJson
```
''';
    }
    return '''
你正在增强一个已保存的 OpenOmniBot Function，让它更像一个可复用的小 API。

只返回一个 JSON object，不要解释、不要 Markdown。这个 object 必须是修改后的完整 Function spec。

要求：
- 必须保留同一个 function_id。
- 必须保留 execution.steps 和 source_context；不要发明坐标、XML、截图或新的 UI 动作。
- 优化 name 和 description，让后续 agent 能判断什么时候该调用它。
- 如果存在明显的用户输入字面量，把它变成参数，并把对应 step argument 绑定到参数。
- 除了文本、名称、描述、参数元数据，不要修改确定性的 replay 数据。
- 不确定时，原样返回这个 Function。

Function JSON:
```json
$prettyFunctionJson
```
''';
  }

  static Future<_AgentFunctionJsonResult> _waitForAgentFunctionJson({
    required int conversationId,
    required String functionId,
    required Duration timeout,
  }) async {
    final deadline = DateTime.now().add(timeout);
    var lastAssistantText = '';
    while (DateTime.now().isBefore(deadline)) {
      final messages = await ConversationHistoryService.getConversationMessages(
        conversationId,
        mode: ConversationMode.normal,
      );
      for (final message in messages.reversed) {
        if (message.user != 2) continue;
        final text = message.text?.trim() ?? '';
        if (text.isEmpty) continue;
        lastAssistantText = text;
        final parsed = _extractFunctionJsonFromText(text);
        if (parsed == null) continue;
        final parsedFunctionId = (parsed['function_id'] ?? '')
            .toString()
            .trim();
        if (parsedFunctionId != functionId) {
          return _AgentFunctionJsonResult.failure(
            rawText: text,
            errorMessage:
                'Agent returned function_id "$parsedFunctionId", expected "$functionId"',
          );
        }
        return _AgentFunctionJsonResult.success(
          functionJson: parsed,
          rawText: text,
        );
      }
      await Future<void>.delayed(_agentPollInterval);
    }
    return _AgentFunctionJsonResult.failure(
      rawText: lastAssistantText,
      errorMessage: 'Agent enhancement timed out',
    );
  }

  static Future<RunLogFunctionEnhancementJob> _transition(
    RunLogFunctionEnhancementJob job,
  ) async {
    if (_canceledJobIds.contains(job.jobId)) {
      return job;
    }
    final updated = job.copyWith(updatedAt: DateTime.now().toUtc());
    await _upsertJob(updated);
    return updated;
  }

  static Future<RunLogFunctionEnhancementJob?> _findJob(String jobId) async {
    final jobs = await _loadJobs();
    for (final job in jobs) {
      if (job.jobId == jobId) {
        return job;
      }
    }
    return null;
  }

  static Future<List<RunLogFunctionEnhancementJob>> _loadJobs() async {
    final prefs = await SharedPreferences.getInstance();
    final rawList = prefs.getStringList(_jobsKey) ?? const <String>[];
    final output = <RunLogFunctionEnhancementJob>[];
    for (final raw in rawList) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          final job = RunLogFunctionEnhancementJob.fromJson(
            decoded.map((key, value) => MapEntry(key.toString(), value)),
          );
          if (job.jobId.isNotEmpty && job.functionId.isNotEmpty) {
            output.add(job);
          }
        }
      } catch (_) {
        // Ignore corrupt persisted jobs; a new enhancement can be queued.
      }
    }
    output.sort((a, b) => a.updatedAt.compareTo(b.updatedAt));
    return output;
  }

  static Future<void> _upsertJob(RunLogFunctionEnhancementJob job) async {
    final jobs = await _loadJobs();
    final index = jobs.indexWhere((item) => item.jobId == job.jobId);
    if (index >= 0) {
      jobs[index] = job;
    } else {
      jobs.add(job);
    }
    jobs.sort((a, b) => a.updatedAt.compareTo(b.updatedAt));
    final retained = jobs.length > 40 ? jobs.sublist(jobs.length - 40) : jobs;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(
      _jobsKey,
      retained.map((item) => jsonEncode(item.toJson())).toList(),
    );
    _jobChangedController.add(job);
  }

  static String _statusMessage(
    FunctionEnhancementStatus status,
    bool useEnglish,
  ) {
    switch (status) {
      case FunctionEnhancementStatus.enhanced:
        return useEnglish
            ? 'Agent enhancement applied, saved, and registered.'
            : 'Agent 增强已应用、保存并注册。';
      case FunctionEnhancementStatus.partial:
        return useEnglish
            ? 'Agent enhancement partially applied, saved, and registered.'
            : 'Agent 增强已部分应用、保存并注册。';
      case FunctionEnhancementStatus.unchanged:
        return useEnglish
            ? 'Agent checked this Function, found no safe change, and registered it.'
            : 'Agent 已检查，没有安全可应用的变化，并已注册。';
      case FunctionEnhancementStatus.failed:
        return useEnglish ? 'Agent enhancement failed.' : 'Agent 增强失败。';
      case FunctionEnhancementStatus.enhancing:
        return useEnglish ? 'Agent enhancement is running.' : 'Agent 正在后台增强。';
      case FunctionEnhancementStatus.none:
        return '';
    }
  }
}

RunLogFunctionEnhancementJobPhase _phaseFromWire(dynamic value) {
  final name = value?.toString().trim().toLowerCase() ?? '';
  for (final phase in RunLogFunctionEnhancementJobPhase.values) {
    if (phase.name == name) {
      return phase;
    }
  }
  return RunLogFunctionEnhancementJobPhase.queued;
}

FunctionEnhancementStatus _enhancementStatusFromWire(dynamic value) {
  final name = value?.toString().trim().toLowerCase() ?? '';
  for (final status in FunctionEnhancementStatus.values) {
    if (status.name == name) {
      return status;
    }
  }
  return FunctionEnhancementStatus.none;
}

Map<String, dynamic> _stringKeyMap(dynamic value) {
  final safe = _jsonSafe(value);
  if (safe is Map) {
    return safe.map((key, item) => MapEntry(key.toString(), item));
  }
  return <String, dynamic>{};
}

Map<String, dynamic>? _nullableStringKeyMap(dynamic value) {
  final map = _stringKeyMap(value);
  return map.isEmpty ? null : map;
}

int? _nullableInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString().trim() ?? '');
}

Map<String, dynamic>? _functionJsonFromToolResult(
  Map<String, dynamic>? result,
) {
  if (result == null || result['success'] == false) {
    return null;
  }
  return _nullableStringKeyMap(result['function']) ??
      _nullableStringKeyMap(result['function_spec']) ??
      _nullableStringKeyMap(result['updated_function']) ??
      (_looksLikeFunctionSpec(result) ? _stringKeyMap(result) : null);
}

bool _looksLikeFunctionSpec(Map<String, dynamic> value) {
  final functionId = (value['function_id'] ?? '').toString().trim();
  final execution = _stringKeyMap(value['execution']);
  final steps = execution['steps'];
  return functionId.isNotEmpty && steps is List && steps.isNotEmpty;
}

Map<String, dynamic> _agentHiddenFunctionJson(Map<String, dynamic> rawJson) {
  return _functionJsonWithAgentVisibility(rawJson, agentVisible: false);
}

Map<String, dynamic> _agentVisibleFunctionJson(Map<String, dynamic> rawJson) {
  return _functionJsonWithAgentVisibility(rawJson, agentVisible: true);
}

Map<String, dynamic> _functionJsonWithAgentVisibility(
  Map<String, dynamic> rawJson, {
  required bool agentVisible,
}) {
  final cloned = _stringKeyMap(rawJson);
  cloned['agent_visible'] = agentVisible;
  cloned['visibility'] = agentVisible ? 'agent_reusable' : 'manual_function';
  final metadata = _stringKeyMap(cloned['metadata']);
  cloned['metadata'] = <String, dynamic>{
    ...metadata,
    'agent_visible': agentVisible,
    'visibility': agentVisible ? 'agent_reusable' : 'manual_function',
  };
  return cloned;
}

String? _firstNonBlank(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  return null;
}

String? _updateFunctionErrorMessage(Map<String, dynamic> result) {
  for (final key in const <String>[
    'error_message',
    'message',
    'reason',
    'error',
  ]) {
    final value = result[key]?.toString().trim();
    if (value != null && value.isNotEmpty) {
      return value;
    }
  }
  final save = _stringKeyMap(result['save']);
  final saveError = save['error_message']?.toString().trim();
  if (saveError != null && saveError.isNotEmpty) {
    return saveError;
  }
  return null;
}

dynamic _jsonSafe(dynamic value) {
  if (value == null || value is String || value is num || value is bool) {
    return value;
  }
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), _jsonSafe(item)));
  }
  if (value is Iterable) {
    return value.map(_jsonSafe).toList(growable: false);
  }
  return value.toString();
}

Map<String, dynamic>? _extractFunctionJsonFromText(String raw) {
  final candidates = <String>[
    raw.trim(),
    ..._jsonFenceBodies(raw),
    ..._balancedJsonObjectCandidates(raw),
  ];
  for (final candidate in candidates) {
    final trimmed = candidate.trim();
    if (trimmed.isEmpty) continue;
    try {
      final decoded = jsonDecode(trimmed);
      if (decoded is Map) {
        final map = _stringKeyMap(decoded);
        if (_looksLikeFunctionSpec(map)) return map;
      }
    } catch (_) {
      // Keep trying other candidate spans.
    }
  }
  return null;
}

Iterable<String> _jsonFenceBodies(String raw) sync* {
  final fencePattern = RegExp(
    r'```(?:json)?\s*([\s\S]*?)```',
    caseSensitive: false,
  );
  for (final match in fencePattern.allMatches(raw)) {
    final body = match.group(1)?.trim() ?? '';
    if (body.isNotEmpty) yield body;
  }
}

Iterable<String> _balancedJsonObjectCandidates(String raw) sync* {
  var cursor = 0;
  while (cursor < raw.length) {
    final start = raw.indexOf('{', cursor);
    if (start < 0) return;
    final end = _findBalancedJsonObjectEnd(raw, start);
    if (end == null) return;
    yield raw.substring(start, end + 1);
    cursor = start + 1;
  }
}

int? _findBalancedJsonObjectEnd(String raw, int start) {
  var depth = 0;
  var inString = false;
  var escaped = false;
  for (var index = start; index < raw.length; index += 1) {
    final char = raw[index];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (inString && char == '\\') {
      escaped = true;
      continue;
    }
    if (char == '"') {
      inString = !inString;
      continue;
    }
    if (inString) continue;
    if (char == '{') {
      depth += 1;
    } else if (char == '}') {
      depth -= 1;
      if (depth == 0) return index;
    }
  }
  return null;
}

String _safeId(String value) {
  final safe = value
      .replaceAll(RegExp(r'[^A-Za-z0-9_-]+'), '_')
      .replaceAll(RegExp(r'_+'), '_')
      .replaceAll(RegExp(r'^[_-]+|[_-]+$'), '');
  return safe.isEmpty ? 'function' : safe;
}

class _AgentFunctionJsonResult {
  const _AgentFunctionJsonResult._({
    required this.success,
    this.functionJson,
    this.rawText = '',
    this.errorMessage,
  });

  final bool success;
  final Map<String, dynamic>? functionJson;
  final String rawText;
  final String? errorMessage;

  factory _AgentFunctionJsonResult.success({
    required Map<String, dynamic> functionJson,
    required String rawText,
  }) {
    return _AgentFunctionJsonResult._(
      success: true,
      functionJson: functionJson,
      rawText: rawText,
    );
  }

  factory _AgentFunctionJsonResult.failure({
    required String rawText,
    required String errorMessage,
  }) {
    return _AgentFunctionJsonResult._(
      success: false,
      rawText: rawText,
      errorMessage: errorMessage,
    );
  }
}
