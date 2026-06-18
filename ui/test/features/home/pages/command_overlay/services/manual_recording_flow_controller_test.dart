import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';

void main() {
  test('manual recording command accepts visible shortcut labels', () {
    expect(ManualRecordingFlowController.isCommand('录制轨迹'), isTrue);
    expect(ManualRecordingFlowController.isCommand('开始录制轨迹'), isTrue);
    expect(ManualRecordingFlowController.isCommand('轨迹录制'), isTrue);
    expect(
      ManualRecordingFlowController.isCommand('Record trajectory'),
      isTrue,
    );
  });

  test('manual recording command rejects ordinary chat text', () {
    expect(ManualRecordingFlowController.isCommand('帮我打开设置'), isFalse);
    expect(ManualRecordingFlowController.isCommand(''), isFalse);
  });
}
