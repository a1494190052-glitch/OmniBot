import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class FunctionRunLogPage extends StatefulWidget {
  const FunctionRunLogPage({super.key, this.initialTab = 0});

  final int initialTab;

  @override
  State<FunctionRunLogPage> createState() => _FunctionRunLogPageState();
}

class _FunctionRunLogPageState extends State<FunctionRunLogPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(
      length: 2,
      vsync: this,
      initialIndex: widget.initialTab.clamp(0, 1).toInt(),
    );
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: CommonAppBar(title: _t('复用', 'Functions'), primary: true),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            TabBar(
              controller: _tabController,
              tabs: [
                Tab(text: _t('复用指令', 'Functions')),
                Tab(text: _t('执行记录', 'RunLogs')),
              ],
            ),
            Expanded(
              child: TabBarView(
                controller: _tabController,
                children: const [_FunctionList(), _RunLogList()],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FunctionList extends StatefulWidget {
  const _FunctionList();

  @override
  State<_FunctionList> createState() => _FunctionListState();
}

class _FunctionListState extends State<_FunctionList> {
  bool _loading = true;
  String? _error;
  List<Map<String, dynamic>> _items = const [];
  final Set<String> _busy = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await AssistsMessageService.listFunctions(
        limit: 100,
        includeHidden: true,
      );
      if (!mounted) return;
      setState(() {
        _items = _list(result['functions']);
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.toString();
        _loading = false;
      });
    }
  }

  Future<void> _run(Map<String, dynamic> item) async {
    final functionId = _text(item['function_id']);
    if (functionId.isEmpty || _busy.contains(functionId)) return;
    setState(() => _busy.add(functionId));
    try {
      final spec = await AssistsMessageService.getFunction(functionId) ?? item;
      if (!mounted) return;
      final args = await _askArguments(context, spec);
      if (args == null) return;
      final result = await AssistsMessageService.runFunction(
        functionId: functionId,
        arguments: args,
        taskId: 'function-ui-${DateTime.now().millisecondsSinceEpoch}',
      );
      if (!mounted) return;
      showToast(
        result['success'] == false
            ? _first([
                result['error_message'],
                result['message'],
                _t('执行失败', 'Run failed'),
              ])
            : _t('执行完成', 'Run finished'),
        type: result['success'] == false ? ToastType.error : ToastType.success,
      );
      await _showJson(context, _t('执行结果', 'Run result'), result);
    } catch (error) {
      if (mounted) showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy.remove(functionId));
    }
  }

  Future<void> _delete(Map<String, dynamic> item) async {
    final functionId = _text(item['function_id']);
    if (functionId.isEmpty || _busy.contains(functionId)) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_t('删除复用指令', 'Delete Function')),
        content: Text(_functionTitle(item)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: Text(_t('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: Text(_t('删除', 'Delete')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busy.add(functionId));
    try {
      final result = await AssistsMessageService.deleteFunction(functionId);
      if (!mounted) return;
      if (result['deleted'] == true || result['success'] == true) {
        showToast(_t('已删除', 'Deleted'), type: ToastType.success);
        await _load();
      } else {
        showToast(
          _first([
            result['error_message'],
            result['message'],
            _t('删除失败', 'Delete failed'),
          ]),
          type: ToastType.error,
        );
      }
    } finally {
      if (mounted) setState(() => _busy.remove(functionId));
    }
  }

  Future<void> _enhance(Map<String, dynamic> item) async {
    final functionId = _text(item['function_id']);
    if (functionId.isEmpty || _busy.contains(functionId)) return;
    setState(() => _busy.add(functionId));
    try {
      final spec = await AssistsMessageService.getFunction(functionId) ?? item;
      final result = await AssistsMessageService.updateFunction(
        functionId: functionId,
        runId: _text(spec['source_run_id']).isEmpty
            ? null
            : _text(spec['source_run_id']),
        mode: 'enhance',
        autoAnalyzeWithModel: true,
        extraArgs: const {'offline_job': true},
      );
      if (!mounted) return;
      showToast(
        result['success'] == false
            ? _first([
                result['error_message'],
                result['message'],
                _t('增强失败', 'Enhance failed'),
              ])
            : _t('增强已保存', 'Enhancement saved'),
        type: result['success'] == false ? ToastType.error : ToastType.success,
      );
      await _load();
      if (mounted) {
        await _showJson(context, _t('增强结果', 'Enhance result'), result);
      }
    } catch (error) {
      if (mounted) showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy.remove(functionId));
    }
  }

  Future<void> _details(Map<String, dynamic> item) async {
    final functionId = _text(item['function_id']);
    final spec = functionId.isEmpty
        ? item
        : await AssistsMessageService.getFunction(functionId) ?? item;
    if (mounted) await _showJson(context, _functionTitle(spec), spec);
  }

  @override
  Widget build(BuildContext context) {
    return _SimpleAsyncList(
      loading: _loading,
      error: _error,
      emptyText: _t('暂无复用指令', 'No Functions yet'),
      onRefresh: _load,
      itemCount: _items.length,
      itemBuilder: (context, index) {
        final item = _items[index];
        final functionId = _text(item['function_id']);
        final busy = _busy.contains(functionId);
        return ListTile(
          leading: busy
              ? const SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.bolt_rounded),
          title: Text(
            _functionTitle(item),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(
            _functionSubtitle(item),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          onTap: busy ? null : () => _details(item),
          trailing: PopupMenuButton<String>(
            enabled: !busy,
            onSelected: (value) {
              if (value == 'run') _run(item);
              if (value == 'enhance') _enhance(item);
              if (value == 'details') _details(item);
              if (value == 'delete') _delete(item);
            },
            itemBuilder: (context) => [
              PopupMenuItem(value: 'run', child: Text(_t('执行', 'Run'))),
              PopupMenuItem(value: 'enhance', child: Text(_t('增强', 'Enhance'))),
              PopupMenuItem(value: 'details', child: Text(_t('详情', 'Details'))),
              PopupMenuItem(value: 'delete', child: Text(_t('删除', 'Delete'))),
            ],
          ),
        );
      },
    );
  }
}

class _RunLogList extends StatefulWidget {
  const _RunLogList();

  @override
  State<_RunLogList> createState() => _RunLogListState();
}

class _RunLogListState extends State<_RunLogList> {
  bool _loading = true;
  bool _recording = false;
  String? _error;
  List<Map<String, dynamic>> _items = const [];
  final Set<String> _busy = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await AssistsMessageService.getInternalRunLogs(limit: 100);
      if (!mounted) return;
      setState(() {
        _items = _list(result['runs']);
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = error.toString();
        _loading = false;
      });
    }
  }

  Future<void> _startManualRecording() async {
    if (_recording) return;
    final name = await _askText(
      context,
      title: _t('手动录制', 'Manual recording'),
      label: _t('指令名称', 'Function name'),
      initialValue: _t('人工学习轨迹', 'Manual trajectory'),
      confirmText: _t('开始', 'Start'),
    );
    if (name == null || !mounted) return;
    setState(() => _recording = true);
    try {
      final result = await AssistsMessageService.startHumanTrajectoryLearning(
        name: name,
        description: name,
      );
      if (!mounted) return;
      showToast(
        result['success'] == false
            ? _first([
                result['error_message'],
                result['message'],
                _t('录制失败', 'Recording failed'),
              ])
            : _t('录制已保存', 'Recording saved'),
        type: result['success'] == false ? ToastType.error : ToastType.success,
      );
      await _load();
      if (mounted) {
        await _showRunLogTimeline(
          context,
          _first([result['name'], result['run_id'], _t('录制结果', 'Recording')]),
          _map(result['run_log']).isEmpty ? result : _map(result['run_log']),
        );
      }
    } catch (error) {
      if (mounted) showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _recording = false);
    }
  }

  Future<void> _convert(Map<String, dynamic> item) async {
    final runId = _text(item['run_id']);
    if (runId.isEmpty || _busy.contains(runId)) return;
    setState(() => _busy.add(runId));
    try {
      final result =
          await AssistsMessageService.convertInternalRunLogToFunction(
            runId: runId,
            register: true,
            agentVisible: false,
          );
      if (!mounted) return;
      showToast(
        result['success'] == false
            ? _first([
                result['error_message'],
                result['message'],
                _t('保存失败', 'Save failed'),
              ])
            : _t('已保存为复用指令', 'Function saved'),
        type: result['success'] == false ? ToastType.error : ToastType.success,
      );
      await _showJson(context, _t('保存结果', 'Save result'), result);
    } catch (error) {
      if (mounted) showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy.remove(runId));
    }
  }

  Future<void> _details(Map<String, dynamic> item) async {
    final runId = _text(item['run_id']);
    if (runId.isEmpty || _busy.contains(runId)) return;
    setState(() => _busy.add(runId));
    try {
      final result = await AssistsMessageService.getInternalRunLogTimeline(
        runId: runId,
      );
      if (mounted) await _showRunLogTimeline(context, _runTitle(item), result);
    } catch (error) {
      if (mounted) showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy.remove(runId));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  _t('执行记录', 'RunLogs'),
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
              FilledButton.icon(
                onPressed: _recording ? null : _startManualRecording,
                icon: _recording
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.fiber_manual_record_rounded),
                label: Text(_t('手动录制', 'Record')),
              ),
            ],
          ),
        ),
        Expanded(
          child: _SimpleAsyncList(
            loading: _loading,
            error: _error,
            emptyText: _t('暂无执行记录', 'No RunLogs yet'),
            onRefresh: _load,
            itemCount: _items.length,
            itemBuilder: (context, index) {
              final item = _items[index];
              final runId = _text(item['run_id']);
              final busy = _busy.contains(runId);
              return ListTile(
                leading: busy
                    ? const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Icon(
                        item['success'] == true
                            ? Icons.check_circle_outline
                            : Icons.route_outlined,
                      ),
                title: Text(
                  _runTitle(item),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  _runSubtitle(item),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: busy ? null : () => _details(item),
                trailing: PopupMenuButton<String>(
                  enabled: !busy,
                  onSelected: (value) {
                    if (value == 'save') _convert(item);
                    if (value == 'details') _details(item);
                  },
                  itemBuilder: (context) => [
                    PopupMenuItem(
                      value: 'save',
                      child: Text(_t('保存为复用', 'Save Function')),
                    ),
                    PopupMenuItem(
                      value: 'details',
                      child: Text(_t('详情', 'Details')),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _SimpleAsyncList extends StatelessWidget {
  const _SimpleAsyncList({
    required this.loading,
    required this.error,
    required this.emptyText,
    required this.onRefresh,
    required this.itemCount,
    required this.itemBuilder,
  });

  final bool loading;
  final String? error;
  final String emptyText;
  final Future<void> Function() onRefresh;
  final int itemCount;
  final IndexedWidgetBuilder itemBuilder;

  @override
  Widget build(BuildContext context) {
    if (loading) return const Center(child: CircularProgressIndicator());
    if (error != null) return _CenterMessage(error!, action: onRefresh);
    if (itemCount == 0) return _CenterMessage(emptyText, action: onRefresh);
    return RefreshIndicator(
      onRefresh: onRefresh,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: itemCount,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: itemBuilder,
      ),
    );
  }
}

class _CenterMessage extends StatelessWidget {
  const _CenterMessage(this.text, {required this.action});

  final String text;
  final Future<void> Function() action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(text, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            OutlinedButton(onPressed: action, child: Text(_t('刷新', 'Refresh'))),
          ],
        ),
      ),
    );
  }
}

Future<Map<String, dynamic>?> _askArguments(
  BuildContext context,
  Map<String, dynamic> spec,
) async {
  final names = _argumentNames(spec);
  if (names.isEmpty) return <String, dynamic>{};
  final controllers = {for (final name in names) name: TextEditingController()};
  try {
    return showDialog<Map<String, dynamic>>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(_t('填写执行参数', 'Run arguments')),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final entry in controllers.entries) ...[
                TextField(
                  controller: entry.value,
                  decoration: InputDecoration(
                    labelText: entry.key,
                    border: const OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 10),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(null),
            child: Text(_t('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () {
              Navigator.of(context).pop({
                for (final entry in controllers.entries)
                  if (entry.value.text.trim().isNotEmpty)
                    entry.key: entry.value.text.trim(),
              });
            },
            child: Text(_t('执行', 'Run')),
          ),
        ],
      ),
    );
  } finally {
    for (final controller in controllers.values) {
      controller.dispose();
    }
  }
}

Future<void> _showJson(BuildContext context, String title, Object? value) {
  final text = const JsonEncoder.withIndent('  ').convert(value);
  return showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
      content: SizedBox(
        width: double.maxFinite,
        child: SingleChildScrollView(
          child: SelectableText(
            text,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () {
            Clipboard.setData(ClipboardData(text: text));
            showToast(_t('已复制', 'Copied'), type: ToastType.success);
          },
          child: Text(_t('复制', 'Copy')),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_t('关闭', 'Close')),
        ),
      ],
    ),
  );
}

Future<void> _showRunLogTimeline(
  BuildContext context,
  String title,
  Map<String, dynamic> payload,
) {
  final primaryCards = _list(payload['cards']);
  final cards = primaryCards.isEmpty ? _list(payload['steps']) : primaryCards;
  if (cards.isEmpty) return _showJson(context, title, payload);
  return showDialog<void>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
      content: SizedBox(
        width: double.maxFinite,
        child: ListView.separated(
          shrinkWrap: true,
          itemCount: cards.length,
          separatorBuilder: (_, __) => const Divider(height: 1),
          itemBuilder: (context, index) {
            final card = cards[index];
            return ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              leading: CircleAvatar(
                radius: 14,
                child: Text(
                  '${index + 1}',
                  style: const TextStyle(fontSize: 12),
                ),
              ),
              title: Text(
                _timelineTitle(card),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(
                _timelineSubtitle(card),
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
              onTap: () => _showJson(
                context,
                _timelineTitle(card).isEmpty
                    ? '${_t('步骤', 'Step')} ${index + 1}'
                    : _timelineTitle(card),
                card,
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => _showJson(context, title, payload),
          child: Text(_t('查看 JSON', 'View JSON')),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_t('关闭', 'Close')),
        ),
      ],
    ),
  );
}

Future<String?> _askText(
  BuildContext context, {
  required String title,
  required String label,
  String initialValue = '',
  String confirmText = '',
}) async {
  final controller = TextEditingController(text: initialValue);
  try {
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: controller,
          autofocus: true,
          decoration: InputDecoration(
            labelText: label,
            border: const OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(null),
            child: Text(_t('取消', 'Cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: Text(confirmText.isEmpty ? _t('确定', 'OK') : confirmText),
          ),
        ],
      ),
    );
  } finally {
    controller.dispose();
  }
}

String _functionTitle(Map<String, dynamic> item) {
  return _first([
    item['name'],
    item['title'],
    item['description'],
    item['function_id'],
  ]);
}

String _functionSubtitle(Map<String, dynamic> item) {
  return [
    if (_text(item['description']).isNotEmpty) _text(item['description']),
    '${_t('步骤', 'Steps')} ${_int(item['step_count'])}',
    if (_argumentNames(item).isNotEmpty)
      '${_t('参数', 'Args')} ${_argumentNames(item).join(', ')}',
  ].join(' · ');
}

String _runTitle(Map<String, dynamic> item) {
  return _first([
    item['goal'],
    item['operation_description'],
    item['execution_summary'],
    item['run_id'],
  ]);
}

String _runSubtitle(Map<String, dynamic> item) {
  return [
    if (_int(item['step_count']) > 0)
      '${_int(item['step_count'])} ${_t('步', 'steps')}',
    if (_text(item['run_status']).isNotEmpty) _text(item['run_status']),
    if (_text(item['registered_function_id']).isNotEmpty) _t('已保存', 'Saved'),
  ].join(' · ');
}

String _timelineTitle(Map<String, dynamic> item) {
  final header = _map(item['header']);
  return _first([
    item['title'],
    header['title'],
    item['summary'],
    item['tool'],
    item['action'],
    item['kind'],
  ]);
}

String _timelineSubtitle(Map<String, dynamic> item) {
  final args = _map(item['args']);
  final parts = <String>[
    if (_text(item['tool']).isNotEmpty) _text(item['tool']),
    if (_text(item['executor']).isNotEmpty) _text(item['executor']),
    if (_text(item['status']).isNotEmpty) _text(item['status']),
    if (args.isNotEmpty) _compactJson(args),
  ];
  return parts.join(' · ');
}

String _compactJson(Object? value) {
  final text = jsonEncode(value);
  return text.length <= 180 ? text : '${text.substring(0, 180)}...';
}

List<String> _argumentNames(Map<String, dynamic> item) {
  final explicit = item['parameter_names'];
  if (explicit is List) {
    return explicit.map(_text).where((text) => text.isNotEmpty).toList();
  }
  final parameters = _map(item['parameters']);
  final properties = _map(parameters['properties']);
  if (properties.isNotEmpty) return properties.keys.toList();
  final inputProperties = _map(_map(item['input_schema'])['properties']);
  return inputProperties.keys.toList();
}

String _first(Iterable<Object?> values) {
  for (final value in values) {
    final text = _text(value);
    if (text.isNotEmpty) return text;
  }
  return '';
}

String _text(Object? value) => (value ?? '').toString().trim();

int _int(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(_text(value)) ?? 0;
}

Map<String, dynamic> _map(Object? value) {
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return <String, dynamic>{};
}

List<Map<String, dynamic>> _list(Object? value) {
  if (value is! List) return const [];
  return value.whereType<Map>().map(_map).toList(growable: false);
}

String _t(String zh, String en) => LegacyTextLocalizer.isEnglish ? en : zh;
