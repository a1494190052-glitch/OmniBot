import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/webchat/web_chat_app.dart';
import 'package:ui/webchat/web_chat_fonts.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  // Preload the bundled CJK font into the engine's font collection so the
  // very first frame already paints Chinese glyphs (eliminates the brief
  // "tofu" flash caused by CanvasKit's async font fallback download).
  await WebChatFonts.ensureLoaded();
  runApp(const WebChatApp());
}
