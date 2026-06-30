import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Helpers and chrome-aware layout constants for the desktop runner.
///
/// On macOS we hide the system title bar (`fullSizeContentView` +
/// `titlebarAppearsTransparent`) so the Flutter top bar can host the
/// traffic-light controls inline with the existing drawer / companion /
/// dynamic-island row. This file owns:
///
///   * platform detection that downstream layout code uses to switch into the
///     unified-bar variant;
///   * geometry constants for the area reserved for the traffic lights;
///   * the `omnibot/desktop_window` method channel that lets Flutter ask AppKit
///     to drag, zoom, or minimise the window.
const String _kDesktopWindowChannelName = 'omnibot/desktop_window';

const MethodChannel _desktopWindowChannel = MethodChannel(
  _kDesktopWindowChannelName,
);

/// True when the Flutter binary is running as a native desktop app on macOS.
/// On web (kIsWeb) the runtime platform reports as macOS too, hence the
/// explicit guard.
bool get isMacOSDesktopFlutter {
  if (kIsWeb) return false;
  return defaultTargetPlatform == TargetPlatform.macOS;
}

/// Width reserved on the left of the unified top bar for the macOS traffic
/// light buttons (close / minimise / zoom). Empirically the rightmost edge of
/// the zoom button on Sonoma sits at ~74dp; 84dp gives a tasteful gutter.
const double kMacOSTrafficLightsInset = 84.0;

/// Vertical space the traffic-light row reaches into the content. Used to top-
/// pad anything sitting at the top-left of the window (e.g. the embedded
/// HomeDrawer) so its contents do not collide with the system buttons.
const double kMacOSTitleBarHeight = 28.0;

/// Minimum window width below which we fall back to the mobile-style overlay
/// drawer instead of the split-pane shell.
const double kMacOSDesktopMinShellWidth = 720.0;

/// Ask AppKit to drag the window. Safe to call on non-macOS desktops — it just
/// no-ops because the method channel is not registered.
Future<void> startMacOSWindowDrag() async {
  if (!isMacOSDesktopFlutter) return;
  try {
    await _desktopWindowChannel.invokeMethod<void>('startDrag');
  } catch (_) {
    // Channel may not be registered in tests / unsupported builds.
  }
}

/// Ask AppKit to toggle zoom on the window (the green traffic light).
Future<void> toggleMacOSWindowMaximize() async {
  if (!isMacOSDesktopFlutter) return;
  try {
    await _desktopWindowChannel.invokeMethod<void>('toggleMaximize');
  } catch (_) {}
}

/// Ask AppKit to miniaturise (minimise) the window.
Future<void> minimizeMacOSWindow() async {
  if (!isMacOSDesktopFlutter) return;
  try {
    await _desktopWindowChannel.invokeMethod<void>('minimize');
  } catch (_) {}
}

/// Wraps [child] so that pressing on it (rather than tapping any interactive
/// descendant) initiates a window drag on macOS. We use `Listener` so the drag
/// gesture cooperates with native AppKit drag tracking — Flutter's
/// `GestureDetector.onPan*` would consume the down event before `performDrag`
/// could read it.
class MacOSWindowDragArea extends StatelessWidget {
  const MacOSWindowDragArea({
    super.key,
    required this.child,
    this.enabled = true,
    this.onDoubleTap,
  });

  final Widget child;
  final bool enabled;
  final VoidCallback? onDoubleTap;

  @override
  Widget build(BuildContext context) {
    if (!enabled || !isMacOSDesktopFlutter) {
      return child;
    }
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerDown: (event) {
        if (event.kind != PointerDeviceKind.mouse) return;
        if (event.buttons != kPrimaryMouseButton) return;
        // Defer to AppKit immediately on press; subsequent move events are
        // tracked natively by performDrag.
        // ignore: discarded_futures
        startMacOSWindowDrag();
      },
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onDoubleTap: onDoubleTap ?? toggleMacOSWindowMaximize,
        child: child,
      ),
    );
  }
}
