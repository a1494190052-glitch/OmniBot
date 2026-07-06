import 'dart:async';
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
  final Set<String> _openingRunLogIds = {};
  final Map<String, FunctionRunProgressEvent> _runProgressBySignature = {};
  StreamSubscription<FunctionRunProgressEvent>? _runProgressSubscription;
  bool _isLearning = false;

  @override
  void initState() {
    super.initState();
    _runProgressSubscription = RunLogFunctionService.functionRunProgressStream
        .listen(_handleRunProgressEvent);
    _load();
  }

  @override
  void dispose() {
    _runProgressSubscription?.cancel();
    super.dispose();
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
        _hasMore = _boolFromResult(result, 'has_more', 'hasMore');
        _nextOffset = _intFromResult(
          result,
          'next_offset',
          'nextOffset',
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
        _hasMore = _boolFromResult(result, 'has_more', 'hasMore');
        _nextOffset = _intFromResult(
          result,
          'next_offset',
          'nextOffset',
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
      _runProgressBySignature.remove(group.signature);
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
        _runProgressBySignature.remove(group.signature);
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
          _runProgressBySignature.remove(group.signature);
        });
      }
    }
  }

  Future<void> _openDetails(_FunctionGroup group) async {
    if (!mounted) return;
    await _showFunctionSpecDetails(context, group: group, onClosed: _load);
  }

  Future<void> _openRunLogs(_FunctionGroup group) async {
    await _openRunLogsForGroup(
      context: context,
      group: group,
      isOpening: () => _openingRunLogIds.contains(group.signature),
      setOpening: (value) {
        if (!mounted) return;
        setState(() {
          if (value) {
            _openingRunLogIds.add(group.signature);
          } else {
            _openingRunLogIds.remove(group.signature);
          }
        });
      },
    );
  }

  FunctionRunProgressEvent? get _runningFunctionProgressEvent =>
      _runningProgressEventFor(
        groups: _functions,
        runningIds: _runningIds,
        progressBySignature: _runProgressBySignature,
      );

  void _handleRunProgressEvent(FunctionRunProgressEvent event) {
    if (!mounted || event.functionId.isEmpty) return;
    final signature = _signatureForFunctionId(event.functionId);
    if (signature == null) return;
    setState(() {
      if (event.isTerminal) {
        _runProgressBySignature.remove(signature);
        _runningIds.remove(signature);
      } else {
        _runningIds.add(signature);
        _runProgressBySignature[signature] = event;
      }
    });
  }

  String? _signatureForFunctionId(String functionId) {
    final id = functionId.trim();
    if (id.isEmpty) return null;
    for (final group in _functions) {
      if (group.items.any((item) => item.functionId == id)) {
        return group.signature;
      }
    }
    return null;
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
            isOpeningRunLog: _openingRunLogIds.contains(group.signature),
            onRun: () => _run(group),
            onDelete: () => _delete(group),
            onOpenDetails: () => _openDetails(group),
            onOpenRunLogs: () => _openRunLogs(group),
          );
        },
      ),
    );
    final runningEvent = _runningFunctionProgressEvent;
    if (runningEvent == null) return list;
    return Column(
      children: [
        _FunctionLibraryProgressSlot(event: runningEvent),
        Expanded(child: list),
      ],
    );
  }
}

class _FunctionLibraryProgressSlot extends StatelessWidget {
  const _FunctionLibraryProgressSlot({required this.event});

  final FunctionRunProgressEvent event;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final message = event.message.trim();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 12),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withValues(alpha: 0.10),
        border: Border(
          bottom: BorderSide(
            color: palette.accentPrimary.withValues(alpha: 0.18),
          ),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            _progressTitle(event),
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          if (message.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              message,
              style: TextStyle(color: palette.textSecondary, fontSize: 11),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ],
      ),
    );
  }

  static String _progressTitle(FunctionRunProgressEvent event) {
    final name = event.label.trim().isNotEmpty
        ? event.label.trim()
        : event.functionId.trim();
    final prefix = event.isRunning
        ? AppTextLocalizer.choose(zh: '正在执行', en: 'Running')
        : event.status == 'stopped'
        ? AppTextLocalizer.choose(zh: '已停止', en: 'Stopped')
        : event.status == 'failed' || event.message.contains('失败')
        ? AppTextLocalizer.choose(zh: '执行失败', en: 'Failed')
        : AppTextLocalizer.choose(zh: '执行完成', en: 'Completed');
    final step = event.displayStepNumber;
    final stepText = step != null && step > 0
        ? (event.stepCount > 0 ? ' $step/${event.stepCount}' : ' $step')
        : '';
    final target = name.isEmpty
        ? AppTextLocalizer.choose(zh: '复用指令', en: 'Function')
        : name;
    return '$prefix $target$stepText';
  }
}

FunctionRunProgressEvent? _runningProgressEventFor({
  required List<_FunctionGroup> groups,
  required Set<String> runningIds,
  required Map<String, FunctionRunProgressEvent> progressBySignature,
}) {
  if (runningIds.isEmpty) return null;
  for (final group in groups) {
    if (!runningIds.contains(group.signature)) continue;
    return progressBySignature[group.signature] ??
        _fallbackRunningProgressEvent(group);
  }
  return null;
}

FunctionRunProgressEvent _fallbackRunningProgressEvent(_FunctionGroup group) {
  final function = group.primary;
  final stepCount = function.stepCount > 0
      ? function.stepCount
      : function.stepSummaries.length;
  final label = group.displayName.trim().isNotEmpty
      ? group.displayName.trim()
      : function.displayName;
  final message = AppTextLocalizer.choose(
    zh: '准备执行复用指令',
    en: 'Preparing Function',
  );
  final raw = <String, dynamic>{
    'status': 'started',
    'function_id': function.functionId,
    'functionId': function.functionId,
    'label': label,
    'message': message,
    if (stepCount > 0) 'step_count': stepCount,
    if (stepCount > 0) 'stepCount': stepCount,
  };
  return FunctionRunProgressEvent(
    status: 'started',
    runId: '',
    taskId: '',
    functionId: function.functionId,
    label: label,
    message: message,
    stepCount: stepCount,
    currentStepIndex: null,
    currentStepNumber: null,
    embeddedInVlmTask: false,
    timestampMs: 0,
    rawJson: raw,
  );
}

class _FunctionCard extends StatelessWidget {
  const _FunctionCard({
    required this.group,
    required this.isDeleting,
    required this.isRunning,
    required this.isOpeningRunLog,
    required this.onRun,
    required this.onDelete,
    required this.onOpenDetails,
    required this.onOpenRunLogs,
  });

  final _FunctionGroup group;
  final bool isDeleting;
  final bool isRunning;
  final bool isOpeningRunLog;
  final VoidCallback onRun;
  final VoidCallback onDelete;
  final VoidCallback onOpenDetails;
  final VoidCallback onOpenRunLogs;

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
      sourceRunCount: group.sourceRunIds.length,
      runCount: group.runCount,
      successCount: group.successCount,
      failCount: group.failCount,
      lastRunSuccess: group.lastRunSuccess,
      agentVisible: group.isAgentVisible,
      isRunning: isRunning,
      onRun: onRun,
      onRunLogsTap: group.runLogIds.isEmpty ? null : onOpenRunLogs,
      isBusy: isDeleting || isOpeningRunLog,
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

bool _boolFromResult(
  Map<String, dynamic> result,
  String snakeKey,
  String camelKey,
) {
  return result[snakeKey] == true || result[camelKey] == true;
}

int _intFromResult(
  Map<String, dynamic> result,
  String snakeKey,
  String camelKey,
) {
  final raw = result[snakeKey] ?? result[camelKey];
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
    final metadata = _FunctionSummary._asMap(specJson['metadata']);
    final spec = FunctionSpec(
      json: specJson,
      agentPrompt: functionAgentPrompt(specJson),
      aiEnhanced:
          _asBool(specJson['ai_enhanced']) ||
          _asBool(specJson['aiEnhanced']) ||
          _asBool(metadata['ai_enhanced']) ||
          _asBool(metadata['aiEnhanced']),
    );
    final runId = _functionSpecSheetRunId(group, specJson);
    final importResult = _functionSpecSheetImportResult(group, specJson, runId);
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

Future<void> _openRunLogsForGroup({
  required BuildContext context,
  required _FunctionGroup group,
  required bool Function() isOpening,
  required ValueChanged<bool> setOpening,
}) async {
  if (isOpening()) return;
  setOpening(true);
  List<_FunctionRunLogEntry> entries = const [];
  try {
    entries = await _loadFunctionRunLogEntries(group);
    if (!context.mounted) return;
    if (entries.isEmpty) {
      showToast(
        _text(context, '暂无可查看的执行记录', 'No execution records available'),
        type: ToastType.error,
      );
    }
  } catch (error) {
    if (context.mounted) showToast(error.toString(), type: ToastType.error);
  } finally {
    if (context.mounted) setOpening(false);
  }
  if (entries.isEmpty || !context.mounted) return;
  final selected = await showModalBottomSheet<_FunctionRunLogEntry>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) =>
        _FunctionRunLogPickerSheet(title: group.displayName, entries: entries),
  );
  if (selected == null || !context.mounted) return;
  await showRunLogTimelineSheet(
    context,
    runId: selected.runId,
    title: _functionRunLogTitle(context, selected),
  );
}

Future<List<_FunctionRunLogEntry>> _loadFunctionRunLogEntries(
  _FunctionGroup group,
) async {
  final ids = group.runLogIds.toList(growable: true);
  if (ids.isEmpty && group.hasLastRun) {
    final lastRun = await RunLogFunctionService.getFunctionLastRunLog(
      group.lastRunFunctionId,
    );
    final lastRunId = lastRun.runId.trim();
    if (lastRunId.isNotEmpty) ids.add(lastRunId);
  }
  if (ids.isEmpty) return const [];

  final limit = (ids.length * 4).clamp(50, 500).toInt();
  final runsById = <String, UtgRunLogSummary>{};
  try {
    final snapshot = await RunLogFunctionService.getInternalRunLogs(
      limit: limit,
    );
    for (final run in snapshot.runs) {
      final id = run.runId.trim();
      if (id.isNotEmpty) runsById[id] = run;
    }
  } catch (_) {
    // Source RunLog ids are still usable even when summary lookup is absent.
  }
  return ids
      .where((id) => id.trim().isNotEmpty)
      .map((id) => _FunctionRunLogEntry(id.trim(), runsById[id.trim()]))
      .toList(growable: false);
}

class _FunctionRunLogEntry {
  const _FunctionRunLogEntry(this.runId, this.run);

  final String runId;
  final UtgRunLogSummary? run;
}

class _FunctionRunLogPickerSheet extends StatelessWidget {
  const _FunctionRunLogPickerSheet({
    required this.title,
    required this.entries,
  });

  final String title;
  final List<_FunctionRunLogEntry> entries;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final maxHeight = MediaQuery.sizeOf(context).height * 0.72;
    return SafeArea(
      top: false,
      child: Align(
        alignment: Alignment.bottomCenter,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: maxHeight),
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: palette.surfacePrimary,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(18),
              ),
              border: Border(top: BorderSide(color: palette.borderSubtle)),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.16),
                  blurRadius: 22,
                  offset: const Offset(0, -8),
                ),
              ],
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 16, 10, 10),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              title.trim().isEmpty
                                  ? _text(context, '执行记录', 'Execution records')
                                  : title.trim(),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 18,
                                fontWeight: FontWeight.w800,
                                height: 1.2,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              _text(
                                context,
                                '执行记录 · ${entries.length}',
                                'Execution records · ${entries.length}',
                              ),
                              style: TextStyle(
                                color: palette.textSecondary,
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                      ),
                      IconButton(
                        tooltip: _text(context, '关闭', 'Close'),
                        icon: const Icon(Icons.close_rounded),
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ],
                  ),
                ),
                Flexible(
                  child: ListView.separated(
                    shrinkWrap: true,
                    padding: const EdgeInsets.fromLTRB(14, 0, 14, 16),
                    itemCount: entries.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 8),
                    itemBuilder: (context, index) {
                      final entry = entries[index];
                      return _FunctionRunLogTile(
                        entry: entry,
                        onTap: () => Navigator.of(context).pop(entry),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FunctionRunLogTile extends StatelessWidget {
  const _FunctionRunLogTile({required this.entry, required this.onTap});

  final _FunctionRunLogEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final status = _functionRunLogStatus(context, entry);
    final meta = _functionRunLogMeta(context, entry);
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Ink(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: context.isDarkTheme
                ? palette.surfaceSecondary
                : palette.pageBackground,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: status.color.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(status.icon, size: 17, color: status.color),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      _functionRunLogTitle(context, entry),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                        height: 1.25,
                      ),
                    ),
                    if (meta.isNotEmpty) ...[
                      const SizedBox(height: 5),
                      Text(
                        meta,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                    const SizedBox(height: 5),
                    Text(
                      entry.runId,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textTertiary,
                        fontSize: 11,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Icon(
                Icons.chevron_right_rounded,
                color: palette.textTertiary,
                size: 20,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _FunctionRunLogStatus {
  const _FunctionRunLogStatus({
    required this.icon,
    required this.color,
    required this.label,
  });

  final IconData icon;
  final Color color;
  final String label;
}

String _functionRunLogTitle(BuildContext context, _FunctionRunLogEntry entry) {
  final run = entry.run;
  final title = _firstNonBlankValue([
    run?.goal,
    run?.operationDescription,
    run?.executionSummary,
    run?.selectorLabel,
  ]);
  if (title.isNotEmpty) return title;
  final id = entry.runId.trim();
  final traceLabel = _text(context, '轨迹', 'Trace');
  if (id.length > 14) return '$traceLabel ${id.substring(0, 14)}';
  return id.isEmpty
      ? _text(context, '执行记录', 'Execution record')
      : '$traceLabel $id';
}

String _functionRunLogMeta(BuildContext context, _FunctionRunLogEntry entry) {
  final run = entry.run;
  if (run == null) {
    return _text(context, '点击查看该次结果', 'Tap to view this result');
  }
  final status = _functionRunLogStatus(context, entry).label;
  final parts = <String>[
    status,
    if (run.stepCount > 0)
      _text(context, '${run.stepCount} 步', '${run.stepCount} steps'),
    _functionRunLogTimeLabel(run),
  ].where((value) => value.trim().isNotEmpty).toList(growable: false);
  return parts.join(' · ');
}

String _functionRunLogTimeLabel(UtgRunLogSummary run) {
  final millis = run.startedAtMs ?? run.finishedAtMs;
  DateTime? time;
  if (millis != null && millis > 0) {
    time = DateTime.fromMillisecondsSinceEpoch(millis).toLocal();
  } else {
    time = DateTime.tryParse(
      _firstNonBlankValue([run.startedAt, run.finishedAt]),
    )?.toLocal();
  }
  if (time == null) return '';
  String two(int value) => value.toString().padLeft(2, '0');
  return '${time.year}/${two(time.month)}/${two(time.day)} ${two(time.hour)}:${two(time.minute)}';
}

_FunctionRunLogStatus _functionRunLogStatus(
  BuildContext context,
  _FunctionRunLogEntry entry,
) {
  final run = entry.run;
  if (run == null) {
    return _FunctionRunLogStatus(
      icon: Icons.receipt_long_outlined,
      color: context.omniPalette.textTertiary,
      label: _text(context, '执行记录', 'Execution record'),
    );
  }
  final rawStatus = run.runStatus.trim().toLowerCase();
  if (!run.runFinished ||
      rawStatus == 'running' ||
      rawStatus == 'in_progress') {
    return _FunctionRunLogStatus(
      icon: Icons.timelapse_rounded,
      color: context.isDarkTheme
          ? const Color(0xFFFFD166)
          : const Color(0xFFE6A700),
      label: _text(context, '运行中', 'Running'),
    );
  }
  final success = run.runSuccess ?? run.success;
  if (success) {
    return _FunctionRunLogStatus(
      icon: Icons.check_circle_outline_rounded,
      color: context.isDarkTheme
          ? const Color(0xFF63D98A)
          : const Color(0xFF19A974),
      label: _text(context, '已完成', 'Done'),
    );
  }
  return _FunctionRunLogStatus(
    icon: Icons.error_outline_rounded,
    color: context.isDarkTheme
        ? const Color(0xFFFF7A7A)
        : const Color(0xFFE14C4C),
    label: _text(context, '失败', 'Failed'),
  );
}

Map<String, dynamic> _functionSpecJsonFromDetail(
  Map<String, dynamic>? rawSpec,
) {
  if (rawSpec == null || rawSpec.isEmpty) return const {};
  final wrapped = rawSpec['function_spec'] ?? rawSpec['spec'];
  if (wrapped is Map) return _deepStringKeyMap(wrapped);
  return _deepStringKeyMap(rawSpec);
}

UtgRunLogImportResult _functionSpecSheetImportResult(
  _FunctionGroup group,
  Map<String, dynamic> specJson,
  String runId,
) {
  final functionId = _firstNonBlankValue([
    specJson['function_id'],
    specJson['functionId'],
    group.primary.functionId,
  ]);
  final sourceRunIds = _functionSourceRunIds(group, specJson);
  final metadata = _FunctionSummary._asMap(specJson['metadata']);
  final visibility = _firstNonBlankValue([
    specJson['visibility'],
    metadata['visibility'],
    group.primary.visibility,
  ]);
  final agentVisible =
      _asNullableBool(specJson['agent_visible']) ??
      _asNullableBool(specJson['agentVisible']) ??
      _asNullableBool(metadata['agent_visible']) ??
      group.isAgentVisible;
  return UtgRunLogImportResult.fromMap({
    'success': true,
    'run_id': runId,
    'function_id': functionId,
    'created_function_id': functionId,
    'functions_created': 0,
    'asset_kind': 'reusable_function',
    'asset_state': 'native_local',
    'hit_function_ids': <String>[if (functionId.isNotEmpty) functionId],
    'source_run_ids': sourceRunIds,
    'agent_visible': agentVisible,
    if (visibility.isNotEmpty) 'visibility': visibility,
  });
}

String _functionSpecSheetRunId(
  _FunctionGroup group,
  Map<String, dynamic> specJson,
) {
  return _firstNonBlankValue(_functionSourceRunIds(group, specJson));
}

List<String> _functionSourceRunIds(
  _FunctionGroup group,
  Map<String, dynamic> specJson,
) {
  final ids = <String>[];
  void add(dynamic value) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty && !ids.contains(text)) ids.add(text);
  }

  void addMany(dynamic value) {
    if (value is Iterable) {
      for (final item in value) {
        add(item);
      }
    }
  }

  for (final id in group.sourceRunIds) {
    add(id);
  }
  final source = _FunctionSummary._asMap(specJson['source']);
  final metadata = _FunctionSummary._asMap(specJson['metadata']);
  add(specJson['source_run_id']);
  add(specJson['sourceRunId']);
  add(specJson['run_id']);
  add(specJson['runId']);
  add(source['source_run_id']);
  add(source['sourceRunId']);
  add(source['run_id']);
  add(source['runId']);
  add(metadata['source_run_id']);
  add(metadata['sourceRunId']);
  addMany(specJson['source_run_ids']);
  addMany(specJson['sourceRunIds']);
  addMany(source['source_run_ids']);
  addMany(source['sourceRunIds']);
  addMany(source['run_ids']);
  addMany(source['runIds']);
  addMany(metadata['source_run_ids']);
  addMany(metadata['sourceRunIds']);
  return ids;
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

String _firstNonBlankValue(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  return '';
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
    required this.cardCount,
    required this.stepCount,
    required this.parameterNames,
    required this.createdAt,
    required this.updatedAt,
    required this.runCount,
    required this.successCount,
    required this.failCount,
    required this.lastRunAt,
    required this.lastRunId,
    required this.lastRunSuccess,
    required this.sourceRunIds,
    required this.stepSummaries,
    required this.agentVisible,
    required this.visibility,
  });

  factory _FunctionSummary.fromMap(Map<String, dynamic> map) {
    final params = map['parameter_names'];
    final runStats = _asMap(map['run_stats']);
    final lastRun = _asMap(map['last_run'] ?? runStats['last_run']);
    final sourceRunIds = map['source_run_ids'];
    final stepSummaries = map['step_summaries'];
    final stepCount = _asInt(map['step_count']);
    final cardCount = _asInt(map['card_count']);
    return _FunctionSummary(
      functionId: (map['function_id'] ?? '').toString(),
      name: (map['name'] ?? '').toString(),
      description: (map['description'] ?? '').toString(),
      cardCount: cardCount > 0 ? cardCount : stepCount,
      stepCount: stepCount,
      parameterNames: params is List
          ? params.map((e) => e.toString()).toList(growable: false)
          : const [],
      createdAt: (map['registered_at'] ?? map['created_at'] ?? '').toString(),
      updatedAt: (map['updated_at'] ?? '').toString(),
      runCount: _asInt(runStats['run_count'] ?? map['run_count']),
      successCount: _asInt(runStats['success_count'] ?? map['success_count']),
      failCount: _asInt(runStats['fail_count'] ?? map['fail_count']),
      lastRunAt:
          (runStats['last_run_at'] ??
                  lastRun['created_at'] ??
                  map['last_run_at'] ??
                  '')
              .toString(),
      lastRunId: _firstNonBlankValue([
        lastRun['run_id'],
        lastRun['runId'],
        runStats['last_run_id'],
        runStats['lastRunId'],
        map['last_run_id'],
        map['lastRunId'],
      ]),
      lastRunSuccess: _asNullableBool(
        lastRun['success'] ?? runStats['last_success'] ?? map['last_success'],
      ),
      sourceRunIds: sourceRunIds is List
          ? sourceRunIds.map((e) => e.toString()).toList(growable: false)
          : const [],
      stepSummaries: stepSummaries is List
          ? stepSummaries
                .whereType<Map>()
                .map(
                  (item) => _StepSummary.fromMap(
                    Map<String, dynamic>.from(
                      item.map((k, v) => MapEntry(k.toString(), v)),
                    ),
                  ),
                )
                .toList(growable: false)
          : const [],
      agentVisible:
          _asNullableBool(map['agent_visible']) ??
          _asNullableBool(_asMap(map['metadata'])['agent_visible']) ??
          false,
      visibility:
          (map['visibility'] ?? _asMap(map['metadata'])['visibility'] ?? '')
              .toString(),
    );
  }

  final String functionId;
  final String name;
  final String description;
  final int cardCount;
  final int stepCount;
  final List<String> parameterNames;
  final String createdAt;
  final String updatedAt;
  final int runCount;
  final int successCount;
  final int failCount;
  final String lastRunAt;
  final String lastRunId;
  final bool? lastRunSuccess;
  final List<String> sourceRunIds;
  final List<_StepSummary> stepSummaries;
  final bool agentVisible;
  final String visibility;

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
    return [
      cardCount.toString(),
      stepCount.toString(),
      paramKey,
      stepKey,
    ].join('||');
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
    required this.id,
    required this.title,
    required this.kind,
    required this.executor,
    required this.tool,
    required this.raw,
  });

  factory _StepSummary.fromMap(Map<String, dynamic> map, {int? fallbackIndex}) {
    final index = map.containsKey('index')
        ? _FunctionSummary._asInt(map['index'])
        : (fallbackIndex ?? 0);
    final normalized = Map<String, dynamic>.from(map);
    normalized.putIfAbsent('index', () => index);
    normalized.putIfAbsent('step_id', () => map['id'] ?? 'step_${index + 1}');
    normalized.putIfAbsent(
      'summary',
      () => [map['summary'], map['title'], map['tool'], map['step_id']]
          .map((value) => value?.toString().trim() ?? '')
          .firstWhere((value) => value.isNotEmpty, orElse: () => ''),
    );
    return _StepSummary(
      index: index,
      id: (map['id'] ?? '').toString(),
      title: (map['title'] ?? '').toString(),
      kind: (map['kind'] ?? '').toString(),
      executor: (map['executor'] ?? '').toString(),
      tool: (map['tool'] ?? '').toString(),
      raw: normalized,
    );
  }

  final int index;
  final String id;
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

  int get runCount => items.fold<int>(0, (sum, item) => sum + item.runCount);

  int get successCount =>
      items.fold<int>(0, (sum, item) => sum + item.successCount);

  int get failCount => items.fold<int>(0, (sum, item) => sum + item.failCount);

  _FunctionSummary? get latestRunFunction {
    _FunctionSummary? latest;
    DateTime? latestTime;
    for (final item in items) {
      final hasLastRun =
          item.lastRunId.trim().isNotEmpty ||
          item.lastRunAt.trim().isNotEmpty ||
          item.lastRunSuccess != null;
      if (!hasLastRun) continue;
      final time = _parseTimestamp(item.lastRunAt);
      if (latest == null) {
        latest = item;
        latestTime = time;
        continue;
      }
      if (time != null && (latestTime == null || time.isAfter(latestTime))) {
        latest = item;
        latestTime = time;
      }
    }
    return latest;
  }

  bool get hasLastRun => runCount > 0 || latestRunFunction != null;

  String get lastRunId => latestRunFunction?.lastRunId.trim() ?? '';

  String get lastRunFunctionId =>
      latestRunFunction?.functionId.trim().isNotEmpty == true
      ? latestRunFunction!.functionId.trim()
      : primary.functionId;

  List<String> get runLogIds {
    final ids = <String>{};
    for (final id in sourceRunIds) {
      final normalized = id.trim();
      if (normalized.isNotEmpty) ids.add(normalized);
    }
    final latestId = lastRunId.trim();
    if (ids.isEmpty && latestId.isNotEmpty) ids.add(latestId);
    return ids.toList(growable: false);
  }

  bool? get lastRunSuccess {
    _FunctionSummary? latest;
    DateTime? latestTime;
    for (final item in items) {
      if (item.lastRunSuccess == null) continue;
      final time = _parseTimestamp(item.lastRunAt);
      if (latest == null) {
        latest = item;
        latestTime = time;
        continue;
      }
      if (time != null && (latestTime == null || time.isAfter(latestTime))) {
        latest = item;
        latestTime = time;
      }
    }
    return latest?.lastRunSuccess;
  }

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

  String get createdAt {
    final parsed = items
        .map((item) => _parseTimestamp(item.createdAt))
        .whereType<DateTime>()
        .toList(growable: false);
    if (parsed.isEmpty) return primary.createdAt;
    parsed.sort();
    return parsed.first.millisecondsSinceEpoch.toString();
  }

  List<String> get sourceRunIds {
    final ids = <String>{};
    for (final item in items) {
      ids.addAll(item.sourceRunIds);
    }
    return ids.toList(growable: false);
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
      final functionId = (result['function_id'] ?? '').toString();
      final conversionSuccess =
          result['conversion_success'] == true ||
          result['conversionSuccess'] == true ||
          functionId.isNotEmpty;
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
  final rawParameters = spec?['parameters'];
  if (rawParameters is! List) return const {};
  final arguments = <String, dynamic>{};
  for (final item in rawParameters) {
    if (item is! Map) continue;
    final name = (item['name'] ?? '').toString().trim();
    if (name.isEmpty || !item.containsKey('default')) continue;
    final value = item['default'];
    if (value != null) {
      arguments[name] = value;
    }
  }
  return arguments;
}

List<_ParameterSummary> _missingRequiredRunParameters(
  Map<String, dynamic>? spec,
  Map<String, dynamic> arguments,
) {
  final rawParameters = spec?['parameters'];
  if (rawParameters is! List) return const [];
  final missing = <_ParameterSummary>[];
  for (final item in rawParameters) {
    if (item is! Map) continue;
    final parameter = _ParameterSummary.fromMap(
      Map<String, dynamic>.from(item.map((k, v) => MapEntry(k.toString(), v))),
    );
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
  final dateCompare = _compareDateTimeDesc(a.createdAt, b.createdAt);
  if (dateCompare != null) return dateCompare;
  final runCompare = b.runCount.compareTo(a.runCount);
  if (runCompare != 0) return runCompare;
  return a.displayName.compareTo(b.displayName);
}

int _compareFunctionGroups(_FunctionGroup a, _FunctionGroup b) {
  final dateCompare = _compareDateTimeDesc(a.createdAt, b.createdAt);
  if (dateCompare != null) return dateCompare;
  final runCompare = b.runCount.compareTo(a.runCount);
  if (runCompare != 0) return runCompare;
  return a.displayName.compareTo(b.displayName);
}

int? _compareDateTimeDesc(String a, String b) {
  final left = _parseTimestamp(a);
  final right = _parseTimestamp(b);
  if (left == null || right == null) return null;
  return right.compareTo(left);
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

DateTime? _parseTimestamp(String raw) {
  final text = raw.trim();
  if (text.isEmpty) return null;
  final millis = int.tryParse(text);
  if (millis != null && millis > 0) {
    return DateTime.fromMillisecondsSinceEpoch(millis);
  }
  return DateTime.tryParse(text);
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}
