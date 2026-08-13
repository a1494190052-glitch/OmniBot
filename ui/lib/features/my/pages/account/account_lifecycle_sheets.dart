import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/account_service.dart';

class PlatformUsageSheet extends StatefulWidget {
  const PlatformUsageSheet({
    super.key,
    required this.english,
    required this.errorMessage,
  });

  final bool english;
  final String Function(PlatformException) errorMessage;

  @override
  State<PlatformUsageSheet> createState() => _PlatformUsageSheetState();
}

class _PlatformUsageSheetState extends State<PlatformUsageSheet> {
  List<PlatformUsageEntry>? _entries;
  String? _error;

  String _text(String zh, String en) => widget.english ? en : zh;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _entries = null;
      _error = null;
    });
    try {
      final entries = await AccountService.listPlatformUsage();
      if (mounted) setState(() => _entries = entries);
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = widget.errorMessage(error));
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = _text(
            '暂时无法读取用量，请稍后重试',
            'Usage is temporarily unavailable. Try again later.',
          );
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.72,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _text('最近平台用量', 'Recent platform usage'),
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _text(
                            '仅显示最近 20 条，额度以服务器结算为准。',
                            'Shows the latest 20 records. Server settlement is authoritative.',
                          ),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    key: const ValueKey('refresh-platform-usage'),
                    tooltip: _text('刷新', 'Refresh'),
                    onPressed: _entries == null ? null : _load,
                    icon: const Icon(LucideIcons.refreshCw),
                  ),
                  IconButton(
                    tooltip: _text('关闭', 'Close'),
                    onPressed: () => Navigator.pop(context),
                    icon: const Icon(LucideIcons.x),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(child: _buildBody()),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    final error = _error;
    if (error != null) {
      return _SheetMessage(
        icon: LucideIcons.circleAlert,
        message: error,
        actionLabel: _text('重试', 'Retry'),
        onAction: _load,
      );
    }
    final entries = _entries;
    if (entries == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (entries.isEmpty) {
      return _SheetMessage(
        icon: LucideIcons.chartNoAxesColumnIncreasing,
        message: _text(
          '还没有平台用量记录。使用官方 AI 后会显示在这里。',
          'No platform usage yet. Official AI calls will appear here.',
        ),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
      itemCount: entries.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final entry = entries[index];
        final model = entry.model.trim().isEmpty
            ? _text('官方模型', 'Official model')
            : entry.model;
        return ListTile(
          key: ValueKey('platform-usage-$index'),
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: const Icon(LucideIcons.bot),
          title: Text(model, maxLines: 1, overflow: TextOverflow.ellipsis),
          subtitle: Text(
            '${formatAccountDate(entry.createdAt)}\n'
            '${_text('输入', 'Input')} ${entry.promptTokens} · '
            '${_text('输出', 'Output')} ${entry.completionTokens} · '
            '${_text('共', 'Total')} ${entry.totalTokens}',
          ),
          isThreeLine: true,
          trailing: Text(
            _text('消耗 ${entry.quotaUsed}', 'Used ${entry.quotaUsed}'),
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        );
      },
    );
  }
}

class SessionsSheet extends StatefulWidget {
  const SessionsSheet({
    super.key,
    required this.english,
    required this.errorMessage,
  });

  final bool english;
  final String Function(PlatformException) errorMessage;

  @override
  State<SessionsSheet> createState() => _SessionsSheetState();
}

class _SessionsSheetState extends State<SessionsSheet> {
  List<AccountDeviceSession>? _sessions;
  String? _error;
  String? _notice;
  String? _busySessionId;
  bool _revokingAll = false;

  String _text(String zh, String en) => widget.english ? en : zh;
  bool get _busy => _busySessionId != null || _revokingAll;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _sessions = null;
      _error = null;
      _notice = null;
    });
    try {
      final sessions = await AccountService.listSessions();
      sessions.sort((left, right) {
        if (left.current != right.current) return left.current ? -1 : 1;
        final leftTime = left.lastUsedAt ?? left.createdAt;
        final rightTime = right.lastUsedAt ?? right.createdAt;
        return (rightTime ?? DateTime.fromMillisecondsSinceEpoch(0)).compareTo(
          leftTime ?? DateTime.fromMillisecondsSinceEpoch(0),
        );
      });
      if (mounted) setState(() => _sessions = sessions);
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = widget.errorMessage(error));
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = _text(
            '暂时无法读取登录设备，请稍后重试',
            'Signed-in devices are temporarily unavailable. Try again later.',
          );
        });
      }
    }
  }

  Future<void> _revoke(AccountDeviceSession session) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('退出这个设备？', 'Sign out this device?')),
        content: Text(
          _text(
            '这个设备需要重新输入邮箱和密码才能使用账号。',
            'This device must sign in again with the email and password.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            key: const ValueKey('confirm-revoke-session'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text('确认退出', 'Sign out')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _busySessionId = session.id;
      _error = null;
      _notice = null;
    });
    try {
      await AccountService.revokeSession(session.id);
      if (!mounted) return;
      setState(() {
        _sessions = _sessions
            ?.where((item) => item.id != session.id)
            .toList(growable: false);
        _busySessionId = null;
        _notice = _text('已退出该设备', 'Device signed out');
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _busySessionId = null;
        _error = widget.errorMessage(error);
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _busySessionId = null;
        _error = _text(
          '退出设备失败，请稍后重试',
          'Could not sign out the device. Try again later.',
        );
      });
    }
  }

  Future<void> _revokeOtherSessions() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('退出全部其他设备？', 'Sign out all other devices?')),
        content: Text(
          _text(
            '当前设备会保持登录，其他设备都需要重新登录。',
            'This device stays signed in. Other devices must sign in again.',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(_text('取消', 'Cancel')),
          ),
          FilledButton(
            key: const ValueKey('confirm-revoke-other-sessions'),
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(_text('全部退出', 'Sign out all')),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() {
      _revokingAll = true;
      _error = null;
      _notice = null;
    });
    try {
      final revoked = await AccountService.revokeOtherSessions();
      if (!mounted) return;
      setState(() {
        _sessions = _sessions
            ?.where((session) => session.current)
            .toList(growable: false);
        _revokingAll = false;
        _notice = _text(
          '已退出 $revoked 个其他设备',
          'Signed out $revoked other device(s)',
        );
      });
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _revokingAll = false;
        _error = widget.errorMessage(error);
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _revokingAll = false;
        _error = _text(
          '退出其他设备失败，请稍后重试',
          'Could not sign out other devices. Try again later.',
        );
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final sessions = _sessions;
    final hasOtherSessions =
        sessions?.any((session) => !session.current) ?? false;
    return SafeArea(
      top: false,
      child: SizedBox(
        height: MediaQuery.sizeOf(context).height * 0.76,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _text('登录设备', 'Signed-in devices'),
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          _text(
                            '服务目前仅记录登录时间，暂不读取设备名称。',
                            'The service records sign-in times without reading device names.',
                          ),
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: _text('刷新', 'Refresh'),
                    onPressed: sessions == null || _busy ? null : _load,
                    icon: const Icon(LucideIcons.refreshCw),
                  ),
                  IconButton(
                    tooltip: _text('关闭', 'Close'),
                    onPressed: _busy ? null : () => Navigator.pop(context),
                    icon: const Icon(LucideIcons.x),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            if (_error != null && sessions != null)
              _InlineSheetNotice(message: _error!, error: true)
            else if (_notice != null)
              _InlineSheetNotice(message: _notice!),
            Expanded(child: _buildBody()),
            if (hasOtherSessions)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                child: OutlinedButton.icon(
                  key: const ValueKey('revoke-other-sessions'),
                  onPressed: _busy ? null : _revokeOtherSessions,
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(46),
                  ),
                  icon: _revokingAll
                      ? const SizedBox.square(
                          dimension: 17,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(LucideIcons.logOut, size: 18),
                  label: Text(_text('退出全部其他设备', 'Sign out all other devices')),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_sessions == null && _error == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_sessions == null) {
      return _SheetMessage(
        icon: LucideIcons.circleAlert,
        message: _error!,
        actionLabel: _text('重试', 'Retry'),
        onAction: _load,
      );
    }
    final sessions = _sessions!;
    if (sessions.isEmpty) {
      return _SheetMessage(
        icon: LucideIcons.smartphone,
        message: _text('没有可显示的登录设备', 'No sessions to display'),
      );
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
      itemCount: sessions.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final session = sessions[index];
        final otherIndex = session.current
            ? 0
            : sessions
                  .take(index + 1)
                  .where((candidate) => !candidate.current)
                  .length;
        final title = session.current
            ? _text('当前设备', 'Current device')
            : _text('其他登录设备 $otherIndex', 'Other device $otherIndex');
        final busy = _busySessionId == session.id;
        return ListTile(
          key: ValueKey('account-session-${session.id}'),
          contentPadding: const EdgeInsets.symmetric(horizontal: 4),
          leading: Icon(
            session.current ? LucideIcons.smartphone : LucideIcons.monitor,
          ),
          title: Text(title),
          subtitle: Text(
            '${_text('最近活动', 'Last active')} '
            '${formatAccountDate(session.lastUsedAt ?? session.createdAt)}',
          ),
          trailing: session.current
              ? null
              : TextButton(
                  key: ValueKey('revoke-session-${session.id}'),
                  onPressed: _busy ? null : () => _revoke(session),
                  child: busy
                      ? const SizedBox.square(
                          dimension: 17,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Text(_text('退出', 'Sign out')),
                ),
        );
      },
    );
  }
}

class _SheetMessage extends StatelessWidget {
  const _SheetMessage({
    required this.icon,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final IconData icon;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 32),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            if (actionLabel != null && onAction != null) ...[
              const SizedBox(height: 16),
              FilledButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}

class _InlineSheetNotice extends StatelessWidget {
  const _InlineSheetNotice({required this.message, this.error = false});

  final String message;
  final bool error;

  @override
  Widget build(BuildContext context) {
    final color = error
        ? Theme.of(context).colorScheme.error
        : Theme.of(context).colorScheme.primary;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(message, style: TextStyle(color: color)),
    );
  }
}

String formatAccountDate(DateTime? value) {
  if (value == null) return '--';
  final local = value.toLocal();
  String twoDigits(int number) => number.toString().padLeft(2, '0');
  return '${local.year}-${twoDigits(local.month)}-${twoDigits(local.day)} '
      '${twoDigits(local.hour)}:${twoDigits(local.minute)}';
}
