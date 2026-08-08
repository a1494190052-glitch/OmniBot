import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

import '../onboarding_definitions.dart';
import '../onboarding_l10n.dart';
import '../onboarding_provider_controller.dart';
import '../widgets/onboarding_common.dart';
import '../widgets/onboarding_option_row.dart';
import '../widgets/onboarding_page_scaffold.dart';

/// Step 4: choose which model provider to connect.
class OnboardingProviderPage extends StatelessWidget {
  const OnboardingProviderPage({
    super.key,
    required this.controller,
    required this.scrollController,
  });

  final OnboardingProviderController controller;
  final ScrollController scrollController;

  @override
  Widget build(BuildContext context) {
    return OnboardingPageScaffold(
      icon: LucideIcons.brainCircuit,
      title: onbTr(context, '选择模型提供商', 'Choose a model provider'),
      description: onbTr(
        context,
        '选择你已有账号或 API Key 的服务，下一页再填写连接信息。',
        'Choose a service for which you already have an account or API key. Connection details come next.',
      ),
      scrollController: scrollController,
      children: [
        if (controller.loading)
          OnboardingLoadingRow(
            label: onbTr(context, '正在读取已有模型配置…', 'Loading existing model settings…'),
          )
        else ...[
          for (var i = 0; i < providerOptions.length; i++) ...[
            if (i > 0) const OnboardingRowDivider(),
            _ProviderRow(option: providerOptions[i], controller: controller),
          ],
        ],
      ],
    );
  }
}

class _ProviderRow extends StatelessWidget {
  const _ProviderRow({required this.option, required this.controller});

  final ProviderOption option;
  final OnboardingProviderController controller;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final selected = option.id == controller.selectedProviderId;
    final displayLabel = option.id == 'custom'
        ? onbTr(context, '兼容 API', 'Compatible API')
        : option.label;
    final vendor = ModelVendorCatalog.byKey(option.vendorKey);
    final iconSurface = switch (option.vendorKey) {
      'moonshot' => const Color(0xFF111827),
      'deepseek' => const Color(0xFFEEF1FF),
      'xiaomi' => const Color(0xFFFFF1E8),
      _ => palette.surfaceSecondary,
    };
    final iconColor = switch (option.vendorKey) {
      'xiaomi' => const Color(0xFFFF6900),
      _ => palette.textPrimary,
    };
    return OnboardingOptionRow(
      tapKey: ValueKey<String>('tutorial-provider-${option.id}'),
      leading: Container(
        key: ValueKey<String>('tutorial-provider-icon-${option.id}'),
        width: 40,
        height: 40,
        decoration: BoxDecoration(
          color: iconSurface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: palette.borderSubtle.withValues(alpha: 0.8),
          ),
        ),
        alignment: Alignment.center,
        child: vendor == null
            ? Icon(LucideIcons.plugZap, size: 20, color: palette.textSecondary)
            : ProviderVendorIcon(
                vendor: vendor,
                size: 22,
                monochromeColor: iconColor,
              ),
      ),
      title: displayLabel,
      selected: selected,
      onTap: controller.busy
          ? null
          : () => controller.applyProviderOption(option),
    );
  }
}
