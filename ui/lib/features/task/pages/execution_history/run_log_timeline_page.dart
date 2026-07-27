import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/features/task/pages/execution_history/widgets/reusable_function_card.dart';
import 'package:ui/features/task/run_log/oob_canonical_action_schema.dart';
import 'package:ui/features/task/run_log/function_spec.dart';
import 'package:ui/features/task/run_log/run_log_function_service.dart';
import 'package:ui/features/task/pages/scheduled_tasks/widgets/schedule_task_sheet.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/image_preview_overlay.dart';

Future<void> showRunLogTimelineSheet(
  BuildContext context, {
  required String runId,
  String title = '',
  String? baseUrl,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) =>
        _RunLogTimelineSheetFrame(runId: runId, title: title, baseUrl: baseUrl),
  );
}

/// 通过 runId + stepId 直接跳到单步 detail sheet。
/// 找不到匹配的 step 时 fallback 到完整 timeline。
Future<void> showRunLogStepDetailSheet(
  BuildContext context, {
  required String runId,
  required String stepId,
  String title = '',
  String? baseUrl,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) => _StepDetailLoader(
      runId: runId,
      stepId: stepId,
      title: title,
      baseUrl: baseUrl,
    ),
  );
}

Future<void> showReusableFunctionSpecSheet(
  BuildContext context, {
  required FunctionSpec spec,
  required String runId,
  String? baseUrl,
  UtgRunLogImportResult? initialImportResult,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) => _ReusableFunctionSpecSheet(
      spec: spec,
      runId: runId,
      baseUrl: baseUrl,
      initialImportResult: initialImportResult,
    ),
  );
}

/// 内部 widget：先拉 canonical RunLog，再定位到目标 step 并展示单步 detail。
class _StepDetailLoader extends StatefulWidget {
  const _StepDetailLoader({
    required this.runId,
    required this.stepId,
    required this.title,
    this.baseUrl,
  });

  final String runId;
  final String stepId;
  final String title;
  final String? baseUrl;

  @override
  State<_StepDetailLoader> createState() => _StepDetailLoaderState();
}

class _StepDetailLoaderState extends State<_StepDetailLoader> {
  bool _loading = true;
  Map<String, dynamic>? _step;
  int _stepIndex = 0;
  Map<String, dynamic> _payload = const {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final payload = await RunLogFunctionService.getInternalRunLogTimeline(
        runId: widget.runId,
      );
      if (!mounted) return;
      final steps = _extractTimelineSteps(payload);
      final targetId = widget.stepId.trim().toLowerCase();
      Map<String, dynamic>? matched;
      int matchedIndex = 0;
      for (int i = 0; i < steps.length; i++) {
        final step = steps[i];
        if (_timelineStepMatchesId(step, targetId)) {
          matched = step;
          matchedIndex = i;
          break;
        }
      }
      setState(() {
        _payload = payload;
        _step = matched ?? (steps.isNotEmpty ? steps.first : null);
        _stepIndex = matched != null ? matchedIndex : 0;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      final palette = context.omniPalette;
      return GestureDetector(
        onTap: () => Navigator.of(context, rootNavigator: true).maybePop(),
        behavior: HitTestBehavior.opaque,
        child: SafeArea(
          top: false,
          child: Align(
            alignment: Alignment.bottomCenter,
            child: GestureDetector(
              onTap: () {},
              child: Container(
                height: 180,
                width: double.infinity,
                decoration: BoxDecoration(
                  color: palette.pageBackground,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(22),
                  ),
                ),
                child: Center(
                  child: CircularProgressIndicator(
                    color: palette.textSecondary,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    }
    final step = _step;
    if (step == null) {
      // 没找到任何 step，fallback 到完整 timeline
      return _RunLogTimelineSheetFrame(
        runId: widget.runId,
        title: widget.title,
        baseUrl: widget.baseUrl,
      );
    }
    return _StepDetailSheet(
      step: step,
      fallbackIndex: _stepIndex,
      runId: widget.runId,
      title: widget.title,
      payload: _payload,
      baseUrl: widget.baseUrl,
    );
  }
}

class RunLogTimelinePage extends StatefulWidget {
  const RunLogTimelinePage({
    super.key,
    required this.runId,
    required this.title,
    this.baseUrl,
    this.embedded = false,
  });

  final String runId;
  final String title;
  final String? baseUrl;
  final bool embedded;

  @override
  State<RunLogTimelinePage> createState() => _RunLogTimelinePageState();
}

class _RunLogTimelinePageState extends State<RunLogTimelinePage> {
  static const _refreshInterval = Duration(seconds: 2);

  Map<String, dynamic> _payload = const {};
  List<Map<String, dynamic>> _steps = [];
  List<_RunLogStepGroup> _stepGroups = const [];
  bool _isLoading = true;
  bool _isConvertingFunction = false;
  bool _isEnhancingFunction = false;
  bool _isReplayingRunLog = false;
  FunctionSpec? _savedFunctionSpec;
  UtgRunLogImportResult? _savedFunctionImportResult;
  _RunLogFunctionPanelStatus _functionPanelStatus =
      _RunLogFunctionPanelStatus.idle;
  String? _functionPanelMessage;
  String? _functionPanelError;
  String? _error;
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool showLoading = true}) async {
    if (showLoading) {
      setState(() {
        _isLoading = true;
        _error = null;
      });
    }
    try {
      final payload = await RunLogFunctionService.getInternalRunLogTimeline(
        runId: widget.runId,
      );
      final steps = _extractTimelineSteps(payload);
      final stepGroups = _groupTimelineSteps(steps);
      final completedEnhancementSpec = await _loadCompletedFunctionEnhancement(
        payload,
      );
      if (!mounted) return;
      setState(() {
        _payload = payload;
        _steps = steps;
        _stepGroups = stepGroups;
        final error = _runLogPayloadError(context, payload);
        _error = error;
        if (completedEnhancementSpec != null) {
          final diagnostics = _functionEnhancementDiagnostics(payload);
          _savedFunctionSpec = completedEnhancementSpec;
          _functionPanelStatus = _panelStatusFromFunctionSpec(
            completedEnhancementSpec,
          );
          _functionPanelMessage = _firstNonBlank([
            diagnostics['message'],
            completedEnhancementSpec.enhancementMessage,
            _conversionEnhancementMessage(
              context,
              completedEnhancementSpec.enhancementStatus,
            ),
          ]);
          _functionPanelError =
              completedEnhancementSpec.enhancementStatus ==
                  FunctionEnhancementStatus.failed
              ? _firstNonBlank([
                  diagnostics['error_message'],
                  diagnostics['error_code'],
                ])
              : null;
        } else if (_functionPanelStatus == _RunLogFunctionPanelStatus.saved) {
          _functionPanelStatus = _RunLogFunctionPanelStatus.idle;
          _functionPanelMessage = null;
          _functionPanelError = null;
        }
        _isLoading = false;
      });
      _scheduleRefreshIfNeeded(payload);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = context.l10n.omniflowAssetRunLogNotReady;
        _isLoading = false;
      });
      _refreshTimer?.cancel();
    }
  }

  Future<FunctionSpec?> _loadCompletedFunctionEnhancement(
    Map<String, dynamic> payload,
  ) async {
    final currentSpec = _savedFunctionSpec;
    if (currentSpec?.enhancementStatus != FunctionEnhancementStatus.enhancing) {
      return null;
    }
    final diagnostics = _functionEnhancementDiagnostics(payload);
    final status = _functionEnhancementStatus(diagnostics['status']);
    if (status == FunctionEnhancementStatus.none ||
        status == FunctionEnhancementStatus.enhancing) {
      return null;
    }
    var functionJson = currentSpec!.json;
    final stored = await RunLogFunctionService.getFunction(
      currentSpec.functionId,
    );
    final storedFunction = _asStringKeyMap(stored?['function']);
    if (storedFunction.isNotEmpty) {
      functionJson = storedFunction;
    }
    final message = _firstNonBlank([diagnostics['message']]);
    return FunctionSpec(
      json: functionJson,
      agentPrompt: functionAgentPrompt(functionJson),
      aiEnhanced: currentSpec.aiEnhanced || status.isApplied,
      enhancementStatus: status,
      enhancementMessage: message.isEmpty ? null : message,
    );
  }

  void _scheduleRefreshIfNeeded(Map<String, dynamic> payload) {
    _refreshTimer?.cancel();
    final enhancementPending =
        _savedFunctionSpec?.enhancementStatus ==
        FunctionEnhancementStatus.enhancing;
    if (!mounted || (_isRunLogFinished(payload) && !enhancementPending)) return;
    _refreshTimer = Timer(_refreshInterval, () {
      if (mounted) {
        unawaited(_load(showLoading: false));
      }
    });
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    final stepCount = _steps.length;
    final subtitleParts = <String>[
      if (_payload.isNotEmpty) _runLogStatusInfo(context, _payload).label,
      if (stepCount > 0) l10n.runLogTimelineStepCount(stepCount),
    ].where((item) => item.trim().isNotEmpty).toList(growable: false);
    final subtitle = subtitleParts.isNotEmpty
        ? subtitleParts.join(' · ')
        : null;
    final title = l10n.runLogTimelineTitle;
    final convertEligibility = _runLogConvertEligibility(
      context,
      _payload,
      _steps,
    );
    final savedSpec = _savedFunctionSpec;
    final List<Widget> actions = <Widget>[
      Tooltip(
        message: _text(context, '执行复用指令', 'Run Function'),
        child: IconButton(
          key: const ValueKey('run-log-action-replay'),
          icon: _isReplayingRunLog
              ? SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: palette.textPrimary,
                  ),
                )
              : const Icon(Icons.play_arrow_rounded),
          color: palette.textPrimary,
          onPressed:
              _steps.isEmpty || _isConvertingFunction || _isReplayingRunLog
              ? null
              : _executeCurrentRunLog,
        ),
      ),
      Tooltip(
        message: savedSpec != null
            ? _text(context, '查看复用指令', 'View Function')
            : convertEligibility.canConvert
            ? _text(context, '保存为复用指令', 'Save Function')
            : convertEligibility.message,
        child: IconButton(
          key: const ValueKey('run-log-action-save-function'),
          icon: _isConvertingFunction
              ? SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: palette.textPrimary,
                  ),
                )
              : savedSpec != null
              ? const Icon(Icons.cloud_done_outlined)
              : const Icon(Icons.cloud_upload_outlined),
          color: palette.textPrimary,
          onPressed:
              _isConvertingFunction ||
                  _isReplayingRunLog ||
                  (savedSpec == null && !convertEligibility.canConvert)
              ? null
              : savedSpec != null
              ? _openSavedFunctionSheet
              : _registerCurrentRunLog,
        ),
      ),
      Tooltip(
        message: _text(context, '查看调试信息', 'View debug info'),
        child: IconButton(
          key: const ValueKey('run-log-action-debug'),
          icon: const Icon(Icons.data_object_rounded),
          color: palette.textPrimary,
          onPressed: _payload.isEmpty && _steps.isEmpty
              ? null
              : _showRunLogDebugSheet,
        ),
      ),
      Tooltip(
        message: _text(context, '复制全部文本', 'Copy all text'),
        child: IconButton(
          key: const ValueKey('run-log-action-copy'),
          icon: const Icon(Icons.copy_all_rounded),
          color: palette.textPrimary,
          onPressed: _steps.isEmpty ? null : _copyAllText,
        ),
      ),
    ];

    if (widget.embedded) {
      return ColoredBox(
        color: palette.pageBackground,
        child: Column(
          children: [
            _RunLogTimelineSheetHeader(
              title: title,
              subtitle: subtitle,
              actions: actions,
            ),
            Expanded(child: _buildBody(context)),
          ],
        ),
      );
    }

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        titleWidget: _RunLogTimelineHeaderTitle(
          title: title,
          subtitle: subtitle,
        ),
        height: 52,
        primary: true,
        actions: actions,
      ),
      body: _buildBody(context),
    );
  }

  Widget _buildBody(BuildContext context) {
    final l10n = context.l10n;
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _RunLogTimelineEmptyNotice(
        icon: Icons.route_rounded,
        title: l10n.runLogTimelineLoadFailed,
        message: _error!,
      );
    }
    if (_steps.isEmpty) {
      return ListView(
        padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
        children: [
          _RunLogOverviewCard(payload: _payload, stepCount: 0),
          const SizedBox(height: 14),
          _RunLogTimelineEmptyNotice(
            icon: Icons.check_circle_outline_rounded,
            title: l10n.runLogTimelineEmpty,
            message: _runLogEmptyMessage(context, _payload),
          ),
        ],
      );
    }
    final stepGroups = _stepGroups;
    final functionStatusStrip = _buildFunctionStatusStrip(context);
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
          sliver: SliverToBoxAdapter(
            child: _RunLogOverviewCard(
              payload: _payload,
              stepCount: _steps.length,
            ),
          ),
        ),
        if (functionStatusStrip != null)
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
            sliver: SliverToBoxAdapter(child: functionStatusStrip),
          ),
        const SliverToBoxAdapter(child: SizedBox(height: 12)),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate((context, index) {
              final group = stepGroups[index];
              return _StepCard(
                step: group.step,
                fallbackIndex: group.fallbackIndex,
                isLast: index == stepGroups.length - 1,
                onTap: () => _showStepDetail(group.step, group.fallbackIndex),
              );
            }, childCount: stepGroups.length),
          ),
        ),
      ],
    );
  }

  Future<void> _copyAllText() async {
    final text = _buildRunLogTranscript();
    if (text.trim().isEmpty) {
      showToast(
        _text(context, '暂无可复制内容', 'Nothing to copy'),
        type: ToastType.warning,
      );
      return;
    }
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    showToast(
      _text(context, '已复制全部执行文本', 'Copied full execution text'),
      type: ToastType.success,
    );
  }

  Future<void> _registerCurrentRunLog() async {
    if (_steps.isEmpty || _isConvertingFunction) {
      return;
    }
    final convertEligibility = _runLogConvertEligibility(
      context,
      _payload,
      _steps,
    );
    if (!convertEligibility.canConvert) {
      setState(() {
        _functionPanelStatus = _RunLogFunctionPanelStatus.failed;
        _functionPanelMessage = convertEligibility.message;
        _functionPanelError = convertEligibility.message;
      });
      return;
    }
    setState(() {
      _isConvertingFunction = true;
      _functionPanelStatus = _RunLogFunctionPanelStatus.saving;
      _functionPanelMessage = _text(context, '正在保存复用指令', 'Saving Function');
      _functionPanelError = null;
    });
    final registrationFailedText = _text(
      context,
      '注册失败',
      'Registration failed',
    );
    try {
      final result =
          await RunLogFunctionService.convertInternalRunLogToFunction(
            runId: widget.runId,
            register: true,
          );
      final functionId = _firstNonBlank([result['function_id']]);
      if (result['success'] != true || functionId.isEmpty) {
        final error = result['error_message']?.toString().trim();
        throw Exception(
          error?.isNotEmpty == true ? error : registrationFailedText,
        );
      }
      final functionSpec = _asStringKeyMap(result['function']);
      if (functionSpec.isEmpty) {
        throw Exception('Function conversion returned no Function');
      }
      final spec = _functionSpecFromConversionResult(result, functionSpec);
      final importResult = UtgRunLogImportResult.fromMap(result);
      if (!mounted) return;
      setState(() {
        _isConvertingFunction = false;
        _savedFunctionSpec = spec;
        _savedFunctionImportResult = importResult;
        _functionPanelStatus = _panelStatusFromFunctionSpec(spec);
        _functionPanelMessage = _firstNonBlank([
          spec.enhancementMessage,
          _conversionEnhancementMessage(context, spec.enhancementStatus),
        ]);
        _functionPanelError = null;
      });
      _scheduleRefreshIfNeeded(_payload);
    } catch (e) {
      if (!mounted) return;
      final message = _text(context, '注册失败', 'Registration failed');
      setState(() {
        _isConvertingFunction = false;
        _functionPanelStatus = _RunLogFunctionPanelStatus.failed;
        _functionPanelMessage = message;
        _functionPanelError = e.toString();
      });
    }
  }

  Future<void> _executeCurrentRunLog() async {
    if (_steps.isEmpty || _isConvertingFunction || _isReplayingRunLog) {
      return;
    }
    setState(() {
      _isReplayingRunLog = true;
    });
    showToast(
      _text(context, '正在执行复用指令', 'Running Function'),
      type: ToastType.info,
    );
    final executionFailedText = _text(context, '复用指令执行失败', 'Function failed');
    final conversionFailedText = _text(
      context,
      '复用指令生成失败',
      'Function generation failed',
    );

    try {
      final convertResult =
          await RunLogFunctionService.convertInternalRunLogToFunction(
            runId: widget.runId,
            register: true,
          );
      final functionId = _firstNonBlank([convertResult['function_id']]);
      if (convertResult['success'] != true || functionId.isEmpty) {
        final message = convertResult['error_message']?.toString().trim();
        throw Exception(
          message?.isNotEmpty == true ? message : conversionFailedText,
        );
      }
      final spec = convertResult['function'] is Map
          ? Map<String, dynamic>.from(
              (convertResult['function'] as Map).map(
                (key, value) => MapEntry(key.toString(), value),
              ),
            )
          : const <String, dynamic>{};

      final arguments = _defaultArgumentsForFunctionSpec(spec);
      final result = await RunLogFunctionService.runFunction(
        functionId: functionId,
        arguments: arguments,
      );
      if (!mounted) return;
      setState(() {
        _isReplayingRunLog = false;
      });
      showToast(
        functionRunResultToastMessage(context, result),
        type: functionRunResultToastType(result),
      );
      await showFunctionRunResultSheet(
        context,
        result: result,
        title: _text(context, '复用指令执行结果', 'Function result'),
        arguments: arguments,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isReplayingRunLog = false;
      });
      showToast('$executionFailedText: $e', type: ToastType.error);
    }
  }

  Widget? _buildFunctionStatusStrip(BuildContext context) {
    final shouldShow =
        _functionPanelStatus != _RunLogFunctionPanelStatus.idle ||
        _savedFunctionSpec != null ||
        _functionPanelError?.trim().isNotEmpty == true;
    if (!shouldShow) return null;
    final savedSpec = _savedFunctionSpec;
    final isBusy = _isConvertingFunction || _isEnhancingFunction;
    final canView = savedSpec != null && !isBusy;
    final canEnhance =
        savedSpec != null &&
        !isBusy &&
        (_functionPanelStatus == _RunLogFunctionPanelStatus.saved ||
            _functionPanelStatus == _RunLogFunctionPanelStatus.failed);
    final canRetrySave =
        savedSpec == null &&
        !isBusy &&
        _functionPanelStatus == _RunLogFunctionPanelStatus.failed;
    return _RunLogFunctionStatusStrip(
      key: const ValueKey('run-log-function-status-strip'),
      status: _functionPanelStatus,
      spec: savedSpec,
      message: _functionPanelMessage,
      error: _functionPanelError,
      canView: canView,
      canEnhance: canEnhance,
      canRetrySave: canRetrySave,
      onView: canView ? _openSavedFunctionSheet : null,
      onEnhance: canEnhance ? _enhanceSavedRunLogFunction : null,
      onRetrySave: canRetrySave ? _registerCurrentRunLog : null,
    );
  }

  Future<void> _openSavedFunctionSheet() async {
    final spec = _savedFunctionSpec;
    if (spec == null) return;
    await _showReusableFunctionSheet(
      spec,
      initialImportResult: _savedFunctionImportResult,
    );
  }

  Future<void> _enhanceSavedRunLogFunction() async {
    final currentSpec = _savedFunctionSpec;
    if (currentSpec == null ||
        _isConvertingFunction ||
        _isEnhancingFunction ||
        _isReplayingRunLog) {
      return;
    }
    setState(() {
      _isEnhancingFunction = true;
      _functionPanelStatus = _RunLogFunctionPanelStatus.enhancing;
      _functionPanelMessage = _text(context, '正在增强复用指令', 'Enhancing Function');
      _functionPanelError = null;
    });
    try {
      final result = await RunLogFunctionService.enhanceFunction(
        functionId: currentSpec.functionId,
        runId: widget.runId,
      );
      final updatedSpec = _functionSpecFromEnhancementResult(
        result,
        fallback: currentSpec,
      );
      if (!mounted) return;
      setState(() {
        _isEnhancingFunction = false;
        _savedFunctionSpec = updatedSpec;
        _functionPanelStatus = _panelStatusFromFunctionSpec(updatedSpec);
        _functionPanelMessage = _firstNonBlank([
          result['message'],
          updatedSpec.enhancementMessage,
        ]);
        _functionPanelError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isEnhancingFunction = false;
        _functionPanelStatus = _RunLogFunctionPanelStatus.failed;
        _functionPanelMessage = _text(
          context,
          '增强失败，当前复用指令保持不变',
          'Enhancement failed. The Function is unchanged.',
        );
        _functionPanelError = error.toString();
      });
    }
  }

  String _buildRunLogTranscript() {
    final l10n = context.l10n;
    final transcriptTitle = widget.title.trim().isEmpty
        ? l10n.runLogTimelineTitle
        : widget.title.trim();
    final lines = <String>[
      '# $transcriptTitle',
      '',
      'Run ID: ${widget.runId}',
      l10n.runLogTimelineStepCount(_steps.length),
    ];

    final goal = _firstNonBlank([
      _payload['goal'],
      _runLogDiagnostics(_payload)['description'],
    ]);
    if (goal.isNotEmpty) {
      lines.add('${l10n.omniflowAssetGoal}: $goal');
    }

    lines.add('');
    lines.add('## ${_text(context, '执行步骤', 'Execution steps')}');
    for (var index = 0; index < _steps.length; index++) {
      if (index > 0) {
        lines.add('');
      }
      lines.add(
        _RunLogStepSnapshot.fromStep(
          _steps[index],
          fallbackIndex: index,
        ).toTranscript(),
      );
    }

    if (_payload.isNotEmpty) {
      lines.add('');
      lines.add('## ${_text(context, '原始时间线数据', 'Raw timeline payload')}');
      lines.add(_prettyUserJson(_payload));
    }

    return lines.join('\n').trimRight();
  }

  Future<void> _showStepDetail(Map<String, dynamic> step, int index) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (sheetContext) => _StepDetailSheet(
        step: step,
        fallbackIndex: index,
        runId: widget.runId,
        title: widget.title,
        payload: _payload,
        baseUrl: widget.baseUrl,
      ),
    );
  }

  Future<void> _showRunLogDebugSheet() {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (sheetContext) => _RunLogDebugSheet(
        runId: widget.runId,
        payload: _payload,
        steps: _steps,
      ),
    );
  }

  Future<void> _showReusableFunctionSheet(
    FunctionSpec spec, {
    UtgRunLogImportResult? initialImportResult,
  }) {
    return showReusableFunctionSpecSheet(
      context,
      spec: spec,
      runId: widget.runId,
      baseUrl: widget.baseUrl,
      initialImportResult: initialImportResult,
    );
  }
}

enum _RunLogFunctionPanelStatus {
  idle,
  saving,
  enhancing,
  saved,
  enhanced,
  unchanged,
  failed,
}

_RunLogFunctionPanelStatus _panelStatusFromFunctionSpec(FunctionSpec spec) {
  switch (spec.enhancementStatus) {
    case FunctionEnhancementStatus.enhancing:
      return _RunLogFunctionPanelStatus.enhancing;
    case FunctionEnhancementStatus.enhanced:
    case FunctionEnhancementStatus.partial:
      return _RunLogFunctionPanelStatus.enhanced;
    case FunctionEnhancementStatus.unchanged:
      return _RunLogFunctionPanelStatus.unchanged;
    case FunctionEnhancementStatus.failed:
      return _RunLogFunctionPanelStatus.failed;
    case FunctionEnhancementStatus.none:
      return _RunLogFunctionPanelStatus.saved;
  }
}

FunctionEnhancementStatus _functionEnhancementStatus(dynamic value) {
  final status = (value ?? '').toString().trim().toLowerCase();
  for (final candidate in FunctionEnhancementStatus.values) {
    if (candidate.wireName == status) return candidate;
  }
  return FunctionEnhancementStatus.none;
}

FunctionSpec _functionSpecFromConversionResult(
  Map<String, dynamic> result,
  Map<String, dynamic> functionJson,
) {
  final status = _functionEnhancementStatus(result['enhancement_status']);
  final message = _firstNonBlank([result['message']]);
  return FunctionSpec(
    json: functionJson,
    agentPrompt: functionAgentPrompt(functionJson),
    aiEnhanced: status.isApplied,
    enhancementStatus: status,
    enhancementMessage: message.isEmpty ? null : message,
  );
}

String _conversionEnhancementMessage(
  BuildContext context,
  FunctionEnhancementStatus status,
) {
  switch (status) {
    case FunctionEnhancementStatus.enhanced:
    case FunctionEnhancementStatus.partial:
      return _text(context, '复用指令已增强', 'Function enhanced');
    case FunctionEnhancementStatus.unchanged:
      return _text(context, '已检查，无需修改', 'Checked, no change');
    case FunctionEnhancementStatus.failed:
      return _text(
        context,
        '增强失败，复用指令已保存，可重试',
        'Enhancement failed. The Function was saved and can be retried.',
      );
    case FunctionEnhancementStatus.enhancing:
      return _text(context, '正在增强复用指令', 'Enhancing Function');
    case FunctionEnhancementStatus.none:
      return _text(context, '已保存为复用指令', 'Function saved');
  }
}

FunctionSpec _functionSpecFromEnhancementResult(
  Map<String, dynamic> result, {
  required FunctionSpec fallback,
}) {
  if (result['success'] != true) {
    final message = _firstNonBlank([
      result['error_message'],
      result['message'],
    ]);
    throw Exception(message.isEmpty ? 'Function enhancement failed' : message);
  }
  final updatedJson = _asStringKeyMap(result['updated_function']);
  if (updatedJson.isEmpty) {
    throw Exception('Function enhancement returned no updated Function');
  }
  final status = _functionEnhancementStatus(result['enhancement_status']);
  final message = _firstNonBlank([result['message']]);
  return FunctionSpec(
    json: updatedJson,
    agentPrompt: functionAgentPrompt(updatedJson),
    aiEnhanced: fallback.aiEnhanced || status.isApplied,
    enhancementStatus: status,
    enhancementMessage: message.isEmpty ? null : message,
  );
}

class _RunLogFunctionStatusStrip extends StatelessWidget {
  const _RunLogFunctionStatusStrip({
    super.key,
    required this.status,
    required this.spec,
    required this.message,
    required this.error,
    required this.canView,
    required this.canEnhance,
    required this.canRetrySave,
    required this.onView,
    required this.onEnhance,
    required this.onRetrySave,
  });

  final _RunLogFunctionPanelStatus status;
  final FunctionSpec? spec;
  final String? message;
  final String? error;
  final bool canView;
  final bool canEnhance;
  final bool canRetrySave;
  final VoidCallback? onView;
  final VoidCallback? onEnhance;
  final VoidCallback? onRetrySave;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = _color(context);
    final busy =
        status == _RunLogFunctionPanelStatus.saving ||
        status == _RunLogFunctionPanelStatus.enhancing;
    final title = _title(context);
    final detail = _firstNonBlank([
      error,
      if ((message ?? '').trim() != title.trim()) message,
      if ((spec?.name ?? '').trim() != title.trim()) spec?.name,
      spec?.functionId,
    ]);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(10, 9, 8, 9),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.16 : 0.08),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.24)),
      ),
      child: Row(
        children: [
          if (busy)
            SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(strokeWidth: 2, color: color),
            )
          else
            Icon(_icon, size: 18, color: color),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w700,
                    color: palette.textPrimary,
                  ),
                ),
                if (detail.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(
                    detail,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      height: 1.25,
                      color: palette.textSecondary,
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (canView)
            TextButton.icon(
              key: const ValueKey('run-log-function-open-detail'),
              onPressed: onView,
              icon: const Icon(Icons.visibility_outlined, size: 16),
              label: Text(_text(context, '查看', 'View')),
            ),
          if (canEnhance)
            TextButton.icon(
              key: const ValueKey('run-log-function-enhance'),
              onPressed: onEnhance,
              icon: const Icon(Icons.auto_awesome_rounded, size: 16),
              label: Text(
                status == _RunLogFunctionPanelStatus.failed
                    ? _text(context, '重试', 'Retry')
                    : _text(context, '增强', 'Enhance'),
              ),
            ),
          if (canRetrySave)
            TextButton.icon(
              key: const ValueKey('run-log-function-retry-save'),
              onPressed: onRetrySave,
              icon: const Icon(Icons.refresh_rounded, size: 16),
              label: Text(_text(context, '重试', 'Retry')),
            ),
        ],
      ),
    );
  }

  IconData get _icon {
    switch (status) {
      case _RunLogFunctionPanelStatus.saved:
        return Icons.cloud_done_outlined;
      case _RunLogFunctionPanelStatus.enhanced:
        return Icons.auto_awesome_rounded;
      case _RunLogFunctionPanelStatus.unchanged:
        return Icons.fact_check_outlined;
      case _RunLogFunctionPanelStatus.failed:
        return Icons.error_outline_rounded;
      case _RunLogFunctionPanelStatus.idle:
      case _RunLogFunctionPanelStatus.saving:
      case _RunLogFunctionPanelStatus.enhancing:
        return Icons.info_outline_rounded;
    }
  }

  Color _color(BuildContext context) {
    switch (status) {
      case _RunLogFunctionPanelStatus.saved:
      case _RunLogFunctionPanelStatus.enhanced:
      case _RunLogFunctionPanelStatus.unchanged:
        return _successColor(context);
      case _RunLogFunctionPanelStatus.failed:
        return _errorColor(context);
      case _RunLogFunctionPanelStatus.idle:
      case _RunLogFunctionPanelStatus.saving:
      case _RunLogFunctionPanelStatus.enhancing:
        return _routeColor(context);
    }
  }

  String _title(BuildContext context) {
    switch (status) {
      case _RunLogFunctionPanelStatus.saving:
        return _text(context, '正在保存复用指令', 'Saving Function');
      case _RunLogFunctionPanelStatus.enhancing:
        return _text(context, '正在增强复用指令', 'Enhancing Function');
      case _RunLogFunctionPanelStatus.saved:
        return _text(context, '已保存为复用指令', 'Function saved');
      case _RunLogFunctionPanelStatus.enhanced:
        return _text(context, '复用指令已增强', 'Function enhanced');
      case _RunLogFunctionPanelStatus.unchanged:
        return _text(context, '已检查，无需修改', 'Checked, no change');
      case _RunLogFunctionPanelStatus.failed:
        return _text(context, '处理失败', 'Action failed');
      case _RunLogFunctionPanelStatus.idle:
        return '';
    }
  }
}

class _RunLogTimelineEmptyNotice extends StatelessWidget {
  const _RunLogTimelineEmptyNotice({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 28, color: palette.textTertiary),
            const SizedBox(height: 12),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 15,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.35,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RunLogOverviewCard extends StatelessWidget {
  const _RunLogOverviewCard({required this.payload, required this.stepCount});

  final Map<String, dynamic> payload;
  final int stepCount;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final diagnostics = _runLogDiagnostics(payload);
    final status = _runLogStatusInfo(context, payload);
    final goal = payload['goal']?.toString().trim() ?? '';
    final error = _firstNonBlank([
      payload['error'],
      status.kind == _RunLogStatusKind.failed
          ? diagnostics['done_reason']
          : null,
    ]);
    final tokenSummary = _RunLogTokenUsageAggregate.fromPayload(payload);
    final durationMs = _asInt(diagnostics['duration_ms']);
    final chips = <MapEntry<String, String>>[
      MapEntry(_text(context, '步骤', 'Steps'), stepCount.toString()),
      if (durationMs != null)
        MapEntry(_text(context, '耗时', 'Duration'), _formatMs(durationMs)),
      if (tokenSummary.totalTokens != null)
        MapEntry(
          _text(context, '模型用量', 'Model usage'),
          _formatTokens(tokenSummary.totalTokens!),
        ),
    ];

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(11, 10, 11, 10),
      decoration: BoxDecoration(
        color: Color.alphaBlend(
          status.color.withValues(alpha: isDark ? 0.15 : 0.07),
          isDark ? palette.surfaceSecondary : Colors.white,
        ),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: status.color.withValues(alpha: isDark ? 0.34 : 0.20),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  color: status.color.withValues(alpha: isDark ? 0.20 : 0.12),
                  borderRadius: BorderRadius.circular(7),
                ),
                child: Icon(status.icon, color: status.color, size: 14),
              ),
              const SizedBox(width: 9),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      status.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 0,
                      ),
                    ),
                    if (goal.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text(
                        goal,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 12,
                          height: 1.32,
                          letterSpacing: 0,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
          if (chips.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: chips
                  .map(
                    (entry) =>
                        _SummaryPill(label: entry.key, value: entry.value),
                  )
                  .toList(growable: false),
            ),
          ],
          if (error.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              error,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: status.kind == _RunLogStatusKind.failed
                    ? _errorColor(context)
                    : palette.textSecondary,
                fontSize: 12,
                height: 1.32,
                fontWeight: status.kind == _RunLogStatusKind.failed
                    ? FontWeight.w600
                    : FontWeight.w400,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _RunLogTimelineSheetFrame extends StatefulWidget {
  const _RunLogTimelineSheetFrame({
    required this.runId,
    required this.title,
    this.baseUrl,
  });

  final String runId;
  final String title;
  final String? baseUrl;

  @override
  State<_RunLogTimelineSheetFrame> createState() =>
      _RunLogTimelineSheetFrameState();
}

class _RunLogTimelineSheetFrameState extends State<_RunLogTimelineSheetFrame> {
  static const double _minHeightFactor = 0.36;
  static const double _maxHeightFactor = 0.94;

  double? _heightFactor;

  double _initialHeightFactor(double viewportHeight) {
    return viewportHeight < 720 ? 0.72 : 0.62;
  }

  void _handleDragUpdate(DragUpdateDetails details, double availableHeight) {
    if (availableHeight <= 0) {
      return;
    }
    final delta = details.primaryDelta ?? details.delta.dy;
    setState(() {
      final current =
          _heightFactor ??
          _initialHeightFactor(MediaQuery.sizeOf(context).height);
      _heightFactor = (current - delta / availableHeight).clamp(
        _minHeightFactor,
        _maxHeightFactor,
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final palette = context.omniPalette;
    final resolvedTitle = widget.title.trim().isEmpty
        ? context.l10n.runLogTimelineTitle
        : widget.title.trim();
    final availableHeight = math.max(
      320.0,
      mediaQuery.size.height -
          mediaQuery.padding.top -
          mediaQuery.viewInsets.bottom,
    );
    final heightFactor =
        _heightFactor ?? _initialHeightFactor(mediaQuery.size.height);
    const borderRadius = BorderRadius.vertical(top: Radius.circular(24));

    return SafeArea(
      top: false,
      child: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.only(bottom: mediaQuery.viewInsets.bottom),
        child: SizedBox(
          height: availableHeight * heightFactor,
          width: double.infinity,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: palette.pageBackground,
              borderRadius: borderRadius,
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.18),
                  blurRadius: 32,
                  offset: const Offset(0, -8),
                ),
              ],
            ),
            child: ClipRRect(
              borderRadius: borderRadius,
              child: Material(
                color: palette.pageBackground,
                child: Column(
                  children: [
                    GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onVerticalDragUpdate: (details) =>
                          _handleDragUpdate(details, availableHeight),
                      child: SizedBox(
                        height: 22,
                        width: double.infinity,
                        child: Center(
                          child: Container(
                            width: 42,
                            height: 4,
                            decoration: BoxDecoration(
                              color: palette.textPrimary.withValues(
                                alpha: 0.18,
                              ),
                              borderRadius: BorderRadius.circular(999),
                            ),
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: RunLogTimelinePage(
                        runId: widget.runId,
                        title: resolvedTitle,
                        baseUrl: widget.baseUrl,
                        embedded: true,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _RunLogTimelineSheetHeader extends StatelessWidget {
  const _RunLogTimelineSheetHeader({
    required this.title,
    required this.subtitle,
    required this.actions,
  });

  final String title;
  final String? subtitle;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      height: 48,
      padding: const EdgeInsets.only(left: 18, right: 4),
      decoration: BoxDecoration(
        color: palette.pageBackground,
        border: Border(
          bottom: BorderSide(color: palette.borderSubtle, width: 0.5),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: _RunLogTimelineHeaderTitle(
              title: title,
              subtitle: subtitle,
              alignment: CrossAxisAlignment.start,
            ),
          ),
          ...actions,
        ],
      ),
    );
  }
}

class _RunLogDebugSheet extends StatelessWidget {
  const _RunLogDebugSheet({
    required this.runId,
    required this.payload,
    required this.steps,
  });

  final String runId;
  final Map<String, dynamic> payload;
  final List<Map<String, dynamic>> steps;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final thinkingEntries = _collectRunLogThinkingEntries(payload, steps);
    final rawJson = <String, dynamic>{
      'run_id': runId,
      'payload': payload,
      'steps': steps,
    };
    final copyValue = _prettyJson(rawJson);
    return DraggableScrollableSheet(
      initialChildSize: 0.72,
      minChildSize: 0.42,
      maxChildSize: 0.94,
      expand: false,
      builder: (sheetContext, controller) {
        return Material(
          color: Colors.transparent,
          child: Container(
            decoration: BoxDecoration(
              color: palette.pageBackground,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(18),
              ),
              border: Border(top: BorderSide(color: palette.borderSubtle)),
            ),
            child: Column(
              children: [
                const SizedBox(height: 10),
                Container(
                  width: 40,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.borderSubtle,
                    borderRadius: BorderRadius.circular(99),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 14, 8, 10),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          _text(context, '执行记录调试信息', 'Execution debug info'),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                            color: palette.textPrimary,
                          ),
                        ),
                      ),
                      Tooltip(
                        message: _text(context, '复制全部 JSON', 'Copy all JSON'),
                        child: IconButton(
                          icon: const Icon(Icons.content_copy_rounded),
                          color: palette.textSecondary,
                          onPressed: () {
                            Clipboard.setData(ClipboardData(text: copyValue));
                            showToast(
                              _text(context, '已复制 JSON', 'JSON copied'),
                              type: ToastType.success,
                            );
                          },
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close_rounded),
                        color: palette.textSecondary,
                        onPressed: () => Navigator.of(context).maybePop(),
                      ),
                    ],
                  ),
                ),
                Divider(height: 1, color: palette.borderSubtle),
                Expanded(
                  child: SingleChildScrollView(
                    controller: controller,
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _CollapsibleSection(
                          title: _text(context, '思考过程', 'Reasoning'),
                          copyValue: thinkingEntries
                              .map((entry) => '${entry.path}\n${entry.text}')
                              .join('\n\n'),
                          initiallyExpanded: true,
                          child: thinkingEntries.isEmpty
                              ? _EmptyDebugText(
                                  text: _text(
                                    context,
                                    '当前执行记录没有记录 thinking/reasoning 字段。',
                                    'No thinking/reasoning fields were recorded in this execution record.',
                                  ),
                                )
                              : Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: thinkingEntries
                                      .map(
                                        (entry) => Padding(
                                          padding: const EdgeInsets.only(
                                            bottom: 10,
                                          ),
                                          child: _ThinkingDebugEntryView(
                                            entry: entry,
                                          ),
                                        ),
                                      )
                                      .toList(growable: false),
                                ),
                        ),
                        const SizedBox(height: 10),
                        _CollapsibleSection(
                          title: _text(context, '实际返回 JSON', 'Raw JSON'),
                          copyValue: copyValue,
                          initiallyExpanded: true,
                          child: _JsonBlock(value: rawJson),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _ThinkingDebugEntry {
  const _ThinkingDebugEntry({required this.path, required this.text});

  final String path;
  final String text;
}

class _ThinkingDebugEntryView extends StatelessWidget {
  const _ThinkingDebugEntryView({required this.entry});

  final _ThinkingDebugEntry entry;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          entry.path,
          style: TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: palette.textSecondary,
            fontFamily: 'monospace',
          ),
        ),
        const SizedBox(height: 5),
        _JsonText(text: entry.text),
      ],
    );
  }
}

class _EmptyDebugText extends StatelessWidget {
  const _EmptyDebugText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Text(
      text,
      style: TextStyle(
        fontSize: 12,
        color: palette.textSecondary,
        height: 1.35,
      ),
    );
  }
}

class _RunLogTimelineHeaderTitle extends StatelessWidget {
  const _RunLogTimelineHeaderTitle({
    required this.title,
    required this.subtitle,
    this.alignment = CrossAxisAlignment.center,
  });

  final String title;
  final String? subtitle;
  final CrossAxisAlignment alignment;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final subtitleText = subtitle?.trim() ?? '';
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: alignment,
      children: [
        Text(
          title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: palette.textPrimary,
            letterSpacing: 0,
            height: 1.08,
          ),
        ),
        if (subtitleText.isNotEmpty) ...[
          const SizedBox(height: 2),
          Text(
            subtitleText,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: palette.textTertiary,
              letterSpacing: 0,
              height: 1.05,
            ),
          ),
        ],
      ],
    );
  }
}

// ─── Step card with left-side timeline connector ──────────────────────────────

class _StepCard extends StatelessWidget {
  const _StepCard({
    required this.step,
    required this.fallbackIndex,
    required this.isLast,
    required this.onTap,
  });

  final Map<String, dynamic> step;
  final int fallbackIndex;
  final bool isLast;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final l10n = context.l10n;

    final snapshot = _RunLogStepSnapshot.fromStep(
      step,
      fallbackIndex: fallbackIndex,
    );
    final success = snapshot.success ?? true;
    final isPending = snapshot.isPending;
    final compileKind = snapshot.compileKind;
    final source = _runLogStepSource(snapshot);
    final sourceColor = _runLogStepSourceColor(context, source);
    final hasSourceBadge = _hasRunLogSourceBadge(source);
    final displayTitle = _runLogStepDisplayTitle(context, snapshot);

    final isHit = compileKind == 'hit';
    final dotColor = isPending
        ? _runningColor(context)
        : success
        ? (hasSourceBadge
              ? sourceColor
              : (isHit ? _successColor(context) : _routeColor(context)))
        : _errorColor(context);
    final lineColor = isDark ? palette.borderSubtle : Colors.grey.shade200;
    final baseCardColor = isDark ? palette.surfaceSecondary : Colors.white;
    final cardColor = hasSourceBadge
        ? Color.alphaBlend(
            sourceColor.withValues(alpha: isDark ? 0.17 : 0.075),
            baseCardColor,
          )
        : baseCardColor;
    final borderColor = hasSourceBadge
        ? sourceColor.withValues(alpha: isDark ? 0.40 : 0.24)
        : (isDark ? palette.borderSubtle : Colors.grey.shade100);
    final preview = snapshot.previewText(context);

    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Timeline spine: dot + vertical line
          SizedBox(
            width: 32,
            child: Column(
              children: [
                Container(
                  width: 10,
                  height: 10,
                  margin: const EdgeInsets.only(top: 14),
                  decoration: BoxDecoration(
                    color: dotColor,
                    shape: BoxShape.circle,
                    boxShadow: [
                      BoxShadow(
                        color: dotColor.withValues(alpha: 0.35),
                        blurRadius: 6,
                      ),
                    ],
                  ),
                ),
                if (!isLast)
                  Expanded(
                    child: Center(
                      child: Container(width: 1.5, color: lineColor),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          // Card content
          Expanded(
            child: Container(
              margin: EdgeInsets.only(bottom: isLast ? 0 : 10),
              decoration: BoxDecoration(
                color: cardColor,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: borderColor),
              ),
              child: Material(
                color: Colors.transparent,
                borderRadius: BorderRadius.circular(12),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: onTap,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        // Header row: step number + badge + duration + status
                        Row(
                          children: [
                            Text(
                              _stepLabel(context, snapshot.stepNumber),
                              style: TextStyle(
                                fontSize: 11,
                                color: palette.textSecondary,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                            const SizedBox(width: 6),
                            if (hasSourceBadge)
                              _RunLogStepSourceBadge(source: source)
                            else
                              _RouteBadge(compileKind: compileKind, l10n: l10n),
                            const Spacer(),
                            if (snapshot.durationMs != null)
                              Text(
                                _formatMs(snapshot.durationMs!),
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textSecondary,
                                ),
                              ),
                            if (snapshot.totalTokens != null) ...[
                              const SizedBox(width: 6),
                              Text(
                                _formatTokens(snapshot.totalTokens!),
                                style: TextStyle(
                                  fontSize: 11,
                                  color: palette.textSecondary,
                                ),
                              ),
                            ],
                            const SizedBox(width: 6),
                            Icon(
                              isPending
                                  ? Icons.timelapse_rounded
                                  : success
                                  ? Icons.check_circle_outline
                                  : Icons.cancel_outlined,
                              size: 14,
                              color: isPending
                                  ? _runningColor(context)
                                  : success
                                  ? _successColor(context)
                                  : _errorColor(context),
                            ),
                            const SizedBox(width: 4),
                            Icon(
                              Icons.chevron_right_rounded,
                              size: 16,
                              color: palette.textTertiary,
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        // Title
                        Text(
                          displayTitle.isEmpty
                              ? l10n.runLogTimelineUnknown
                              : displayTitle,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            color: palette.textPrimary,
                          ),
                        ),
                        if (snapshot.toolName.isNotEmpty &&
                            snapshot.toolName != displayTitle) ...[
                          const SizedBox(height: 4),
                          Text(
                            snapshot.toolName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                              fontFamily: 'monospace',
                            ),
                          ),
                        ],
                        if (snapshot.summary.isNotEmpty &&
                            snapshot.summary != displayTitle) ...[
                          const SizedBox(height: 6),
                          Text(
                            snapshot.summary,
                            key: ValueKey(
                              'run-log-step-summary-${snapshot.stepNumber}',
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 12,
                              color: palette.textSecondary,
                              height: 1.3,
                            ),
                          ),
                        ],
                        if (snapshot.thinking.isNotEmpty) ...[
                          const SizedBox(height: 8),
                          _StepTracePreview(
                            key: ValueKey(
                              'run-log-step-thinking-${snapshot.stepNumber}',
                            ),
                            label: _text(context, '思考过程', 'Reasoning'),
                            text: snapshot.thinking,
                            maxLines: 4,
                          ),
                        ],
                        if (!_isEmptyJsonValue(snapshot.args)) ...[
                          const SizedBox(height: 8),
                          _StepTracePreview(
                            key: ValueKey(
                              'run-log-step-arguments-${snapshot.stepNumber}',
                            ),
                            label: _text(context, '参数', 'Arguments'),
                            text: _compactUserJson(snapshot.args),
                            maxLines: 4,
                            monospace: true,
                          ),
                        ],
                        if (preview.isNotEmpty &&
                            _isEmptyJsonValue(snapshot.args)) ...[
                          const SizedBox(height: 4),
                          Text(
                            preview,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                              height: 1.25,
                            ),
                          ),
                        ],
                        // Package name (if present)
                        if (snapshot.packageName.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(
                            snapshot.packageName,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _StepTracePreview extends StatelessWidget {
  const _StepTracePreview({
    super.key,
    required this.label,
    required this.text,
    required this.maxLines,
    this.monospace = false,
  });

  final String label;
  final String text;
  final int maxLines;
  final bool monospace;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: 11,
            color: palette.textTertiary,
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: 3),
        Text(
          text,
          maxLines: maxLines,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            color: palette.textSecondary,
            height: 1.3,
            fontFamily: monospace ? 'monospace' : null,
          ),
        ),
      ],
    );
  }
}

class RunLogStyleFunctionStepList extends StatelessWidget {
  const RunLogStyleFunctionStepList({
    super.key,
    required this.steps,
    this.title,
    this.initiallyExpanded = false,
    this.copyValue,
    this.actionBuilder,
  });

  final List<Map<String, dynamic>> steps;
  final String? title;
  final bool initiallyExpanded;
  final String? copyValue;
  final FunctionRunStepActionBuilder? actionBuilder;

  @override
  Widget build(BuildContext context) {
    return _CollapsibleSection(
      title:
          title ??
          '${_text(context, '动作步骤', 'Action steps')} · ${steps.length}',
      copyValue: copyValue ?? _prettyUserJson(steps),
      initiallyExpanded: initiallyExpanded,
      child: Column(
        children: steps
            .asMap()
            .entries
            .map(
              (entry) => Padding(
                padding: EdgeInsets.only(
                  bottom: entry.key == steps.length - 1 ? 0 : 8,
                ),
                child: _RunLogStyleFunctionStepTile(
                  index: entry.key,
                  step: entry.value,
                  actionBuilder: actionBuilder,
                ),
              ),
            )
            .toList(growable: false),
      ),
    );
  }
}

class _RunLogStyleFunctionStepTile extends StatelessWidget {
  const _RunLogStyleFunctionStepTile({
    required this.index,
    required this.step,
    this.actionBuilder,
  });

  final int index;
  final Map<String, dynamic> step;
  final FunctionRunStepActionBuilder? actionBuilder;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final displayStep = _functionStepAsRunLogStep(step, index);
    final snapshot = _RunLogStepSnapshot.fromStep(
      displayStep,
      fallbackIndex: index,
    );
    final success = snapshot.success ?? true;
    final source = _runLogStepSource(snapshot);
    final sourceColor = _runLogStepSourceColor(context, source);
    final hasSourceBadge = _hasRunLogSourceBadge(source);
    final displayTitle = _runLogStepDisplayTitle(context, snapshot);
    final preview = snapshot.previewText(context);
    final statusColor = success ? _successColor(context) : _errorColor(context);
    final borderColor = hasSourceBadge
        ? sourceColor.withValues(alpha: context.isDarkTheme ? 0.40 : 0.24)
        : palette.borderSubtle;
    final baseColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : Colors.white;
    final cardColor = hasSourceBadge
        ? Color.alphaBlend(
            sourceColor.withValues(alpha: context.isDarkTheme ? 0.16 : 0.06),
            baseColor,
          )
        : baseColor;
    final trailing = actionBuilder?.call(context, index, step);

    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _showFunctionStepDetail(context, displayStep, index),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.fromLTRB(11, 10, 11, 10),
          decoration: BoxDecoration(
            color: cardColor,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: borderColor),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text(
                    _stepLabel(context, snapshot.stepNumber),
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textSecondary,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0,
                    ),
                  ),
                  const SizedBox(width: 6),
                  if (hasSourceBadge)
                    _RunLogStepSourceBadge(source: source)
                  else
                    _RouteBadge(
                      compileKind: snapshot.compileKind,
                      l10n: context.l10n,
                    ),
                  const Spacer(),
                  if (snapshot.durationMs != null) ...[
                    Text(
                      _formatMs(snapshot.durationMs!),
                      style: TextStyle(
                        fontSize: 11,
                        color: palette.textSecondary,
                      ),
                    ),
                    const SizedBox(width: 6),
                  ],
                  Icon(
                    success
                        ? Icons.check_circle_outline_rounded
                        : Icons.error_outline_rounded,
                    size: 15,
                    color: statusColor,
                  ),
                  const SizedBox(width: 4),
                  Icon(
                    Icons.chevron_right_rounded,
                    size: 16,
                    color: palette.textTertiary,
                  ),
                ],
              ),
              const SizedBox(height: 7),
              Text(
                displayTitle.isEmpty
                    ? _text(context, '未命名步骤', 'Untitled step')
                    : displayTitle,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: palette.textPrimary,
                  height: 1.25,
                  letterSpacing: 0,
                ),
              ),
              if (snapshot.toolName.isNotEmpty &&
                  snapshot.toolName != displayTitle) ...[
                const SizedBox(height: 4),
                Text(
                  snapshot.toolName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: palette.textSecondary,
                    fontFamily: 'monospace',
                  ),
                ),
              ],
              if (preview.isNotEmpty) ...[
                const SizedBox(height: 4),
                Text(
                  preview,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: palette.textSecondary,
                    height: 1.25,
                  ),
                ),
              ],
              if (trailing != null) ...[
                const SizedBox(height: 6),
                Align(alignment: Alignment.centerRight, child: trailing),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

Future<void> _showFunctionStepDetail(
  BuildContext context,
  Map<String, dynamic> step,
  int index,
) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (_) => _StepDetailSheet(
      step: step,
      fallbackIndex: index,
      runId: '',
      title: _asStringKeyMap(step['action'])['tool']?.toString() ?? '',
      payload: const <String, dynamic>{},
    ),
  );
}

class _StepDetailSheet extends StatefulWidget {
  const _StepDetailSheet({
    required this.step,
    required this.fallbackIndex,
    required this.runId,
    required this.title,
    required this.payload,
    this.baseUrl,
  });

  final Map<String, dynamic> step;
  final int fallbackIndex;
  final String runId;
  final String title;
  final Map<String, dynamic> payload;
  final String? baseUrl;

  @override
  State<_StepDetailSheet> createState() => _StepDetailSheetState();
}

class _StepDetailSheetState extends State<_StepDetailSheet> {
  Map<String, dynamic> _beforeState = const {};
  Map<String, dynamic> _afterState = const {};
  bool _isLoadingStates = false;

  @override
  void initState() {
    super.initState();
    unawaited(_loadStates());
  }

  Future<void> _loadStates() async {
    final beforeStateId =
        widget.step['before_state_id']?.toString().trim() ?? '';
    final afterStateId = widget.step['after_state_id']?.toString().trim() ?? '';
    if (beforeStateId.isEmpty && afterStateId.isEmpty) return;
    setState(() => _isLoadingStates = true);
    final states = await Future.wait(
      [
        if (beforeStateId.isNotEmpty)
          RunLogFunctionService.getInternalRunLogState(stateId: beforeStateId)
        else
          Future.value(const <String, dynamic>{}),
        if (afterStateId.isNotEmpty)
          RunLogFunctionService.getInternalRunLogState(stateId: afterStateId)
        else
          Future.value(const <String, dynamic>{}),
      ].map((future) async {
        try {
          final state = await future;
          return state['success'] == false ? const <String, dynamic>{} : state;
        } catch (_) {
          return const <String, dynamic>{};
        }
      }),
    );
    if (!mounted) return;
    setState(() {
      _beforeState = states[0];
      _afterState = states[1];
      _isLoadingStates = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final snapshot = _RunLogStepSnapshot.fromStep(
      widget.step,
      fallbackIndex: widget.fallbackIndex,
    );
    final success = snapshot.success ?? true;
    final isPending = snapshot.isPending;
    final statusColor = isPending
        ? _runningColor(context)
        : success
        ? _successColor(context)
        : _errorColor(context);
    final sheetHeight = MediaQuery.of(context).size.height * 0.55;
    final source = _runLogStepSource(snapshot);
    final displayTitle = _runLogStepDisplayTitle(context, snapshot);
    final beforeState = _beforeState.isNotEmpty
        ? _beforeState
        : snapshot.before;
    final afterState = _afterState.isNotEmpty ? _afterState : snapshot.after;

    return GestureDetector(
      onTap: () => Navigator.of(context, rootNavigator: true).maybePop(),
      behavior: HitTestBehavior.opaque,
      child: SafeArea(
        top: false,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: GestureDetector(
            onTap: () {},
            child: SizedBox(
              height: sheetHeight,
              width: double.infinity,
              child: Container(
                decoration: BoxDecoration(
                  color: isDark ? palette.surfacePrimary : Colors.white,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(22),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(
                        alpha: isDark ? 0.35 : 0.14,
                      ),
                      blurRadius: 26,
                      offset: const Offset(0, -10),
                    ),
                  ],
                ),
                child: Column(
                  children: [
                    const SizedBox(height: 10),
                    Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderSubtle,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 14, 10, 10),
                      child: Row(
                        children: [
                          Container(
                            width: 28,
                            height: 28,
                            decoration: BoxDecoration(
                              color: statusColor.withValues(alpha: 0.12),
                              shape: BoxShape.circle,
                            ),
                            child: Icon(
                              isPending
                                  ? Icons.timelapse_rounded
                                  : success
                                  ? Icons.check_circle_outline_rounded
                                  : Icons.error_outline_rounded,
                              size: 16,
                              color: statusColor,
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  '${_runLogStepDetailTitle(context, source)} · ${_stepLabel(context, snapshot.stepNumber)}',
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  displayTitle.isEmpty
                                      ? _text(context, '未知步骤', 'Unknown step')
                                      : displayTitle,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 16,
                                    color: palette.textPrimary,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.close_rounded),
                            color: palette.textSecondary,
                            onPressed: () => Navigator.of(context).maybePop(),
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, color: palette.borderSubtle),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // Canonical action tool.
                            if (snapshot.toolName.isNotEmpty)
                              Row(
                                children: [
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 8,
                                      vertical: 4,
                                    ),
                                    decoration: BoxDecoration(
                                      color: _routeColor(
                                        context,
                                      ).withValues(alpha: isDark ? 0.15 : 0.09),
                                      borderRadius: BorderRadius.circular(6),
                                      border: Border.all(
                                        color: _routeColor(context).withValues(
                                          alpha: isDark ? 0.30 : 0.18,
                                        ),
                                      ),
                                    ),
                                    child: Text(
                                      snapshot.toolName,
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontFamily: 'monospace',
                                        fontWeight: FontWeight.w600,
                                        color: _routeColor(context),
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            const SizedBox(height: 10),
                            // Status / route / duration pills
                            _SummaryGrid(snapshot: snapshot),
                            if (snapshot.hasTokenUsage) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(
                                  context,
                                  '在线模型用量',
                                  'Online model usage',
                                ),
                                copyValue: _prettyJson({
                                  'token_usage': snapshot.tokenUsage,
                                  if (snapshot.tokenUsageAttempts.isNotEmpty)
                                    'token_usage_attempts':
                                        snapshot.tokenUsageAttempts,
                                }),
                                initiallyExpanded: false,
                                child: _JsonBlock(
                                  value: {
                                    'token_usage': snapshot.tokenUsage,
                                    if (snapshot.tokenUsageAttempts.isNotEmpty)
                                      'token_usage_attempts':
                                          snapshot.tokenUsageAttempts,
                                  },
                                ),
                              ),
                            ],
                            if (_shouldShowVisualActionPanel(snapshot)) ...[
                              const SizedBox(height: 10),
                              _VlmStepActionPanel(
                                snapshot: snapshot,
                                source: source,
                              ),
                            ],
                            // Key param highlight row
                            if (snapshot.previewText(context).isNotEmpty) ...[
                              const SizedBox(height: 8),
                              Container(
                                width: double.infinity,
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10,
                                  vertical: 8,
                                ),
                                decoration: BoxDecoration(
                                  color: statusColor.withValues(
                                    alpha: isDark ? 0.09 : 0.06,
                                  ),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                    color: statusColor.withValues(
                                      alpha: isDark ? 0.22 : 0.15,
                                    ),
                                  ),
                                ),
                                child: Text(
                                  snapshot.previewText(context),
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    height: 1.3,
                                  ),
                                ),
                              ),
                            ],
                            if (snapshot.summary.isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _CollapsibleSection(
                                title: _text(
                                  context,
                                  '动作说明',
                                  'Action rationale',
                                ),
                                copyValue: snapshot.summary,
                                initiallyExpanded: true,
                                child: SelectableText(
                                  snapshot.summary,
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textPrimary,
                                    height: 1.45,
                                  ),
                                ),
                              ),
                            ],
                            if (snapshot.thinking.isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _CollapsibleSection(
                                title: _text(context, '思考过程', 'Reasoning'),
                                copyValue: snapshot.thinking,
                                initiallyExpanded: true,
                                child: SelectableText(
                                  snapshot.thinking,
                                  key: const ValueKey(
                                    'run-log-step-detail-thinking',
                                  ),
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textPrimary,
                                    height: 1.45,
                                  ),
                                ),
                              ),
                            ],
                            // Arguments — expanded by default
                            if (!_isEmptyJsonValue(snapshot.args)) ...[
                              const SizedBox(height: 12),
                              _CollapsibleSection(
                                title: _text(context, '参数', 'Arguments'),
                                copyValue: _prettyUserJson(snapshot.args),
                                initiallyExpanded: true,
                                child: _JsonBlock(
                                  value: _userVisibleJson(snapshot.args),
                                ),
                              ),
                            ],
                            // Result — expanded by default
                            if (!_isEmptyJsonValue(snapshot.result)) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '结果', 'Result'),
                                copyValue: _prettyUserJson(snapshot.result),
                                initiallyExpanded: true,
                                child: _JsonBlock(
                                  value: _userVisibleJson(snapshot.result),
                                ),
                              ),
                            ],
                            // Before / after — collapsed by default
                            if (beforeState.isNotEmpty ||
                                afterState.isNotEmpty) ...[
                              const SizedBox(height: 8),
                              _CollapsibleSection(
                                title: _text(context, '前后状态', 'Before / after'),
                                copyValue: _prettyUserJson({
                                  if (beforeState.isNotEmpty)
                                    'before': beforeState,
                                  if (afterState.isNotEmpty)
                                    'after': afterState,
                                }),
                                initiallyExpanded: false,
                                child: _isLoadingStates
                                    ? const Center(
                                        child: Padding(
                                          padding: EdgeInsets.all(12),
                                          child: CircularProgressIndicator(
                                            strokeWidth: 2,
                                          ),
                                        ),
                                      )
                                    : _BeforeAfterStateView(
                                        before: beforeState,
                                        after: afterState,
                                      ),
                              ),
                            ],
                            // Raw JSON — collapsed by default
                            const SizedBox(height: 8),
                            _CollapsibleSection(
                              title: _text(context, '原始 JSON', 'Raw JSON'),
                              copyValue: _prettyUserJson(widget.step),
                              initiallyExpanded: false,
                              child: _JsonBlock(
                                value: _userVisibleJson(widget.step),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ReusableFunctionSpecSheet extends StatefulWidget {
  const _ReusableFunctionSpecSheet({
    required this.spec,
    required this.runId,
    this.baseUrl,
    this.initialImportResult,
  });

  final FunctionSpec spec;
  final String runId;
  final String? baseUrl;
  final UtgRunLogImportResult? initialImportResult;

  @override
  State<_ReusableFunctionSpecSheet> createState() =>
      _ReusableFunctionSpecSheetState();
}

class _ReusableFunctionSpecSheetState
    extends State<_ReusableFunctionSpecSheet> {
  late FunctionSpec _draftSpec;
  late TextEditingController _nameController;
  late TextEditingController _descriptionController;
  late UtgRunLogImportResult? _importResult;
  UtgManualRunResult? _runResult;
  bool _isImporting = false;
  bool _isEnhancing = false;
  bool _isExecuting = false;
  bool _isScheduling = false;
  bool _hasStructuralEdits = false;
  FunctionEnhancementStatus _enhancementStatus = FunctionEnhancementStatus.none;
  String? _enhancementMessage;
  late String _lastSavedSpecFingerprint;
  String? _apiError;

  FunctionSpec get spec =>
      _draftSpec.copyWith(json: _functionJsonWithHeaderEdits(_draftSpec.json));

  FunctionEnhancementStatus get _visibleEnhancementStatus => _isEnhancing
      ? FunctionEnhancementStatus.enhancing
      : _enhancementStatus != FunctionEnhancementStatus.none
      ? _enhancementStatus
      : spec.enhancementStatus;

  String? get _visibleEnhancementMessage =>
      _enhancementMessage ?? spec.enhancementMessage;

  @override
  void initState() {
    super.initState();
    _draftSpec = widget.spec;
    _nameController = TextEditingController(text: widget.spec.name);
    _descriptionController = TextEditingController(
      text: (widget.spec.json['description'] ?? '').toString(),
    );
    _importResult = widget.initialImportResult;
    _enhancementStatus = widget.spec.enhancementStatus;
    _enhancementMessage = widget.spec.enhancementMessage;
    _lastSavedSpecFingerprint = _specFingerprint(spec.json);
    _nameController.addListener(_onHeaderFieldChanged);
    _descriptionController.addListener(_onHeaderFieldChanged);
  }

  @override
  void dispose() {
    _nameController.removeListener(_onHeaderFieldChanged);
    _descriptionController.removeListener(_onHeaderFieldChanged);
    _nameController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  void _onHeaderFieldChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final sheetHeight = MediaQuery.of(context).size.height * 0.82;
    final hasRegisteredFunction = _registeredFunctionId.isNotEmpty;
    final hasUnsavedEdits = _hasUnsavedEdits;
    final detail = _ReusableFunctionDraftSnapshot.fromSpec(spec.json);
    final enhancementStatus = _visibleEnhancementStatus;
    final isAgentVisible = _isAgentVisible;
    final hasAgentEnhanced = spec.aiEnhanced || enhancementStatus.isApplied;
    final canEnhance =
        hasRegisteredFunction &&
        !hasUnsavedEdits &&
        !_isEnhancing &&
        (enhancementStatus == FunctionEnhancementStatus.none ||
            enhancementStatus == FunctionEnhancementStatus.failed);
    return GestureDetector(
      onTap: () => Navigator.of(context, rootNavigator: true).maybePop(),
      behavior: HitTestBehavior.opaque,
      child: SafeArea(
        top: false,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: GestureDetector(
            onTap: () {},
            child: SizedBox(
              height: sheetHeight,
              width: double.infinity,
              child: Container(
                decoration: BoxDecoration(
                  color: isDark ? palette.surfacePrimary : Colors.white,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(22),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(
                        alpha: isDark ? 0.35 : 0.14,
                      ),
                      blurRadius: 26,
                      offset: const Offset(0, -10),
                    ),
                  ],
                ),
                child: Column(
                  children: [
                    const SizedBox(height: 10),
                    Container(
                      width: 36,
                      height: 4,
                      decoration: BoxDecoration(
                        color: palette.borderSubtle,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(18, 14, 10, 10),
                      child: Row(
                        children: [
                          Container(
                            width: 30,
                            height: 30,
                            decoration: BoxDecoration(
                              color: _routeColor(context).withValues(
                                alpha: context.isDarkTheme ? 0.18 : 0.12,
                              ),
                              shape: BoxShape.circle,
                            ),
                            child: Icon(
                              Icons.auto_awesome_rounded,
                              size: 16,
                              color: _routeColor(context),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  hasUnsavedEdits
                                      ? _text(
                                          context,
                                          '已修改，保存后生效',
                                          'Edited, save to apply',
                                        )
                                      : enhancementStatus ==
                                            FunctionEnhancementStatus.enhancing
                                      ? _text(
                                          context,
                                          '后台增强中',
                                          'Enhancing in background',
                                        )
                                      : enhancementStatus ==
                                            FunctionEnhancementStatus.failed
                                      ? _text(
                                          context,
                                          'Agent 增强失败',
                                          'Agent enhancement failed',
                                        )
                                      : enhancementStatus ==
                                            FunctionEnhancementStatus.unchanged
                                      ? _text(
                                          context,
                                          'Agent 已检查',
                                          'Agent checked',
                                        )
                                      : enhancementStatus ==
                                            FunctionEnhancementStatus.partial
                                      ? _text(
                                          context,
                                          'Agent 部分增强结果',
                                          'Agent partially enhanced',
                                        )
                                      : hasAgentEnhanced
                                      ? _text(
                                          context,
                                          'Agent 增强结果',
                                          'Agent enhanced Function',
                                        )
                                      : hasRegisteredFunction
                                      ? _text(
                                          context,
                                          '轨迹保存结果',
                                          'Saved trace Function',
                                        )
                                      : _text(
                                          context,
                                          '本地生成结果',
                                          'Locally prepared Function',
                                        ),
                                  style: TextStyle(
                                    fontSize: 12,
                                    color: palette.textSecondary,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 2),
                                Text(
                                  spec.name.isEmpty
                                      ? spec.functionId
                                      : spec.name,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 16,
                                    color: palette.textPrimary,
                                    fontWeight: FontWeight.w700,
                                  ),
                                ),
                              ],
                            ),
                          ),
                          IconButton(
                            icon: const Icon(Icons.close_rounded),
                            color: palette.textSecondary,
                            onPressed: () => Navigator.of(context).maybePop(),
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, color: palette.borderSubtle),
                    Expanded(
                      child: SingleChildScrollView(
                        padding: const EdgeInsets.fromLTRB(18, 14, 18, 24),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            ReusableFunctionCard(
                              title: spec.name.isEmpty
                                  ? spec.functionId
                                  : spec.name,
                              description: (spec.json['description'] ?? '')
                                  .toString(),
                              steps: detail.steps
                                  .map(
                                    (step) => ReusableFunctionStepPreview(
                                      index: step.index,
                                      title: step.displayTitle,
                                      tool: step.tool,
                                      executor: '',
                                      kind: 'function',
                                    ),
                                  )
                                  .toList(growable: false),
                              stepCount: detail.steps.length,
                              parameterCount: detail.parameters.length,
                              isRunning: _isExecuting,
                              runButtonKey: const ValueKey(
                                'run-log-reusable-run-action',
                              ),
                              onRun: _isImporting || _isExecuting
                                  ? null
                                  : _executeRegisteredFunction,
                            ),
                            if (spec.warning != null &&
                                spec.warning!.trim().isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _WarningBox(text: spec.warning!),
                            ],
                            if (_apiError != null &&
                                _apiError!.trim().isNotEmpty) ...[
                              const SizedBox(height: 12),
                              _WarningBox(text: _apiError!),
                            ],
                            if (enhancementStatus !=
                                FunctionEnhancementStatus.none) ...[
                              const SizedBox(height: 12),
                              _EnhancementStatusBox(
                                status: enhancementStatus,
                                message: _visibleEnhancementMessage,
                                isSaving: _isImporting,
                                isSaved: !hasUnsavedEdits,
                              ),
                            ],
                            const SizedBox(height: 14),
                            Row(
                              children: [
                                Expanded(
                                  child: _SpecActionButton(
                                    key: const ValueKey(
                                      'run-log-reusable-primary-action',
                                    ),
                                    icon: hasUnsavedEdits
                                        ? Icons.cloud_upload_outlined
                                        : enhancementStatus ==
                                              FunctionEnhancementStatus.failed
                                        ? Icons.refresh_rounded
                                        : hasAgentEnhanced
                                        ? Icons.check_circle_outline_rounded
                                        : Icons.auto_awesome_rounded,
                                    label: _isImporting
                                        ? _text(context, '保存中', 'Saving')
                                        : hasUnsavedEdits
                                        ? _text(context, '保存修改', 'Save changes')
                                        : _isEnhancing
                                        ? _text(context, '后台增强中', 'Enhancing')
                                        : enhancementStatus ==
                                              FunctionEnhancementStatus.failed
                                        ? _text(
                                            context,
                                            '重试增强',
                                            'Retry enhance',
                                          )
                                        : enhancementStatus ==
                                              FunctionEnhancementStatus
                                                  .unchanged
                                        ? _text(context, '已检查', 'Checked')
                                        : hasAgentEnhanced
                                        ? _text(context, '已增强', 'Enhanced')
                                        : _text(context, '增强', 'Enhance'),
                                    onTap:
                                        _isImporting ||
                                            _isEnhancing ||
                                            _isExecuting ||
                                            _isScheduling
                                        ? null
                                        : hasUnsavedEdits
                                        ? _registerFunction
                                        : canEnhance
                                        ? _enhanceWithAgent
                                        : null,
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _SpecActionButton(
                                    icon: isAgentVisible
                                        ? Icons.verified_rounded
                                        : Icons.app_registration_rounded,
                                    label: _isImporting
                                        ? _text(context, '注册中', 'Registering')
                                        : isAgentVisible
                                        ? _text(context, '已注册', 'Registered')
                                        : _text(context, '注册', 'Register'),
                                    onTap:
                                        _isImporting ||
                                            _isExecuting ||
                                            _isScheduling ||
                                            isAgentVisible
                                        ? null
                                        : _publishFunctionForAgent,
                                  ),
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: _SpecActionButton(
                                    icon: Icons.event_available_rounded,
                                    label: _isScheduling
                                        ? _text(context, '打开中', 'Opening')
                                        : _text(context, '定时任务', 'Schedule'),
                                    onTap:
                                        _isImporting ||
                                            _isExecuting ||
                                            _isScheduling
                                        ? null
                                        : _scheduleRegisteredFunction,
                                  ),
                                ),
                              ],
                            ),
                            if (_runResult != null) ...[
                              const SizedBox(height: 12),
                              _FunctionApiStatusBox(
                                functionId: _registeredFunctionId,
                                importResult: _importResult,
                                runResult: _runResult,
                                apiCallJson: _apiCallJson,
                              ),
                            ],
                            const SizedBox(height: 14),
                            _ReusableFunctionHeaderEditor(
                              nameController: _nameController,
                              descriptionController: _descriptionController,
                            ),
                            const SizedBox(height: 16),
                            Row(
                              children: [
                                Expanded(
                                  child: _ReusableFunctionSectionTitle(
                                    text: _text(
                                      context,
                                      '动作步骤',
                                      'Action steps',
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            if (detail.steps.isEmpty)
                              _ReusableFunctionEmptyText(
                                text: _text(context, '暂无步骤', 'No steps'),
                              )
                            else
                              RunLogStyleFunctionStepList(
                                title:
                                    '${_text(context, '动作步骤', 'Action steps')} · ${detail.steps.length}',
                                steps: detail.steps
                                    .map((step) => step.raw)
                                    .toList(growable: false),
                                initiallyExpanded: true,
                                copyValue: _prettyUserJson(
                                  detail.steps
                                      .map((step) => step.raw)
                                      .toList(growable: false),
                                ),
                                actionBuilder: (context, index, rawStep) {
                                  if (index < 0 ||
                                      index >= detail.steps.length) {
                                    return null;
                                  }
                                  final step = detail.steps[index];
                                  final canEdit =
                                      !_isImporting && !_isExecuting;
                                  final canDelete =
                                      canEdit && detail.steps.length > 1;
                                  return Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Tooltip(
                                        message: context
                                            .l10n
                                            .functionLibraryStepEditTitle,
                                        child: IconButton(
                                          icon: const Icon(
                                            Icons.edit_outlined,
                                            size: 18,
                                          ),
                                          visualDensity: VisualDensity.compact,
                                          color:
                                              context.omniPalette.textSecondary,
                                          onPressed: canEdit
                                              ? () => _editStep(step)
                                              : null,
                                        ),
                                      ),
                                      Tooltip(
                                        message: context
                                            .l10n
                                            .functionLibraryStepDeleteTitle,
                                        child: IconButton(
                                          icon: const Icon(
                                            Icons.delete_outline,
                                            size: 18,
                                          ),
                                          visualDensity: VisualDensity.compact,
                                          color:
                                              context.omniPalette.textSecondary,
                                          onPressed: canDelete
                                              ? () => _deleteStep(step)
                                              : null,
                                        ),
                                      ),
                                    ],
                                  );
                                },
                              ),
                            const SizedBox(height: 12),
                            _ReusableFunctionSectionTitle(
                              text: _text(context, '参数', 'Parameters'),
                            ),
                            const SizedBox(height: 8),
                            if (detail.parameters.isEmpty)
                              _ReusableFunctionEmptyText(
                                text: _text(context, '暂无参数', 'No parameters'),
                              )
                            else
                              Column(
                                children: detail.parameters
                                    .map(
                                      (parameter) => Padding(
                                        padding: const EdgeInsets.only(
                                          bottom: 8,
                                        ),
                                        child: _ReusableFunctionParameterTile(
                                          parameter: parameter,
                                        ),
                                      ),
                                    )
                                    .toList(growable: false),
                              ),
                            const SizedBox(height: 12),
                            _CollapsibleSection(
                              title: _text(context, '高级信息', 'Advanced'),
                              initiallyExpanded: false,
                              copyValue: _functionJsonForUser,
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  if (_registeredFunctionId.isNotEmpty ||
                                      _importResult != null ||
                                      _apiCallJson.trim().isNotEmpty) ...[
                                    _FunctionApiStatusBox(
                                      functionId: _registeredFunctionId,
                                      importResult: _importResult,
                                      runResult: null,
                                      apiCallJson: _apiCallJson,
                                    ),
                                    const SizedBox(height: 12),
                                  ],
                                  _ReusableFunctionSectionTitle(
                                    text: _text(
                                      context,
                                      '复用指令 JSON',
                                      'Function JSON',
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  _JsonText(text: _functionJsonForUser),
                                  const SizedBox(height: 12),
                                  _ReusableFunctionSectionTitle(
                                    text: _text(
                                      context,
                                      'Agent 复用提示',
                                      'Agent reuse prompt',
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  _JsonText(text: _agentPromptForUser),
                                  if (_apiCallJson.trim().isNotEmpty) ...[
                                    const SizedBox(height: 12),
                                    _ReusableFunctionSectionTitle(
                                      text: _text(context, '执行调用', 'Run call'),
                                    ),
                                    const SizedBox(height: 8),
                                    _JsonText(text: _apiCallJson),
                                  ],
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _editStep(_ReusableFunctionStepSummary step) async {
    if (_isImporting || _isExecuting) {
      return;
    }
    final editedStep = await _showReusableFunctionStepEditorDialog(
      context,
      step.raw,
    );
    if (editedStep == null || !mounted) return;
    final updatedJson = _replaceReusableFunctionStep(
      spec.json,
      step,
      editedStep,
    );
    if (updatedJson == null) {
      showToast(
        context.l10n.functionLibraryStepEditMissing,
        type: ToastType.error,
      );
      return;
    }
    await _updateDraftJson(updatedJson, structuralEdit: true);
    if (!mounted) return;
    showToast(
      _text(context, '步骤已更新，保存后生效', 'Step updated. Save to apply.'),
      type: ToastType.success,
    );
  }

  Future<void> _deleteStep(_ReusableFunctionStepSummary step) async {
    if (_isImporting || _isExecuting) {
      return;
    }
    final detail = _ReusableFunctionDraftSnapshot.fromSpec(spec.json);
    if (detail.steps.length <= 1) {
      showToast(context.l10n.functionLibraryStepKeepOne, type: ToastType.error);
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(dialogContext.l10n.functionLibraryStepDeleteTitle),
        content: Text(
          dialogContext.l10n.functionLibraryStepDeleteConfirm(
            step.displayTitle,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(dialogContext.l10n.omniflowCancel),
          ),
          FilledButton.icon(
            icon: const Icon(Icons.delete_outline_rounded, size: 18),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            label: Text(dialogContext.l10n.functionLibraryDelete),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final updatedJson = _removeReusableFunctionStep(spec.json, step);
    if (updatedJson == null) {
      showToast(
        context.l10n.functionLibraryStepDeleteMissing,
        type: ToastType.error,
      );
      return;
    }
    await _updateDraftJson(updatedJson, structuralEdit: true);
    if (!mounted) return;
    showToast(
      _text(context, '步骤已删除，保存后生效', 'Step deleted. Save to apply.'),
      type: ToastType.success,
    );
  }

  Future<void> _updateDraftJson(
    Map<String, dynamic> json, {
    required bool structuralEdit,
  }) async {
    if (!mounted) return;
    setState(() {
      _draftSpec = _draftSpec.copyWith(
        json: json,
        agentPrompt: functionAgentPrompt(json),
      );
      _runResult = null;
      if (structuralEdit) {
        _hasStructuralEdits = true;
      }
    });
  }

  Future<void> _enhanceWithAgent() async {
    final functionId = _registeredFunctionId;
    if (functionId.isEmpty ||
        _isImporting ||
        _isEnhancing ||
        _isExecuting ||
        _isScheduling ||
        _hasUnsavedEdits) {
      return;
    }
    final currentSpec = spec;
    setState(() {
      _isEnhancing = true;
      _enhancementStatus = FunctionEnhancementStatus.enhancing;
      _enhancementMessage = _text(
        context,
        '正在后台增强复用指令',
        'Enhancing Function in the background',
      );
      _apiError = null;
    });
    try {
      final result = await RunLogFunctionService.enhanceFunction(
        functionId: functionId,
        runId: widget.runId,
      );
      final updatedSpec = _functionSpecFromEnhancementResult(
        result,
        fallback: currentSpec,
      );
      if (!mounted) return;
      _nameController.text = updatedSpec.name;
      _descriptionController.text = (updatedSpec.json['description'] ?? '')
          .toString();
      setState(() {
        _isEnhancing = false;
        _draftSpec = updatedSpec;
        _enhancementStatus = updatedSpec.enhancementStatus;
        _enhancementMessage = _firstNonBlank([
          result['message'],
          updatedSpec.enhancementMessage,
        ]);
        _lastSavedSpecFingerprint = _specFingerprint(updatedSpec.json);
        _hasStructuralEdits = false;
        _runResult = null;
        _apiError = null;
      });
      showToast(
        updatedSpec.enhancementStatus == FunctionEnhancementStatus.unchanged
            ? _text(context, '已检查，无需修改', 'Checked, no change')
            : _text(context, '复用指令已增强', 'Function enhanced'),
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isEnhancing = false;
        _enhancementStatus = FunctionEnhancementStatus.failed;
        _enhancementMessage = _text(
          context,
          '增强失败，当前复用指令保持不变',
          'Enhancement failed. The Function is unchanged.',
        );
        _apiError = error.toString();
      });
    }
  }

  Future<bool> _registerFunction({
    String? successMessage,
    bool agentVisible = false,
  }) async {
    if (_isImporting) {
      return false;
    }
    setState(() {
      _isImporting = true;
      _apiError = null;
    });
    try {
      final nativeRunLogId = _nativeRunLogRegistrationRunId;
      if (nativeRunLogId.isNotEmpty && !_hasStructuralEdits) {
        final result =
            await RunLogFunctionService.convertInternalRunLogToFunction(
              runId: nativeRunLogId,
              register: true,
              functionId: spec.functionId,
              name: spec.name,
              description: (spec.json['description'] ?? '').toString(),
              agentVisible: agentVisible,
            );
        if (!mounted) return false;
        final registeredId = _firstNonBlank([result['function_id']]);
        if (result['success'] != true || registeredId.isEmpty) {
          final message = _firstNonBlank([
            result['error_message'],
            _text(context, '注册失败', 'Registration failed'),
          ]);
          setState(() {
            _isImporting = false;
            _apiError = message;
          });
          showToast(message, type: ToastType.error);
          return false;
        }
        final savedSpec = _specFromSavePayload(spec, result);
        setState(() {
          _importResult = UtgRunLogImportResult.fromMap(result);
          _draftSpec = savedSpec;
          _lastSavedSpecFingerprint = _specFingerprint(savedSpec.json);
          _hasStructuralEdits = false;
          _isImporting = false;
        });
        showToast(
          successMessage ?? _text(context, '已保存为复用指令', 'Function saved'),
          type: ToastType.success,
        );
        return true;
      }

      final result = await RunLogFunctionService.registerFunction(
        function: _functionJsonForAgentVisibility(
          spec.json,
          agentVisible: agentVisible,
        ),
      );
      if (!mounted) return false;
      final registeredId = result.functionId.trim();
      if (result.success && registeredId.isEmpty) {
        final message = _text(
          context,
          '注册返回缺少复用指令 ID',
          'Registration returned no Function ID',
        );
        setState(() {
          _isImporting = false;
          _apiError = message;
        });
        showToast(message, type: ToastType.error);
        return false;
      }
      final savedSpec = _specFromSavePayload(spec, result.rawJson);
      setState(() {
        _importResult = UtgRunLogImportResult.fromMap({
          'success': result.success,
          'run_id': widget.runId,
          'function_id': registeredId,
          'agent_visible': result.rawJson['agent_visible'] == true,
        });
        if (result.success) {
          _draftSpec = savedSpec;
          _lastSavedSpecFingerprint = _specFingerprint(savedSpec.json);
          _hasStructuralEdits = false;
        }
        _isImporting = false;
      });
      if (result.success) {
        showToast(
          successMessage ?? _text(context, '已保存为复用指令', 'Function saved'),
          type: ToastType.success,
        );
        return true;
      } else {
        final message = result.errorMessage?.trim();
        setState(() {
          _apiError = message?.isNotEmpty == true
              ? message
              : _text(context, '注册失败', 'Registration failed');
        });
        showToast(_apiError!, type: ToastType.error);
        return false;
      }
    } catch (e) {
      if (!mounted) return false;
      setState(() {
        _isImporting = false;
        _apiError = e.toString();
      });
      showToast(_apiError!, type: ToastType.error);
      return false;
    }
  }

  Future<void> _executeRegisteredFunction() async {
    if (_isExecuting || _isImporting) {
      return;
    }
    var functionId = _registeredFunctionId;
    if (functionId.isEmpty) {
      await _registerFunction();
      if (!mounted) return;
      functionId = _registeredFunctionId;
    }
    if (functionId.isEmpty) {
      showToast(
        _text(context, '没有可执行的复用指令', 'Missing runnable Function'),
        type: ToastType.warning,
      );
      return;
    }

    setState(() {
      _isExecuting = true;
      _apiError = null;
    });
    try {
      final result = await RunLogFunctionService.runFunction(
        functionId: functionId,
        arguments: _defaultArguments,
      );
      if (!mounted) return;
      setState(() {
        _isExecuting = false;
        _apiError = result.success
            ? null
            : functionRunResultToastMessage(context, result);
      });
      showToast(
        functionRunResultToastMessage(context, result),
        type: functionRunResultToastType(result),
      );
      await showFunctionRunResultSheet(
        context,
        result: result,
        title: _text(context, '复用指令执行结果', 'Function result'),
        arguments: _defaultArguments,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isExecuting = false;
        _apiError = e.toString();
      });
      showToast(_apiError!, type: ToastType.error);
    }
  }

  Future<void> _publishFunctionForAgent() async {
    await _registerFunction(
      successMessage: _text(context, '已注册为复用指令', 'Function registered'),
      agentVisible: true,
    );
  }

  Future<void> _scheduleRegisteredFunction() async {
    if (_isScheduling || _isImporting || _isExecuting) {
      return;
    }
    setState(() {
      _isScheduling = true;
      _apiError = null;
    });
    try {
      var functionId = _registeredFunctionId;
      if (functionId.isEmpty) {
        await _registerFunction();
        if (!mounted) return;
        functionId = _registeredFunctionId;
      }
      if (functionId.isEmpty) {
        setState(() {
          _isScheduling = false;
        });
        showToast(
          _text(context, '复用指令保存失败，无法转定时任务', 'Function registration failed'),
          type: ToastType.error,
        );
        return;
      }

      final taskId = 'reusable-function-$functionId';
      final existingTask =
          await ScheduledTaskStorageService.getScheduledTaskById(taskId);
      if (!mounted) return;
      final result = await ScheduleTaskSheet.show(
        context: context,
        existingTask:
            existingTask ??
            ScheduledTask(
              id: taskId,
              title: spec.name.isEmpty ? functionId : spec.name,
              targetKind: 'subagent',
              subagentPrompt: _functionSchedulePrompt(functionId),
              type: ScheduledTaskType.fixedTime,
              createdAt: DateTime.now().millisecondsSinceEpoch,
            ),
      );
      if (!mounted) return;
      setState(() {
        _isScheduling = false;
      });
      if (result == null) {
        return;
      }
      final saved = await ScheduledTaskStorageService.addScheduledTask(result);
      if (!mounted) return;
      if (!saved) {
        showToast(
          _text(context, '定时任务保存失败', 'Failed to save scheduled task'),
          type: ToastType.error,
        );
        return;
      }
      ScheduledTaskSchedulerService.scheduleTask(result);
      showToast(
        _text(context, '已转为定时任务', 'Scheduled task created'),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isScheduling = false;
        _apiError = e.toString();
      });
      showToast(_apiError!, type: ToastType.error);
    }
  }

  bool get _hasUnsavedEdits =>
      _specFingerprint(spec.json) != _lastSavedSpecFingerprint;

  FunctionSpec _specFromSavePayload(
    FunctionSpec current,
    Map<String, dynamic> payload,
  ) {
    final savedJson = _functionSpecJsonFromSavePayload(payload);
    if (savedJson.isEmpty) return current;
    if (!payload.containsKey('enhancement_status')) {
      return current.copyWith(
        json: savedJson,
        agentPrompt: functionAgentPrompt(savedJson),
      );
    }
    final enhancementStatus = _functionEnhancementStatus(
      payload['enhancement_status'],
    );
    final enhancementMessage = _firstNonBlank([payload['message']]);
    return current.copyWith(
      json: savedJson,
      agentPrompt: functionAgentPrompt(savedJson),
      aiEnhanced: enhancementStatus.isApplied,
      enhancementStatus: enhancementStatus,
      enhancementMessage: enhancementMessage.isEmpty
          ? null
          : enhancementMessage,
    );
  }

  Map<String, dynamic> _functionJsonWithHeaderEdits(
    Map<String, dynamic> rawJson,
  ) {
    final cloned = _deepCopyStringMap(rawJson);
    final name = _nameController.text.trim();
    final description = _descriptionController.text.trim();
    if (name.isNotEmpty) {
      cloned['name'] = name;
    }
    cloned['description'] = description;
    return cloned;
  }

  Map<String, dynamic> _functionJsonForAgentVisibility(
    Map<String, dynamic> rawJson, {
    required bool agentVisible,
  }) {
    final cloned = _deepCopyStringMap(rawJson);
    cloned['agent_visible'] = agentVisible;
    return cloned;
  }

  bool get _isAgentVisible {
    bool? readFlag(dynamic value) {
      if (value is bool) return value;
      final text = value?.toString().trim().toLowerCase();
      if (text == 'true' || text == '1' || text == 'yes') return true;
      if (text == 'false' || text == '0' || text == 'no') return false;
      return null;
    }

    final raw = _importResult?.rawJson ?? const <String, dynamic>{};
    return readFlag(raw['agent_visible']) ??
        readFlag(spec.json['agent_visible']) ??
        true;
  }

  String get _registeredFunctionId {
    if (_hasUnsavedEdits) {
      return '';
    }
    final importResult = _importResult;
    return _firstNonBlank([
      if (importResult?.success == true) importResult?.functionId,
      if (_runResult?.success == true) _runResult?.functionId,
    ]);
  }

  String get _nativeRunLogRegistrationRunId {
    return widget.runId.trim();
  }

  Map<String, dynamic> get _defaultArguments {
    return _defaultArgumentsForFunctionSpec(spec.json);
  }

  String get _functionJsonForUser => _prettyUserJson(spec.json);

  String get _agentPromptForUser => _userVisibleString(spec.agentPrompt);

  String _functionSchedulePrompt(String functionId) =>
      'Run reusable function $functionId with arguments '
      '${jsonEncode(_defaultArguments)}';

  String get _apiCallJson {
    final functionId = _registeredFunctionId;
    if (functionId.isEmpty) {
      return '';
    }
    return _prettyUserJson({
      'action': 'run_reusable_function',
      'function_id': functionId,
      'arguments': _defaultArguments,
    });
  }
}

class _ReusableFunctionHeaderEditor extends StatelessWidget {
  const _ReusableFunctionHeaderEditor({
    required this.nameController,
    required this.descriptionController,
  });

  final TextEditingController nameController;
  final TextEditingController descriptionController;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 11, 12, 12),
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfaceSecondary : Colors.white,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        children: [
          TextField(
            controller: nameController,
            decoration: InputDecoration(
              labelText: _text(context, '名称', 'Name'),
              border: const OutlineInputBorder(),
              isDense: true,
            ),
            textInputAction: TextInputAction.next,
          ),
          const SizedBox(height: 10),
          TextField(
            controller: descriptionController,
            decoration: InputDecoration(
              labelText: _text(context, '简介', 'Description'),
              border: const OutlineInputBorder(),
              isDense: true,
            ),
            minLines: 2,
            maxLines: 4,
          ),
        ],
      ),
    );
  }
}

class _ReusableFunctionSectionTitle extends StatelessWidget {
  const _ReusableFunctionSectionTitle({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Text(
      text,
      style: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.w700,
        color: palette.textTertiary,
        letterSpacing: 0.2,
      ),
    );
  }
}

class _ReusableFunctionEmptyText extends StatelessWidget {
  const _ReusableFunctionEmptyText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Text(
        text,
        style: TextStyle(fontSize: 13, color: context.omniPalette.textTertiary),
      ),
    );
  }
}

class _ReusableFunctionParameterTile extends StatelessWidget {
  const _ReusableFunctionParameterTile({required this.parameter});

  final _ReusableFunctionParameterSummary parameter;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final meta = [
      if (parameter.type.isNotEmpty) parameter.type,
      if (parameter.required) _text(context, '必填', 'required'),
      if (parameter.defaultValue.isNotEmpty)
        '${_text(context, '默认', 'default')}: ${parameter.defaultValue}',
    ].join(' · ');
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(10, 9, 10, 9),
      decoration: BoxDecoration(
        color: context.isDarkTheme ? palette.surfaceSecondary : Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            parameter.name,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 3),
            Text(
              meta,
              style: TextStyle(fontSize: 11, color: palette.textSecondary),
            ),
          ],
          if (parameter.description.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              parameter.description,
              style: TextStyle(
                fontSize: 11,
                color: palette.textTertiary,
                height: 1.35,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ReusableFunctionDraftSnapshot {
  const _ReusableFunctionDraftSnapshot({
    required this.parameters,
    required this.steps,
  });

  final List<_ReusableFunctionParameterSummary> parameters;
  final List<_ReusableFunctionStepSummary> steps;

  factory _ReusableFunctionDraftSnapshot.fromSpec(
    Map<String, dynamic> functionSpec,
  ) {
    final rawSteps = functionSpec['steps'];
    final steps = rawSteps is List
        ? rawSteps
              .asMap()
              .entries
              .map(
                (entry) => _ReusableFunctionStepSummary.fromMap(
                  _asStringKeyMap(entry.value),
                  fallbackIndex: entry.key,
                ),
              )
              .where((step) => step.raw.isNotEmpty)
              .toList(growable: false)
        : const <_ReusableFunctionStepSummary>[];
    return _ReusableFunctionDraftSnapshot(
      parameters: _reusableFunctionParameters(functionSpec['input_schema']),
      steps: steps,
    );
  }
}

class _ReusableFunctionStepSummary {
  const _ReusableFunctionStepSummary({
    required this.index,
    required this.id,
    required this.raw,
  });

  factory _ReusableFunctionStepSummary.fromMap(
    Map<String, dynamic> raw, {
    required int fallbackIndex,
  }) {
    final index = _asInt(raw['step_index']) ?? fallbackIndex;
    final normalized = Map<String, dynamic>.from(raw);
    normalized['step_index'] = index;
    return _ReusableFunctionStepSummary(
      index: index,
      id: (raw['source_state_id'] ?? '').toString(),
      raw: normalized,
    );
  }

  final int index;
  final String id;
  final Map<String, dynamic> raw;

  String get displayTitle {
    return tool;
  }

  String get tool => (_asStringKeyMap(raw['action'])['tool'] ?? '').toString();
}

class _ReusableFunctionParameterSummary {
  const _ReusableFunctionParameterSummary({
    required this.name,
    required this.type,
    required this.required,
    required this.description,
    required this.defaultValue,
  });

  factory _ReusableFunctionParameterSummary.fromMap(Map<String, dynamic> raw) {
    return _ReusableFunctionParameterSummary(
      name: (raw['name'] ?? '').toString(),
      type: (raw['type'] ?? '').toString(),
      required: _asBool(raw['required']) == true,
      description: (raw['description'] ?? '').toString(),
      defaultValue: (raw['default'] ?? '').toString(),
    );
  }

  final String name;
  final String type;
  final bool required;
  final String description;
  final String defaultValue;
}

const _customReusableStepToolValue = '__custom_reusable_step_tool__';

enum _ReusableFunctionStepArgType { string, integer, number, boolean }

_ReusableFunctionStepArgType _reusableStepArgTypeFromSchema(
  OobActionArgType type,
) {
  switch (type) {
    case OobActionArgType.integer:
      return _ReusableFunctionStepArgType.integer;
    case OobActionArgType.number:
      return _ReusableFunctionStepArgType.number;
    case OobActionArgType.boolean:
      return _ReusableFunctionStepArgType.boolean;
    case OobActionArgType.string:
    case OobActionArgType.object:
    case OobActionArgType.stringArray:
      return _ReusableFunctionStepArgType.string;
  }
}

String _reusableStepArgHintFromSchema(OobActionArgSpec arg) {
  if (arg.enumValues.isNotEmpty) return arg.enumValues.join('/');
  if (arg.minimum != null && arg.maximum != null) {
    return '${arg.minimum}-${arg.maximum}';
  }
  if (arg.minimum != null) return '>= ${arg.minimum}';
  return '';
}

class _ReusableFunctionStepArgField {
  const _ReusableFunctionStepArgField(
    this.key, {
    this.type = _ReusableFunctionStepArgType.string,
    this.hint = '',
  });

  final String key;
  final _ReusableFunctionStepArgType type;
  final String hint;
}

class _ReusableFunctionStepOperationDefinition {
  const _ReusableFunctionStepOperationDefinition({
    required this.value,
    required this.zhLabel,
    required this.enLabel,
    this.argsTemplate = const {},
    this.fields = const [],
  });

  final String value;
  final String zhLabel;
  final String enLabel;
  final Map<String, dynamic> argsTemplate;
  final List<_ReusableFunctionStepArgField> fields;

  String label(BuildContext context) => _text(context, zhLabel, enLabel);
}

final _reusableFunctionStepOperations = OobCanonicalActionSchema
    .editorVisibleTools
    .map(
      (tool) => _ReusableFunctionStepOperationDefinition(
        value: tool.name,
        zhLabel: tool.uiLabel.zhCn,
        enLabel: tool.uiLabel.enUs,
        argsTemplate: Map<String, dynamic>.from(tool.argsTemplate),
        fields: tool.args
            .map(
              (arg) => _ReusableFunctionStepArgField(
                arg.name,
                type: _reusableStepArgTypeFromSchema(arg.type),
                hint: _reusableStepArgHintFromSchema(arg),
              ),
            )
            .toList(growable: false),
      ),
    )
    .toList(growable: false);

String _normalizeReplayToolName(String tool) =>
    OobCanonicalActionSchema.normalizeToolName(tool);

String? _replayableActionForToolName(String tool) {
  final normalized = _normalizeReplayToolName(tool);
  return OobCanonicalActionSchema.replayableToolNames.contains(normalized)
      ? normalized
      : null;
}

Future<Map<String, dynamic>?> _showReusableFunctionStepEditorDialog(
  BuildContext context,
  Map<String, dynamic> rawStep, {
  bool isNew = false,
}) {
  return showDialog<Map<String, dynamic>>(
    context: context,
    builder: (dialogContext) =>
        _ReusableFunctionStepEditorDialog(rawStep: rawStep, isNew: isNew),
  );
}

class _ReusableFunctionStepEditorDialog extends StatefulWidget {
  const _ReusableFunctionStepEditorDialog({
    required this.rawStep,
    required this.isNew,
  });

  final Map<String, dynamic> rawStep;
  final bool isNew;

  @override
  State<_ReusableFunctionStepEditorDialog> createState() =>
      _ReusableFunctionStepEditorDialogState();
}

class _ReusableFunctionStepEditorDialogState
    extends State<_ReusableFunctionStepEditorDialog> {
  late final TextEditingController _customToolController;
  late final TextEditingController _argsController;
  final Map<String, TextEditingController> _argControllers = {};
  late String _selectedTool;
  String? _errorText;

  @override
  void initState() {
    super.initState();
    final action = _asStringKeyMap(widget.rawStep['action']);
    final rawTool = (action['tool'] ?? '').toString().trim();
    final operation = _reusableOperationDefinitionForTool(rawTool);
    _selectedTool = operation?.value ?? _customReusableStepToolValue;
    _customToolController = TextEditingController(
      text: operation == null ? rawTool : '',
    );
    _argsController = TextEditingController(
      text: const JsonEncoder.withIndent('  ').convert(
        action['args'] is Map
            ? _deepCopyStringMap(_asStringKeyMap(action['args']))
            : _selectedOperation?.argsTemplate ?? const {},
      ),
    );
    _rebuildArgControllers(_decodedArgsOrEmpty());
  }

  @override
  void dispose() {
    _customToolController.dispose();
    _argsController.dispose();
    for (final controller in _argControllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  _ReusableFunctionStepOperationDefinition? get _selectedOperation {
    for (final operation in _reusableFunctionStepOperations) {
      if (operation.value == _selectedTool) return operation;
    }
    return null;
  }

  void _onOperationChanged(String? value) {
    if (value == null || value == _selectedTool) return;
    final previousArgs = _decodedArgsOrEmpty();
    setState(() {
      _selectedTool = value;
      _errorText = null;
      final definition = _selectedOperation;
      if (definition != null) {
        final nextArgs = _argsForReusableToolSwitch(
          definition.value,
          previousArgs,
          definition.argsTemplate,
        );
        _setArgsJson(nextArgs);
        _rebuildArgControllers(nextArgs);
      } else {
        _rebuildArgControllers(previousArgs);
      }
    });
  }

  void _rebuildArgControllers(Map<String, dynamic> args) {
    for (final controller in _argControllers.values) {
      controller.dispose();
    }
    _argControllers.clear();
    final fields =
        _selectedOperation?.fields ?? const <_ReusableFunctionStepArgField>[];
    for (final field in fields) {
      _argControllers[field.key] = TextEditingController(
        text: _reusableArgFieldText(args[field.key]),
      );
    }
  }

  void _syncArgsJsonFromFields() {
    final definition = _selectedOperation;
    if (definition == null) return;
    final args = _decodedArgsOrEmpty();
    for (final field in definition.fields) {
      final raw = _argControllers[field.key]?.text.trim() ?? '';
      if (raw.isEmpty) {
        args.remove(field.key);
      } else {
        args[field.key] = _parseReusableArgFieldValue(raw, field.type);
      }
    }
    _setArgsJson(args);
  }

  void _setArgsJson(Map<String, dynamic> args) {
    final pretty = const JsonEncoder.withIndent('  ').convert(_jsonSafe(args));
    _argsController.value = TextEditingValue(
      text: pretty,
      selection: TextSelection.collapsed(offset: pretty.length),
    );
  }

  Map<String, dynamic> _decodedArgsOrEmpty() {
    try {
      final decoded = jsonDecode(
        _argsController.text.trim().isEmpty ? '{}' : _argsController.text,
      );
      if (decoded is Map) return _asStringKeyMap(decoded);
    } catch (_) {
      return const {};
    }
    return const {};
  }

  void _save() {
    if (_selectedOperation != null) {
      _syncArgsJsonFromFields();
    }
    final enteredTool = _selectedTool == _customReusableStepToolValue
        ? _customToolController.text.trim()
        : _selectedTool;
    if (enteredTool.isEmpty) {
      setState(() => _errorText = context.l10n.functionLibraryStepToolRequired);
      return;
    }
    final dynamic decodedArgs;
    try {
      decodedArgs = jsonDecode(
        _argsController.text.trim().isEmpty ? '{}' : _argsController.text,
      );
    } catch (_) {
      setState(() => _errorText = context.l10n.functionLibraryStepArgsInvalid);
      return;
    }
    if (decodedArgs is! Map) {
      setState(
        () => _errorText = context.l10n.functionLibraryStepArgsObjectRequired,
      );
      return;
    }
    Navigator.of(context).pop(
      _buildReusableFunctionStepFromEdit(
        rawStep: widget.rawStep,
        tool: enteredTool,
        args: _asStringKeyMap(decodedArgs),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selectedDefinition = _selectedOperation;
    final fields =
        selectedDefinition?.fields ?? const <_ReusableFunctionStepArgField>[];
    return AlertDialog(
      title: Text(
        widget.isNew
            ? _text(context, '添加步骤', 'Add step')
            : context.l10n.functionLibraryStepEditTitle,
      ),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _selectedTool,
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: context.l10n.functionLibraryStepToolLabel,
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
                items: [
                  for (final operation in _reusableFunctionStepOperations)
                    DropdownMenuItem<String>(
                      value: operation.value,
                      child: Text(
                        '${operation.value} · ${operation.label(context)}',
                      ),
                    ),
                  DropdownMenuItem<String>(
                    value: _customReusableStepToolValue,
                    child: Text(_text(context, '自定义工具', 'Custom tool')),
                  ),
                ],
                onChanged: _onOperationChanged,
              ),
              if (_selectedTool == _customReusableStepToolValue) ...[
                const SizedBox(height: 10),
                TextField(
                  controller: _customToolController,
                  decoration: InputDecoration(
                    labelText: _text(context, '工具名', 'Tool name'),
                    border: const OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ],
              const SizedBox(height: 12),
              Text(
                _text(context, '参数', 'Parameters'),
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: palette.textTertiary,
                  letterSpacing: 0,
                ),
              ),
              const SizedBox(height: 8),
              if (fields.isEmpty)
                Text(
                  _text(context, '此操作无需参数', 'This action has no parameters'),
                  style: TextStyle(fontSize: 12, color: palette.textTertiary),
                )
              else
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    for (final field in fields)
                      SizedBox(
                        width: _reusableArgFieldWidth(field),
                        child: TextField(
                          controller: _argControllers[field.key],
                          keyboardType: _keyboardTypeForReusableArgField(
                            field.type,
                          ),
                          decoration: InputDecoration(
                            labelText: field.key,
                            helperText: field.hint.isEmpty ? null : field.hint,
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                          onChanged: (_) => _syncArgsJsonFromFields(),
                        ),
                      ),
                  ],
                ),
              const SizedBox(height: 12),
              TextField(
                controller: _argsController,
                keyboardType: TextInputType.multiline,
                minLines: 5,
                maxLines: 10,
                style: const TextStyle(fontFamily: 'monospace'),
                decoration: InputDecoration(
                  labelText: context.l10n.functionLibraryStepArgsLabel,
                  border: const OutlineInputBorder(),
                  isDense: true,
                ),
              ),
              if (_errorText != null) ...[
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    _errorText!,
                    style: TextStyle(fontSize: 12, color: _errorColor(context)),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(context.l10n.omniflowCancel),
        ),
        FilledButton.icon(
          icon: Icon(
            widget.isNew ? Icons.add_rounded : Icons.save_outlined,
            size: 18,
          ),
          label: Text(
            widget.isNew
                ? _text(context, '添加', 'Add')
                : context.l10n.omniflowSaveConfig,
          ),
          onPressed: _save,
        ),
      ],
    );
  }
}

_ReusableFunctionStepOperationDefinition? _reusableOperationDefinitionForTool(
  String tool,
) {
  final action =
      _replayableActionForToolName(tool) ?? _normalizeReplayToolName(tool);
  for (final operation in _reusableFunctionStepOperations) {
    if (operation.value == action) return operation;
  }
  return null;
}

Map<String, dynamic> _argsForReusableToolSwitch(
  String nextTool,
  Map<String, dynamic> previousArgs,
  Map<String, dynamic> argsTemplate,
) {
  final args = <String, dynamic>{...argsTemplate};
  final nextKeys = OobCanonicalActionSchema.argNames(nextTool);

  for (final key in nextKeys) {
    if (previousArgs.containsKey(key)) {
      args[key] = previousArgs[key];
    }
  }

  final target = _firstNonBlank([
    previousArgs['target_description'],
    previousArgs['description'],
    previousArgs['target'],
  ]);
  if (target.isNotEmpty && nextKeys.contains('target_description')) {
    args['target_description'] = target;
  }

  if (nextKeys.contains('x') && !args.containsKey('x')) {
    final x = _defaultReusablePointX(previousArgs);
    if (x != null) args['x'] = x;
  }
  if (nextKeys.contains('y') && !args.containsKey('y')) {
    final y = _defaultReusablePointY(previousArgs);
    if (y != null) args['y'] = y;
  }

  if (nextTool == 'swipe') {
    final centerX = _defaultReusablePointX(previousArgs);
    final centerY = _defaultReusablePointY(previousArgs);
    args.putIfAbsent('x1', () => centerX ?? 0);
    args.putIfAbsent('x2', () => centerX ?? args['x1'] ?? 0);
    args.putIfAbsent('y1', () => centerY ?? 0);
    args.putIfAbsent('y2', () {
      final y = _asNum(centerY);
      return y == null ? args['y1'] ?? 0 : math.max(0, y - 480);
    });
    args.putIfAbsent('direction', () => 'up');
  }

  for (final key in const ['raw_x', 'raw_y', 'rawX', 'rawY']) {
    if (previousArgs.containsKey(key)) {
      args[key] = previousArgs[key];
    }
  }
  return args;
}

dynamic _defaultReusablePointX(Map<String, dynamic> args) {
  final direct = _firstPresentReusableArg(args, const ['x', 'raw_x', 'rawX']);
  if (direct != null) return direct;
  final x1 = _asNum(args['x1']);
  final x2 = _asNum(args['x2']);
  if (x1 != null && x2 != null) return (x1 + x2) / 2;
  return _firstPresentReusableArg(args, const ['x1', 'x2']);
}

dynamic _defaultReusablePointY(Map<String, dynamic> args) {
  final direct = _firstPresentReusableArg(args, const ['y', 'raw_y', 'rawY']);
  if (direct != null) return direct;
  final y1 = _asNum(args['y1']);
  final y2 = _asNum(args['y2']);
  if (y1 != null && y2 != null) return (y1 + y2) / 2;
  return _firstPresentReusableArg(args, const ['y1', 'y2']);
}

dynamic _firstPresentReusableArg(Map<String, dynamic> args, List<String> keys) {
  for (final key in keys) {
    if (args.containsKey(key) && args[key] != null) return args[key];
  }
  return null;
}

num? _asNum(dynamic value) {
  if (value is num) return value;
  if (value is String) return num.tryParse(value.trim());
  return null;
}

String _reusableArgFieldText(dynamic value) {
  if (value == null) return '';
  if (value is String || value is num || value is bool) {
    return value.toString();
  }
  return jsonEncode(_jsonSafe(value));
}

dynamic _parseReusableArgFieldValue(
  String raw,
  _ReusableFunctionStepArgType type,
) {
  switch (type) {
    case _ReusableFunctionStepArgType.integer:
      return int.tryParse(raw) ?? raw;
    case _ReusableFunctionStepArgType.number:
      return num.tryParse(raw) ?? raw;
    case _ReusableFunctionStepArgType.boolean:
      final normalized = raw.trim().toLowerCase();
      if (normalized == 'true' || normalized == '1' || normalized == 'yes') {
        return true;
      }
      if (normalized == 'false' || normalized == '0' || normalized == 'no') {
        return false;
      }
      return raw;
    case _ReusableFunctionStepArgType.string:
      return raw;
  }
}

TextInputType _keyboardTypeForReusableArgField(
  _ReusableFunctionStepArgType type,
) {
  switch (type) {
    case _ReusableFunctionStepArgType.integer:
      return TextInputType.number;
    case _ReusableFunctionStepArgType.number:
      return const TextInputType.numberWithOptions(decimal: true);
    case _ReusableFunctionStepArgType.boolean:
    case _ReusableFunctionStepArgType.string:
      return TextInputType.text;
  }
}

double _reusableArgFieldWidth(_ReusableFunctionStepArgField field) {
  if (field.type == _ReusableFunctionStepArgType.boolean) return 132;
  if (field.type == _ReusableFunctionStepArgType.integer ||
      field.type == _ReusableFunctionStepArgType.number) {
    return 136;
  }
  switch (field.key) {
    case 'text':
    case 'package_name':
    case 'target_description':
      return 214;
    default:
      return 160;
  }
}

Map<String, dynamic> _buildReusableFunctionStepFromEdit({
  required Map<String, dynamic> rawStep,
  required String tool,
  required Map<String, dynamic> args,
}) {
  final normalizedTool = _normalizeReplayToolName(tool);
  final action = _replayableActionForToolName(tool);
  final effectiveTool = action ?? normalizedTool;
  final existingArgs = _asStringKeyMap(
    _asStringKeyMap(rawStep['action'])['args'],
  );
  final editedArgs = action == null
      ? _deepCopyStringMap(args)
      : _canonicalReusableArgsBySchema(effectiveTool, args);
  if (existingArgs['target'] is Map) {
    editedArgs['target'] = _deepCopyStringMap(
      _asStringKeyMap(existingArgs['target']),
    );
  }
  return <String, dynamic>{
    'step_index': _asInt(rawStep['step_index']) ?? 0,
    'source_state_id': (rawStep['source_state_id'] ?? '').toString(),
    'action': <String, dynamic>{'tool': effectiveTool, 'args': editedArgs},
  };
}

Map<String, dynamic>? _replaceReusableFunctionStep(
  Map<String, dynamic> spec,
  _ReusableFunctionStepSummary step,
  Map<String, dynamic> replacement,
) {
  final updatedSpec = _deepCopyStringMap(spec);
  final rawSteps = updatedSpec['steps'];
  if (rawSteps is! List) return null;
  final steps = rawSteps.map(_asStringKeyMap).toList(growable: true);
  final index = step.index;
  if (index < 0 || index >= steps.length) return null;
  steps[index] = <String, dynamic>{
    'step_index': index,
    'source_state_id': (steps[index]['source_state_id'] ?? '').toString(),
    'action': _deepCopyStringMap(_asStringKeyMap(replacement['action'])),
  };
  updatedSpec['steps'] = steps;
  return updatedSpec;
}

Map<String, dynamic>? _removeReusableFunctionStep(
  Map<String, dynamic> spec,
  _ReusableFunctionStepSummary step,
) {
  final updatedSpec = _deepCopyStringMap(spec);
  final rawSteps = updatedSpec['steps'];
  if (rawSteps is! List || rawSteps.length <= 1) return null;
  final steps = rawSteps.map(_asStringKeyMap).toList(growable: true);
  final index = step.index;
  if (index < 0 || index >= steps.length) return null;
  steps.removeAt(index);
  for (var nextIndex = 0; nextIndex < steps.length; nextIndex++) {
    steps[nextIndex]['step_index'] = nextIndex;
  }
  updatedSpec['steps'] = steps;
  _shiftReusableBindingsAfterStepRemoval(updatedSpec, index);
  return updatedSpec;
}

Map<String, dynamic> _canonicalReusableArgsBySchema(
  String action,
  Map<String, dynamic> args,
) {
  final output = <String, dynamic>{};
  for (final key in OobCanonicalActionSchema.argNames(action)) {
    final value = _firstPresentReusableArg(args, [key]);
    if (value != null) output[key] = value;
  }
  for (final key in const ['raw_x', 'raw_y', 'rawX', 'rawY']) {
    final value = _firstPresentReusableArg(args, [key]);
    if (value != null) output[key] = value;
  }
  return output;
}

void _shiftReusableBindingsAfterStepRemoval(
  Map<String, dynamic> spec,
  int removedIndex,
) {
  final rawBindings = spec['bindings'];
  if (rawBindings is! List) return;
  final oldBindings = rawBindings.map(_asStringKeyMap).toList(growable: false);
  final oldNames = oldBindings
      .map(_bindingArgumentName)
      .whereType<String>()
      .toSet();
  final shifted = oldBindings
      .map((binding) => _shiftReusableBinding(binding, removedIndex))
      .whereType<Map<String, dynamic>>()
      .toList(growable: false);
  spec['bindings'] = shifted;
  final remainingNames = shifted
      .map(_bindingArgumentName)
      .whereType<String>()
      .toSet();
  final removedNames = oldNames.difference(remainingNames);
  if (removedNames.isEmpty) return;
  final schema = _asStringKeyMap(spec['input_schema']);
  final properties = _asStringKeyMap(schema['properties']);
  for (final name in removedNames) {
    properties.remove(name);
  }
  final required = schema['required'];
  if (required is List && removedNames.isNotEmpty) {
    required.removeWhere((name) => removedNames.contains(name?.toString()));
  }
  if (schema.isNotEmpty) {
    schema['properties'] = properties;
    spec['input_schema'] = schema;
  }
}

Map<String, dynamic>? _shiftReusableBinding(
  Map<String, dynamic> binding,
  int removedIndex,
) {
  final target = (binding['target'] ?? '').toString();
  final match = RegExp(
    r'^\$\.steps\[(\d+)\](\.action\.args(?:\..+)?)$',
  ).firstMatch(target);
  if (match == null) return binding;
  final index = int.tryParse(match.group(1) ?? '');
  if (index == null) return binding;
  if (index == removedIndex) return null;
  if (index < removedIndex) return binding;
  return <String, dynamic>{
    'source': binding['source'],
    'target': '\$.steps[${index - 1}]${match.group(2)}',
  };
}

String? _bindingArgumentName(Map<String, dynamic> binding) {
  final match = RegExp(
    r'^\$\.arguments\.([A-Za-z_][A-Za-z0-9_]*)',
  ).firstMatch((binding['source'] ?? '').toString());
  return match?.group(1);
}

List<_ReusableFunctionParameterSummary> _reusableFunctionParameters(
  dynamic rawParameters,
) {
  final schema = _asStringKeyMap(rawParameters);
  final properties = _asStringKeyMap(schema['properties']);
  if (properties.isEmpty) return const <_ReusableFunctionParameterSummary>[];
  final requiredNames = schema['required'] is List
      ? (schema['required'] as List).map((item) => item.toString()).toSet()
      : const <String>{};
  return properties.entries
      .map((entry) {
        final property = _asStringKeyMap(entry.value);
        return _ReusableFunctionParameterSummary.fromMap({
          ...property,
          'name': entry.key,
          'required': requiredNames.contains(entry.key),
        });
      })
      .toList(growable: false);
}

Map<String, dynamic> _deepCopyStringMap(Map<String, dynamic> value) {
  final cloned = jsonDecode(jsonEncode(_jsonSafe(value)));
  if (cloned is Map) {
    return cloned.map((key, item) => MapEntry(key.toString(), item));
  }
  return <String, dynamic>{};
}

Map<String, dynamic> _functionSpecJsonFromSavePayload(
  Map<String, dynamic> payload,
) {
  for (final key in const ['function', 'updated_function']) {
    final map = _asStringKeyMap(payload[key]);
    if (map.isNotEmpty) return _deepCopyStringMap(map);
  }
  return const <String, dynamic>{};
}

String _specFingerprint(Map<String, dynamic> value) {
  return jsonEncode(_jsonSafe(value));
}

class _EnhancementStatusBox extends StatelessWidget {
  const _EnhancementStatusBox({
    required this.status,
    required this.message,
    required this.isSaving,
    required this.isSaved,
  });

  final FunctionEnhancementStatus status;
  final String? message;
  final bool isSaving;
  final bool isSaved;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = _statusColor(context);
    final body = message?.trim().isNotEmpty == true
        ? message!.trim()
        : _defaultMessage(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.14 : 0.09),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withValues(alpha: 0.26)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(_icon, size: 18, color: color),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _title(context),
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textPrimary,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  body,
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textSecondary,
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  IconData get _icon {
    switch (status) {
      case FunctionEnhancementStatus.enhancing:
        return Icons.sync_rounded;
      case FunctionEnhancementStatus.enhanced:
        return Icons.auto_awesome_rounded;
      case FunctionEnhancementStatus.partial:
        return Icons.rule_rounded;
      case FunctionEnhancementStatus.unchanged:
        return Icons.fact_check_outlined;
      case FunctionEnhancementStatus.failed:
        return Icons.error_outline_rounded;
      case FunctionEnhancementStatus.none:
        return Icons.info_outline_rounded;
    }
  }

  Color _statusColor(BuildContext context) {
    switch (status) {
      case FunctionEnhancementStatus.enhanced:
        return _successColor(context);
      case FunctionEnhancementStatus.partial:
        return _warningColor(context);
      case FunctionEnhancementStatus.failed:
        return _errorColor(context);
      case FunctionEnhancementStatus.enhancing:
      case FunctionEnhancementStatus.unchanged:
      case FunctionEnhancementStatus.none:
        return _routeColor(context);
    }
  }

  String _title(BuildContext context) {
    switch (status) {
      case FunctionEnhancementStatus.enhancing:
        return isSaving
            ? _text(context, '增强：保存中', 'Enhancement: saving')
            : _text(context, '增强：后台执行中', 'Enhancement: running in background');
      case FunctionEnhancementStatus.enhanced:
        if (!isSaved) {
          return _text(
            context,
            '增强：已生成，待保存',
            'Enhancement: generated, save pending',
          );
        }
        return _text(context, '增强：已增强并保存', 'Enhancement: enhanced and saved');
      case FunctionEnhancementStatus.partial:
        if (!isSaved) {
          return _text(
            context,
            '增强：部分生成，待保存',
            'Enhancement: partially generated, save pending',
          );
        }
        return _text(
          context,
          '增强：部分增强并保存',
          'Enhancement: partially enhanced and saved',
        );
      case FunctionEnhancementStatus.unchanged:
        if (!isSaved) {
          return _text(
            context,
            '增强：已检查，待保存',
            'Enhancement: checked, save pending',
          );
        }
        return _text(context, '增强：已检查，无需修改', 'Enhancement: checked, no change');
      case FunctionEnhancementStatus.failed:
        return _text(
          context,
          '增强：失败，可重试',
          'Enhancement: failed, retry available',
        );
      case FunctionEnhancementStatus.none:
        return _text(context, '增强：未执行', 'Enhancement: not run');
    }
  }

  String _defaultMessage(BuildContext context) {
    switch (status) {
      case FunctionEnhancementStatus.enhancing:
        return _text(
          context,
          'Agent 正在后台整理名称、步骤、参数和复用元数据。',
          'Agent is refining labels, steps, parameters, and reuse metadata in the background.',
        );
      case FunctionEnhancementStatus.enhanced:
        return _text(
          context,
          '已产生可用增强并写回复用指令库。',
          'Useful enhancement was produced and written back to the Function library.',
        );
      case FunctionEnhancementStatus.partial:
        return _text(
          context,
          '有可用增强已保留，未通过的片段已跳过。',
          'Useful enhancement was kept; failed sections were skipped.',
        );
      case FunctionEnhancementStatus.unchanged:
        return _text(
          context,
          'Agent 已检查当前复用指令，没有安全可应用的变化。',
          'Agent checked the Function and found no safe applicable change.',
        );
      case FunctionEnhancementStatus.failed:
        return _text(
          context,
          '没有写入增强结果，当前复用指令保持原样。',
          'No enhancement was written. The current Function is unchanged.',
        );
      case FunctionEnhancementStatus.none:
        return '';
    }
  }
}

class _FunctionApiStatusBox extends StatelessWidget {
  const _FunctionApiStatusBox({
    required this.functionId,
    required this.importResult,
    required this.runResult,
    required this.apiCallJson,
  });

  final String functionId;
  final UtgRunLogImportResult? importResult;
  final UtgManualRunResult? runResult;
  final String apiCallJson;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final runResult = this.runResult;
    final statusColor = runResult == null || runResult.success
        ? _successColor(context)
        : _errorColor(context);
    final lines = <String>[
      if (functionId.isNotEmpty)
        _text(context, '复用指令：$functionId', 'Function: $functionId'),
      if (importResult != null) _importStatusText(context, importResult!),
      if (runResult != null) _runStatusText(context, runResult),
    ];
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: statusColor.withValues(alpha: context.isDarkTheme ? 0.14 : 0.09),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: statusColor.withValues(alpha: 0.28)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(
                Icons.play_circle_outline_rounded,
                size: 18,
                color: statusColor,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  lines.join('\n'),
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textSecondary,
                    height: 1.35,
                  ),
                ),
              ),
              if (apiCallJson.trim().isNotEmpty)
                Tooltip(
                  message: _text(context, '复制执行调用', 'Copy run call'),
                  child: IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.content_copy_rounded, size: 16),
                    color: palette.textSecondary,
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: apiCallJson));
                      showToast(
                        _text(context, '已复制执行调用', 'Run call copied'),
                        type: ToastType.success,
                      );
                    },
                  ),
                ),
            ],
          ),
          if (runResult != null && runResult.stepResults.isNotEmpty) ...[
            const SizedBox(height: 10),
            FunctionRunResultInlinePanel(result: runResult),
          ],
        ],
      ),
    );
  }

  String _importStatusText(
    BuildContext context,
    UtgRunLogImportResult importResult,
  ) {
    if (!importResult.success) {
      return _text(context, '保存：失败', 'Save: failed');
    }
    return _text(context, '保存：已保存', 'Save: saved');
  }

  String _runStatusText(BuildContext context, UtgManualRunResult result) {
    final progressText = _runProgressText(context, result);
    final stepCount = result.stepCount;
    final stepText = progressText.isNotEmpty
        ? ' · $progressText'
        : stepCount > 0
        ? ' · ${result.successStepCount}/$stepCount'
        : '';
    if (result.completedVlmFallback) {
      return _text(
        context,
        '执行：自动执行完成$stepText',
        'Run: completed automatically$stepText',
      );
    }
    if (result.completedLocal) {
      return _text(
        context,
        '执行：本地执行完成$stepText',
        'Run: completed locally$stepText',
      );
    }
    if (!result.success) {
      return _text(context, '执行：失败$stepText', 'Run: failed$stepText');
    }
    return _text(context, '执行：已开始$stepText', 'Run: started$stepText');
  }

  String _runProgressText(BuildContext context, UtgManualRunResult result) {
    final currentStepNumber = result.currentStepNumber;
    final stepCount = result.stepCount;
    if (currentStepNumber != null && currentStepNumber > 0) {
      final value = stepCount > 0
          ? '$currentStepNumber/$stepCount'
          : '$currentStepNumber';
      return _text(context, '执行到第 $value 步', 'Step $value');
    }
    return '';
  }
}

class _SpecActionButton extends StatelessWidget {
  const _SpecActionButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final enabled = onTap != null;
    return Material(
      color: context.isDarkTheme
          ? palette.surfaceSecondary
          : Colors.grey.shade100,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 16,
                color: enabled ? palette.textPrimary : palette.textTertiary,
              ),
              const SizedBox(width: 7),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: enabled ? palette.textPrimary : palette.textTertiary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _WarningBox extends StatelessWidget {
  const _WarningBox({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final warningColor = _warningColor(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: warningColor.withValues(
          alpha: context.isDarkTheme ? 0.18 : 0.13,
        ),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          color: palette.textSecondary,
          height: 1.35,
        ),
      ),
    );
  }
}

class _VlmStepActionPanel extends StatelessWidget {
  const _VlmStepActionPanel({required this.snapshot, required this.source});

  final _RunLogStepSnapshot snapshot;
  final _RunLogStepSource source;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final color = _runLogStepSourceColor(context, source);
    final args = snapshot.args;
    final result = _asStringKeyMap(snapshot.result);
    final action = _vlmActionLabel(context, snapshot.toolName).trim();
    final target = _runLogStepTarget(snapshot).trim();
    final coordinates = _vlmCoordinateText(args);
    final resultText = _firstNonBlank([
      result['summary'],
      result['message'],
      result['error_message'],
    ]).trim();
    final meta = <MapEntry<String, String>>[
      if (snapshot.packageName.isNotEmpty)
        MapEntry(_text(context, '应用', 'Package'), snapshot.packageName),
      if (coordinates.isNotEmpty)
        MapEntry(_text(context, '坐标', 'Coordinates'), coordinates),
      if (snapshot.durationMs != null)
        MapEntry(
          _text(context, '耗时', 'Duration'),
          _formatMs(snapshot.durationMs!),
        ),
    ];

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(11, 10, 11, 11),
      decoration: BoxDecoration(
        color: color.withValues(alpha: isDark ? 0.15 : 0.075),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: color.withValues(alpha: isDark ? 0.34 : 0.22),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.touch_app_rounded, size: 16, color: color),
              const SizedBox(width: 7),
              Expanded(
                child: Text(
                  _runLogStepActionPanelTitle(context, source),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: color,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0,
                    height: 1.1,
                  ),
                ),
              ),
              if (action.isNotEmpty)
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 7,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: isDark ? 0.20 : 0.12),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    action,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: color,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0,
                      height: 1,
                    ),
                  ),
                ),
            ],
          ),
          if (target.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              target,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 13,
                color: palette.textPrimary,
                fontWeight: FontWeight.w600,
                letterSpacing: 0,
                height: 1.25,
              ),
            ),
          ],
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: meta
                  .map(
                    (entry) => _VlmActionMetaPill(
                      label: entry.key,
                      value: entry.value,
                    ),
                  )
                  .toList(growable: false),
            ),
          ],
          if (resultText.isNotEmpty && resultText != target) ...[
            const SizedBox(height: 8),
            Text(
              resultText,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                color: palette.textSecondary,
                fontWeight: FontWeight.w500,
                letterSpacing: 0,
                height: 1.3,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _VlmActionMetaPill extends StatelessWidget {
  const _VlmActionMetaPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary.withValues(alpha: 0.78)
            : Colors.white.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle.withValues(alpha: 0.72)),
      ),
      child: RichText(
        text: TextSpan(
          style: TextStyle(
            fontSize: 12,
            color: palette.textSecondary,
            letterSpacing: 0,
            height: 1.05,
          ),
          children: [
            TextSpan(text: '$label  '),
            TextSpan(
              text: value,
              style: TextStyle(
                color: palette.textPrimary,
                fontWeight: FontWeight.w600,
                fontFamily: value.contains(',') ? 'monospace' : null,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SummaryGrid extends StatelessWidget {
  const _SummaryGrid({required this.snapshot});

  final _RunLogStepSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final source = _runLogStepSource(snapshot);
    final items = <MapEntry<String, String>>[
      MapEntry(_text(context, '状态', 'Status'), snapshot.statusLabel(context)),
      MapEntry(
        _text(context, '执行方式', 'Execution'),
        _runLogStepSourceLabel(context, source),
      ),
      if (!_hasRunLogSourceBadge(source) && snapshot.compileKind.isNotEmpty)
        MapEntry(
          _text(context, '处理方式', 'Handling'),
          snapshot.routeLabel(context),
        ),
      if (snapshot.durationMs != null)
        MapEntry(
          _text(context, '耗时', 'Duration'),
          _formatMs(snapshot.durationMs!),
        ),
      if (snapshot.hasTokenUsage)
        MapEntry(
          _text(context, '模型用量', 'Model usage'),
          snapshot.tokenUsageLabel(context),
        ),
      if (snapshot.packageName.isNotEmpty)
        MapEntry(_text(context, '应用包名', 'Package'), snapshot.packageName),
    ];
    if (items.isEmpty) {
      return const SizedBox.shrink();
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: items
          .map((entry) => _SummaryPill(label: entry.key, value: entry.value))
          .toList(growable: false),
    );
  }
}

class _SummaryPill extends StatelessWidget {
  const _SummaryPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(10),
      ),
      child: RichText(
        text: TextSpan(
          style: TextStyle(fontSize: 11, color: palette.textSecondary),
          children: [
            TextSpan(text: '$label  '),
            TextSpan(
              text: value,
              style: TextStyle(
                color: palette.textPrimary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CollapsibleSection extends StatefulWidget {
  const _CollapsibleSection({
    required this.title,
    required this.child,
    this.copyValue,
    this.initiallyExpanded = true,
  });

  final String title;
  final Widget child;
  final String? copyValue;
  final bool initiallyExpanded;

  @override
  State<_CollapsibleSection> createState() => _CollapsibleSectionState();
}

class _CollapsibleSectionState extends State<_CollapsibleSection> {
  late bool _expanded;

  @override
  void initState() {
    super.initState();
    _expanded = widget.initiallyExpanded;
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: isDark ? palette.surfaceSecondary : Colors.grey.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Material(
            color: Colors.transparent,
            borderRadius: _expanded
                ? const BorderRadius.vertical(top: Radius.circular(12))
                : BorderRadius.circular(12),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: () => setState(() => _expanded = !_expanded),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 8, 10),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        widget.title,
                        style: TextStyle(
                          fontSize: 13,
                          color: palette.textPrimary,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    if (_expanded &&
                        widget.copyValue != null &&
                        widget.copyValue!.trim().isNotEmpty)
                      Tooltip(
                        message: _text(context, '复制', 'Copy'),
                        child: IconButton(
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(
                            Icons.content_copy_rounded,
                            size: 16,
                          ),
                          color: palette.textSecondary,
                          onPressed: () {
                            Clipboard.setData(
                              ClipboardData(text: widget.copyValue!),
                            );
                            showToast(
                              _text(context, '已复制', 'Copied'),
                              type: ToastType.success,
                            );
                          },
                        ),
                      ),
                    Icon(
                      _expanded
                          ? Icons.keyboard_arrow_up_rounded
                          : Icons.keyboard_arrow_down_rounded,
                      size: 20,
                      color: palette.textSecondary,
                    ),
                  ],
                ),
              ),
            ),
          ),
          if (_expanded) ...[
            Divider(height: 1, color: palette.borderSubtle),
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
              child: widget.child,
            ),
          ],
        ],
      ),
    );
  }
}

class _BeforeAfterStateView extends StatelessWidget {
  const _BeforeAfterStateView({required this.before, required this.after});

  final Map<String, dynamic> before;
  final Map<String, dynamic> after;

  @override
  Widget build(BuildContext context) {
    final imagePaths = <String>[
      if (before.isNotEmpty) _stateScreenshotPath(before),
      if (after.isNotEmpty) _stateScreenshotPath(after),
    ].where((path) => path.isNotEmpty).toSet().toList(growable: false);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (before.isNotEmpty)
          _RunLogStatePanel(
            title: _text(context, '操作前', 'Before'),
            state: before,
            imagePaths: imagePaths,
          ),
        if (before.isNotEmpty && after.isNotEmpty) const SizedBox(height: 12),
        if (after.isNotEmpty)
          _RunLogStatePanel(
            title: _text(context, '操作后', 'After'),
            state: after,
            imagePaths: imagePaths,
          ),
      ],
    );
  }
}

class _RunLogStatePanel extends StatelessWidget {
  const _RunLogStatePanel({
    required this.title,
    required this.state,
    required this.imagePaths,
  });

  final String title;
  final Map<String, dynamic> state;
  final List<String> imagePaths;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final screenshotPath = _stateScreenshotPath(state);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: TextStyle(
            fontSize: 12,
            color: palette.textSecondary,
            fontWeight: FontWeight.w600,
          ),
        ),
        if (screenshotPath.isNotEmpty) ...[
          const SizedBox(height: 8),
          _RunLogScreenshotPreview(path: screenshotPath, allPaths: imagePaths),
        ],
        const SizedBox(height: 8),
        _JsonBlock(value: _userVisibleJson(state)),
      ],
    );
  }
}

class _RunLogScreenshotPreview extends StatelessWidget {
  const _RunLogScreenshotPreview({required this.path, required this.allPaths});

  final String path;
  final List<String> allPaths;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final sources = (allPaths.isEmpty ? <String>[path] : allPaths)
        .map((item) => FileImageSource(item) as ImagePreviewSource)
        .toList(growable: false);
    final initialIndex = math.max(0, allPaths.indexOf(path));
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: () => ImagePreviewOverlay.showAll(
          context,
          sources: sources,
          initialIndex: initialIndex,
        ),
        child: Container(
          width: double.infinity,
          height: 220,
          decoration: BoxDecoration(
            color: context.isDarkTheme
                ? Colors.black.withValues(alpha: 0.22)
                : Colors.white,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: palette.borderSubtle),
          ),
          clipBehavior: Clip.antiAlias,
          child: Image.file(
            File(path),
            fit: BoxFit.contain,
            errorBuilder: (context, error, stackTrace) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(
                    path,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textTertiary,
                      fontFamily: 'monospace',
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _JsonBlock extends StatelessWidget {
  const _JsonBlock({required this.value});

  final dynamic value;

  @override
  Widget build(BuildContext context) {
    return _JsonText(text: _prettyJson(value));
  }
}

class _JsonText extends StatelessWidget {
  const _JsonText({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? Colors.black.withValues(alpha: 0.22)
            : Colors.white,
        borderRadius: BorderRadius.circular(8),
      ),
      child: SelectableText(
        text.trim().isEmpty ? '{}' : text,
        style: TextStyle(
          fontSize: 11,
          height: 1.35,
          color: palette.textPrimary,
          fontFamily: 'monospace',
        ),
      ),
    );
  }
}

class _RouteBadge extends StatelessWidget {
  const _RouteBadge({required this.compileKind, required this.l10n});

  final String compileKind;
  final dynamic l10n;

  @override
  Widget build(BuildContext context) {
    final isHit = compileKind == 'hit';
    final isMiss = compileKind == 'miss';
    if (!isHit && !isMiss) return const SizedBox.shrink();

    final label = isHit
        ? l10n.executionRouteMemorized
        : l10n.executionRouteAiPlanning;
    final color = isHit ? _successColor(context) : _routeColor(context);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: color,
          height: 1,
        ),
      ),
    );
  }
}

class _RunLogStepSourceBadge extends StatelessWidget {
  const _RunLogStepSourceBadge({required this.source});

  final _RunLogStepSource source;

  @override
  Widget build(BuildContext context) {
    final color = _runLogStepSourceColor(context, source);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.18 : 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.26)),
      ),
      child: Text(
        _runLogStepSourceLabel(context, source),
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          color: color,
          height: 1,
        ),
      ),
    );
  }
}

class _RunLogStepSnapshot {
  const _RunLogStepSnapshot({
    required this.step,
    required this.metadata,
    required this.action,
    required this.args,
    required this.result,
    required this.before,
    required this.after,
    required this.stepNumber,
    required this.title,
    required this.toolName,
    required this.compileKind,
    required this.status,
    required this.success,
    required this.durationMs,
    required this.packageName,
    required this.summary,
    required this.thinking,
    required this.tokenUsage,
    required this.tokenUsageAttempts,
  });

  final Map<String, dynamic> step;
  final Map<String, dynamic> metadata;
  final Map<String, dynamic> action;
  final Map<String, dynamic> args;
  final Map<String, dynamic> result;
  final Map<String, dynamic> before;
  final Map<String, dynamic> after;
  final int stepNumber;
  final String title;
  final String toolName;
  final String compileKind;
  final String status;
  final bool? success;
  final int? durationMs;
  final String packageName;
  final String summary;
  final String thinking;
  final Map<String, dynamic> tokenUsage;
  final List<Map<String, dynamic>> tokenUsageAttempts;

  int? get totalTokens => _asInt(tokenUsage['total_tokens']);

  bool get isPending => status == 'running' || status == 'waiting_user';

  bool get isFailed => status == 'failed' || success == false;

  bool get isVlmStep {
    return metadata['source']?.toString().trim().toLowerCase() == 'vlm';
  }

  factory _RunLogStepSnapshot.fromStep(
    Map<String, dynamic> step, {
    required int fallbackIndex,
  }) {
    final metadata = _asStringKeyMap(step['metadata']);
    final action = _asStringKeyMap(step['action']);
    final toolName = action['tool']?.toString().trim() ?? '';
    final args = _asStringKeyMap(action['args']);
    final result = _asStringKeyMap(step['result']);
    final beforeStateId = step['before_state_id']?.toString().trim() ?? '';
    final afterStateId = step['after_state_id']?.toString().trim() ?? '';
    final before = beforeStateId.isEmpty
        ? const <String, dynamic>{}
        : <String, dynamic>{'state_id': beforeStateId};
    final after = afterStateId.isEmpty
        ? const <String, dynamic>{}
        : <String, dynamic>{'state_id': afterStateId};
    final stepIndex = _asInt(step['step_index']) ?? fallbackIndex;
    final stepNumber = stepIndex + 1;
    final source = metadata['source']?.toString().trim() ?? '';
    final success = _asBool(result['success']);
    final status =
        metadata['status']?.toString().trim().toLowerCase() ??
        (success == true ? 'succeeded' : 'failed');
    final durationMs = _asInt(metadata['duration_ms']);
    final packageName = args['package_name']?.toString().trim() ?? '';
    final thinking = _firstNonBlank([metadata['thinking']]);
    final summary = _firstNonBlank([metadata['summary'], result['error']]);
    final tokenUsage = _firstMap(metadata, const ['token_usage']);
    final tokenUsageAttempts = _asStringKeyMapList(
      metadata['token_usage_attempts'],
    );

    return _RunLogStepSnapshot(
      step: step,
      metadata: metadata,
      action: action,
      args: args,
      result: result,
      before: before,
      after: after,
      stepNumber: stepNumber,
      title: toolName.isNotEmpty ? toolName : summary,
      toolName: toolName,
      compileKind: source,
      status: status,
      success: success,
      durationMs: durationMs,
      packageName: packageName,
      summary: summary,
      thinking: thinking,
      tokenUsage: tokenUsage,
      tokenUsageAttempts: tokenUsageAttempts,
    );
  }

  String previewText(BuildContext context) {
    final parts = <String>[];
    final target = _canonicalActionPreview(toolName, args);
    if (target.isNotEmpty) {
      parts.add(target);
    }
    final x = args['x'];
    final y = args['y'];
    if (x != null && y != null) {
      parts.add('($x, $y)');
    }
    final direction = args['direction']?.toString().trim() ?? '';
    if (direction.isNotEmpty) {
      parts.add(direction);
    }
    return parts.join(' · ');
  }

  String get actionForCopy {
    if (action.isNotEmpty) {
      return _prettyJson(action);
    }
    return toolName;
  }

  bool get hasTokenUsage => tokenUsage.isNotEmpty;

  String statusLabel(BuildContext context) {
    if (status == 'running') {
      return _text(context, '执行中', 'Running');
    }
    if (status == 'waiting_user') {
      return _text(context, '等待用户', 'Waiting for user');
    }
    if (success == null) {
      return _text(context, '未知', 'Unknown');
    }
    return success!
        ? _text(context, '成功', 'Success')
        : _text(context, '失败', 'Failed');
  }

  String routeLabel(BuildContext context) {
    if (compileKind == 'hit') {
      return context.l10n.executionRouteMemorized;
    }
    if (compileKind == 'miss') {
      return context.l10n.executionRouteAiPlanning;
    }
    if (compileKind == 'vlm_step' || compileKind == 'vlm') {
      return _text(context, '自动执行', 'Automatic execution');
    }
    return compileKind;
  }

  String tokenUsageLabel(BuildContext context) {
    final total = totalTokens;
    final promptTokens = _asInt(tokenUsage['prompt_tokens']);
    final completionTokens = _asInt(tokenUsage['completion_tokens']);
    final imageTokens = _asInt(tokenUsage['image_tokens']);
    final cachedTokens = _asInt(tokenUsage['cached_tokens']);
    final resolvedModel = tokenUsage['resolved_model']?.toString().trim() ?? '';
    final parts = <String>[];
    if (total != null) {
      parts.add(_formatTokens(total));
    }
    if (promptTokens != null || completionTokens != null) {
      parts.add('P${promptTokens ?? 0}/C${completionTokens ?? 0}');
    }
    if (imageTokens != null && imageTokens > 0) {
      parts.add(
        _localeValue(context, zh: '图片 $imageTokens', en: 'image $imageTokens'),
      );
    }
    if (cachedTokens != null && cachedTokens > 0) {
      parts.add(
        _localeValue(
          context,
          zh: '缓存 $cachedTokens',
          en: 'cached $cachedTokens',
        ),
      );
    }
    if (resolvedModel.isNotEmpty) {
      parts.add(resolvedModel);
    }
    return parts.isEmpty ? _text(context, '未知', 'Unknown') : parts.join(' · ');
  }

  String toTranscript() {
    final lines = <String>[
      '### Step $stepNumber',
      if (title.isNotEmpty) 'Title: $title',
      if (toolName.isNotEmpty) 'Tool: $toolName',
      if (compileKind.isNotEmpty)
        'Execution: ${_userVisibleString(compileKind)}',
      if (success != null) 'Success: $success',
      if (durationMs != null) 'Duration: ${_formatMs(durationMs!)}',
      if (summary.isNotEmpty) 'Summary: $summary',
      if (hasTokenUsage) 'Model usage: ${tokenUsageLabelTextOnly()}',
      if (packageName.isNotEmpty) 'Package: $packageName',
    ];

    if (thinking.isNotEmpty) {
      _appendTranscriptSection(lines, 'Reasoning', thinking);
    }
    _appendTranscriptSection(lines, 'Action', action);
    _appendTranscriptSection(lines, 'Arguments', args);
    _appendTranscriptSection(lines, 'Result', result);
    _appendTranscriptSection(lines, 'Token Usage', {
      if (tokenUsage.isNotEmpty) 'token_usage': tokenUsage,
      if (tokenUsageAttempts.isNotEmpty)
        'token_usage_attempts': tokenUsageAttempts,
    });
    if (before.isNotEmpty || after.isNotEmpty) {
      _appendTranscriptSection(lines, 'Before / After', {
        if (before.isNotEmpty) 'before': before,
        if (after.isNotEmpty) 'after': after,
      });
    }
    _appendTranscriptSection(lines, 'Raw JSON', step);
    return lines.join('\n').trimRight();
  }

  String tokenUsageLabelTextOnly() {
    final total = totalTokens;
    final promptTokens = _asInt(tokenUsage['prompt_tokens']);
    final completionTokens = _asInt(tokenUsage['completion_tokens']);
    final imageTokens = _asInt(tokenUsage['image_tokens']);
    final cachedTokens = _asInt(tokenUsage['cached_tokens']);
    final resolvedModel = tokenUsage['resolved_model']?.toString().trim() ?? '';
    final parts = <String>[];
    if (total != null) parts.add(_formatTokens(total));
    if (promptTokens != null || completionTokens != null) {
      parts.add('prompt=${promptTokens ?? 0}');
      parts.add('completion=${completionTokens ?? 0}');
    }
    if (imageTokens != null && imageTokens > 0) {
      parts.add('image=$imageTokens');
    }
    if (cachedTokens != null && cachedTokens > 0) {
      parts.add('cached=$cachedTokens');
    }
    if (resolvedModel.isNotEmpty) parts.add('model=$resolvedModel');
    return parts.join(', ');
  }
}

List<_ThinkingDebugEntry> _collectRunLogThinkingEntries(
  Map<String, dynamic> _,
  List<Map<String, dynamic>> steps,
) {
  final entries = <_ThinkingDebugEntry>[];
  for (var index = 0; index < steps.length; index++) {
    final metadata = _asStringKeyMap(steps[index]['metadata']);
    final text = _firstNonBlank([metadata['thinking']]);
    if (text.isNotEmpty) {
      entries.add(
        _ThinkingDebugEntry(
          path: 'steps[$index].metadata.thinking',
          text: text,
        ),
      );
    }
  }
  return entries;
}

void _appendTranscriptSection(List<String> lines, String title, dynamic value) {
  if (_isEmptyJsonValue(value)) {
    return;
  }
  lines
    ..add('')
    ..add('$title:')
    ..add(_prettyUserJson(value));
}

String _formatMs(int ms) {
  if (ms < 1000) return '${ms}ms';
  return '${(ms / 1000).toStringAsFixed(1)}s';
}

String _formatTokens(int tokens) {
  if (tokens >= 1000) {
    return '${(tokens / 1000).toStringAsFixed(tokens >= 10000 ? 1 : 2)}k';
  }
  return '$tokens';
}

String _stepLabel(BuildContext context, int stepNumber) {
  return _localeValue(context, zh: '第 $stepNumber 步', en: 'Step $stepNumber');
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}

T _localeValue<T>(BuildContext context, {required T zh, required T en}) {
  return AppTextLocalizer.chooseValue(
    zh: zh,
    en: en,
    locale: Localizations.maybeLocaleOf(context),
  );
}

Map<String, dynamic> _defaultArgumentsForFunctionSpec(
  Map<String, dynamic> functionSpec,
) {
  final inputSchema = _asStringKeyMap(functionSpec['input_schema']);
  final properties = _asStringKeyMap(inputSchema['properties']);
  final arguments = <String, dynamic>{};
  for (final entry in properties.entries) {
    final property = _asStringKeyMap(entry.value);
    final defaultValue = property['default'];
    if (defaultValue == null) continue;
    arguments[entry.key] = defaultValue;
  }
  return arguments;
}

Color _successColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF63D98A)
      : const Color(0xFF2F8F4E);
}

Color _errorColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFF7A7A)
      : const Color(0xFFDC2626);
}

Color _routeColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF7AB7FF)
      : const Color(0xFF3B82F6);
}

Color _modelFreeColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF4DD6C9)
      : const Color(0xFF0F9F8F);
}

Color _vlmColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFF6BA9)
      : const Color(0xFFDB2777);
}

Color _humanColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFB86B)
      : const Color(0xFFD97706);
}

Color _warningColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFD166)
      : const Color(0xFFFFC04D);
}

Color _runningColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFFD166)
      : const Color(0xFFE6A700);
}

enum _RunLogStatusKind { running, success, failed, unknown }

class _RunLogStatusInfo {
  const _RunLogStatusInfo({
    required this.kind,
    required this.label,
    required this.title,
    required this.color,
    required this.icon,
  });

  final _RunLogStatusKind kind;
  final String label;
  final String title;
  final Color color;
  final IconData icon;
}

_RunLogStatusInfo _runLogStatusInfo(
  BuildContext context,
  Map<String, dynamic> payload,
) {
  final rawStatus = payload['status']?.toString().trim().toLowerCase() ?? '';
  final finished = _isRunLogFinished(payload);
  final success = _runLogSuccess(payload);
  final errorMessage = payload['error']?.toString().trim() ?? '';
  if (!finished || rawStatus == 'running') {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.running,
      label: _text(context, '运行中', 'Running'),
      title: _text(context, '执行还在进行中', 'Execution is still running'),
      color: _runningColor(context),
      icon: Icons.timelapse_rounded,
    );
  }
  if (success == false || errorMessage.isNotEmpty) {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.failed,
      label: _text(context, '失败', 'Failed'),
      title: _text(context, '执行失败', 'Execution failed'),
      color: _errorColor(context),
      icon: Icons.error_outline_rounded,
    );
  }
  if (success == true) {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.success,
      label: _text(context, '已完成', 'Done'),
      title: _text(context, '执行已完成', 'Execution completed'),
      color: _successColor(context),
      icon: Icons.check_circle_outline_rounded,
    );
  }
  if (rawStatus == 'failed' || rawStatus == 'error') {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.failed,
      label: _text(context, '失败', 'Failed'),
      title: _text(context, '执行失败', 'Execution failed'),
      color: _errorColor(context),
      icon: Icons.error_outline_rounded,
    );
  }
  return _RunLogStatusInfo(
    kind: _RunLogStatusKind.unknown,
    label: _text(context, '未知', 'Unknown'),
    title: _text(context, '执行状态未知', 'Execution status unknown'),
    color: context.omniPalette.textTertiary,
    icon: Icons.help_outline_rounded,
  );
}

enum _RunLogStepSource { agentVlm, human, omniflowReplay, route }

bool _isVlmRunLogStep(_RunLogStepSnapshot snapshot) => snapshot.isVlmStep;

_RunLogStepSource _runLogStepSource(_RunLogStepSnapshot snapshot) {
  return switch (snapshot.metadata['source']?.toString().trim().toLowerCase()) {
    'vlm' => _RunLogStepSource.agentVlm,
    'human_trajectory' ||
    'human_takeover' ||
    'manual_recording' => _RunLogStepSource.human,
    'omniflow_replay' || 'function' => _RunLogStepSource.omniflowReplay,
    _ => _RunLogStepSource.route,
  };
}

bool _hasRunLogSourceBadge(_RunLogStepSource source) {
  return source == _RunLogStepSource.agentVlm ||
      source == _RunLogStepSource.human ||
      source == _RunLogStepSource.omniflowReplay;
}

Color _runLogStepSourceColor(BuildContext context, _RunLogStepSource source) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _vlmColor(context);
    case _RunLogStepSource.human:
      return _humanColor(context);
    case _RunLogStepSource.omniflowReplay:
      return _modelFreeColor(context);
    case _RunLogStepSource.route:
      return _routeColor(context);
  }
}

String _runLogStepSourceLabel(BuildContext context, _RunLogStepSource source) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _text(context, '自动执行', 'Automatic');
    case _RunLogStepSource.human:
      return _text(context, '人类', 'Human');
    case _RunLogStepSource.omniflowReplay:
      return _text(context, '复用指令', 'Function');
    case _RunLogStepSource.route:
      return _text(context, '工具调用', 'Tool call');
  }
}

String _runLogStepDetailTitle(BuildContext context, _RunLogStepSource source) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _text(context, '自动执行记录', 'Automatic run');
    case _RunLogStepSource.human:
      return _text(context, '人类接管记录', 'Human takeover');
    case _RunLogStepSource.omniflowReplay:
      return _text(context, '复用指令执行记录', 'Function run');
    case _RunLogStepSource.route:
      return _text(context, '工具调用', 'Tool call');
  }
}

String _runLogStepActionPanelTitle(
  BuildContext context,
  _RunLogStepSource source,
) {
  switch (source) {
    case _RunLogStepSource.agentVlm:
      return _text(context, '自动执行动作', 'Automatic action');
    case _RunLogStepSource.human:
      return _text(context, '人类操作', 'Human action');
    case _RunLogStepSource.omniflowReplay:
      return _text(context, '复用指令动作', 'Function action');
    case _RunLogStepSource.route:
      return _text(context, '工具调用', 'Tool call');
  }
}

bool _shouldShowVisualActionPanel(_RunLogStepSnapshot snapshot) {
  final source = _runLogStepSource(snapshot);
  return source == _RunLogStepSource.agentVlm ||
      source == _RunLogStepSource.human ||
      source == _RunLogStepSource.omniflowReplay;
}

String _runLogStepDisplayTitle(
  BuildContext context,
  _RunLogStepSnapshot snapshot,
) {
  if (!_isVlmRunLogStep(snapshot)) {
    return snapshot.title;
  }
  final action = _vlmActionLabel(context, snapshot.toolName);
  final target = _runLogStepTarget(snapshot);
  if (action.isEmpty) {
    return target.isNotEmpty ? target : snapshot.title;
  }
  if (target.isEmpty || target == action) {
    return action;
  }
  return '$action $target';
}

String _runLogStepTarget(_RunLogStepSnapshot snapshot) {
  return _canonicalActionPreview(snapshot.toolName, snapshot.args);
}

String _canonicalActionPreview(String tool, Map<String, dynamic> args) {
  return switch (tool.trim().toLowerCase()) {
    'input_text' => args['text']?.toString().trim() ?? '',
    'open_app' => args['package_name']?.toString().trim() ?? '',
    'press_key' => args['key']?.toString().trim() ?? '',
    'wait' => args['duration_ms'] == null ? '' : '${args['duration_ms']}ms',
    'swipe' => args['direction']?.toString().trim() ?? '',
    _ => '',
  };
}

String _vlmCoordinateText(Map<String, dynamic> args) {
  final x = _firstNonBlank([args['x']]);
  final y = _firstNonBlank([args['y']]);
  if (x.isNotEmpty && y.isNotEmpty) {
    return '$x,$y';
  }
  final x1 = _firstNonBlank([args['x1']]);
  final y1 = _firstNonBlank([args['y1']]);
  final x2 = _firstNonBlank([args['x2']]);
  final y2 = _firstNonBlank([args['y2']]);
  if (x1.isNotEmpty && y1.isNotEmpty && x2.isNotEmpty && y2.isNotEmpty) {
    return '$x1,$y1 -> $x2,$y2';
  }
  return '';
}

String _vlmActionLabel(BuildContext context, String raw) {
  switch (raw.trim()) {
    case 'click':
      return _text(context, '点击', 'Tap');
    case 'input_text':
      return _text(context, '输入', 'Type');
    case 'swipe':
      return _text(context, '滑动', 'Swipe');
    case 'long_press':
      return _text(context, '长按', 'Long press');
    case 'open_app':
      return _text(context, '打开应用', 'Open app');
    case 'press_key':
      return _text(context, '按键', 'Press key');
    case 'wait':
      return _text(context, '等待', 'Wait');
  }
  return raw;
}

List<Map<String, dynamic>> _extractTimelineSteps(Map<String, dynamic> payload) {
  if (!_isCanonicalRunLog(payload)) {
    return const <Map<String, dynamic>>[];
  }
  final steps = payload['steps'];
  if (steps is! List) {
    return const <Map<String, dynamic>>[];
  }
  return steps
      .map(_asStringKeyMap)
      .where((step) => step.isNotEmpty)
      .toList(growable: false);
}

class _RunLogStepGroup {
  const _RunLogStepGroup({required this.step, required this.fallbackIndex});

  final Map<String, dynamic> step;
  final int fallbackIndex;
}

List<_RunLogStepGroup> _groupTimelineSteps(List<Map<String, dynamic>> steps) {
  return [
    for (var index = 0; index < steps.length; index++)
      _RunLogStepGroup(step: steps[index], fallbackIndex: index),
  ];
}

class _RunLogTokenUsageAggregate {
  const _RunLogTokenUsageAggregate({
    required this.totalTokens,
    required this.promptTokens,
    required this.completionTokens,
    required this.cachedTokens,
    required this.callCount,
    required this.stepCount,
  });

  final int? totalTokens;
  final int? promptTokens;
  final int? completionTokens;
  final int? cachedTokens;
  final int? callCount;
  final int? stepCount;

  bool get hasUsage =>
      totalTokens != null ||
      promptTokens != null ||
      completionTokens != null ||
      cachedTokens != null ||
      callCount != null ||
      stepCount != null;

  factory _RunLogTokenUsageAggregate.fromPayload(Map<String, dynamic> payload) {
    final diagnostics = _runLogDiagnostics(payload);
    final usage = _firstMap(diagnostics, const ['token_usage']);
    final byStep = _asStringKeyMapList(
      _firstPresentValue(diagnostics, const ['token_usage_by_step']),
    );
    final byCall = _asStringKeyMapList(
      _firstPresentValue(diagnostics, const ['token_usage_by_call']),
    );
    return _RunLogTokenUsageAggregate(
      totalTokens: _asInt(
        diagnostics['token_usage_total'] ?? usage['total_tokens'],
      ),
      promptTokens: _asInt(usage['prompt_tokens']),
      completionTokens: _asInt(usage['completion_tokens']),
      cachedTokens: _asInt(usage['cached_tokens']),
      callCount:
          _asInt(
            diagnostics['token_usage_call_count'] ??
                usage['call_count'] ??
                usage['attempt_count'],
          ) ??
          (byCall.isNotEmpty ? byCall.length : null),
      stepCount:
          _asInt(usage['step_count']) ??
          (byStep.isNotEmpty ? byStep.length : null),
    );
  }
}

String? _runLogPayloadError(
  BuildContext context,
  Map<String, dynamic> payload,
) {
  if (_isCanonicalRunLog(payload)) {
    return null;
  }
  if (payload.isNotEmpty && payload['schema_version'] != null) {
    return _text(
      context,
      '不支持的 RunLog schema_version：${payload['schema_version']}',
      'Unsupported RunLog schema_version: ${payload['schema_version']}',
    );
  }
  final success = _asBool(payload['success']);
  if (success != false) {
    return _text(
      context,
      'RunLog 不是 canonical 格式，无法展示。',
      'RunLog is not in canonical format.',
    );
  }
  final code = payload['error_code']?.toString().trim().toUpperCase();
  if (code == 'NOT_FOUND' || code == 'RUN_LOG_ID_EMPTY') {
    return context.l10n.omniflowAssetRunLogNotReady;
  }
  final message = payload['error_message']?.toString().trim();
  if (message != null && message.isNotEmpty) {
    return message;
  }
  return context.l10n.runLogTimelineLoadFailed;
}

class _RunLogConvertEligibility {
  const _RunLogConvertEligibility({
    required this.canConvert,
    required this.message,
  });

  final bool canConvert;
  final String message;
}

_RunLogConvertEligibility _runLogConvertEligibility(
  BuildContext context,
  Map<String, dynamic> payload,
  List<Map<String, dynamic>> steps,
) {
  if (steps.isEmpty) {
    return _RunLogConvertEligibility(
      canConvert: false,
      message: _text(context, '暂无可注册步骤', 'No steps to register'),
    );
  }
  if (!_isRunLogFinished(payload)) {
    return _RunLogConvertEligibility(
      canConvert: false,
      message: _text(
        context,
        '执行还在进行中，完成后才能注册',
        'This run is still executing. Register after it finishes.',
      ),
    );
  }
  return _RunLogConvertEligibility(
    canConvert: true,
    message: _text(context, '注册轨迹', 'Register trace'),
  );
}

bool _isRunLogFinished(Map<String, dynamic> payload) {
  return payload['status'] != 'running';
}

bool? _runLogSuccess(Map<String, dynamic> payload) {
  return _asBool(payload['success']);
}

String _runLogEmptyMessage(BuildContext context, Map<String, dynamic> payload) {
  final success = _asBool(payload['success']);
  final doneReason = _runLogDiagnostics(
    payload,
  )['done_reason']?.toString().trim();
  if (success == true) {
    return _text(
      context,
      '这次回复没有工具调用，只有最终文本，因此没有可展开的执行步骤。',
      'This reply did not call tools, so there are no execution steps to expand.',
    );
  }
  if (doneReason != null && doneReason.isNotEmpty) {
    return context.l10n.omniflowAssetNoSteps;
  }
  return context.l10n.runLogTimelineEmpty;
}

bool _isCanonicalRunLog(Map<String, dynamic> payload) {
  return payload['schema_version'] == 'omniflow.canonical_run_log.v1';
}

Map<String, dynamic> _runLogDiagnostics(Map<String, dynamic> payload) =>
    _asStringKeyMap(payload['diagnostics']);

Map<String, dynamic> _functionEnhancementDiagnostics(
  Map<String, dynamic> payload,
) => _asStringKeyMap(_runLogDiagnostics(payload)['function_enhancement']);

Map<String, dynamic> _functionStepAsRunLogStep(
  Map<String, dynamic> step,
  int fallbackIndex,
) {
  final action = _asStringKeyMap(step['action']);
  final sourceStateId = step['source_state_id']?.toString().trim() ?? '';
  return <String, dynamic>{
    'step_index': _asInt(step['step_index']) ?? fallbackIndex,
    'before_state_id': sourceStateId,
    'action': <String, dynamic>{
      'tool': action['tool']?.toString().trim() ?? '',
      'args': _asStringKeyMap(action['args']),
    },
    'result': const <String, dynamic>{'success': true},
    'after_state_id': sourceStateId,
    'metadata': <String, dynamic>{
      'step_id': 'function-step-$fallbackIndex',
      'status': 'succeeded',
      'summary': action['tool']?.toString().trim() ?? '',
      'source': 'function',
    },
  };
}

Map<String, dynamic> _asStringKeyMap(dynamic value) {
  if (value is! Map) {
    return const <String, dynamic>{};
  }
  return value.map((key, item) => MapEntry(key.toString(), item));
}

List<Map<String, dynamic>> _asStringKeyMapList(dynamic value) {
  if (value is! List) {
    return const <Map<String, dynamic>>[];
  }
  return value
      .map(_asStringKeyMap)
      .where((item) => item.isNotEmpty)
      .toList(growable: false);
}

Map<String, dynamic> _firstMap(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    final map = _asStringKeyMap(source[key]);
    if (map.isNotEmpty) {
      return map;
    }
  }
  return const <String, dynamic>{};
}

dynamic _firstPresentValue(Map<String, dynamic> source, List<String> keys) {
  for (final key in keys) {
    if (source.containsKey(key) && source[key] != null) {
      return source[key];
    }
  }
  return null;
}

String _firstNonBlank(List<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
}

bool _timelineStepMatchesId(Map<String, dynamic> step, String targetId) {
  final normalizedTarget = targetId.trim().toLowerCase();
  if (normalizedTarget.isEmpty) {
    return false;
  }
  final stepIndex = _asInt(step['step_index']);
  final candidates = <dynamic>[
    if (stepIndex != null) stepIndex.toString(),
    if (stepIndex != null) 'step_$stepIndex',
    if (stepIndex != null) 'step_${stepIndex + 1}',
  ];
  for (final candidate in candidates) {
    if ((candidate?.toString().trim().toLowerCase() ?? '') ==
        normalizedTarget) {
      return true;
    }
  }
  return false;
}

String _stateScreenshotPath(Map<String, dynamic> state) {
  final rawPath = state['screenshot_path']?.toString().trim() ?? '';
  if (rawPath.isEmpty) {
    return '';
  }
  if (rawPath.startsWith('file://')) {
    try {
      return Uri.parse(rawPath).toFilePath();
    } catch (_) {
      return rawPath;
    }
  }
  return rawPath;
}

bool? _asBool(dynamic value) {
  if (value is bool) {
    return value;
  }
  final text = value?.toString().trim().toLowerCase();
  if (text == 'true') {
    return true;
  }
  if (text == 'false') {
    return false;
  }
  return null;
}

int? _asInt(dynamic value) {
  if (value is int) {
    return value;
  }
  if (value is num) {
    return value.toInt();
  }
  return int.tryParse(value?.toString().trim() ?? '');
}

dynamic _decodeJsonIfNeeded(dynamic value) {
  if (value is! String) {
    return value;
  }
  final trimmed = value.trim();
  if (trimmed.isEmpty) {
    return value;
  }
  final startsLikeJson = trimmed.startsWith('{') || trimmed.startsWith('[');
  if (!startsLikeJson) {
    return value;
  }
  try {
    return jsonDecode(trimmed);
  } catch (_) {
    return value;
  }
}

bool _isEmptyJsonValue(dynamic value) {
  if (value == null) {
    return true;
  }
  if (value is String) {
    return value.trim().isEmpty;
  }
  if (value is Map || value is Iterable) {
    return value.isEmpty;
  }
  return false;
}

String _prettyJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(_jsonSafe(value));
  } catch (_) {
    return value?.toString() ?? '';
  }
}

String _prettyUserJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(_userVisibleJson(value));
  } catch (_) {
    return _userVisibleString(value?.toString() ?? '');
  }
}

String _compactUserJson(dynamic value) {
  try {
    return jsonEncode(_userVisibleJson(value));
  } catch (_) {
    return _userVisibleString(value?.toString() ?? '');
  }
}

dynamic _userVisibleJson(dynamic value) {
  final safe = _jsonSafe(value);
  if (safe is String) {
    return _userVisibleString(safe);
  }
  if (safe == null || safe is num || safe is bool) {
    return safe;
  }
  if (safe is Map) {
    return safe.map(
      (key, item) =>
          MapEntry(_userVisibleJsonKey(key.toString()), _userVisibleJson(item)),
    );
  }
  if (safe is Iterable) {
    return safe.map(_userVisibleJson).toList(growable: false);
  }
  return _userVisibleString(safe.toString());
}

String _userVisibleJsonKey(String key) => _userVisibleString(key);

String _userVisibleString(String value) {
  return value
      .replaceAll(RegExp(r'RunLog', caseSensitive: false), 'execution_record')
      .replaceAll(RegExp(r'OmniFlow', caseSensitive: false), 'function')
      .replaceAll(RegExp(r'\bVLM\b', caseSensitive: false), 'automatic')
      .replaceAll(RegExp('compile', caseSensitive: false), 'execution')
      .replaceAll('编译', '执行')
      .replaceAll('运行日志', '执行记录')
      .replaceAll(RegExp(r'参考\s*function', caseSensitive: false), '参考复用指令')
      .replaceAll(
        RegExp(r'reusable[_\s-]*function', caseSensitive: false),
        'function',
      )
      .replaceAll('函数', '复用指令');
}

dynamic _jsonSafe(dynamic value) {
  final decoded = _decodeJsonIfNeeded(value);
  if (decoded == null ||
      decoded is String ||
      decoded is num ||
      decoded is bool) {
    return decoded;
  }
  if (decoded is Map) {
    return decoded.map(
      (key, item) => MapEntry(key.toString(), _jsonSafe(item)),
    );
  }
  if (decoded is Iterable) {
    return decoded.map(_jsonSafe).toList(growable: false);
  }
  return decoded.toString();
}
