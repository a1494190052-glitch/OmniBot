import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import '../onboarding_definitions.dart';
import '../onboarding_environment_controller.dart';
import '../onboarding_l10n.dart';
import '../widgets/onboarding_option_row.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Step 2: pick a starter development-environment preset.
class OnboardingDevelopmentPage extends StatelessWidget {
  const OnboardingDevelopmentPage({
    super.key,
    required this.controller,
    required this.scrollController,
  });

  final OnboardingEnvironmentController controller;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context) {
    return OnboardingPageScaffold(
      icon: LucideIcons.codeXml,
      title: onbTr(context, '选择开发环境', 'Choose a development setup'),
      description: onbTr(
        context,
        '选择最接近你日常工作的初始工具组合，之后仍可单独增删。',
        'Pick the starter toolset closest to your work. Components can be changed later.',
      ),
      scrollController: scrollController,
      children: [
        for (var i = 0; i < environmentPresets.length; i++) ...[
          if (i > 0) const OnboardingRowDivider(),
          _PresetRow(preset: environmentPresets[i], controller: controller),
        ],
      ],
    );
  }
}

class _PresetRow extends StatelessWidget {
  const _PresetRow({required this.preset, required this.controller});

  final EnvironmentPreset preset;
  final OnboardingEnvironmentController controller;

  @override
  Widget build(BuildContext context) {
    final selected = preset.id == controller.presetId;
    return OnboardingOptionRow(
      tapKey: ValueKey<String>('tutorial-environment-${preset.id}'),
      leading: OnboardingOptionIcon(icon: preset.icon, selected: selected),
      title: onbTr(context, preset.titleZh, preset.titleEn),
      description: onbTr(context, preset.descriptionZh, preset.descriptionEn),
      detail: preset.contents,
      selected: selected,
      onTap: controller.isBusy
          ? null
          : () => controller.selectPreset(preset.id),
    );
  }
}
