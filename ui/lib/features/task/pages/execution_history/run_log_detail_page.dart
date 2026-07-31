import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import 'package:ui/features/task/run_log/run_log_metrics.dart';

class RunLogDetailPage extends StatefulWidget {
  const RunLogDetailPage({super.key, required this.runId});

  final String runId;

  @override
  State<RunLogDetailPage> createState() => _RunLogDetailPageState();
}

class _RunLogDetailPageState extends State<RunLogDetailPage> {
  Map<String, dynamic>? _runLog;
  bool _loading = true;
  bool _converting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final runLog = await OmniFlowToolClient.getRunLog(widget.runId);
      if (!mounted) return;
      setState(() {
        _runLog = runLog;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  Future<void> _convert() async {
    if (_converting) return;
    setState(() => _converting = true);
    try {
      final result = await OmniFlowToolClient.convertRunLog(widget.runId);
      if (!mounted) return;
      final success = result['success'] != false;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            success
                ? _text(context, '已注册为复用指令', 'Function registered')
                : (result['error_message']?.toString() ?? 'Conversion failed'),
          ),
        ),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => _converting = false);
    }
  }

  Future<void> _showState(String stateId) async {
    if (stateId.isEmpty) return;
    try {
      final state = await OmniFlowToolClient.getRunLogState(stateId);
      if (!mounted) return;
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        builder: (context) => _StateSheet(state: state),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('RunLog'),
        actions: [
          IconButton(
            tooltip: _text(context, '刷新', 'Refresh'),
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      floatingActionButton: _runLog == null
          ? null
          : FloatingActionButton.extended(
              onPressed: _converting ? null : _convert,
              icon: _converting
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.add_task_rounded),
              label: Text(_text(context, '注册为复用指令', 'Register Function')),
            ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: TextButton.icon(
          onPressed: _load,
          icon: const Icon(Icons.refresh_rounded),
          label: Text(_error!),
        ),
      );
    }
    final runLog = _runLog;
    if (runLog == null) return const SizedBox.shrink();
    final steps = _mapList(runLog['steps']);
    final metrics = RunLogMetrics.fromPayload(runLog);
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
      children: [
        Text(
          runLog['goal']?.toString() ?? widget.runId,
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const SizedBox(height: 4),
        SelectableText(widget.runId),
        const SizedBox(height: 16),
        _RunLogSummaryCard(metrics: metrics),
        const SizedBox(height: 16),
        if (steps.isEmpty)
          Text(_text(context, '没有可显示的步骤', 'No steps to display'))
        else
          for (final step in steps)
            _buildStep(step, runLogStepTokenUsage(runLog, step)),
      ],
    );
  }

  Widget _buildStep(Map<String, dynamic> step, RunLogTokenUsage? tokenUsage) {
    final result = _map(step['result']);
    final succeeded = result['success'] == true;
    final beforeStateId = step['before_state_id']?.toString() ?? '';
    final afterStateId = step['after_state_id']?.toString() ?? '';
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                CircleAvatar(
                  radius: 14,
                  child: Text('${step['step_index'] ?? '?'}'),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    _actionLabel(step['action']),
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                Icon(
                  succeeded ? Icons.check_circle_rounded : Icons.error_rounded,
                  color: succeeded ? Colors.green : Colors.red,
                ),
              ],
            ),
            const SizedBox(height: 10),
            SelectableText(
              const JsonEncoder.withIndent('  ').convert(step['action']),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (tokenUsage?.totalTokens != null) ...[
              const SizedBox(height: 10),
              Text(
                'Step Token ${formatRunLogStepTokens(tokenUsage!)}',
                style: Theme.of(context).textTheme.labelMedium,
              ),
            ],
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton(
                  onPressed: beforeStateId.isEmpty
                      ? null
                      : () => _showState(beforeStateId),
                  child: Text(_text(context, '前置状态', 'Before state')),
                ),
                OutlinedButton(
                  onPressed: afterStateId.isEmpty
                      ? null
                      : () => _showState(afterStateId),
                  child: Text(_text(context, '后置状态', 'After state')),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _RunLogSummaryCard extends StatelessWidget {
  const _RunLogSummaryCard({required this.metrics});

  final RunLogMetrics metrics;

  @override
  Widget build(BuildContext context) {
    final usage = metrics.tokenUsage;
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _text(context, '运行概览', 'Run summary'),
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 20,
              runSpacing: 12,
              children: [
                if (metrics.startedAt != null)
                  _RunLogMetric(
                    label: _text(context, '开始时间', 'Started'),
                    value: formatRunLogTimestamp(metrics.startedAt!),
                  ),
                if (metrics.durationMs != null)
                  _RunLogMetric(
                    label: _text(context, '耗时', 'Duration'),
                    value: formatRunLogDuration(metrics.durationMs!),
                  ),
                if (metrics.model != null)
                  _RunLogMetric(
                    label: _text(context, '模型', 'Model'),
                    value: metrics.model!,
                  ),
                if (metrics.callCount != null)
                  _RunLogMetric(
                    label: _text(context, 'VLM 调用', 'VLM calls'),
                    value: '${metrics.callCount}',
                  ),
              ],
            ),
            const Divider(height: 28),
            Text(
              _text(context, 'Token 消耗', 'Token usage'),
              style: Theme.of(context).textTheme.labelLarge,
            ),
            const SizedBox(height: 10),
            if (!usage.hasUsage)
              Text(
                _text(
                  context,
                  '当前模型服务未提供 Token 统计',
                  'The model provider did not report token usage',
                ),
                style: Theme.of(context).textTheme.bodySmall,
              )
            else
              Wrap(
                spacing: 20,
                runSpacing: 12,
                children: [
                  if (usage.promptTokens != null)
                    _RunLogMetric(
                      label: _text(context, '输入', 'Prompt'),
                      value: formatRunLogTokens(usage.promptTokens!),
                    ),
                  if (usage.completionTokens != null)
                    _RunLogMetric(
                      label: _text(context, '输出', 'Completion'),
                      value: formatRunLogTokens(usage.completionTokens!),
                    ),
                  if (usage.totalTokens != null)
                    _RunLogMetric(
                      label: _text(context, '总计', 'Total'),
                      value: formatRunLogTokens(usage.totalTokens!),
                    ),
                  if (usage.cachedTokens != null)
                    _RunLogMetric(
                      label: _text(context, '缓存', 'Cached'),
                      value: formatRunLogTokens(usage.cachedTokens!),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}

class _RunLogMetric extends StatelessWidget {
  const _RunLogMetric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 2),
        Text(
          value,
          style: Theme.of(
            context,
          ).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
        ),
      ],
    );
  }
}

class _StateSheet extends StatelessWidget {
  const _StateSheet({required this.state});

  final Map<String, dynamic> state;

  @override
  Widget build(BuildContext context) {
    final screenshotPath = state['screenshot_path']?.toString() ?? '';
    final screenshot = File(screenshotPath);
    return SafeArea(
      child: DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.85,
        minChildSize: 0.4,
        maxChildSize: 0.95,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.all(16),
          children: [
            Text(
              state['state_id']?.toString() ?? 'State',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 12),
            if (screenshotPath.isNotEmpty && screenshot.existsSync())
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.file(screenshot),
              )
            else
              Text(_text(context, '状态截图不可用', 'State screenshot unavailable')),
            const SizedBox(height: 16),
            Text(
              '${state['package_name'] ?? ''} ${state['activity_name'] ?? ''}',
            ),
            const SizedBox(height: 12),
            SelectableText(state['xml']?.toString() ?? ''),
          ],
        ),
      ),
    );
  }
}

List<Map<String, dynamic>> _mapList(dynamic value) => value is List
    ? value.whereType<Map>().map(_map).toList(growable: false)
    : const [];

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : <String, dynamic>{};

String _actionLabel(dynamic value) {
  final action = _map(value);
  return action['tool']?.toString() ??
      action['type']?.toString() ??
      action['action_type']?.toString() ??
      'action';
}

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;
