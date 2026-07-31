import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_detail_sheet.dart';
import 'package:ui/features/task/run_log/run_log_metrics.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/services/omni_plugin_service.dart';

class OmniFlowExecutionCenterPage extends StatefulWidget {
  const OmniFlowExecutionCenterPage({super.key});

  @override
  State<OmniFlowExecutionCenterPage> createState() =>
      _OmniFlowExecutionCenterPageState();
}

class _OmniFlowExecutionCenterPageState
    extends State<OmniFlowExecutionCenterPage> {
  static const _pluginId = 'com.omnimind.omni-vlm-lite';

  OmniPluginItem? _plugin;
  List<Map<String, dynamic>> _functions = const [];
  List<Map<String, dynamic>> _runLogs = const [];
  bool _loading = true;
  String? _error;

  bool get _ready => _plugin?.installed == true && _plugin?.enabled == true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    if (mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final plugin = await OmniPluginService.getPlugin(_pluginId);
      if (plugin?.installed != true || plugin?.enabled != true) {
        if (!mounted) return;
        setState(() {
          _plugin = plugin;
          _functions = const [];
          _runLogs = const [];
          _loading = false;
        });
        return;
      }
      final results = await Future.wait([
        OmniFlowToolClient.listFunctions(),
        OmniFlowToolClient.listRunLogs(),
      ]);
      if (!mounted) return;
      setState(() {
        _plugin = plugin;
        _functions = _mapList(results[0]['functions']);
        _runLogs = _mapList(results[1]['runs']);
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

  Future<void> _enablePlugin() async {
    setState(() => _loading = true);
    try {
      await OmniPluginService.setEnabled(_pluginId, true);
      await _load();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  Future<void> _showFunctionDetails(Map<String, dynamic> function) {
    return showModalBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.28),
      builder: (_) => FunctionDetailSheet(
        initialFunction: function,
        loadFunction: OmniFlowToolClient.getFunction,
        onReplay: _replay,
        onDelete: _deleteFunction,
      ),
    );
  }

  Future<void> _replay(Map<String, dynamic> function) async {
    final functionId = _string(function['function_id']);
    if (functionId.isEmpty) return;
    final arguments = await _collectArguments(function);
    if (arguments == null || !mounted) return;
    await _runAction(
      () => OmniFlowToolClient.replayFunction(functionId, arguments),
      success: _text(context, '执行已完成', 'Run completed'),
    );
  }

  Future<Map<String, dynamic>?> _collectArguments(
    Map<String, dynamic> function,
  ) async {
    final inputSchema = _map(function['input_schema']);
    final properties = _map(inputSchema['properties']);
    if (properties.isEmpty) return <String, dynamic>{};
    final requiredValue = inputSchema['required'];
    final required = requiredValue is List
        ? requiredValue.map((value) => value.toString()).toSet()
        : <String>{};
    final values = <String, String>{};
    final result = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text(context, '填写执行参数', 'Run arguments')),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: properties.entries
                .map((entry) {
                  final schema = _map(entry.value);
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: TextFormField(
                      onChanged: (value) => values[entry.key] = value,
                      decoration: InputDecoration(
                        labelText: required.contains(entry.key)
                            ? '${entry.key} *'
                            : entry.key,
                        helperText: _string(schema['description']).nullIfEmpty,
                      ),
                    ),
                  );
                })
                .toList(growable: false),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () {
              final parsedValues = <String, dynamic>{};
              for (final entry in properties.entries) {
                final value = (values[entry.key] ?? '').trim();
                if (required.contains(entry.key) && value.isEmpty) {
                  return;
                }
                if (value.isNotEmpty) {
                  parsedValues[entry.key] = _parseArgument(
                    value,
                    _string(_map(entry.value)['type']),
                  );
                }
              }
              Navigator.pop(dialogContext, parsedValues);
            },
            child: Text(_text(context, '开始执行', 'Run')),
          ),
        ],
      ),
    );
    return result;
  }

  Future<void> _deleteFunction(Map<String, dynamic> function) async {
    final functionId = _string(function['function_id']);
    if (functionId.isEmpty) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text(context, '删除复用指令', 'Delete Function')),
        content: Text(_text(context, '删除后无法继续执行。', 'This cannot be undone.')),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text(context, '取消', 'Cancel')),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text(context, '删除', 'Delete')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _runAction(
      () => OmniFlowToolClient.deleteFunction(functionId),
      success: _text(context, '复用指令已删除', 'Function deleted'),
      reload: true,
    );
  }

  Future<void> _convertRunLog(Map<String, dynamic> runLog) async {
    final runId = _string(runLog['run_id']);
    if (runId.isEmpty) return;
    await _runAction(
      () => OmniFlowToolClient.convertRunLog(runId),
      success: _text(context, '已注册为复用指令', 'Function registered'),
      reload: true,
    );
  }

  Future<void> _runAction(
    Future<Map<String, dynamic>> Function() action, {
    required String success,
    bool reload = false,
  }) async {
    try {
      final result = await action();
      if (!mounted) return;
      if (result['success'] == false) {
        throw StateError(
          _string(result['error_message']).nullIfEmpty ??
              _string(result['error_code']).nullIfEmpty ??
              _text(context, 'OmniFlow 操作失败', 'OmniFlow operation failed'),
        );
      }
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(success)));
      if (reload) await _load();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(error.toString())));
    }
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title: Text(_text(context, '执行中心', 'Execution Center')),
          actions: [
            IconButton(
              tooltip: _text(context, '刷新', 'Refresh'),
              onPressed: _loading ? null : _load,
              icon: const Icon(Icons.refresh_rounded),
            ),
          ],
          bottom: TabBar(
            tabs: [
              Tab(text: _text(context, '复用指令', 'Functions')),
              Tab(text: _text(context, '运行记录', 'Run Logs')),
            ],
          ),
        ),
        body: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return _MessageState(
        icon: Icons.error_outline_rounded,
        title: _text(context, '加载失败', 'Failed to load'),
        message: _error!,
        actionLabel: _text(context, '重试', 'Retry'),
        onAction: _load,
      );
    }
    if (!_ready) {
      final installed = _plugin?.installed == true;
      return _MessageState(
        icon: Icons.extension_outlined,
        title: installed
            ? _text(context, 'Omni VLM Lite 未启用', 'Omni VLM Lite is disabled')
            : _text(
                context,
                '先安装 Omni VLM Lite',
                'Install Omni VLM Lite first',
              ),
        message: installed
            ? _text(
                context,
                '启用后即可查看运行记录、注册和执行复用指令。',
                'Enable it to inspect Run Logs and register reusable Functions.',
              )
            : _text(
                context,
                '运行时会作为 Skill 下载，不会打入 APK。',
                'The runtime is installed as a Skill and is not bundled in the APK.',
              ),
        actionLabel: installed
            ? _text(context, '启用插件', 'Enable plugin')
            : _text(context, '前往插件市场', 'Open Plugin Market'),
        onAction: installed
            ? _enablePlugin
            : () => context.push('/home/plugin_market/$_pluginId'),
      );
    }
    return TabBarView(
      children: [
        _FunctionsTab(
          functions: _functions,
          onOpenDetails: _showFunctionDetails,
          onReplay: _replay,
          onDelete: _deleteFunction,
        ),
        _RunLogsTab(runLogs: _runLogs, onConvert: _convertRunLog),
      ],
    );
  }
}

class _FunctionsTab extends StatelessWidget {
  const _FunctionsTab({
    required this.functions,
    required this.onOpenDetails,
    required this.onReplay,
    required this.onDelete,
  });

  final List<Map<String, dynamic>> functions;
  final ValueChanged<Map<String, dynamic>> onOpenDetails;
  final ValueChanged<Map<String, dynamic>> onReplay;
  final ValueChanged<Map<String, dynamic>> onDelete;

  @override
  Widget build(BuildContext context) {
    if (functions.isEmpty) {
      return _EmptyTab(
        icon: Icons.replay_rounded,
        title: _text(context, '暂无复用指令', 'No Functions yet'),
        message: _text(
          context,
          '在运行记录中选择成功记录并注册。',
          'Register a successful execution from Run Logs.',
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: functions.length,
      separatorBuilder: (_, __) => const SizedBox(height: 12),
      itemBuilder: (context, index) {
        final function = functions[index];
        final name =
            _string(function['name']).nullIfEmpty ??
            _string(function['function_id']);
        return Card(
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: () => onOpenDetails(function),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          name,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      Icon(
                        Icons.chevron_right_rounded,
                        color: Theme.of(context).colorScheme.outline,
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    _string(function['description']).nullIfEmpty ??
                        _string(function['function_id']),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      FilledButton.icon(
                        onPressed: () => onReplay(function),
                        icon: const Icon(Icons.play_arrow_rounded),
                        label: Text(_text(context, '执行', 'Run')),
                      ),
                      TextButton.icon(
                        onPressed: () => onDelete(function),
                        icon: const Icon(Icons.delete_outline_rounded),
                        label: Text(_text(context, '删除', 'Delete')),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _RunLogsTab extends StatelessWidget {
  const _RunLogsTab({required this.runLogs, required this.onConvert});

  final List<Map<String, dynamic>> runLogs;
  final ValueChanged<Map<String, dynamic>> onConvert;

  @override
  Widget build(BuildContext context) {
    if (runLogs.isEmpty) {
      return _EmptyTab(
        icon: Icons.receipt_long_outlined,
        title: _text(context, '暂无运行记录', 'No Run Logs yet'),
        message: _text(
          context,
          '在线 VLM 执行完成后会保存规范运行记录。',
          'Online VLM executions save canonical run logs.',
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.all(16),
      itemCount: runLogs.length,
      separatorBuilder: (_, __) => const SizedBox(height: 12),
      itemBuilder: (context, index) {
        final runLog = runLogs[index];
        final metrics = RunLogMetrics.fromPayload(runLog);
        final runId = _string(runLog['run_id']);
        final status = _string(runLog['status']).nullIfEmpty ?? 'unknown';
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _string(runLog['goal']).nullIfEmpty ?? runId,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    Chip(label: Text(_runStatusLabel(context, status))),
                  ],
                ),
                Text(runId, style: Theme.of(context).textTheme.bodySmall),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    if (metrics.startedAt != null)
                      _RunLogMetricChip(
                        icon: Icons.schedule_rounded,
                        label: formatRunLogTimestamp(metrics.startedAt!),
                      ),
                    if (metrics.durationMs != null)
                      _RunLogMetricChip(
                        icon: Icons.timer_outlined,
                        label: formatRunLogDuration(metrics.durationMs!),
                      ),
                    if (metrics.tokenUsage.totalTokens != null)
                      _RunLogMetricChip(
                        icon: Icons.data_usage_rounded,
                        label: _text(
                          context,
                          '模型用量 ${formatRunLogTokens(metrics.tokenUsage.totalTokens!)}',
                          '${formatRunLogTokens(metrics.tokenUsage.totalTokens!)} tokens',
                        ),
                      )
                    else
                      _RunLogMetricChip(
                        icon: Icons.data_usage_rounded,
                        label: _text(
                          context,
                          '模型用量未提供',
                          'Token usage unavailable',
                        ),
                      ),
                    if (metrics.model != null)
                      _RunLogMetricChip(
                        icon: Icons.smart_toy_outlined,
                        label: metrics.model!,
                      ),
                    if (metrics.callCount != null)
                      _RunLogMetricChip(
                        icon: Icons.repeat_rounded,
                        label: _text(
                          context,
                          '${metrics.callCount} 次 VLM 调用',
                          '${metrics.callCount} VLM calls',
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    FilledButton.tonalIcon(
                      onPressed: () => context.push('/task/run_log/$runId'),
                      icon: const Icon(Icons.timeline_rounded),
                      label: Text(_text(context, '查看运行记录', 'View Run Log')),
                    ),
                    OutlinedButton.icon(
                      onPressed: () => onConvert(runLog),
                      icon: const Icon(Icons.add_task_rounded),
                      label: Text(
                        _text(context, '注册为复用指令', 'Register Function'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _RunLogMetricChip extends StatelessWidget {
  const _RunLogMetricChip({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14),
          const SizedBox(width: 5),
          Text(label, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }
}

class _EmptyTab extends StatelessWidget {
  const _EmptyTab({
    required this.icon,
    required this.title,
    required this.message,
  });

  final IconData icon;
  final String title;
  final String message;

  @override
  Widget build(BuildContext context) =>
      _MessageState(icon: icon, title: title, message: message);
}

class _MessageState extends StatelessWidget {
  const _MessageState({
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 48, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 16),
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(message, textAlign: TextAlign.center),
            if (onAction != null && actionLabel != null) ...[
              const SizedBox(height: 20),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
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

String _string(dynamic value) => value?.toString().trim() ?? '';

String _runStatusLabel(BuildContext context, String status) => switch (status
    .toLowerCase()) {
  'success' || 'succeeded' || 'completed' => _text(context, '成功', 'Succeeded'),
  'running' || 'pending' => _text(context, '执行中', 'Running'),
  'failed' || 'error' => _text(context, '失败', 'Failed'),
  'cancelled' || 'canceled' => _text(context, '已取消', 'Cancelled'),
  _ => _text(context, '未知', 'Unknown'),
};

dynamic _parseArgument(String value, String type) => switch (type) {
  'integer' => int.tryParse(value) ?? value,
  'number' => num.tryParse(value) ?? value,
  'boolean' => value.toLowerCase() == 'true',
  'object' || 'array' => jsonDecode(value),
  _ => value,
};

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
