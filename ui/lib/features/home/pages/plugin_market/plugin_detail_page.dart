import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/omni_plugin_item.dart';
import 'package:ui/services/omni_plugin_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

class PluginDetailPage extends StatefulWidget {
  const PluginDetailPage({
    super.key,
    required this.pluginId,
    this.initialPlugin,
  });

  final String pluginId;
  final OmniPluginItem? initialPlugin;

  @override
  State<PluginDetailPage> createState() => _PluginDetailPageState();
}

class _PluginDetailPageState extends State<PluginDetailPage> {
  OmniPluginItem? _plugin;
  bool _loading = true;
  bool _busy = false;
  bool _changed = false;

  @override
  void initState() {
    super.initState();
    _plugin = widget.initialPlugin;
    _loading = _plugin == null;
    unawaited(_loadPlugin(showLoading: _plugin == null));
  }

  Future<void> _loadPlugin({bool showLoading = true}) async {
    if (showLoading && mounted) setState(() => _loading = true);
    try {
      final plugin = await OmniPluginService.getPlugin(widget.pluginId);
      if (!mounted) return;
      setState(() {
        _plugin = plugin;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _loading = false);
      showToast(context.l10n.pluginLoadFailed, type: ToastType.error);
    }
  }

  Future<void> _install() async {
    final plugin = _plugin;
    if (plugin == null) return;
    await _runStateAction(
      () => OmniPluginService.install(plugin.id),
      successMessage: context.l10n.pluginInstalledMsg(plugin.name),
      failureMessage: context.l10n.pluginInstallFailed,
    );
  }

  Future<void> _toggle(bool enabled) async {
    final plugin = _plugin;
    if (plugin == null) return;
    await _runStateAction(
      () => OmniPluginService.setEnabled(plugin.id, enabled),
      successMessage: enabled
          ? context.l10n.pluginEnabledMsg(plugin.name)
          : context.l10n.pluginDisabledMsg(plugin.name),
      failureMessage: context.l10n.pluginToggleFailed,
    );
  }

  Future<void> _runStateAction(
    Future<OmniPluginItem> Function() action, {
    required String successMessage,
    required String failureMessage,
  }) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final updated = await action();
      if (!mounted) return;
      setState(() {
        _plugin = updated;
        _changed = true;
      });
      showToast(successMessage, type: ToastType.success);
    } catch (_) {
      if (mounted) showToast(failureMessage, type: ToastType.error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _uninstall() async {
    final plugin = _plugin;
    if (plugin == null || _busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(context.l10n.pluginUninstallTitle),
        content: Text(context.l10n.pluginUninstallConfirmMsg(plugin.name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(context.l10n.pluginCancel),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
            child: Text(context.l10n.pluginUninstall),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _busy = true);
    try {
      await OmniPluginService.uninstall(plugin.id);
      final updated = await OmniPluginService.getPlugin(plugin.id);
      if (!mounted) return;
      setState(() {
        _plugin = updated;
        _changed = true;
      });
      showToast(
        context.l10n.pluginUninstalledMsg(plugin.name),
        type: ToastType.success,
      );
    } catch (_) {
      if (mounted) {
        showToast(context.l10n.pluginUninstallFailed, type: ToastType.error);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) context.pop(_changed);
      },
      child: Scaffold(
        backgroundColor: context.isDarkTheme
            ? palette.pageBackground
            : AppColors.background,
        appBar: CommonAppBar(
          title: context.l10n.pluginDetailTitle,
          primary: true,
          onBackPressed: () => context.pop(_changed),
        ),
        body: _loading
            ? const Center(child: CircularProgressIndicator())
            : _plugin == null
            ? _buildUnavailable()
            : _buildDetails(_plugin!),
        bottomNavigationBar: _plugin == null
            ? null
            : _buildBottomActions(_plugin!),
      ),
    );
  }

  Widget _buildUnavailable() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.extension_off_outlined,
            size: 48,
            color: palette.textTertiary,
          ),
          const SizedBox(height: 12),
          Text(
            context.l10n.pluginMarketEmpty,
            style: TextStyle(color: palette.textPrimary, fontSize: 16),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: () => _loadPlugin(),
            child: Text(context.l10n.pluginRetry),
          ),
        ],
      ),
    );
  }

  Widget _buildDetails(OmniPluginItem plugin) {
    final palette = context.omniPalette;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: palette.accentPrimary.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(15),
              ),
              child: Icon(
                Icons.extension_rounded,
                size: 29,
                color: palette.accentPrimary,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    plugin.name,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 20,
                      fontWeight: FontWeight.w600,
                      height: 1.3,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${plugin.publisher} · v${plugin.version}',
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 7),
                  Text(
                    _statusLabel(plugin),
                    style: TextStyle(
                      color: plugin.enabled
                          ? palette.accentPrimary
                          : palette.textTertiary,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 30),
        SettingsSectionTitle(label: context.l10n.pluginAboutTitle),
        const SizedBox(height: 10),
        Text(
          plugin.description.trim().isEmpty
              ? context.l10n.pluginNoDescription
              : plugin.description,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 13,
            height: 1.65,
          ),
        ),
        const SizedBox(height: 28),
        SettingsSectionTitle(label: context.l10n.pluginCapabilitiesTitle),
        const SizedBox(height: 8),
        if (plugin.capabilities.isEmpty)
          Text(
            context.l10n.pluginNoCapabilities,
            style: TextStyle(color: palette.textTertiary, fontSize: 13),
          )
        else
          ...plugin.capabilities.map(
            (capability) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 7),
              child: Row(
                children: [
                  Icon(
                    Icons.check_circle_outline_rounded,
                    size: 18,
                    color: palette.accentPrimary,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      capability,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 24),
        SettingsSectionTitle(label: context.l10n.pluginInformationTitle),
        const SizedBox(height: 6),
        _buildInfoRow(context.l10n.pluginPublisherLabel, plugin.publisher),
        _buildInfoRow(context.l10n.pluginVersionLabel, plugin.version),
        _buildInfoRow(context.l10n.pluginTypeLabel, _kindLabel(plugin.kind)),
        if (plugin.downloadSizeBytes > 0)
          _buildInfoRow(
            context.l10n.pluginDownloadSizeLabel,
            _formatBytes(plugin.downloadSizeBytes),
          ),
        _buildInfoRow(
          context.l10n.pluginInterfaceVersionLabel,
          plugin.interfaceVersion.toString(),
        ),
        if (!plugin.compatible ||
            plugin.errorMessage?.trim().isNotEmpty == true)
          Padding(
            padding: const EdgeInsets.only(top: 18),
            child: Text(
              !plugin.compatible
                  ? context.l10n.pluginIncompatible
                  : plugin.errorMessage!.trim(),
              style: const TextStyle(
                color: AppColors.alertRed,
                fontSize: 12,
                height: 1.5,
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildInfoRow(String label, String value) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: TextStyle(color: palette.textSecondary, fontSize: 13),
            ),
          ),
          const SizedBox(width: 20),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.end,
              style: TextStyle(color: palette.textPrimary, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBottomActions(OmniPluginItem plugin) {
    final palette = context.omniPalette;
    return Material(
      color: context.isDarkTheme ? palette.surfacePrimary : Colors.white,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 14),
          child: plugin.installed
              ? Row(
                  children: [
                    Expanded(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            context.l10n.pluginEnableTitle,
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 14,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            context.l10n.pluginEnableDescription,
                            style: TextStyle(
                              color: palette.textTertiary,
                              fontSize: 11,
                            ),
                          ),
                        ],
                      ),
                    ),
                    TextButton(
                      onPressed: _busy ? null : _uninstall,
                      style: TextButton.styleFrom(
                        foregroundColor: AppColors.alertRed,
                      ),
                      child: Text(context.l10n.pluginUninstall),
                    ),
                    const SizedBox(width: 4),
                    Switch.adaptive(
                      value: plugin.enabled,
                      onChanged: _busy || !plugin.compatible ? null : _toggle,
                    ),
                  ],
                )
              : SizedBox(
                  width: double.infinity,
                  height: 46,
                  child: FilledButton(
                    onPressed: _busy || !plugin.compatible ? null : _install,
                    child: _busy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(context.l10n.pluginInstall),
                  ),
                ),
        ),
      ),
    );
  }

  String _statusLabel(OmniPluginItem plugin) {
    if (!plugin.compatible) return context.l10n.pluginIncompatible;
    if (!plugin.installed) return context.l10n.pluginStatusNotInstalled;
    if (plugin.enabled) return context.l10n.pluginStatusEnabled;
    return context.l10n.pluginStatusInstalled;
  }

  String _kindLabel(String kind) {
    return switch (kind) {
      'bundled_module' => context.l10n.pluginKindBundledModule,
      'companion_app' => context.l10n.pluginKindCompanionApp,
      _ => context.l10n.pluginKindRuntimeBundle,
    };
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '—';
    const megabyte = 1024 * 1024;
    if (bytes >= megabyte) {
      return '${(bytes / megabyte).toStringAsFixed(bytes >= 10 * megabyte ? 0 : 1)} MB';
    }
    return '${(bytes / 1024).toStringAsFixed(0)} KB';
  }
}
