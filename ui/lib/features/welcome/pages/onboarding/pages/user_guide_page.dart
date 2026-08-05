import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

import '../onboarding_l10n.dart';
import '../widgets/user_guide_link_row.dart';

class UserGuidePage extends StatelessWidget {
  const UserGuidePage({super.key, this.onOpenRoute});

  final ValueChanged<String>? onOpenRoute;

  void _open(BuildContext context, String route) {
    final callback = onOpenRoute;
    if (callback != null) {
      callback(route);
      return;
    }
    context.push(route);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      key: const ValueKey('user-guide-page'),
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: onbTr(context, '小万指南', 'Omnibot Guide'),
        primary: true,
      ),
      body: SafeArea(
        top: false,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: ListView(
              padding: edgeToEdgeScrollPadding(
                context,
                const EdgeInsets.fromLTRB(20, 20, 20, 28),
              ),
              children: [
                Text(
                  onbTr(context, '按需查看', 'Browse by topic'),
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    color: palette.textPrimary,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -0.35,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  onbTr(
                    context,
                    '快速完成基础配置，或直接了解已经内置和可安装的扩展能力。',
                    'Finish the basics or jump directly to built-in and installable capabilities.',
                  ),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: palette.textSecondary,
                    height: 1.55,
                  ),
                ),
                const SizedBox(height: 18),
                UserGuideLinkRow(
                  tapKey: const ValueKey('user-guide-quick-start'),
                  icon: LucideIcons.rocket,
                  title: onbTr(context, '快速开始', 'Quick start'),
                  description: onbTr(
                    context,
                    '聊天 Agent、开发环境、权限与可选模型配置。',
                    'Chat agents, local setup, permissions, and optional model configuration.',
                  ),
                  onTap: () => _open(context, '/home/first_use_tutorial/setup'),
                ),
                const UserGuideRowDivider(),
                UserGuideLinkRow(
                  tapKey: const ValueKey('user-guide-plugin-market'),
                  icon: LucideIcons.packageSearch,
                  title: onbTr(context, '插件市场', 'Plugin market'),
                  description: onbTr(
                    context,
                    '安装和启用能力插件，进入 Dashboard，并把小 App 添加到桌面。',
                    'Install capability plugins, open Dashboards, and add mini Apps to the Home Screen.',
                  ),
                  onTap: () =>
                      _open(context, '/home/first_use_tutorial/plugins'),
                ),
                const UserGuideRowDivider(),
                UserGuideLinkRow(
                  tapKey: const ValueKey('user-guide-other-features'),
                  icon: LucideIcons.blocks,
                  title: onbTr(context, '高级功能支持', 'Advanced features'),
                  description: onbTr(
                    context,
                    'VLM、Memory、插件、RunLog、复用指令与 Skills。',
                    'VLM, Memory, plugins, RunLog, reusable functions, and Skills.',
                  ),
                  onTap: () =>
                      _open(context, '/home/first_use_tutorial/features'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
