import 'dart:async';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/features/task/run_log/run_log_function_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class RunLogListPage extends StatefulWidget {
  const RunLogListPage({super.key, this.baseUrl});

  final String? baseUrl;

  @override
  State<RunLogListPage> createState() => _RunLogListPageState();
}

class _RunLogListPageState extends State<RunLogListPage> {
  static const int _pageSize = 20;

  List<UtgRunLogSummary> _runs = const [];
  List<String> _availableModels = const [];
  bool _isLoading = true;
  bool _isLoadingMore = false;
  bool _hasMore = false;
  int _nextOffset = 0;
  String? _error;
  final TextEditingController _searchController = TextEditingController();
  Timer? _searchDebounce;
  String _sourceFilter = '';
  String _statusFilter = '';
  String _modelFilter = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String _) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 350), _load);
  }

  Future<void> _load() async {
    setState(() {
      _isLoading = true;
      _isLoadingMore = false;
      _error = null;
    });
    try {
      final snapshot = await RunLogFunctionService.getInternalRunLogs(
        limit: _pageSize,
        offset: 0,
        source: _sourceFilter,
        status: _statusFilter,
        model: _modelFilter,
        query: _searchController.text,
      );
      if (!mounted) return;
      setState(() {
        _runs = snapshot.runs;
        _availableModels = snapshot.availableModels;
        _hasMore = snapshot.hasMore;
        _nextOffset = snapshot.nextOffset > 0
            ? snapshot.nextOffset
            : snapshot.runs.length;
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
    setState(() {
      _isLoadingMore = true;
      _error = null;
    });
    try {
      final snapshot = await RunLogFunctionService.getInternalRunLogs(
        limit: _pageSize,
        offset: _nextOffset,
        source: _sourceFilter,
        status: _statusFilter,
        model: _modelFilter,
        query: _searchController.text,
      );
      if (!mounted) return;
      setState(() {
        _runs = [..._runs, ...snapshot.runs];
        _hasMore = snapshot.hasMore;
        _nextOffset = snapshot.nextOffset > 0
            ? snapshot.nextOffset
            : _runs.length;
        _isLoadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoadingMore = false;
      });
      showToast(e.toString(), type: ToastType.error);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: l10n.executionHistoryTitle,
        primary: true,
        actions: [
          Tooltip(
            message: l10n.localModelsRefresh,
            child: IconButton(
              icon: const Icon(Icons.refresh_rounded),
              color: palette.textPrimary,
              onPressed: _isLoading ? null : _load,
            ),
          ),
        ],
      ),
      body: SafeArea(top: false, child: _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    final l10n = context.l10n;
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error != null) {
      return _RunLogEmptyState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载执行记录失败', 'Failed to load execution records'),
        subtitle: _error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _load,
      );
    }

    final runs = _runs;
    return Column(
      children: [
        _buildFilters(context),
        Expanded(
          child: runs.isEmpty
              ? _RunLogEmptyState(
                  icon: Icons.route_outlined,
                  title: _text(context, '没有匹配的执行记录', 'No matching runs'),
                  subtitle: _text(
                    context,
                    '调整搜索或筛选条件后重试。',
                    'Try changing the search or filters.',
                  ),
                  actionLabel: _text(context, '清除筛选', 'Clear filters'),
                  onAction: _clearFilters,
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.separated(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                    itemCount:
                        runs.length + (_hasMore || _isLoadingMore ? 1 : 0),
                    separatorBuilder: (_, __) => const SizedBox(height: 10),
                    itemBuilder: (context, index) {
                      if (index >= runs.length) {
                        WidgetsBinding.instance.addPostFrameCallback(
                          (_) => _loadMore(),
                        );
                        return const _RunLogListFooter();
                      }
                      return _RunLogListItem(
                        run: runs[index],
                        onTap: () => _openRunLog(runs[index]),
                      );
                    },
                  ),
                ),
        ),
      ],
    );
  }

  Widget _buildFilters(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 6),
      child: Column(
        children: [
          TextField(
            controller: _searchController,
            onChanged: _onSearchChanged,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _load(),
            decoration: InputDecoration(
              hintText: _text(
                context,
                '搜索任务、Run ID 或模型',
                'Search task, run ID, or model',
              ),
              prefixIcon: const Icon(Icons.search_rounded),
              suffixIcon: _searchController.text.isEmpty
                  ? null
                  : IconButton(
                      onPressed: () {
                        _searchController.clear();
                        _load();
                      },
                      icon: const Icon(Icons.close_rounded),
                    ),
              filled: true,
              fillColor: palette.surfacePrimary,
              isDense: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide(color: palette.borderSubtle),
              ),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
                borderSide: BorderSide(color: palette.borderSubtle),
              ),
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: _RunLogFilterDropdown(
                  value: _sourceFilter,
                  items: {
                    '': _text(context, '全部来源', 'All sources'),
                    'vlm': 'VLM',
                    'manual': _text(context, '手动录制', 'Manual'),
                  },
                  onChanged: (value) {
                    setState(() => _sourceFilter = value);
                    _load();
                  },
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _RunLogFilterDropdown(
                  value: _statusFilter,
                  items: {
                    '': _text(context, '全部状态', 'All statuses'),
                    'running': _text(context, '运行中', 'Running'),
                    'succeeded': _text(context, '成功', 'Succeeded'),
                    'failed': _text(context, '失败', 'Failed'),
                  },
                  onChanged: (value) {
                    setState(() => _statusFilter = value);
                    _load();
                  },
                ),
              ),
            ],
          ),
          if (_availableModels.isNotEmpty) ...[
            const SizedBox(height: 8),
            _RunLogFilterDropdown(
              value: _availableModels.contains(_modelFilter)
                  ? _modelFilter
                  : '',
              items: {
                '': _text(context, '全部模型', 'All models'),
                for (final model in _availableModels) model: model,
              },
              onChanged: (value) {
                setState(() => _modelFilter = value);
                _load();
              },
            ),
          ],
        ],
      ),
    );
  }

  void _clearFilters() {
    _searchDebounce?.cancel();
    _searchController.clear();
    setState(() {
      _sourceFilter = '';
      _statusFilter = '';
      _modelFilter = '';
    });
    _load();
  }

  void _openRunLog(UtgRunLogSummary run) {
    if (run.runId.trim().isEmpty) {
      showToast(
        _text(context, 'Run ID 为空，无法打开', 'Missing run ID'),
        type: ToastType.warning,
      );
      return;
    }
    GoRouterManager.push(
      '/task/run_log_timeline',
      extra: {
        'runId': run.runId,
        'title': _titleForRun(context, run),
        if (widget.baseUrl != null && widget.baseUrl!.trim().isNotEmpty)
          'baseUrl': widget.baseUrl!.trim(),
      },
    );
  }
}

class _RunLogListFooter extends StatelessWidget {
  const _RunLogListFooter();

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

class _RunLogFilterDropdown extends StatelessWidget {
  const _RunLogFilterDropdown({
    required this.value,
    required this.items,
    required this.onChanged,
  });

  final String value;
  final Map<String, String> items;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return DropdownButtonFormField<String>(
      initialValue: value,
      isExpanded: true,
      isDense: true,
      decoration: InputDecoration(
        filled: true,
        fillColor: palette.surfacePrimary,
        contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(9),
          borderSide: BorderSide(color: palette.borderSubtle),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(9),
          borderSide: BorderSide(color: palette.borderSubtle),
        ),
      ),
      items: items.entries
          .map(
            (entry) => DropdownMenuItem<String>(
              value: entry.key,
              child: Text(
                entry.value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          )
          .toList(growable: false),
      onChanged: (next) {
        if (next != null && next != value) onChanged(next);
      },
    );
  }
}

class _RunLogListItem extends StatelessWidget {
  const _RunLogListItem({required this.run, required this.onTap});

  final UtgRunLogSummary run;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = context.l10n;
    final title = _titleForRun(context, run);
    final statusInfo = _runLogStatusInfo(context, run);
    final meta = [
      if (_runSourceLabel(context, run.source).isNotEmpty)
        _runSourceLabel(context, run.source),
      if (run.stepCount > 0) l10n.runLogTimelineStepCount(run.stepCount),
      if (_formatDuration(run.durationMs).isNotEmpty)
        _formatDuration(run.durationMs),
      if (run.tokenUsageTotal != null) _formatTokenUsage(run.tokenUsageTotal!),
      if (run.modelNames.isNotEmpty) run.modelNames.join(', '),
    ].join(' · ');
    final detail = _firstNonBlank([run.error, run.doneReason, run.toolName]);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: onTap,
        child: Ink(
          decoration: BoxDecoration(
            color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 30,
                      height: 30,
                      decoration: BoxDecoration(
                        color: statusInfo.color.withValues(
                          alpha: context.isDarkTheme ? 0.18 : 0.10,
                        ),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Icon(
                        statusInfo.icon,
                        size: 17,
                        color: statusInfo.color,
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    _RunLogStatusChip(info: statusInfo),
                  ],
                ),
                const SizedBox(height: 8),
                if (meta.isNotEmpty)
                  Text(
                    meta,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                if (detail.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    detail,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: statusInfo.kind == _RunLogStatusKind.failed
                          ? _errorColor(context)
                          : palette.textSecondary,
                      fontSize: 12,
                      fontWeight: statusInfo.kind == _RunLogStatusKind.failed
                          ? FontWeight.w600
                          : FontWeight.w400,
                    ),
                  ),
                ],
                if (run.runId.trim().isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text(
                    run.runId.trim(),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 11,
                      fontFamily: 'monospace',
                    ),
                  ),
                ],
                if (_timeLabel(run).isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          _timeLabel(run),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: palette.textTertiary,
                            fontSize: 11,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Icon(
                        Icons.chevron_right_rounded,
                        color: palette.textTertiary,
                        size: 18,
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RunLogStatusChip extends StatelessWidget {
  const _RunLogStatusChip({required this.info});

  final _RunLogStatusInfo info;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: info.color.withValues(alpha: context.isDarkTheme ? 0.16 : 0.09),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(info.icon, size: 13, color: info.color),
          const SizedBox(width: 4),
          Text(
            info.label,
            style: TextStyle(
              color: palette.textPrimary,
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: 0,
            ),
          ),
        ],
      ),
    );
  }
}

class _RunLogEmptyState extends StatelessWidget {
  const _RunLogEmptyState({
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
        padding: const EdgeInsets.symmetric(horizontal: 28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 34, color: palette.textTertiary),
            const SizedBox(height: 12),
            Text(
              title,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 16,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
                height: 1.35,
              ),
            ),
            const SizedBox(height: 16),
            TextButton.icon(
              onPressed: onAction,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: Text(actionLabel),
            ),
          ],
        ),
      ),
    );
  }
}

String _titleForRun(BuildContext context, UtgRunLogSummary run) {
  final title = _firstNonBlank([run.goal, run.runId]);
  return title.isEmpty ? context.l10n.runLogTimelineUnknown : title;
}

String _timeLabel(UtgRunLogSummary run) {
  final started = run.startedAtMs == null
      ? null
      : DateTime.fromMillisecondsSinceEpoch(run.startedAtMs!).toLocal();
  final finished = run.finishedAtMs == null
      ? null
      : DateTime.fromMillisecondsSinceEpoch(run.finishedAtMs!).toLocal();
  if (started == null && finished == null) {
    return '';
  }
  final formatter = DateFormat('yyyy/MM/dd HH:mm:ss');
  if (started != null && finished != null) {
    return '${formatter.format(started)} - ${formatter.format(finished)}';
  }
  return formatter.format(started ?? finished!);
}

String _formatDuration(num? durationMs) {
  if (durationMs == null || durationMs <= 0) return '';
  if (durationMs < 1000) {
    return '${durationMs.round()}ms';
  }
  final seconds = durationMs / 1000;
  if (seconds < 60) {
    return '${seconds.toStringAsFixed(seconds >= 10 ? 0 : 1)}s';
  }
  final minutes = seconds / 60;
  return '${minutes.toStringAsFixed(minutes >= 10 ? 0 : 1)}min';
}

String _formatTokenUsage(int totalTokens) {
  if (totalTokens <= 0) return '';
  if (totalTokens >= 1000) {
    return '${(totalTokens / 1000).toStringAsFixed(totalTokens >= 10000 ? 1 : 2)}k tokens';
  }
  return '$totalTokens tokens';
}

String _runSourceLabel(BuildContext context, String source) {
  switch (source.trim().toLowerCase()) {
    case 'vlm':
      return 'VLM';
    case 'manual_recording':
    case 'human_trajectory':
    case 'human_takeover':
      return _text(context, '手动录制', 'Manual');
    case 'function':
    case 'omniflow_replay':
      return _text(context, '复用指令', 'Function');
    default:
      return source.trim();
  }
}

String _firstNonBlank(Iterable<Object?> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) {
      return text;
    }
  }
  return '';
}

String _text(BuildContext context, String zh, String en) {
  return AppTextLocalizer.choose(
    zh: zh,
    en: en,
    locale: Localizations.localeOf(context),
  );
}

Color _successColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFF63D98A)
      : const Color(0xFF19A974);
}

Color _errorColor(BuildContext context) {
  return context.isDarkTheme
      ? const Color(0xFFFF7A7A)
      : const Color(0xFFE14C4C);
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
    required this.color,
    required this.icon,
  });

  final _RunLogStatusKind kind;
  final String label;
  final Color color;
  final IconData icon;
}

_RunLogStatusInfo _runLogStatusInfo(
  BuildContext context,
  UtgRunLogSummary run,
) {
  final rawStatus = run.status.trim().toLowerCase();
  if (rawStatus == 'running') {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.running,
      label: _text(context, '运行中', 'Running'),
      color: _runningColor(context),
      icon: Icons.timelapse_rounded,
    );
  }
  if (rawStatus == 'succeeded' && run.success) {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.success,
      label: _text(context, '已完成', 'Done'),
      color: _successColor(context),
      icon: Icons.check_circle_outline_rounded,
    );
  }
  if (rawStatus == 'failed' ||
      rawStatus == 'cancelled' ||
      run.error.trim().isNotEmpty) {
    return _RunLogStatusInfo(
      kind: _RunLogStatusKind.failed,
      label: _text(context, '失败', 'Failed'),
      color: _errorColor(context),
      icon: Icons.error_outline_rounded,
    );
  }
  return _RunLogStatusInfo(
    kind: _RunLogStatusKind.unknown,
    label: _text(context, '未知', 'Unknown'),
    color: context.omniPalette.textTertiary,
    icon: Icons.help_outline_rounded,
  );
}
