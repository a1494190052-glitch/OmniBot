import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/agent_schedule_bridge_service.dart';
import 'package:ui/services/scheduled_task_scheduler_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'deleteWorkspaceScheduledTask':
              return {'deleted': true};
            case 'syncWorkspaceScheduledTasks':
              return {'count': 0};
          }
          return null;
        });
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
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
        'allowedTools': ['legacy_function_run'],
        'oobFunctionId': 'open_settings',
        'oobFunctionArguments': {'package_name': 'com.android.settings'},
      });

      expect(result['success'], true);
      final summary = Map<String, dynamic>.from(result['task'] as Map);
      expect(summary['oobFunctionId'], 'open_settings');
      expect(summary['oobFunctionArguments'], {
        'package_name': 'com.android.settings',
      });
      expect(summary.containsKey('toolProfile'), false);
      expect(summary.containsKey('allowedTools'), false);

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

  test('canonicalizes stored subagent tool profile when scheduling', () {
    final task = ScheduledTask(
      id: 'schedule-subagent-legacy-profile',
      title: 'legacy profile',
      packageName: '',
      nodeId: '',
      suggestionId: '',
      targetKind: 'subagent',
      subagentPrompt: 'run task',
      type: ScheduledTaskType.fixedTime,
      fixedTime: '09:30',
      createdAt: 1,
      suggestionData: const {
        'targetKind': 'subagent',
        'subagentPrompt': 'run task',
        'toolProfile': ' function-management ',
        'allowedTools': ['vlm_task'],
      },
    );

    final policy = ScheduledTaskSchedulerService.subagentAgentPolicyForTask(
      task,
    );

    expect(policy.toolProfile, 'function');
    expect(policy.allowedTools, ['vlm_task']);
  });
}
