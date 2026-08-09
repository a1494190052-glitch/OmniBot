import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

import '../onboarding_l10n.dart';
import '../widgets/user_guide_link_row.dart';

class PluginMarketGuidePage extends StatelessWidget {
  const PluginMarketGuidePage({super.key, this.onOpenRoute});

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
      key: const ValueKey('plugin-market-guide-page'),
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: onbTr(context, '插件市场指南', 'Plugin market guide'),
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
                const EdgeInsets.fromLTRB(20, 18, 20, 28),
              ),
              children: [
                Text(
                  onbTr(context, '按需给小万增加能力', 'Add capabilities when needed'),
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
                    '插件独立安装、启用和更新；不用时可以停用，不会把每种能力都塞进主应用。',
                    'Plugins install, enable, and update independently, so the main app stays focused.',
                  ),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: palette.textSecondary,
                    height: 1.55,
                  ),
                ),
                const SizedBox(height: 26),
                _SectionTitle(label: onbTr(context, '第一次使用', 'First use')),
                const SizedBox(height: 8),
                _GuideStep(
                  number: '01',
                  title: onbTr(context, '选择并启用', 'Choose and enable'),
                  description: onbTr(
                    context,
                    '进入插件市场，打开插件详情；未安装的先安装，安装完成后保持启用。',
                    'Open a plugin, install it if needed, then keep it enabled.',
                  ),
                ),
                const UserGuideRowDivider(),
                _GuideStep(
                  number: '02',
                  title: onbTr(
                    context,
                    '直接告诉小万要做什么',
                    'Tell Omnibot what to do',
                  ),
                  description: onbTr(
                    context,
                    '能力插件会给 Agent 增加对应工具。启用后，直接在聊天中描述你的任务即可。',
                    'Capability plugins add Agent tools. Once enabled, describe the task directly in chat.',
                  ),
                ),
                const UserGuideRowDivider(),
                _GuideStep(
                  number: '03',
                  title: onbTr(
                    context,
                    '从 Dashboard 或桌面进入',
                    'Open from Dashboard or Home Screen',
                  ),
                  description: onbTr(
                    context,
                    '插件详情可以进入 Dashboard；有界面的插件还能添加到桌面。生成的小 App 也会作为独立插件管理。',
                    'Open a Dashboard from plugin details or add visual plugins to the Home Screen. Generated Apps are managed as plugins too.',
                  ),
                ),
                const SizedBox(height: 28),
                _SectionTitle(label: onbTr(context, '推荐先体验', 'Start here')),
                const SizedBox(height: 8),
                UserGuideLinkRow(
                  tapKey: const ValueKey('plugin-guide-omniflow'),
                  icon: LucideIcons.smartphone,
                  title: 'OmniFlow',
                  description: onbTr(
                    context,
                    '让小万操作手机，并保存成功流程再次使用。',
                    'Let Omnibot operate the phone and reuse successful flows.',
                  ),
                  onTap: () => _open(
                    context,
                    '/home/plugin_market/com.omnimind.omni-vlm-lite',
                  ),
                ),
                const SizedBox(height: 24),
                FilledButton.icon(
                  key: const ValueKey('plugin-guide-open-market'),
                  onPressed: () => _open(context, '/home/plugin_market'),
                  icon: const Icon(Icons.extension_rounded),
                  label: Text(onbTr(context, '打开插件市场', 'Open plugin market')),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Text(
      label,
      style: Theme.of(context).textTheme.titleMedium?.copyWith(
        color: context.omniPalette.textPrimary,
        fontWeight: FontWeight.w700,
      ),
    );
  }
}

class _GuideStep extends StatelessWidget {
  const _GuideStep({
    required this.number,
    required this.title,
    required this.description,
  });

  final String number;
  final String title;
  final String description;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 15),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 40,
            child: Text(
              number,
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                color: palette.accentPrimary,
                fontWeight: FontWeight.w800,
                letterSpacing: 0.5,
              ),
            ),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(
                    color: palette.textPrimary,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 5),
                Text(
                  description,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: palette.textSecondary,
                    height: 1.5,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
