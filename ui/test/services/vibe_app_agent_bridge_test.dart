import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/vibe_app_agent_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('starts Xiaowan task and forwards only matching events', () async {
    final gateway = _FakeGateway();
    final events = <Map<String, dynamic>>[];
    final bridge = VibeAppAgentBridge(
      pluginId: 'local.project.fitness-beast',
      appTitle: '健身兽',
      gateway: gateway,
      taskIdFactory: () => 'vibe-run-1',
      onEvent: events.add,
    );

    await bridge.initialize();
    final result = await bridge.send(<String, dynamic>{
      'text': '给我安排下周训练',
      'context': <String, dynamic>{'page': 'weekly-plan'},
    });

    expect(result['runId'], 'vibe-run-1');
    expect(gateway.startedConversationId, 42);
    expect(gateway.startedReasoningEffort, 'none');
    expect(gateway.startedMessage, contains('local.project.fitness-beast'));
    gateway.emit(_event(taskId: 'another-task', text: 'ignore'));
    gateway.emit(_event(taskId: 'vibe-run-1', text: '第一天深蹲'));
    await Future<void>.delayed(Duration.zero);

    expect(events, hasLength(2));
    expect(events.last['text'], '第一天深蹲');
    bridge.dispose();
  });
}

AgentStreamEvent _event({required String taskId, String text = ''}) {
  return AgentStreamEvent(
    taskId: taskId,
    seq: 1,
    kind: AgentStreamEventKind.textSnapshot,
    createdAtMs: 100,
    text: text,
    raw: <String, dynamic>{
      'taskId': taskId,
      'kind': AgentStreamEventKind.textSnapshot.value,
      'text': text,
    },
  );
}

class _FakeGateway implements VibeAppAgentGateway {
  final List<AgentStreamEventCallback> listeners = <AgentStreamEventCallback>[];
  int? startedConversationId;
  String? startedMessage;
  String? startedReasoningEffort;

  @override
  void addStreamListener(AgentStreamEventCallback listener) =>
      listeners.add(listener);

  @override
  Future<bool> cancelTask(String taskId) async => true;

  @override
  Future<int?> createConversation({
    required String title,
    String? summary,
  }) async => 42;

  @override
  Future<List<ConversationModel>> getConversations() async =>
      const <ConversationModel>[];

  @override
  void removeStreamListener(AgentStreamEventCallback listener) =>
      listeners.remove(listener);

  @override
  Future<bool> startTask({
    required String taskId,
    required String userMessage,
    required int conversationId,
    required String reasoningEffort,
  }) async {
    startedConversationId = conversationId;
    startedMessage = userMessage;
    startedReasoningEffort = reasoningEffort;
    return true;
  }

  void emit(AgentStreamEvent event) {
    for (final listener in List<AgentStreamEventCallback>.from(listeners)) {
      listener(event);
    }
  }
}
