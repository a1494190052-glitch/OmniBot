import 'dart:ui';

import 'package:ui/services/storage_service.dart';

class AppTextLocalizer {
  static Locale? _activeLocale;

  static void setResolvedLocale(Locale locale) {
    _activeLocale = locale;
  }

  static void clearResolvedLocale() {
    _activeLocale = null;
  }

  static Locale get _resolvedLocale {
    final activeLocale = _activeLocale;
    if (activeLocale != null) return activeLocale;
    try {
      return StorageService.getResolvedLocale();
    } catch (_) {
      return PlatformDispatcher.instance.locale;
    }
  }

  static String text(String text, {Locale? locale}) {
    return (locale ?? _resolvedLocale).languageCode == 'en'
        ? _englishFallback[text] ?? text
        : text;
  }

  static String choose({
    required String zh,
    required String en,
    Locale? locale,
  }) {
    return (locale ?? _resolvedLocale).languageCode == 'en' ? en : zh;
  }

  static List<String> chooseList({
    required List<String> zh,
    required List<String> en,
    Locale? locale,
  }) {
    return (locale ?? _resolvedLocale).languageCode == 'en' ? en : zh;
  }

  static T chooseValue<T>({required T zh, required T en, Locale? locale}) {
    return (locale ?? _resolvedLocale).languageCode == 'en' ? en : zh;
  }

  static const Map<String, String> _englishFallback = {
    '全部': 'All',
    '桌面': 'Desktop',
  };
}
