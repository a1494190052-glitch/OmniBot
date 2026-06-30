import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Preloads the bundled CJK font that backs the LAN web chat UI.
///
/// Flutter Web's CanvasKit renderer ships only basic Latin/Material fonts in
/// the engine's font collection. Any glyph outside that range triggers an
/// asynchronous fallback download from gstatic.com, which is what causes the
/// "rectangle with an X" tofu flash before Chinese text appears. By loading
/// our bundled Simplified Chinese font into the engine before the first
/// runApp() frame, the first paint already has all required glyphs.
class WebChatFonts {
  WebChatFonts._();

  static const String family = 'OmnibotWebCjk';
  static const String _assetPath = 'assets/fonts/OmnibotWebCjk.otf';

  static bool _loaded = false;
  static Future<void>? _loadingFuture;

  static bool get isLoaded => _loaded;

  static Future<void> ensureLoaded() {
    if (_loaded) return Future<void>.value();
    final existing = _loadingFuture;
    if (existing != null) return existing;
    final future = _load();
    _loadingFuture = future;
    return future;
  }

  static Future<void> _load() async {
    try {
      final loader = FontLoader(family)..addFont(rootBundle.load(_assetPath));
      await loader.load();
      _loaded = true;
    } catch (error, stack) {
      _loadingFuture = null;
      debugPrint('WebChatFonts: failed to preload bundled CJK font: $error');
      debugPrintStack(stackTrace: stack);
    }
  }
}
