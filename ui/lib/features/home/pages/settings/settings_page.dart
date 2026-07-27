import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_svg/svg.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/mcp_server_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/workspace_memory_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _mcpEnabled = false;
  bool _mcpBusy = false;
  McpServerInfo? _mcpInfo;
  bool _workspaceMemoryConfigured = false;

  @override
  void initState() {
    super.initState();
    // 同步读取缓存初始值，首帧即渲染真实状态，避免一帧加载占位符闪烁。
    _mcpEnabled =
        StorageService.getBool(StorageService.kMcpLocalServiceEnabledKey) ??
        false;
    _workspaceMemoryConfigured =
        StorageService.getBool(StorageService.kWorkspaceMemoryConfiguredKey) ??
        false;
    _loadMcpServerState();
    _loadWorkspaceMemoryState();
  }

  Future<void> _loadMcpServerState() async {
    try {
      final info = await McpServerService.getState();
      if (!mounted) return;
      final enabled = info?.enabled == true;
      setState(() {
        _mcpInfo = info;
        _mcpEnabled = enabled;
      });
      // 回写缓存，供下次首帧同步渲染。
      await StorageService.setBool(
        StorageService.kMcpLocalServiceEnabledKey,
        enabled,
      );
    } catch (e) {
      debugPrint('Load MCP state failed: $e');
    }
  }

  Future<void> _loadWorkspaceMemoryState() async {
    try {
      final results = await Future.wait([
        WorkspaceMemoryService.getEmbeddingConfig(),
        WorkspaceMemoryService.getRollupStatus(),
      ]);
      if (!mounted) return;
      final config = results[0] as WorkspaceMemoryEmbeddingConfig;
      setState(() {
        _workspaceMemoryConfigured = config.configured;
      });
      // 回写缓存，供下次首帧同步渲染。
      unawaited(
        StorageService.setBool(
          StorageService.kWorkspaceMemoryConfiguredKey,
          config.configured,
        ),
      );
    } catch (e) {
      debugPrint('Load workspace memory state failed: $e');
    }
  }

  Future<void> _toggleMcpServer(bool enable) async {
    if (_mcpBusy) return;
    setState(() {
      _mcpBusy = true;
      _mcpEnabled = enable;
    });
    try {
      final info = await McpServerService.setEnabled(enable);
      if (!mounted) return;
      final enabled = info?.enabled == true;
      setState(() {
        _mcpInfo = info;
        _mcpEnabled = enabled;
      });
      // 同步更新缓存，保证下次进入设置页首帧即正确。
      unawaited(
        StorageService.setBool(
          StorageService.kMcpLocalServiceEnabledKey,
          enabled,
        ),
      );
      if (enable) {
        final endpoint = info?.endpoint ?? '';
        if (endpoint.isNotEmpty) {
          showToast(
            context.l10n.settingsMcpEnabledToast(endpoint),
            type: ToastType.success,
          );
        }
      } else {
        showToast(context.l10n.settingsMcpDisabledToast);
      }
    } on PlatformException catch (e) {
      if (!mounted) return;
      showToast(
        e.message ?? context.l10n.settingsMcpToggleFailed,
        type: ToastType.error,
      );
      setState(() {
        _mcpEnabled = !enable;
      });
    } catch (e) {
      if (!mounted) return;
      showToast(context.l10n.settingsMcpToggleFailed, type: ToastType.error);
      setState(() {
        _mcpEnabled = !enable;
      });
    } finally {
      if (mounted) {
        setState(() {
          _mcpBusy = false;
        });
      }
    }
  }

  void _showMcpInfo() {
    final info = _mcpInfo;
    if (info == null || info.endpoint.isEmpty) return;
    final l10n = context.l10n;
    final palette = context.omniPalette;

    showModalBottomSheet<void>(
      context: context,
      backgroundColor: palette.surfacePrimary,
      builder: (sheetContext) {
        final sheetPalette = sheetContext.omniPalette;
        final labelStyle = TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w500,
          color: sheetPalette.textSecondary,
        );
        final valueStyle = TextStyle(
          fontSize: 13,
          color: sheetPalette.textPrimary,
        );
        final actionStyle = TextButton.styleFrom(
          foregroundColor: sheetPalette.accentPrimary,
          minimumSize: const Size(0, 36),
          padding: const EdgeInsets.symmetric(horizontal: 10),
        );

        return SafeArea(
          top: false,
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l10n.settingsMcpLocalService,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: sheetPalette.textPrimary,
                  ),
                ),
                const SizedBox(height: 12),
                Text(l10n.settingsMcpAddress, style: labelStyle),
                SelectableText(info.endpoint, style: valueStyle),
                const SizedBox(height: 8),
                Text(l10n.settingsMcpToken, style: labelStyle),
                SelectableText(
                  info.token.isEmpty ? l10n.settingsNotGenerated : info.token,
                  style: valueStyle,
                ),
                const SizedBox(height: 12),
                Align(
                  alignment: AlignmentDirectional.centerEnd,
                  child: Wrap(
                    spacing: 4,
                    runSpacing: 2,
                    alignment: WrapAlignment.end,
                    children: [
                      TextButton(
                        style: actionStyle,
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: info.endpoint));
                          Navigator.of(sheetContext).pop();
                          showToast(l10n.settingsCopiedAddress);
                        },
                        child: Text(l10n.settingsCopyAddress),
                      ),
                      TextButton(
                        style: actionStyle,
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: info.token));
                          Navigator.of(sheetContext).pop();
                          showToast(l10n.settingsCopiedToken);
                        },
                        child: Text(l10n.settingsCopyToken),
                      ),
                      TextButton(
                        style: actionStyle,
                        onPressed: () async {
                          Navigator.of(sheetContext).pop();
                          try {
                            final refreshed =
                                await McpServerService.refreshToken();
                            if (!mounted) return;
                            setState(() {
                              _mcpInfo = refreshed ?? _mcpInfo;
                            });
                            showToast(l10n.settingsTokenRefreshed);
                          } catch (_) {
                            showToast(
                              l10n.settingsTokenRefreshFailed,
                              type: ToastType.error,
                            );
                          }
                        },
                        child: Text(l10n.settingsRefreshToken),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  l10n.settingsMcpSecurityNotice,
                  style: TextStyle(
                    fontSize: 12,
                    color: sheetPalette.textSecondary,
                  ),
                ),
                const SizedBox(height: 8),
              ],
            ),
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final workspaceMemorySubtitle = _workspaceMemoryConfigured
        ? context.l10n.settingsWorkspaceMemoryEnabled
        : context.l10n.settingsWorkspaceMemoryLexical;
    final sections = _buildSections(workspaceMemorySubtitle);

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(title: context.l10n.settingsTitle, primary: true),
      body: SafeArea(
        child: ListView.separated(
          padding: const EdgeInsets.fromLTRB(18, 10, 18, 28),
          itemCount: sections.length,
          separatorBuilder: (_, __) => const SizedBox(height: 24),
          itemBuilder: (context, index) {
            return _buildSettingsSection(sections[index]);
          },
        ),
      ),
    );
  }

  List<_SettingSection> _buildSections(String workspaceMemorySubtitle) {
    return [
      _SettingSection(
        label: context.l10n.settingsSectionModelMemory,
        items: [
          _SettingItem(
            icon: Icons.smart_toy_outlined,
            iconSvg: 'assets/home/model_provider_setting_icon.svg',
            title: context.l10n.settingsModelProviderTitle,
            subtitle: context.l10n.settingsModelProviderSubtitle,
            onTap: () {
              GoRouterManager.push('/home/model_provider_setting');
            },
          ),
          _SettingItem(
            icon: Icons.tune_outlined,
            iconSvg: 'assets/home/scene_model_setting_icon.svg',
            title: context.l10n.settingsSceneModelTitle,
            subtitle: context.l10n.settingsSceneModelSubtitle,
            onTap: () {
              GoRouterManager.push('/home/scene_model_setting');
            },
          ),
          _SettingItem(
            icon: Icons.cloud_sync_outlined,
            iconSvg: 'assets/home/mem0_cloud_setting_icon.svg',
            title: context.l10n.settingsWorkspaceMemoryTitle,
            subtitle: workspaceMemorySubtitle,
            onTap: () async {
              await GoRouterManager.pushForResult(
                '/home/workspace_memory_setting',
              );
              _loadWorkspaceMemoryState();
            },
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionServiceEnvironment,
        items: [
          _SettingItem(
            icon: Icons.hub_outlined,
            title: context.trLegacy('Agent 模式'),
            subtitle: context.trLegacy('管理 ACP Agent、可用状态与统一模型绑定'),
            onTap: () {
              GoRouterManager.push('/home/agent_mode_setting');
            },
          ),
          _SettingItem(
            icon: Icons.code,
            iconSvg: 'assets/home/termux.svg',
            iconColor: AppColors.buttonPrimary,
            title: context.l10n.settingsAlpineTitle,
            subtitle: context.l10n.settingsAlpineSubtitle,
            onTap: () {
              GoRouterManager.push('/home/termux_setting');
            },
          ),
          _SettingItem(
            icon: Icons.cloud_outlined,
            iconSvg: 'assets/home/local_mcp_service_setting_icon.svg',
            title: context.l10n.settingsLocalServiceTitle,
            subtitle: context.l10n.settingsLocalServiceSubtitle,
            trailing: _buildSwitchTrailing(
              value: _mcpEnabled,
              enabled: !_mcpBusy,
              onToggle: (val) async {
                await _toggleMcpServer(val);
              },
            ),
            onTap: _mcpEnabled && !_mcpBusy ? _showMcpInfo : null,
          ),
          _SettingItem(
            icon: Icons.extension_outlined,
            iconSvg: 'assets/home/mcp_tools_setting_icon.svg',
            title: context.l10n.settingsMcpToolsTitle,
            subtitle: context.l10n.settingsMcpToolsSubtitle,
            onTap: () {
              GoRouterManager.push('/home/mcp_tools');
            },
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionExperienceAppearance,
        items: [
          _SettingItem(
            icon: Icons.wallpaper_outlined,
            title: context.l10n.settingsAppearanceTitle,
            subtitle: context.l10n.settingsAppearanceSubtitle,
            onTap: () {
              GoRouterManager.push('/home/background_setting');
            },
          ),
          _SettingItem(
            icon: Icons.more_horiz_rounded,
            iconSvg: 'assets/home/misc_blocks_setting_icon.svg',
            title: context.trLegacy('杂项'),
            subtitle: context.trLegacy('首页、后台隐藏、闹钟、振动与打开方式'),
            onTap: () {
              GoRouterManager.push('/home/experience_misc_setting');
            },
          ),
        ],
      ),
      _SettingSection(
        label: context.l10n.settingsSectionPermissionInfo,
        items: [
          _SettingItem(
            icon: Icons.admin_panel_settings_outlined,
            iconSvg: 'assets/home/app_permission_authorize_icon.svg',
            title: context.l10n.authorizePageTitle,
            subtitle: context.trLegacy('查看并配置悬浮窗、后台运行、Shizuku 等权限'),
            onTap: () {
              GoRouterManager.push('/home/authorize_setting');
            },
          ),
          _SettingItem(
            icon: Icons.storage_outlined,
            title: context.l10n.storageUsageTitle,
            subtitle: context.l10n.storageUsageSubtitle,
            onTap: () {
              GoRouterManager.push('/home/storage_usage');
            },
          ),
          _SettingItem(
            icon: Icons.info_outline,
            iconSvg: 'assets/home/about_icon.svg',
            title: context.l10n.settingsAboutTitle,
            onTap: () {
              GoRouterManager.push('/my/about');
            },
          ),
        ],
      ),
    ];
  }

  Widget _buildSettingsSection(_SettingSection section) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 4, 10),
          child: Text(
            context.trLegacy(section.label),
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.6,
              color: palette.textTertiary,
              fontFamily: 'PingFang SC',
            ),
          ),
        ),
        Column(
          children: List.generate(section.items.length, (index) {
            final isLast = index == section.items.length - 1;
            return Column(
              children: [
                _buildSettingTile(section.items[index], isLast: isLast),
                if (!isLast)
                  Padding(
                    padding: const EdgeInsets.only(left: 30),
                    child: Divider(
                      height: 1,
                      thickness: 1,
                      color: palette.borderSubtle.withValues(
                        alpha: context.isDarkTheme ? 0.5 : 0.78,
                      ),
                    ),
                  ),
              ],
            );
          }),
        ),
      ],
    );
  }

  Widget _buildSettingTile(_SettingItem item, {required bool isLast}) {
    final palette = context.omniPalette;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: item.onTap,
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Padding(
          padding: EdgeInsets.fromLTRB(4, 14, 2, isLast ? 14 : 13),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              _buildLeadingIcon(item),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      context.trLegacy(item.title),
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w500,
                        color: palette.textPrimary,
                        height: 1.5,
                        fontFamily: 'PingFang SC',
                      ),
                    ),
                    if (item.subtitle != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        context.trLegacy(item.subtitle!),
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 11,
                          fontFamily: 'PingFang SC',
                          fontWeight: FontWeight.w400,
                          height: 1.55,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (item.trailing != null)
                item.trailing!
              else if (item.onTap != null)
                Padding(
                  padding: const EdgeInsets.only(left: 12),
                  child: Icon(
                    Icons.chevron_right_rounded,
                    size: 18,
                    color: palette.textTertiary,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLeadingIcon(_SettingItem item) {
    final palette = context.omniPalette;
    final iconColor = item.iconColor ?? palette.textPrimary;
    return SizedBox(
      width: 18,
      height: 18,
      child: item.iconSvg != null
          ? SvgPicture.asset(
              item.iconSvg!,
              width: 18,
              height: 18,
              colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
            )
          : item.icon != null
          ? Icon(item.icon, size: 18, color: iconColor)
          : const SizedBox.shrink(),
    );
  }

  Widget _buildSwitchTrailing({
    required bool value,
    required ValueChanged<bool> onToggle,
    bool enabled = true,
  }) {
    final palette = context.omniPalette;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: enabled ? () => onToggle(!value) : null,
      child: Padding(
        padding: const EdgeInsets.only(left: 12),
        child: AbsorbPointer(
          child: Opacity(
            opacity: enabled ? 1 : 0.5,
            child: FlutterSwitch(
              width: 32,
              height: 18.67,
              toggleSize: 11.3,
              padding: 3,
              activeColor: palette.accentPrimary,
              inactiveColor: palette.borderStrong,
              borderRadius: 28.75,
              value: value,
              onToggle: onToggle,
            ),
          ),
        ),
      ),
    );
  }
}

class _SettingSection {
  final String label;
  final List<_SettingItem> items;

  const _SettingSection({required this.label, required this.items});
}

class _SettingItem {
  final IconData? icon;
  final String? iconSvg;
  final Color? iconColor;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _SettingItem({
    this.icon,
    this.iconSvg,
    this.iconColor,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
  });
}
