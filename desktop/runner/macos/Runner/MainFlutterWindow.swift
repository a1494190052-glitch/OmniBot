import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  override func awakeFromNib() {
    // Storyboard loads us before applicationWillFinishLaunching, so kick off the
    // backend here if needed.
    if BackendSupervisor.shared.port == nil {
      do {
        _ = try BackendSupervisor.shared.start()
        _supervisorLogWindow("started backend from awakeFromNib")
      } catch {
        _supervisorLogWindow("backend start FAILED in awakeFromNib: \(error)")
      }
    }
    let project = FlutterDartProject()
    var args: [String] = []
    if let port = BackendSupervisor.shared.port {
      args.append("--backend-port=\(port)")
    }
    project.dartEntrypointArguments = args
    _supervisorLogWindow("MainFlutterWindow.awakeFromNib: dartEntrypointArguments=\(args)")

    let flutterViewController = FlutterViewController(project: project)
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)

    // Merge the macOS title bar with the Flutter top bar: keep the traffic
    // lights but let Flutter content extend underneath them. The Flutter side
    // is responsible for reserving the inset and providing a draggable region.
    self.titleVisibility = .hidden
    self.titlebarAppearsTransparent = true
    self.styleMask.insert(.fullSizeContentView)
    self.isMovableByWindowBackground = false

    RegisterGeneratedPlugins(registry: flutterViewController)
    DesktopWindowChannel.register(with: flutterViewController, window: self)

    super.awakeFromNib()
  }
}

/// Bridges Flutter UI to NSWindow drag/zoom controls so the unified Flutter top
/// bar can move the window like a real macOS title bar.
final class DesktopWindowChannel {
  private static let channelName = "omnibot/desktop_window"

  static func register(with controller: FlutterViewController, window: NSWindow) {
    let channel = FlutterMethodChannel(
      name: channelName,
      binaryMessenger: controller.engine.binaryMessenger
    )
    let handler = DesktopWindowChannel(window: window)
    channel.setMethodCallHandler { [weak handler] call, result in
      handler?.handle(call: call, result: result) ?? result(FlutterMethodNotImplemented)
    }
  }

  private weak var window: NSWindow?

  private init(window: NSWindow) {
    self.window = window
  }

  private func handle(call: FlutterMethodCall, result: @escaping FlutterResult) {
    guard let window = window else {
      result(FlutterError(code: "NO_WINDOW", message: "Window has been released", details: nil))
      return
    }
    switch call.method {
    case "startDrag":
      // Use the current event when available (e.g. mouseDown still in flight),
      // otherwise synthesise one so calls from later in the event loop still work.
      if let event = NSApp.currentEvent {
        window.performDrag(with: event)
      } else if let synthetic = Self.makeSyntheticDragEvent(for: window) {
        window.performDrag(with: synthetic)
      }
      result(nil)
    case "toggleMaximize":
      window.zoom(nil)
      result(nil)
    case "minimize":
      window.miniaturize(nil)
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private static func makeSyntheticDragEvent(for window: NSWindow) -> NSEvent? {
    let location = NSEvent.mouseLocation
    let windowOrigin = window.frame.origin
    let local = NSPoint(x: location.x - windowOrigin.x, y: location.y - windowOrigin.y)
    return NSEvent.mouseEvent(
      with: .leftMouseDown,
      location: local,
      modifierFlags: [],
      timestamp: ProcessInfo.processInfo.systemUptime,
      windowNumber: window.windowNumber,
      context: nil,
      eventNumber: 0,
      clickCount: 1,
      pressure: 1.0
    )
  }
}

fileprivate func _supervisorLogWindow(_ message: String) {
  let path = (NSHomeDirectory() as NSString).appendingPathComponent("Library/Logs/OmnibotApp/supervisor.log")
  try? FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
  if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil) }
  if let fh = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) {
    try? fh.seekToEnd()
    try? fh.write(contentsOf: Data("[window] \(message)\n".utf8))
    try? fh.close()
  }
}
