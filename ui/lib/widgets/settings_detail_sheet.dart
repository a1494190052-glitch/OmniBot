import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';

ButtonStyle settingsDetailSheetActionStyle(
  BuildContext context, {
  Color? foregroundColor,
}) {
  return TextButton.styleFrom(
    foregroundColor: foregroundColor ?? context.omniPalette.accentPrimary,
    minimumSize: const Size(0, 40),
    padding: const EdgeInsets.symmetric(horizontal: 6),
  );
}

/// Compact bottom-sheet surface used by settings detail cards.
class SettingsDetailSheet extends StatelessWidget {
  const SettingsDetailSheet({
    super.key,
    required this.title,
    required this.body,
    this.subtitle,
    this.actions = const <Widget>[],
    this.actionsKey,
    this.footer,
  });

  final String title;
  final String? subtitle;
  final Widget body;
  final List<Widget> actions;
  final Key? actionsKey;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return SafeArea(
      top: false,
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            if (subtitle != null) ...[
              const SizedBox(height: 4),
              Text(
                subtitle!,
                style: TextStyle(
                  fontSize: 12,
                  height: 1.45,
                  color: palette.textSecondary,
                ),
              ),
            ],
            const SizedBox(height: 12),
            body,
            if (actions.isNotEmpty) ...[
              const SizedBox(height: 12),
              Wrap(
                key: actionsKey,
                spacing: 4,
                runSpacing: 4,
                alignment: WrapAlignment.start,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: actions,
              ),
            ],
            if (footer != null) ...[const SizedBox(height: 8), footer!],
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}
