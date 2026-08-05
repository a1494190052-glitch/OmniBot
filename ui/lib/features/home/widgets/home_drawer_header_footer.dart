part of 'home_drawer.dart';

extension _HomeDrawerHeaderFooter on HomeDrawerState {
  Color get _drawerBackgroundColor {
    if (!context.isDarkTheme) {
      return AppColors.background;
    }
    return context.omniPalette.pageBackground;
  }

  Color get _drawerTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text;
    }
    return context.omniPalette.textPrimary;
  }

  Color get _drawerSecondaryTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text.withValues(alpha: 0.4);
    }
    return context.omniPalette.textSecondary;
  }

  Widget _buildGuideShortcut() {
    final palette = context.omniPalette;
    final foregroundColor = context.isDarkTheme
        ? palette.textSecondary
        : AppColors.text.withValues(alpha: 0.68);

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          key: const ValueKey('home-drawer-omnibot-guide'),
          onTap: () => _navigateTo('/home/first_use_tutorial'),
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            height: 28,
            child: Row(
              children: [
                Icon(
                  Icons.menu_book_outlined,
                  size: 17,
                  color: foregroundColor,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    context.l10n.omnibotGuide,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: foregroundColor,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                Icon(Icons.chevron_right, size: 17, color: foregroundColor),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFooterShortcutBar() {
    final items = <_DrawerShortcutAction>[
      _DrawerShortcutAction(
        label: context.l10n.settingsTitle,
        assetPath: 'assets/home/setting_icon.svg',
        onTap: () => _navigateTo('/home/settings'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.memoryCenterTitle,
        svgString: _kDrawerMemoryIconSvg,
        onTap: () => _navigateTo('/memory/memory_center_page'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.pluginMarketTitle,
        svgString: _kDrawerPluginMarketIconSvg,
        onTap: () => _navigateTo('/home/plugin_market'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.skillStoreTitle,
        svgString: _kDrawerSkillStoreIconSvg,
        onTap: () => _navigateTo('/home/skill_store'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.homeDrawerScheduled,
        assetPath: 'assets/common/schedule_icon.svg',
        onTap: () => _navigateTo('/task/scheduled_tasks'),
      ),
    ];

    const capsuleHeight = 44.0;
    final capsuleColor = context.isDarkTheme
        ? context.omniPalette.surfaceSecondary
        : Colors.white;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Material(
        color: capsuleColor,
        borderRadius: BorderRadius.circular(capsuleHeight / 2),
        clipBehavior: Clip.antiAlias,
        child: SizedBox(
          height: capsuleHeight,
          child: Row(
            children: items
                .map(
                  (item) => Expanded(
                    child: _buildFooterShortcutButton(
                      item,
                      height: capsuleHeight,
                    ),
                  ),
                )
                .toList(growable: false),
          ),
        ),
      ),
    );
  }

  Widget _buildFooterShortcutButton(
    _DrawerShortcutAction item, {
    required double height,
  }) {
    final palette = context.omniPalette;
    final iconColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final icon = item.assetPath != null
        ? SvgPicture.asset(
            item.assetPath!,
            width: 17,
            height: 17,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          )
        : SvgPicture.string(
            item.svgString!,
            width: 17,
            height: 17,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          );

    return Tooltip(
      message: item.label,
      child: InkWell(
        onTap: item.onTap,
        borderRadius: BorderRadius.circular(height / 2),
        child: SizedBox(
          height: height,
          child: Center(child: icon),
        ),
      ),
    );
  }
}
