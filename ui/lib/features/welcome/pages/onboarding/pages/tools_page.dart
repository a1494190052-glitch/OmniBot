import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_definitions.dart';
import '../onboarding_environment_controller.dart';
import '../onboarding_l10n.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Step 3: pick optional tools and review what will be installed.
class OnboardingToolsPage extends StatelessWidget {
  const OnboardingToolsPage({
    super.key,
    required this.controller,
    required this.scrollController,
  });

  final OnboardingEnvironmentController controller;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context) {
    return OnboardingPageScaffold(
      icon: LucideIcons.packagePlus,
      title: onbTr(context, '添加需要的开发工具', 'Add the tools you need'),
      description: onbTr(
        context,
        '这些工具是可选项。编程 Agent 的账号登录可在安装完成后进行。',
        'These tools are optional. Sign in to coding agents after installation.',
      ),
      scrollController: scrollController,
      children: [
        Wrap(
          spacing: 10,
          runSpacing: 10,
          children: optionalTools
              .map((tool) => _OptionalToolChip(tool: tool, controller: controller))
              .toList(growable: false),
        ),
        const SizedBox(height: 26),
        _SetupSummary(controller: controller),
      ],
    );
  }
}

class _OptionalToolChip extends StatelessWidget {
  const _OptionalToolChip({required this.tool, required this.controller});

  final OptionalTool tool;
  final OnboardingEnvironmentController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selected = controller.optionalToolIds.contains(tool.id);
    return Semantics(
      button: true,
      selected: selected,
      label: '${tool.label}, ${onbTr(context, tool.descriptionZh, tool.descriptionEn)}',
      child: FilterChip(
        key: ValueKey<String>('tutorial-tool-${tool.id}'),
        selected: selected,
        onSelected: controller.isBusy
            ? null
            : (_) => controller.toggleOptionalTool(tool.id),
        avatar: Icon(
          tool.icon,
          size: 16,
          color: selected
              ? Theme.of(context).colorScheme.onPrimary
              : palette.textSecondary,
        ),
        label: Text(tool.label),
        tooltip: onbTr(context, tool.descriptionZh, tool.descriptionEn),
        showCheckmark: false,
        selectedColor: palette.accentPrimary,
        backgroundColor: palette.surfacePrimary,
        side: BorderSide(
          color: selected ? palette.accentPrimary : palette.borderSubtle,
        ),
        labelStyle: Theme.of(context).textTheme.labelMedium?.copyWith(
          color: selected
              ? Theme.of(context).colorScheme.onPrimary
              : palette.textPrimary,
          fontWeight: FontWeight.w600,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 9),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
      ),
    );
  }
}

/// Flat summary of what the setup will install: icon rows separated by
/// hairlines instead of a bordered box.
class _SetupSummary extends StatelessWidget {
  const _SetupSummary({required this.controller});

  final OnboardingEnvironmentController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final preset = controller.selectedPreset;
    final extras = controller.selectedToolLabels;

    Widget summaryRow(IconData icon, String value) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(top: 1),
            child: Icon(icon, size: 16, color: palette.accentPrimary),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              value,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: palette.textSecondary,
                height: 1.5,
              ),
            ),
          ),
        ],
      );
    }

    Widget hairline() => Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Divider(height: 1, thickness: 1, color: palette.borderSubtle),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(LucideIcons.listChecks, size: 17, color: palette.accentPrimary),
            const SizedBox(width: 8),
            Text(
              onbTr(context, '将要配置', 'Setup summary'),
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                color: palette.textPrimary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
        const SizedBox(height: 14),
        summaryRow(
          LucideIcons.server,
          '${controller.distributionName} · ${onbTr(context, preset.titleZh, preset.titleEn)}',
        ),
        hairline(),
        summaryRow(LucideIcons.codeXml, preset.contents),
        if (extras.isNotEmpty) ...[
          hairline(),
          summaryRow(
            LucideIcons.packagePlus,
            '${onbTr(context, '附加', 'Extras')}: $extras',
          ),
        ],
      ],
    );
  }
}
