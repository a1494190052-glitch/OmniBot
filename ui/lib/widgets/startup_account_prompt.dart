import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/account/account_page.dart';
import 'package:ui/services/account_service.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/theme_context.dart';

typedef StartupAccountSessionLoader = Future<AccountSessionState> Function();
typedef StartupVersionRefresh = Future<void> Function();

/// Checks account state once per app launch and presents the shared account
/// form as a dismissible card after the first-use tutorial has been completed.
class StartupAccountPrompt extends StatefulWidget {
  const StartupAccountPrompt({
    super.key,
    required this.child,
    this.navigatorKey,
    this.loadSession,
    this.refreshVersionPolicy,
  });

  final Widget child;
  final GlobalKey<NavigatorState>? navigatorKey;
  final StartupAccountSessionLoader? loadSession;
  final StartupVersionRefresh? refreshVersionPolicy;

  @override
  State<StartupAccountPrompt> createState() => _StartupAccountPromptState();
}

class _StartupAccountPromptState extends State<StartupAccountPrompt> {
  bool _checked = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_checkAccount());
    });
  }

  Future<void> _checkAccount() async {
    if (_checked || GoRouterManager.isSubEngine) return;
    _checked = true;
    final onboardingCompleted =
        StorageService.getBool(
          StorageKeys.welcomeCompleted,
          defaultValue: false,
        ) ??
        false;
    if (!onboardingCompleted) return;

    try {
      final refreshVersionPolicy =
          widget.refreshVersionPolicy ??
          () async {
            await AppUpdateService.refreshIfNeeded();
          };
      await refreshVersionPolicy();
      final session =
          await (widget.loadSession ?? AccountService.getSessionState)();
      if (!mounted ||
          !session.configured ||
          session.signedIn ||
          !session.cloudServicePolicyKnown ||
          !session.cloudServiceAccessAllowed) {
        return;
      }
      final navigator =
          widget.navigatorKey?.currentState ??
          GoRouterManager.rootNavigatorKey.currentState;
      final navigatorContext = navigator?.context;
      if (navigatorContext == null || !navigatorContext.mounted) return;
      await showDialog<bool>(
        context: navigatorContext,
        barrierDismissible: true,
        builder: (dialogContext) => _StartupAccountCard(
          onAuthenticated: () => Navigator.of(dialogContext).pop(true),
        ),
      );
    } catch (_) {
      // Startup must stay non-blocking when the account or update service is
      // temporarily unavailable.
    }
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

class _StartupAccountCard extends StatelessWidget {
  const _StartupAccountCard({required this.onAuthenticated});

  static const _darkHeader = 'assets/my/atmosphere-dark-satin-02.webp';
  static const _lightHeader = 'assets/my/atmosphere-light-mineral-02.webp';

  final VoidCallback onAuthenticated;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final availableHeight = MediaQuery.sizeOf(context).height - 40;
    return Dialog(
      key: const ValueKey('startup-account-card'),
      insetPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 20),
      backgroundColor: palette.surfacePrimary,
      surfaceTintColor: Colors.transparent,
      clipBehavior: Clip.antiAlias,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: 480, maxHeight: availableHeight),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AspectRatio(
              aspectRatio: 2.2,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Image.asset(
                    context.isDarkTheme ? _darkHeader : _lightHeader,
                    key: const ValueKey('startup-account-header-image'),
                    fit: BoxFit.cover,
                    alignment: context.isDarkTheme
                        ? Alignment.centerRight
                        : Alignment.center,
                  ),
                  Positioned(
                    top: 10,
                    right: 10,
                    child: IconButton.filledTonal(
                      key: const ValueKey('startup-account-close'),
                      onPressed: () => Navigator.of(context).pop(false),
                      icon: const Icon(LucideIcons.x, size: 18),
                      tooltip:
                          Localizations.localeOf(context).languageCode == 'zh'
                          ? '稍后再说'
                          : 'Maybe later',
                      style: IconButton.styleFrom(
                        backgroundColor: palette.surfacePrimary.withValues(
                          alpha: 0.82,
                        ),
                        foregroundColor: palette.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: AccountPage.authOnly(
                key: const ValueKey('startup-account-auth-form'),
                onAuthenticated: onAuthenticated,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
