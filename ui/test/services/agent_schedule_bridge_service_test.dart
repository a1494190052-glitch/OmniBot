import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/agent_schedule_bridge_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  test(
    'stores scheduled reusable Function facts without tool policy',
    () async {
      final result = await AgentScheduleBridgeService.createTask({
        'taskId': 'schedule-open-settings',
        'title': '打开设置',
        'targetKind': 'subagent',
        'subagentPrompt': 'Function id: open_settings',
        'scheduleType': 'fixed_time',
        'fixedTime': '09:30',
        'repeatDaily': false,
        'enabled': false,
        'toolProfile': 'function_management',
        'allowedTools': ['oob_function_run'],
        'oobFunctionId': 'open_settings',
        'oobFunctionArguments': {'package_name': 'com.android.settings'},
      });

      expect(result['success'], true);
      final task = await ScheduledTaskStorageService.getScheduledTaskById(
        'schedule-open-settings',
      );
      final suggestionData = task?.suggestionData;

      expect(suggestionData?['oobFunctionId'], 'open_settings');
      expect(suggestionData?['oobFunctionArguments'], {
        'package_name': 'com.android.settings',
      });
      expect(suggestionData?.containsKey('toolProfile'), false);
      expect(suggestionData?.containsKey('allowedTools'), false);
    },
  );
}
