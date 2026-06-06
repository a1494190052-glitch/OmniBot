import 'package:flutter/material.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/app_background_widgets.dart';

class OmnibotWorkspaceProjectFrontends extends StatelessWidget {
  final bool translucentSurfaces;

  const OmnibotWorkspaceProjectFrontends({
    super.key,
    this.translucentSurfaces = false,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final l10n = AppLocalizations.of(context);
    final surfaceColor = backgroundSurfaceColor(
      translucent: translucentSurfaces,
      baseColor: palette.surfacePrimary,
      opacity: 0.68,
    );

    return ColoredBox(
      color: Colors.transparent,
      child: Center(
        child: Container(
          margin: const EdgeInsets.all(24),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
          decoration: BoxDecoration(
            color: surfaceColor,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: palette.borderSubtle.withValues(alpha: 0.55)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.web_asset_rounded,
                size: 28,
                color: palette.textPrimary,
              ),
              const SizedBox(height: 10),
              Text(
                l10n?.workbenchWorkspaceProjectFrontendsTitle ??
                    'Project window',
                style: TextStyle(
                  color: palette.textPrimary,
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 6),
              Text(
                l10n?.workbenchWorkspaceProjectFrontendsEmpty ??
                    'No Project frontend is active.',
                style: TextStyle(color: palette.textSecondary, fontSize: 13),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
