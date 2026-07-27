import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_brand_icon.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

enum _AgentFilter { all, available, unavailable }

class AgentModeSettingPage extends StatefulWidget {
  const AgentModeSettingPage({super.key});

  @override
  State<AgentModeSettingPage> createState() => _AgentModeSettingPageState();
}

class _AgentModeSettingPageState extends State<AgentModeSettingPage> {
  AcpAgentCatalog? _catalog;
  _AgentFilter _filter = _AgentFilter.all;
  String _query = '';
  bool _loading = true;
  bool _refreshing = false;
  String? _error;
  String? _busyAgentId;
  // 远程 PC Bridge 状态：先用缓存同步渲染，后台再刷新，避免一帧加载闪烁。
  bool _remoteBridgeEnabled =
      StorageService.getBool(StorageService.kRemoteBridgeEnabledKey) ?? false;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
    unawaited(_loadRemoteBridge());
  }

  Future<void> _load({bool refresh = false}) async {
    if (refresh) {
      setState(() => _refreshing = true);
    }
    try {
      final catalog = refresh
          ? await AgentRuntimeService.refreshAgents()
          : await AgentRuntimeService.listAgents();
      if (!mounted) return;
      setState(() {
        _catalog = catalog;
        _loading = false;
        _refreshing = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _refreshing = false;
        _error = error.toString();
      });
    }
  }

  Future<void> _loadRemoteBridge() async {
    try {
      final config = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!mounted) return;
      final enabled = config.remoteEnabled;
      setState(() => _remoteBridgeEnabled = enabled);
      await StorageService.setBool(
        StorageService.kRemoteBridgeEnabledKey,
        enabled,
      );
    } catch (error) {
      debugPrint('Load remote bridge failed: $error');
    }
  }

  List<AcpAgentProfile> get _visibleAgents {
    final normalizedQuery = _query.trim().toLowerCase();
    return (_catalog?.agents ?? const <AcpAgentProfile>[])
        .where((agent) {
          final matchesQuery =
              normalizedQuery.isEmpty ||
              [
                agent.name,
                agent.description,
                agent.command,
              ].join(' ').toLowerCase().contains(normalizedQuery);
          if (!matchesQuery) return false;
          return switch (_filter) {
            _AgentFilter.all => true,
            _AgentFilter.available => agent.status == 'online',
            _AgentFilter.unavailable => agent.status != 'online',
          };
        })
        .toList(growable: false);
  }

  int _countFor(_AgentFilter filter) {
    final agents = _catalog?.agents ?? const <AcpAgentProfile>[];
    return switch (filter) {
      _AgentFilter.all => agents.length,
      _AgentFilter.available =>
        agents.where((agent) => agent.status == 'online').length,
      _AgentFilter.unavailable =>
        agents.where((agent) => agent.status != 'online').length,
    };
  }

  Future<void> _test(AcpAgentProfile agent) async {
    if (_busyAgentId != null || !agent.enabled) return;
    if (agent.managedAdapter && agent.status == 'unchecked') {
      showToast(
        _text(
          '首次检测会自动准备 ACP 适配器，下载可能需要一些时间。',
          'The first check prepares the ACP adapter and may take a moment.',
        ),
      );
    }
    setState(() => _busyAgentId = agent.id);
    try {
      final result = await AgentRuntimeService.testAgent(agent.id);
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      final ok = result['ok'] == true;
      await showDialog<void>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(
            ok
                ? _text('ACP 初始化成功', 'ACP initialized')
                : _text('ACP 初始化失败', 'ACP initialization failed'),
          ),
          content: SingleChildScrollView(
            child: SelectableText(
              ok
                  ? _formatCapabilities(result['capabilities'])
                  : (result['error']?.toString() ??
                        _text('未知错误', 'Unknown error')),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text(_text('完成', 'Done')),
            ),
          ],
        ),
      );
    } catch (error) {
      if (!mounted) return;
      showToast(error.toString(), type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busyAgentId = null);
    }
  }

  String _formatCapabilities(dynamic value, {String indent = ''}) {
    if (value is Map) {
      return value.entries
          .map((entry) {
            final nested = entry.value;
            if (nested is Map || nested is List) {
              return '$indent${entry.key}:\n'
                  '${_formatCapabilities(nested, indent: '$indent  ')}';
            }
            return '$indent${entry.key}: $nested';
          })
          .join('\n');
    }
    if (value is List) {
      return value
          .map(
            (item) =>
                '$indent- '
                '${_formatCapabilities(item, indent: '$indent  ').trim()}',
          )
          .join('\n');
    }
    return '$indent$value';
  }

  Future<void> _addCustomAgent() async {
    final nameController = TextEditingController();
    final commandController = TextEditingController();
    final argumentsController = TextEditingController();
    final environmentController = TextEditingController();
    var enabled = true;
    final result = await showDialog<AcpAgentProfile>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (dialogContext, setDialogState) => AlertDialog(
          title: Text(_text('添加自定义 ACP Agent', 'Add custom ACP Agent')),
          content: SizedBox(
            width: 460,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(
                    controller: nameController,
                    decoration: InputDecoration(
                      labelText: _text('名称', 'Name'),
                      hintText: 'My ACP Agent',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: commandController,
                    decoration: InputDecoration(
                      labelText: _text('启动命令或路径', 'Command or path'),
                      hintText: '/usr/local/bin/agent',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: argumentsController,
                    minLines: 2,
                    maxLines: 4,
                    decoration: InputDecoration(
                      labelText: _text(
                        '启动参数（每行一个）',
                        'Arguments (one per line)',
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: environmentController,
                    minLines: 3,
                    maxLines: 6,
                    decoration: InputDecoration(
                      labelText: _text('启动环境变量', 'Launch environment'),
                      hintText: 'KEY=VALUE',
                      helperText: _text(
                        '变量直接传给 Agent，由 Agent 自身决定如何使用。',
                        'Variables are passed directly to the Agent.',
                      ),
                    ),
                  ),
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    title: Text(_text('启用 Agent', 'Enable Agent')),
                    value: enabled,
                    onChanged: (value) => setDialogState(() => enabled = value),
                  ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text(_text('取消', 'Cancel')),
            ),
            FilledButton(
              onPressed: () {
                final name = nameController.text.trim();
                final command = commandController.text.trim();
                if (name.isEmpty || command.isEmpty) return;
                Navigator.of(dialogContext).pop(
                  AcpAgentProfile(
                    id: '',
                    name: name,
                    command: command,
                    arguments: _nonEmptyLines(argumentsController.text),
                    environment: _parseEnvironment(environmentController.text),
                    enabled: enabled,
                  ),
                );
              },
              child: Text(_text('保存', 'Save')),
            ),
          ],
        ),
      ),
    );
    nameController.dispose();
    commandController.dispose();
    argumentsController.dispose();
    environmentController.dispose();
    if (result == null) return;
    try {
      final catalog = await AgentRuntimeService.saveAgent(result);
      if (!mounted) return;
      setState(() {
        _catalog = catalog;
        _error = null;
      });
    } catch (error) {
      if (!mounted) return;
      showToast(error.toString(), type: ToastType.error);
    }
  }

  Future<void> _openAgentConfig(AcpAgentProfile agent) async {
    final changed = await GoRouterManager.pushForResult<bool>(
      '/home/agent_config/${Uri.encodeComponent(agent.id)}',
    );
    if (changed == true && mounted) {
      await _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final dark = context.isDarkTheme;
    final background = dark ? palette.pageBackground : AppColors.background;
    final card = dark ? palette.surfacePrimary : Colors.white;
    final agents = _visibleAgents;
    final managed = agents.where((agent) => agent.builtIn).toList();
    final custom = agents.where((agent) => !agent.builtIn).toList();
    return Scaffold(
      backgroundColor: background,
      appBar: CommonAppBar(
        title: _text('Agent 模式', 'Agent mode'),
        primary: true,
        actions: [
          IconButton(
            tooltip: _text('刷新检测', 'Refresh detection'),
            onPressed: _refreshing ? null : () => _load(refresh: true),
            icon: _refreshing
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh_rounded),
          ),
          IconButton(
            tooltip: _text('添加自定义 ACP Agent', 'Add custom ACP Agent'),
            onPressed: _busyAgentId == null ? _addCustomAgent : null,
            icon: const Icon(Icons.add_rounded),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null && (_catalog?.agents.isEmpty ?? true)
            ? Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(_error!, textAlign: TextAlign.center),
                      const SizedBox(height: 12),
                      FilledButton(
                        onPressed: _load,
                        child: Text(_text('重试', 'Retry')),
                      ),
                    ],
                  ),
                ),
              )
            : ListView(
                padding: const EdgeInsets.fromLTRB(18, 12, 18, 28),
                children: [
                  SettingsSectionTitle(
                    label: _text('托管 Agent', 'Managed Agents'),
                    subtitle: _text(
                      '预置 Agent 始终显示；状态来自命令检测与 ACP initialize。API、账号和默认模型由各 Agent 自身配置。',
                      'Built-in Agents always remain visible. Status comes from command detection and ACP initialize. Each Agent owns its API, account, and default model configuration.',
                    ),
                  ),
                  TextField(
                    decoration: InputDecoration(
                      prefixIcon: const Icon(Icons.search_rounded),
                      hintText: _text('搜索 Agent', 'Search Agents'),
                      filled: true,
                      fillColor: card,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide.none,
                      ),
                    ),
                    onChanged: (value) => setState(() => _query = value),
                  ),
                  const SizedBox(height: 10),
                  SegmentedButton<_AgentFilter>(
                    segments: [
                      ButtonSegment(
                        value: _AgentFilter.all,
                        label: Text(
                          '${_text('全部', 'All')} ${_countFor(_AgentFilter.all)}',
                        ),
                      ),
                      ButtonSegment(
                        value: _AgentFilter.available,
                        label: Text(
                          '${_text('可用', 'Available')} '
                          '${_countFor(_AgentFilter.available)}',
                        ),
                      ),
                      ButtonSegment(
                        value: _AgentFilter.unavailable,
                        label: Text(
                          '${_text('不可用', 'Unavailable')} '
                          '${_countFor(_AgentFilter.unavailable)}',
                        ),
                      ),
                    ],
                    selected: {_filter},
                    showSelectedIcon: false,
                    onSelectionChanged: (values) =>
                        setState(() => _filter = values.first),
                  ),
                  if (managed.isNotEmpty) ...[
                    const SizedBox(height: 20),
                    _sectionLabel(_text('预置 Agent', 'Built-in Agents')),
                    const SizedBox(height: 8),
                    for (final agent in managed) ...[
                      _AgentCard(
                        agent: agent,
                        busy: agent.id == _busyAgentId,
                        onTest: () => _test(agent),
                        onConfigure: () => _openAgentConfig(agent),
                        cardColor: card,
                      ),
                      const SizedBox(height: 10),
                    ],
                  ],
                  if (custom.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _sectionLabel(_text('自定义 Agent', 'Custom Agents')),
                    const SizedBox(height: 8),
                    for (final agent in custom) ...[
                      _AgentCard(
                        agent: agent,
                        busy: agent.id == _busyAgentId,
                        onTest: () => _test(agent),
                        onConfigure: () => _openAgentConfig(agent),
                        cardColor: card,
                      ),
                      const SizedBox(height: 10),
                    ],
                  ],
                  if (agents.isEmpty) ...[
                    const SizedBox(height: 42),
                    Center(
                      child: Text(
                        _text('没有匹配的 Agent', 'No matching Agents'),
                        style: TextStyle(color: palette.textSecondary),
                      ),
                    ),
                  ],
                  // 远程 PC Bridge：全局共享配置入口（仅配置远程 Codex app-server 连接）。
                  const SizedBox(height: 24),
                  _sectionLabel(_text('远程运行', 'Remote runtime')),
                  const SizedBox(height: 8),
                  _RemoteBridgeCard(
                    enabled: _remoteBridgeEnabled,
                    cardColor: card,
                    onTap: () {
                      GoRouterManager.push('/home/remote_codex_setting');
                    },
                    english: _english,
                  ),
                ],
              ),
      ),
    );
  }

  Widget _sectionLabel(String value) {
    return Text(
      value,
      style: TextStyle(
        color: context.omniPalette.textSecondary,
        fontSize: 12,
        fontWeight: FontWeight.w600,
      ),
    );
  }
}

class _AgentCard extends StatelessWidget {
  const _AgentCard({
    required this.agent,
    required this.busy,
    required this.onTest,
    required this.onConfigure,
    required this.cardColor,
  });

  final AcpAgentProfile agent;
  final bool busy;
  final VoidCallback onTest;
  final VoidCallback onConfigure;
  final Color cardColor;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final english =
        Localizations.localeOf(context).languageCode.toLowerCase() == 'en';
    final status = _statusPresentation(agent.status, english);
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 14, 10, 10),
      decoration: BoxDecoration(
        color: cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: AgentBrandIcon(
                  agentId: agent.id,
                  size: 20,
                  fallbackColor: palette.accentPrimary,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      agent.name,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Row(
                      children: [
                        Container(
                          width: 7,
                          height: 7,
                          decoration: BoxDecoration(
                            color: status.color,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 5),
                        Text(
                          !agent.enabled
                              ? (english ? 'Disabled' : '已停用')
                              : status.label,
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 11,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
              if (busy)
                const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              else
                IconButton(
                  key: Key('agent-config-${agent.id}'),
                  tooltip: english ? 'Agent configuration' : 'Agent 配置',
                  onPressed: onConfigure,
                  icon: const Icon(Icons.chevron_right_rounded),
                ),
            ],
          ),
          if (agent.description.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              agent.description,
              style: TextStyle(color: palette.textSecondary, fontSize: 12),
            ),
          ],
          const SizedBox(height: 8),
          SelectableText(
            ([agent.command, ...agent.arguments]).join(' '),
            style: TextStyle(
              color: palette.textTertiary,
              fontFamily: 'monospace',
              fontSize: 11,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            english ? 'API: configured by the Agent' : 'API：由 Agent 自身配置',
            style: TextStyle(color: palette.textSecondary, fontSize: 12),
          ),
          if ((agent.lastCheckError ?? '').isNotEmpty &&
              agent.status != 'online') ...[
            const SizedBox(height: 6),
            Text(
              agent.lastCheckError!,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: Theme.of(context).colorScheme.error,
                fontSize: 11,
              ),
            ),
          ],
          const SizedBox(height: 8),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: busy || !agent.enabled || agent.status == 'missing'
                    ? null
                    : onTest,
                child: Text(
                  agent.managedAdapter &&
                          agent.status == 'unchecked' &&
                          agent.lastCheckError?.contains('will be prepared') ==
                              true
                      ? (english ? 'Prepare & initialize' : '准备并初始化')
                      : (english ? 'Initialize' : '初始化检测'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

List<String> _nonEmptyLines(String source) {
  return source
      .split('\n')
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList(growable: false);
}

Map<String, String> _parseEnvironment(String source) {
  final environment = <String, String>{};
  for (final line in source.split('\n')) {
    final separator = line.indexOf('=');
    if (separator <= 0) continue;
    final key = line.substring(0, separator).trim();
    if (key.isEmpty) continue;
    environment[key] = line.substring(separator + 1);
  }
  return environment;
}

({String label, Color color}) _statusPresentation(String status, bool english) {
  return switch (status) {
    'online' => (
      label: english ? 'Available' : '可用',
      color: const Color(0xFF2EAF67),
    ),
    'missing' => (
      label: english ? 'Not installed' : '未安装',
      color: const Color(0xFF98A2B3),
    ),
    'offline' => (
      label: english ? 'Initialization failed' : '初始化失败',
      color: const Color(0xFFE05252),
    ),
    _ => (label: english ? 'Unchecked' : '未检测', color: const Color(0xFFE3A52B)),
  };
}

/// 远程 PC Bridge 卡片入口：点击跳转到独立的 Bridge 配置页（扫码/测试/自动保存全部保留）。
class _RemoteBridgeCard extends StatelessWidget {
  const _RemoteBridgeCard({
    required this.enabled,
    required this.cardColor,
    required this.onTap,
    required this.english,
  });

  final bool enabled;
  final Color cardColor;
  final VoidCallback onTap;
  final bool english;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.fromLTRB(14, 14, 10, 14),
          decoration: BoxDecoration(
            color: cardColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: palette.borderSubtle),
          ),
          child: Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  Icons.terminal_rounded,
                  size: 20,
                  color: palette.accentPrimary,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      english ? 'Remote PC Bridge' : '远程 PC Bridge',
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(
                      enabled
                          ? (english
                                ? 'Enabled — Agent chat uses remote Codex app-server'
                                : '已启用 — Agent 聊天使用远程 Codex app-server')
                          : (english
                                ? 'Configure remote Codex app-server connection'
                                : '配置远程 Codex app-server 连接'),
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right_rounded,
                color: palette.textTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
