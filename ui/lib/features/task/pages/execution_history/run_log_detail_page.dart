import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:ui/features/task/pages/execution_history/widgets/run_log_timeline_components.dart';
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

  Future<void> _showStepDetails(
    Map<String, dynamic> step,
    int fallbackIndex,
    RunLogTokenUsage? tokenUsage,
  ) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (_) => RunLogStepDetailSheet(
        step: step,
        fallbackIndex: fallbackIndex,
        tokenUsage: tokenUsage,
        onShowState: _showState,
      ),
    );
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
        RunLogOverviewPanel(
          payload: runLog,
          metrics: metrics,
          stepCount: steps.length,
        ),
        const SizedBox(height: 14),
        if (steps.isEmpty)
          Text(_text(context, '没有可显示的步骤', 'No steps to display'))
        else
          for (var index = 0; index < steps.length; index++)
            RunLogTimelineStepCard(
              step: steps[index],
              fallbackIndex: index,
              isLast: index == steps.length - 1,
              tokenUsage: runLogStepTokenUsage(runLog, steps[index]),
              onTap: () => _showStepDetails(
                steps[index],
                index,
                runLogStepTokenUsage(runLog, steps[index]),
              ),
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

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;
