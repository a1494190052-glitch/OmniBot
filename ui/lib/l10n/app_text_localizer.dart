import 'dart:ui';

class AppTextLocalizer {
  static String choose({
    required String zh,
    required String en,
    Locale? locale,
  }) {
    final code = locale?.languageCode.toLowerCase();
    return code == 'en' ? en : zh;
  }
}
