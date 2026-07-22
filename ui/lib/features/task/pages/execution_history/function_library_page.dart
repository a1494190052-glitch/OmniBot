import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/features/task/pages/execution_history/widgets/reusable_function_card.dart';
import 'package:ui/features/task/run_log/function_spec.dart';
import 'package:ui/features/task/run_log/run_log_function_service.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class FunctionLibraryPage extends StatefulWidget {
  const FunctionLibraryPage({super.key, this.embedded = false});

  final bool embedded;

  @override
  State<FunctionLibraryPage> createState() => _FunctionLibraryPageState();
}

class FunctionLibraryEmbed extends FunctionLibraryPage {
  const FunctionLibraryEmbed({super.key}) : super(embedded: true);
}

class _FunctionLibraryPageState extends State<FunctionLibraryPage> {
  static const int _pageSize = 30;

  List<_FunctionSummary> _functionSummaries = const [];
  List<_FunctionGroup> _functions = const [];
  bool _isLoading = true;
  bool _isLoadingMore = false;
  bool _hasMore = false;
  int _nextOffset = 0;
  String? _error;
  final Set<String> _deletingIds = {};
  final Set<String> _runningIds = {};
  bool _isLearning = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final result = await RunLogFunctionService.listFunctions(
        limit: _pageSize,
        offset: 0,
        includeHidden: true,
      );
      if (!mounted) return;
      final list = _functionSummariesFromResult(result);
      setState(() {
        _functionSummaries = list;
        _functions = _groupFunctions(list);
        _hasMore = _boolFromResult(result, 'has_more');
        _nextOffset = _intFromResult(
          result,
          'next_offset',
        ).takeIfPositive(list.length);
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _isLoading = false;
      });
    }
  }

  Future<void> _loadMore() async {
    if (_isLoading || _isLoadingMore || !_hasMore) return;
    setState(() => _isLoadingMore = true);
    try {
      final result = await RunLogFunctionService.listFunctions(
        limit: _pageSize,
        offset: _nextOffset,
        includeHidden: true,
      );
      if (!mounted) return;
      final nextItems = _functionSummariesFromResult(result);
      final merged = [..._functionSummaries, ...nextItems];
      setState(() {
        _functionSummaries = merged;
        _functions = _groupFunctions(merged);
        _hasMore = _boolFromResult(result, 'has_more');
        _nextOffset = _intFromResult(
          result,
          'next_offset',
        ).takeIfPositive(merged.length);
        _isLoadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoadingMore = false);
      showToast(e.toString(), type: ToastType.error);
    }
  }

  Future<void> _delete(_FunctionGroup group) async {
    final function = group.primary;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(_text(context, '删除复用指令', 'Delete Function')),
        content: Text(
          _text(
            context,
            group.variantCount > 1
                ? '确定删除「${function.displayName}」及其 ${group.variantCount} 个同类来源？此操作不可撤销。'
                : '确定删除「${function.displayName}」？此操作不可撤销。',
            group.variantCount > 1
                ? 'Delete "${function.displayName}" and its ${group.variantCount} variants? This cannot be undone.'
                : 'Delete "${function.displayName}"? This cannot be undone.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(
              _text(context, '删除', 'Delete'),
              style: const TextStyle(color: AppColors.alertRed),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _deletingIds.add(group.signature));
    try {
      var deletedCount = 0;
      var allDeleted = true;
      for (final item in group.items) {
        final result = await RunLogFunctionService.deleteFunction(
          item.functionId,
        );
        final deleted = result['success'] == true || result['deleted'] == true;
        if (deleted) {
          deletedCount += 1;
        } else {
          allDeleted = false;
        }
      }
      if (!mounted) return;
      if (allDeleted && deletedCount == group.items.length) {
        setState(() {
          final deletedIds = group.items.map((item) => item.functionId).toSet();
          _functionSummaries = _functionSummaries
              .where((item) => !deletedIds.contains(item.functionId))
              .toList(growable: false);
          _functions = _functions
              .where((c) => c.signature != group.signature)
              .toList(growable: false);
        });
        showToast(
          group.variantCount > 1
              ? _text(context, '已删除复用指令组', 'Function group deleted')
              : _text(context, '已删除复用指令', 'Function deleted'),
          type: ToastType.success,
        );
      } else {
        await _load();
        if (!mounted) return;
        showToast(
          _text(context, '部分删除失败', 'Partial delete failed'),
          type: ToastType.error,
        );
      }
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _deletingIds.remove(group.signature));
    }
  }

  Future<void> _startLearning() async {
    await _startHumanTrajectoryLearningFlow(
      context: context,
      isLearning: () => _isLearning,
      setLearning: (value) {
        if (mounted) setState(() => _isLearning = value);
      },
      reload: _load,
    );
  }

  Future<void> _run(_FunctionGroup group) async {
    if (_runningIds.contains(group.signature)) return;
    setState(() {
      _runningIds.add(group.signature);
    });
    try {
      final spec = await RunLogFunctionService.getFunction(
        group.primary.functionId,
      );
      if (!mounted) return;
      final arguments = await _resolveRunArguments(context, spec);
      if (!mounted || arguments == null) return;
      final result = await RunLogFunctionService.runFunction(
        functionId: group.primary.functionId,
        arguments: arguments,
        taskId: 'oob-function-run-${DateTime.now().millisecondsSinceEpoch}',
      );
      if (!mounted) return;
      setState(() {
        _runningIds.remove(group.signature);
      });
      showToast(
        functionRunResultToastMessage(context, result),
        type: functionRunResultToastType(result),
        duration: const Duration(seconds: 3),
      );
      await showFunctionRunResultSheet(
        context,
        result: result,
        title: _text(context, '复用指令执行结果', 'Function result'),
        arguments: arguments,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(e.toString(), type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() {
          _runningIds.remove(group.signature);
        });
      }
    }
  }

  Future<void> _openDetails(_FunctionGroup group) async {
    if (!mounted) return;
    await _showFunctionSpecDetails(context, group: group, onClosed: _load);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    if (widget.embedded) {
      return ColoredBox(
        color: palette.pageBackground,
        child: _buildContent(context),
      );
    }
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text(context, '复用指令库', 'Functions'),
        primary: true,
        actions: [
          Tooltip(
            message: _text(context, '学习操作', 'Learn Actions'),
            child: IconButton(
              icon: _isLearning
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.gesture_rounded),
              color: palette.textPrimary,
              onPressed: _isLearning ? null : _startLearning,
            ),
          ),
          Tooltip(
            message: _text(context, '刷新', 'Refresh'),
            child: IconButton(
              icon: const Icon(Icons.refresh_rounded),
              color: palette.textPrimary,
              onPressed: _isLoading ? null : _load,
            ),
          ),
        ],
      ),
      body: SafeArea(top: false, child: _buildContent(context)),
    );
  }

  Widget _buildContent(BuildContext context) {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _EmptyState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Load Failed'),
        subtitle: _error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _load,
      );
    }
    if (_functions.isEmpty) {
      return _EmptyState(
        icon: Icons.bolt_outlined,
        title: _text(context, '暂无复用指令', 'No Functions yet'),
        subtitle: _text(
          context,
          '可以直接学习一段完整的人类操作，保存后在这里复用。',
          'Learn a complete human-operated trajectory and reuse it here.',
        ),
        actionLabel: _text(context, '学习操作', 'Learn Actions'),
        onAction: _startLearning,
      );
    }
    final list = RefreshIndicator(
      onRefresh: _load,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _functions.length + (_hasMore || _isLoadingMore ? 1 : 0),
        separatorBuilder: (_, __) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          if (index >= _functions.length) {
            WidgetsBinding.instance.addPostFrameCallback((_) => _loadMore());
            return const _FunctionLibraryFooter();
          }
          final group = _functions[index];
          return _FunctionCard(
            group: group,
            isDeleting: _deletingIds.contains(group.signature),
            isRunning: _runningIds.contains(group.signature),
            onRun: () => _run(group),
            onDelete: () => _delete(group),
            onOpenDetails: () => _openDetails(group),
          );
        },
      ),
    );
    return list;
  }
}

class _FunctionCard extends StatelessWidget {
  const _FunctionCard({
    required this.group,
    required this.isDeleting,
    required this.isRunning,
    required this.onRun,
    required this.onDelete,
    required this.onOpenDetails,
  });

  final _FunctionGroup group;
  final bool isDeleting;
  final bool isRunning;
  final VoidCallback onRun;
  final VoidCallback onDelete;
  final VoidCallback onOpenDetails;

  @override
  Widget build(BuildContext context) {
    final function = group.primary;
    final palette = context.omniPalette;
    return ReusableFunctionCard(
      title: group.displayName,
      description: group.displayDescription,
      steps: function.stepSummaries
          .map(
            (step) => ReusableFunctionStepPreview(
              index: step.index,
              title: step.title,
              tool: step.tool,
              executor: step.executor,
              kind: step.kind,
            ),
          )
          .toList(growable: false),
      stepCount: function.stepCount,
      parameterCount: function.parameterNames.length,
      agentVisible: group.isAgentVisible,
      isRunning: isRunning,
      onRun: onRun,
      isBusy: isDeleting,
      actions: [
        ReusableFunctionCardAction(
          icon: Icons.info_outline_rounded,
          color: palette.textSecondary,
          backgroundColor: palette.surfaceSecondary,
          tooltip: _text(context, '详情', 'Details'),
          onTap: onOpenDetails,
        ),
        ReusableFunctionCardAction(
          icon: Icons.delete_outline_rounded,
          color: AppColors.alertRed,
          backgroundColor: AppColors.alertRed.withValues(alpha: 0.08),
          tooltip: _text(context, '删除', 'Delete'),
          onTap: onDelete,
        ),
      ],
    );
  }
}

class _FunctionLibraryFooter extends StatelessWidget {
  const _FunctionLibraryFooter();

  @override
  Widget build(BuildContext context) {
    return const SizedBox(
      height: 52,
      child: Center(
        child: SizedBox(
          width: 20,
          height: 20,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      ),
    );
  }
}

List<_FunctionSummary> _functionSummariesFromResult(
  Map<String, dynamic> result,
) {
  final raw = result['functions'];
  if (raw is! List) return const <_FunctionSummary>[];
  return raw
      .whereType<Map>()
      .map(
        (item) => _FunctionSummary.fromMap(
          Map<String, dynamic>.from(
            item.map((k, v) => MapEntry(k.toString(), v)),
          ),
        ),
      )
      .toList(growable: false);
}

bool _boolFromResult(Map<String, dynamic> result, String key) {
  return result[key] == true;
}

int _intFromResult(Map<String, dynamic> result, String key) {
  final raw = result[key];
  if (raw is num) return raw.toInt();
  return int.tryParse((raw ?? '').toString()) ?? 0;
}

extension _PositiveOffsetFallback on int {
  int takeIfPositive(int fallback) => this > 0 ? this : fallback;
}

Future<void> _showFunctionSpecDetails(
  BuildContext context, {
  required _FunctionGroup group,
  required Future<void> Function() onClosed,
}) async {
  try {
    final rawSpec = await RunLogFunctionService.getFunction(
      group.primary.functionId,
    );
    if (!context.mounted) return;
    final specJson = _functionSpecJsonFromDetail(rawSpec);
    if (specJson.isEmpty) {
      showToast(
        _text(context, '复用指令详情为空', 'Function details are empty'),
        type: ToastType.error,
      );
      return;
    }
    final spec = FunctionSpec(
      json: specJson,
      agentPrompt: functionAgentPrompt(specJson),
      aiEnhanced: false,
    );
    const runId = '';
    final importResult = _functionSpecSheetImportResult(specJson, runId);
    await showReusableFunctionSpecSheet(
      context,
      spec: spec,
      runId: runId,
      initialImportResult: importResult,
    );
    if (context.mounted) await onClosed();
  } catch (error) {
    if (context.mounted) showToast(error.toString(), type: ToastType.error);
  }
}

Map<String, dynamic> _functionSpecJsonFromDetail(
  Map<String, dynamic>? rawSpec,
) {
  if (rawSpec == null || rawSpec.isEmpty) return const {};
  return rawSpec['schema_version'] == 'omniflow.function.v2'
      ? _deepStringKeyMap(rawSpec)
      : const {};
}

UtgRunLogImportResult _functionSpecSheetImportResult(
  Map<String, dynamic> specJson,
  String runId,
) {
  final functionId = (specJson['function_id'] ?? '').toString();
  final agentVisible = specJson['agent_visible'] == true;
  return UtgRunLogImportResult.fromMap({
    'success': true,
    'run_id': runId,
    'function_id': functionId,
    'agent_visible': agentVisible,
  });
}

Map<String, dynamic> _deepStringKeyMap(dynamic value) {
  try {
    final decoded = jsonDecode(jsonEncode(value));
    if (decoded is Map) {
      return decoded.map((key, item) => MapEntry(key.toString(), item));
    }
  } catch (_) {
    if (value is Map) {
      return value.map((key, item) => MapEntry(key.toString(), item));
    }
  }
  return const {};
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.actionLabel,
    required this.onAction,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final String actionLabel;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfaceSecondary
                    : palette.previewFallback,
                shape: BoxShape.circle,
              ),
              alignment: Alignment.center,
              child: Icon(icon, size: 28, color: palette.textSecondary),
            ),
            const SizedBox(height: 16),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 13,
                color: palette.textSecondary,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 20),
            GestureDetector(
              onTap: onAction,
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 10,
                ),
                decoration: BoxDecoration(
                  color: palette.accentPrimary.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(99),
                ),
                child: Text(
                  actionLabel,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: palette.accentPrimary,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FunctionSummary {
  const _FunctionSummary({
    required this.functionId,
    required this.name,
    required this.description,
    required this.stepCount,
    required this.parameterNames,
    required this.stepSummaries,
    required this.agentVisible,
  });

  factory _FunctionSummary.fromMap(Map<String, dynamic> map) {
    final inputSchema = _asMap(map['input_schema']);
    final properties = _asMap(inputSchema['properties']);
    final steps = (map['steps'] as List<dynamic>?) ?? const <dynamic>[];
    return _FunctionSummary(
      functionId: (map['function_id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      stepCount: steps.length,
      parameterNames: properties.keys.toList(growable: false),
      stepSummaries: steps
          .whereType<Map>()
          .map((item) {
            return _StepSummary.fromMap(
              item.map((key, value) => MapEntry(key.toString(), value)),
            );
          })
          .toList(growable: false),
      agentVisible: map['agent_visible'] == true,
    );
  }

  final String functionId;
  final String name;
  final String description;
  final int stepCount;
  final List<String> parameterNames;
  final List<_StepSummary> stepSummaries;
  final bool agentVisible;

  String get displayName {
    final trimmedName = name.trim();
    if (trimmedName.isNotEmpty) return trimmedName;
    return _fallbackNameFromId(functionId);
  }

  String get displayDescription {
    final text = description.trim();
    if (text.isEmpty || text == displayName || text == functionId) return '';
    if (_looksLikeTechnicalId(text)) return '';
    return text;
  }

  String get semanticTitle {
    if (_looksLikeTechnicalSummaryTitle(displayName) &&
        displayDescription.isNotEmpty) {
      return displayDescription;
    }
    return displayName;
  }

  String get semanticDescription {
    if (displayDescription.isEmpty || displayDescription == displayName) {
      return '';
    }
    if (_looksLikeTechnicalSummaryTitle(displayName)) {
      return '';
    }
    return displayDescription;
  }

  String get parameterPreview => _previewNames(parameterNames);

  String get semanticSignature {
    final stepKey = stepSummaries
        .map((step) => step.semanticSignature)
        .join('|');
    final paramKey = parameterNames
        .map(_normalizeSignatureText)
        .where((value) => value.isNotEmpty)
        .join('|');
    return [stepCount.toString(), paramKey, stepKey].join('||');
  }

  static int _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  static Map<String, dynamic> _asMap(dynamic value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return value.map((key, item) => MapEntry(key.toString(), item));
    }
    return const {};
  }

  static bool _looksLikeTechnicalId(String value) {
    final normalized = value.trim();
    return normalized.startsWith('oob_fn_') ||
        normalized.startsWith('debug_') ||
        RegExp(r'^[0-9a-fA-F]{8}[-_]').hasMatch(normalized);
  }

  static bool _looksLikeTechnicalSummaryTitle(String value) {
    final normalized = value.trim().toLowerCase();
    return normalized.startsWith('debug') ||
        normalized.startsWith('oob_fn_') ||
        normalized.contains('runlog') ||
        normalized == 'function' ||
        normalized == 'functions';
  }

  static String _fallbackNameFromId(String value) {
    final normalized = value.trim();
    if (normalized.isEmpty) return '复用指令';
    final cleaned = normalized
        .replaceFirst(RegExp(r'^oob_fn_'), '')
        .replaceFirst(RegExp(r'^debug_'), '')
        .replaceAll(RegExp(r'[_-]+'), ' ')
        .trim();
    if (cleaned.isEmpty) return '复用指令';
    return cleaned
        .split(' ')
        .where((part) => part.isNotEmpty)
        .map((part) => part.length <= 2 ? part.toUpperCase() : part)
        .join(' ');
  }
}

class _StepSummary {
  const _StepSummary({
    required this.index,
    required this.title,
    required this.kind,
    required this.executor,
    required this.tool,
    required this.raw,
  });

  factory _StepSummary.fromMap(Map<String, dynamic> map, {int? fallbackIndex}) {
    final index = map.containsKey('step_index')
        ? _FunctionSummary._asInt(map['step_index'])
        : (fallbackIndex ?? 0);
    final action = _FunctionSummary._asMap(map['action']);
    final tool = (action['tool'] ?? '').toString();
    return _StepSummary(
      index: index,
      title: tool,
      kind: 'action',
      executor: '',
      tool: tool,
      raw: Map<String, dynamic>.from(map),
    );
  }

  final int index;
  final String title;
  final String kind;
  final String executor;
  final String tool;
  final Map<String, dynamic> raw;

  String get displayTitle {
    final text = title.trim();
    if (text.isNotEmpty) return text;
    final toolText = tool.trim();
    if (toolText.isNotEmpty) return toolText;
    return executor.trim().isNotEmpty ? executor.trim() : 'step';
  }

  String get displayTool {
    final toolText = tool.trim();
    if (toolText.isNotEmpty) return toolText;
    final executorText = executor.trim();
    if (executorText.isNotEmpty) return executorText;
    return kind.trim();
  }

  String get semanticSignature {
    return [
      _normalizeSignatureText(displayTitle),
      _normalizeSignatureText(displayTool),
      _normalizeSignatureText(kind),
      _normalizeSignatureText(executor),
    ].join('|');
  }
}

class _FunctionGroup {
  const _FunctionGroup({required this.signature, required this.items});

  final String signature;
  final List<_FunctionSummary> items;

  _FunctionSummary get primary => items.first;

  bool get isAgentVisible => items.any((item) => item.agentVisible);

  int get variantCount => items.length;

  String get displayName {
    for (final item in items) {
      final name = item.displayName.trim();
      if (name.isNotEmpty &&
          !_FunctionSummary._looksLikeTechnicalSummaryTitle(name)) {
        return name;
      }
    }
    return primary.displayName;
  }

  String get displayDescription {
    for (final item in items) {
      final description = item.semanticDescription.trim();
      if (description.isNotEmpty) return description;
    }
    return primary.semanticDescription;
  }
}

class _ParameterSummary {
  const _ParameterSummary({
    required this.name,
    required this.type,
    required this.required,
    required this.description,
    required this.defaultValue,
  });

  factory _ParameterSummary.fromMap(Map<String, dynamic> map) {
    return _ParameterSummary(
      name: (map['name'] ?? '').toString(),
      type: (map['type'] ?? '').toString(),
      required: _asBool(map['required']),
      description: (map['description'] ?? '').toString(),
      defaultValue: (map['default'] ?? '').toString(),
    );
  }

  final String name;
  final String type;
  final bool required;
  final String description;
  final String defaultValue;
}

Future<void> _startHumanTrajectoryLearningFlow({
  required BuildContext context,
  required bool Function() isLearning,
  required ValueChanged<bool> setLearning,
  required Future<void> Function() reload,
}) async {
  if (isLearning()) return;
  if (!context.mounted) return;
  setLearning(true);
  showToast(
    _text(
      context,
      '开始记录操作。请在目标应用中完成点击或滑动，结束后点小万「完成学习」。',
      'Recording started. Perform taps or swipes in the target app, then tap Finish Learning on the floating assistant.',
    ),
    duration: const Duration(seconds: 4),
  );
  try {
    final result = await RunLogFunctionService.startHumanTrajectoryLearning();
    if (!context.mounted) return;
    if (result['success'] == true) {
      final function = result['function'];
      final functionId = function is Map
          ? (function['function_id'] ?? '').toString().trim()
          : '';
      final conversionSuccess = functionId.isNotEmpty;
      showToast(
        !conversionSuccess
            ? _text(
                context,
                '手动录制完成，轨迹已生成；复用指令生成失败',
                'Recording completed and trace was created; Function conversion failed',
              )
            : functionId.isEmpty
            ? _text(context, '已保存复用指令', 'Function saved')
            : _text(
                context,
                '已保存复用指令：$functionId',
                'Function saved: $functionId',
              ),
        type: ToastType.success,
        duration: const Duration(seconds: 3),
      );
      await reload();
    } else {
      showToast(
        (result['error_message'] ?? _text(context, '学习失败', 'Learning failed'))
            .toString(),
        type: ToastType.error,
      );
    }
  } catch (e) {
    if (!context.mounted) return;
    showToast(e.toString(), type: ToastType.error);
  } finally {
    setLearning(false);
  }
}

Future<Map<String, dynamic>?> _resolveRunArguments(
  BuildContext context,
  Map<String, dynamic>? spec,
) async {
  final arguments = _defaultArgumentsForFunctionSpec(spec);
  final missing = _missingRequiredRunParameters(spec, arguments);
  if (missing.isEmpty) {
    return arguments;
  }
  final manualArguments = await _showRunArgumentsDialog(context, missing);
  if (manualArguments == null) {
    return null;
  }
  return <String, dynamic>{...arguments, ...manualArguments};
}

Map<String, dynamic> _defaultArgumentsForFunctionSpec(
  Map<String, dynamic>? spec,
) {
  final inputSchema = _FunctionSummary._asMap(spec?['input_schema']);
  final properties = _FunctionSummary._asMap(inputSchema['properties']);
  final arguments = <String, dynamic>{};
  for (final entry in properties.entries) {
    final property = _FunctionSummary._asMap(entry.value);
    if (!property.containsKey('default')) continue;
    final value = property['default'];
    if (value != null) {
      arguments[entry.key] = value;
    }
  }
  return arguments;
}

List<_ParameterSummary> _missingRequiredRunParameters(
  Map<String, dynamic>? spec,
  Map<String, dynamic> arguments,
) {
  final inputSchema = _FunctionSummary._asMap(spec?['input_schema']);
  final properties = _FunctionSummary._asMap(inputSchema['properties']);
  final required = inputSchema['required'] is List
      ? (inputSchema['required'] as List)
            .map((value) => value.toString())
            .toSet()
      : const <String>{};
  final missing = <_ParameterSummary>[];
  for (final entry in properties.entries) {
    final item = _FunctionSummary._asMap(entry.value);
    final parameter = _ParameterSummary.fromMap(<String, dynamic>{
      ...item,
      'name': entry.key,
      'required': required.contains(entry.key),
    });
    if (!parameter.required || parameter.name.trim().isEmpty) continue;
    final current = arguments[parameter.name];
    final hasValue = current != null && current.toString().trim().isNotEmpty;
    if (!hasValue) {
      missing.add(parameter);
    }
  }
  return missing;
}

Future<Map<String, dynamic>?> _showRunArgumentsDialog(
  BuildContext context,
  List<_ParameterSummary> parameters,
) async {
  final controllers = <String, TextEditingController>{
    for (final parameter in parameters)
      parameter.name: TextEditingController(text: parameter.defaultValue),
  };
  try {
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (ctx) {
        String? errorText;
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            final palette = ctx.omniPalette;
            return AlertDialog(
              title: Text(_text(ctx, '填写执行参数', 'Run arguments')),
              content: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _text(
                          ctx,
                          '这个复用指令需要补充参数后才能执行。',
                          'This Function needs arguments before running.',
                        ),
                        style: TextStyle(
                          fontSize: 13,
                          color: palette.textSecondary,
                          height: 1.35,
                        ),
                      ),
                      const SizedBox(height: 12),
                      for (final parameter in parameters) ...[
                        TextField(
                          controller: controllers[parameter.name],
                          keyboardType: _keyboardTypeForParameter(parameter),
                          decoration: InputDecoration(
                            labelText: parameter.name,
                            helperText: parameter.description.isEmpty
                                ? (parameter.type.isEmpty
                                      ? null
                                      : parameter.type)
                                : parameter.description,
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                        ),
                        const SizedBox(height: 10),
                      ],
                      if (errorText != null) ...[
                        Text(
                          errorText!,
                          style: const TextStyle(
                            fontSize: 12,
                            color: AppColors.alertRed,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(ctx).pop(null),
                  child: Text(_text(ctx, '取消', 'Cancel')),
                ),
                FilledButton(
                  onPressed: () {
                    final values = <String, dynamic>{};
                    final missingNames = <String>[];
                    for (final parameter in parameters) {
                      final raw =
                          controllers[parameter.name]?.text.trim() ?? '';
                      if (raw.isEmpty) {
                        missingNames.add(parameter.name);
                        continue;
                      }
                      values[parameter.name] = _coerceArgumentValue(
                        raw,
                        parameter.type,
                      );
                    }
                    if (missingNames.isNotEmpty) {
                      setDialogState(() {
                        errorText = _text(
                          ctx,
                          '请填写：${missingNames.join(', ')}',
                          'Required: ${missingNames.join(', ')}',
                        );
                      });
                      return;
                    }
                    Navigator.of(ctx).pop(values);
                  },
                  child: Text(_text(ctx, '执行', 'Run')),
                ),
              ],
            );
          },
        );
      },
    );
    return result;
  } finally {
    for (final controller in controllers.values) {
      controller.dispose();
    }
  }
}

TextInputType _keyboardTypeForParameter(_ParameterSummary parameter) {
  final type = parameter.type.toLowerCase();
  if (type.contains('int') ||
      type.contains('number') ||
      type.contains('float') ||
      type.contains('double')) {
    return TextInputType.number;
  }
  return TextInputType.text;
}

dynamic _coerceArgumentValue(String value, String type) {
  final normalizedType = type.trim().toLowerCase();
  if (normalizedType.contains('bool')) {
    final normalizedValue = value.trim().toLowerCase();
    if (normalizedValue == 'true' ||
        normalizedValue == '1' ||
        normalizedValue == 'yes' ||
        normalizedValue == 'y') {
      return true;
    }
    if (normalizedValue == 'false' ||
        normalizedValue == '0' ||
        normalizedValue == 'no' ||
        normalizedValue == 'n') {
      return false;
    }
  }
  if (normalizedType.contains('int')) {
    return int.tryParse(value) ?? value;
  }
  if (normalizedType.contains('number') ||
      normalizedType.contains('float') ||
      normalizedType.contains('double')) {
    return num.tryParse(value) ?? value;
  }
  return value;
}

List<_FunctionGroup> _groupFunctions(List<_FunctionSummary> summaries) {
  final groups = <String, List<_FunctionSummary>>{};
  for (final summary in summaries) {
    groups
        .putIfAbsent(summary.semanticSignature, () => <_FunctionSummary>[])
        .add(summary);
  }
  final result = groups.entries
      .map(
        (entry) => _FunctionGroup(
          signature: entry.key,
          items: entry.value..sort(_compareFunctionSummaries),
        ),
      )
      .toList(growable: false);
  result.sort(_compareFunctionGroups);
  return result;
}

int _compareFunctionSummaries(_FunctionSummary a, _FunctionSummary b) {
  return a.displayName.compareTo(b.displayName);
}

int _compareFunctionGroups(_FunctionGroup a, _FunctionGroup b) {
  return a.displayName.compareTo(b.displayName);
}

String _normalizeSignatureText(String value) {
  final normalized = value.trim().toLowerCase();
  if (normalized.isEmpty) return '';
  return normalized.replaceAll(RegExp(r'\s+'), ' ');
}

String _previewNames(List<String> values, {int maxItems = 3}) {
  final names = <String>[];
  for (final value in values) {
    final trimmed = value.trim();
    if (trimmed.isEmpty || names.contains(trimmed)) continue;
    names.add(trimmed);
  }
  if (names.isEmpty) return '';
  if (names.length <= maxItems) return names.join(' · ');
  return '${names.take(maxItems).join(' · ')} +${names.length - maxItems}';
}

bool _asBool(dynamic value) {
  if (value is bool) return value;
  if (value is num) return value != 0;
  if (value is String) {
    final normalized = value.trim().toLowerCase();
    return normalized == 'true' || normalized == '1' || normalized == 'yes';
  }
  return false;
}

bool? _asNullableBool(dynamic value) {
  if (value == null) return null;
  if (value is bool) return value;
  if (value is num) return value != 0;
  if (value is String) {
    final normalized = value.trim().toLowerCase();
    if (normalized.isEmpty) return null;
    if (normalized == 'true' || normalized == '1' || normalized == 'yes') {
      return true;
    }
    if (normalized == 'false' || normalized == '0' || normalized == 'no') {
      return false;
    }
  }
  return null;
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}
