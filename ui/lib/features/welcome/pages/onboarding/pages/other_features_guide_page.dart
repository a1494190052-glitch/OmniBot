import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

import '../onboarding_l10n.dart';
import '../widgets/user_guide_link_row.dart';

class OtherFeaturesGuidePage extends StatelessWidget {
  const OtherFeaturesGuidePage({super.key, this.onOpenRoute});

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
    final entries = <_FeatureGuideEntry>[
      _FeatureGuideEntry(
        keyName: 'vlm',
        icon: LucideIcons.smartphone,
        title: onbTr(context, 'VLM 与 GUI 操作', 'VLM and GUI actions'),
        description: onbTr(
          context,
          '查看在线 VLM，并安装手动录制、Function、OmniTransfer 与重放能力。',
          'Use online VLM and install recording, Functions, OmniTransfer, and replay.',
        ),
        route: '/home/plugin_market/com.omnimind.omni-vlm-lite',
      ),
      _FeatureGuideEntry(
        keyName: 'runlog',
        icon: LucideIcons.history,
        title: onbTr(context, 'RunLog 与复用指令', 'RunLog and reusable functions'),
        description: onbTr(
          context,
          '查看执行记录，将成功流程注册、增强并按参数重放。',
          'Review runs, register successful flows, enhance them, and replay with parameters.',
        ),
        route: '/task/omniflow?tab=run_logs',
      ),
      _FeatureGuideEntry(
        keyName: 'memory',
        icon: LucideIcons.brainCircuit,
        title: onbTr(context, 'Memory 管理', 'Memory management'),
        description: onbTr(
          context,
          '查看、整理和维护对话与工作区长期记忆。',
          'Review and maintain long-term conversation and workspace memory.',
        ),
        route: '/memory/memory_center_page',
      ),
      _FeatureGuideEntry(
        keyName: 'plugins',
        icon: LucideIcons.blocks,
        title: onbTr(context, '插件市场', 'Plugin market'),
        description: onbTr(
          context,
          '安装、更新或停用按需扩展的能力组件。',
          'Install, update, or disable optional capability bundles.',
        ),
        route: '/home/plugin_market',
      ),
      _FeatureGuideEntry(
        keyName: 'skills',
        icon: LucideIcons.sparkles,
        title: onbTr(context, 'Skills', 'Skills'),
        description: onbTr(
          context,
          '添加可复用的任务说明、工作流和专业能力。',
          'Add reusable task instructions, workflows, and specialized capabilities.',
        ),
        route: '/home/skill_store',
      ),
    ];

    return Scaffold(
      key: const ValueKey('other-features-guide-page'),
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: onbTr(context, '高级功能支持', 'Advanced features'),
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
                const EdgeInsets.fromLTRB(20, 16, 20, 28),
              ),
              children: [
                Text(
                  onbTr(
                    context,
                    '这些能力可以随时查看和配置，不影响先开始聊天。',
                    'These capabilities can be configured anytime and never block starting a chat.',
                  ),
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: palette.textSecondary,
                    height: 1.55,
                  ),
                ),
                const SizedBox(height: 12),
                for (var index = 0; index < entries.length; index++) ...[
                  if (index > 0) const UserGuideRowDivider(),
                  UserGuideLinkRow(
                    tapKey: ValueKey<String>(
                      'other-features-${entries[index].keyName}',
                    ),
                    icon: entries[index].icon,
                    title: entries[index].title,
                    description: entries[index].description,
                    onTap: () => _open(context, entries[index].route),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _FeatureGuideEntry {
  const _FeatureGuideEntry({
    required this.keyName,
    required this.icon,
    required this.title,
    required this.description,
    required this.route,
  });

  final String keyName;
  final IconData icon;
  final String title;
  final String description;
  final String route;
}
