import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test('groups completed agent run by parent task id', () {
    final entries = buildAgentRunTimelineEntries(_buildCompletedRunMessages());

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-1');
    expect(entries.first.group?.thinkingCount, 1);
    expect(entries.first.group?.toolCount, 1);
    expect(entries.first.group?.visibleMessagesNewestFirst.single.text, '最终回答');
  });

  test('falls back to latest text snapshot when history lacks isFinal', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-2-text-2',
        text: '第二版回答',
        taskId: 'task-2',
        kind: 'text_snapshot',
        seq: 22,
        isFinal: null,
      ),
      _assistantMessage(
        id: 'task-2-text-1',
        text: '第一版回答',
        taskId: 'task-2',
        kind: 'text_snapshot',
        seq: 21,
        isFinal: null,
      ),
      _thinkingCard(id: 'task-2-thinking', taskId: 'task-2', seq: 12),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(
      entries.single.group?.visibleMessagesNewestFirst.single.id,
      'task-2-text-2',
    );
  });

  test('groups in-flight task once active text snapshot exists', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: false),
      activeTaskIds: const <String>{'task-1'},
    );

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-1');
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.id,
      'task-1-text',
    );
  });

  test('keeps active run grouped when final text arrives before cleanup', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: true),
      activeTaskIds: const <String>{'task-1'},
    );

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-1');
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.id,
      'task-1-text',
    );
  });

  test(
    'does not fold persisted partial snapshot with explicit non-final flag',
    () {
      final messages = <ChatMessageModel>[
        _assistantMessage(
          id: 'task-4-text',
          text: '未完成回答',
          taskId: 'task-4',
          kind: 'text_snapshot',
          seq: 22,
          isFinal: false,
        ),
        _thinkingCard(id: 'task-4-thinking', taskId: 'task-4', seq: 12),
        ChatMessageModel.userMessage('用户问题', id: 'user-4'),
      ];

      final entries = buildAgentRunTimelineEntries(messages);

      expect(entries, hasLength(3));
      expect(entries.where((entry) => entry.group != null), isEmpty);
    },
  );

  test('keeps permission card visible alongside final permission text', () {
    final messages = <ChatMessageModel>[
      _cardMessage(
        id: 'task-3-permission-card',
        taskId: 'task-3',
        kind: 'permission_required',
        seq: 31,
        cardData: <String, dynamic>{
          'type': 'permission_section',
          'requiredPermissionIds': const <String>['overlay'],
        },
      ),
      _assistantMessage(
        id: 'task-3-permission-text',
        text: '请先授权',
        taskId: 'task-3',
        kind: 'permission_required',
        seq: 30,
        isFinal: true,
      ),
      _thinkingCard(id: 'task-3-thinking', taskId: 'task-3', seq: 10),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(entries.single.group?.visibleMessagesNewestFirst, hasLength(2));
    expect(
      entries.single.group?.visibleMessagesNewestFirst.map(
        (message) => message.id,
      ),
      containsAll(<String>['task-3-permission-card', 'task-3-permission-text']),
    );
  });

  test('groups active codex request as the visible run message', () {
    final messages = <ChatMessageModel>[
      _codexRequestCard(id: 'turn-7-request', taskId: 'turn-7', seq: 12),
      ChatMessageModel.userMessage('需要选择方案', id: 'user-7'),
    ];

    final entries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'turn-7'},
    );

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'turn-7');
    expect(entries.first.group?.processMessagesNewestFirst, isEmpty);
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.id,
      'turn-7-request',
    );
    expect(entries.last.message?.id, 'user-7');
  });

  test('keeps codex request visible after thinking process cards', () {
    final messages = <ChatMessageModel>[
      _codexRequestCard(id: 'turn-8-request', taskId: 'turn-8', seq: 22),
      _thinkingCard(id: 'turn-8-thinking', taskId: 'turn-8', seq: 10),
      ChatMessageModel.userMessage('继续计划吗', id: 'user-8'),
    ];

    final entries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'turn-8'},
    );

    final group = entries.first.group;
    expect(group?.taskId, 'turn-8');
    expect(group?.visibleMessagesNewestFirst.single.id, 'turn-8-request');
    expect(group?.processMessagesNewestFirst.single.id, 'turn-8-thinking');
  });

  test(
    'uses cancelled text as the visible body for a manually stopped run',
    () {
      final messages = <ChatMessageModel>[
        _assistantMessage(
          id: 'task-5-cancelled',
          text: '任务已取消',
          taskId: 'task-5',
          kind: 'text_snapshot',
          seq: 1000000000,
          isFinal: true,
        ),
        _thinkingCard(id: 'task-5-thinking', taskId: 'task-5', seq: 12),
      ];

      final entries = buildAgentRunTimelineEntries(messages);

      expect(entries, hasLength(1));
      expect(
        entries.single.group?.visibleMessagesNewestFirst.single.text,
        '任务已取消',
      );
      expect(
        entries.single.group?.processMessagesNewestFirst.single.id,
        'task-5-thinking',
      );
    },
  );

  test('keeps interleaved DeepSeek content inside fold by entry sequence', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-6-text-2',
        text: '任务已被手动停止。需要换一种方式发送吗？',
        taskId: 'task-6',
        kind: 'text_snapshot',
        seq: 105,
        entrySeq: 5,
        isFinal: true,
      ),
      _thinkingCard(
        id: 'task-6-thinking-2',
        taskId: 'task-6',
        seq: 104,
        entrySeq: 4,
      ),
      _cardMessage(
        id: 'task-6-tool-1',
        taskId: 'task-6',
        kind: 'tool_completed',
        seq: 69,
        entrySeq: 3,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'status': 'failed',
          'toolType': 'vlm',
          'toolTitle': '发送早安短信',
          'summary': '发送失败',
        },
      ),
      ChatMessageModel(
        id: 'task-6-text',
        type: 1,
        user: 2,
        content: <String, dynamic>{
          'id': 'task-6-text',
          'text': '好的，我来通过手机屏幕自动化发送这条短信。',
        },
      ),
      _thinkingCard(
        id: 'task-6-thinking',
        taskId: 'task-6',
        seq: 70,
        entrySeq: 1,
      ),
      ChatMessageModel.userMessage('用户问题', id: 'user-6'),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(2));
    final group = entries.first.group;
    expect(group?.taskId, 'task-6');
    expect(group?.visibleMessagesNewestFirst.single.id, 'task-6-text-2');
    expect(
      group?.processMessagesOldestFirst.map((message) => message.id),
      <String>[
        'task-6-thinking',
        'task-6-text',
        'task-6-tool-1',
        'task-6-thinking-2',
      ],
    );
    expect(entries.last.message?.id, 'user-6');
  });
}

List<ChatMessageModel> _buildCompletedRunMessages({bool isFinal = true}) {
  return <ChatMessageModel>[
    _assistantMessage(
      id: 'task-1-text',
      text: '最终回答',
      taskId: 'task-1',
      kind: 'text_snapshot',
      seq: 30,
      isFinal: isFinal,
    ),
    _cardMessage(
      id: 'task-1-tool',
      taskId: 'task-1',
      kind: 'tool_completed',
      seq: 20,
      cardData: <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '读取配置文件',
        'summary': '配置读取完成',
      },
    ),
    _thinkingCard(id: 'task-1-thinking', taskId: 'task-1', seq: 10),
    ChatMessageModel.userMessage('用户问题', id: 'user-1'),
  ];
}

ChatMessageModel _assistantMessage({
  required String id,
  required String text,
  required String taskId,
  required String kind,
  required int seq,
  int? entrySeq,
  bool? isFinal = false,
}) {
  return ChatMessageModel(
    id: id,
    type: 1,
    user: 2,
    content: <String, dynamic>{'text': text, 'id': id},
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': kind,
      'seq': seq,
      if (entrySeq != null) 'entrySeq': entrySeq,
      'entryId': id,
      if (isFinal != null) 'isFinal': isFinal,
    },
  );
}

ChatMessageModel _thinkingCard({
  required String id,
  required String taskId,
  required int seq,
  int? entrySeq,
}) {
  return _cardMessage(
    id: id,
    taskId: taskId,
    kind: 'thinking_snapshot',
    seq: seq,
    entrySeq: entrySeq,
    cardData: <String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': '思考过程',
      'stage': 4,
      'isLoading': false,
      'taskID': taskId,
      'cardId': id,
    },
  );
}

ChatMessageModel _codexRequestCard({
  required String id,
  required String taskId,
  required int seq,
}) {
  return _cardMessage(
    id: id,
    taskId: taskId,
    kind: 'clarify_required',
    seq: seq,
    cardData: <String, dynamic>{
      'type': 'codex_request',
      'taskId': taskId,
      'cardId': id,
      'requestId': id,
      'requestKind': 'user_input',
      'status': 'pending',
    },
  );
}

ChatMessageModel _cardMessage({
  required String id,
  required String taskId,
  required String kind,
  required int seq,
  int? entrySeq,
  required Map<String, dynamic> cardData,
}) {
  return ChatMessageModel.cardMessage(
    cardData,
    id: id,
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': kind,
      'seq': seq,
      if (entrySeq != null) 'entrySeq': entrySeq,
      'entryId': id,
      'isFinal': false,
    },
  );
}
