import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/chat_card_message_helpers.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_card_policy.dart';
import 'package:ui/services/assists_core_service.dart';

void main() {
  test('upserts OOB function progress as a shared summary card', () {
    final messages = <ChatMessageModel>[];
    final started = OobFunctionRunProgressEvent.fromMap(<String, dynamic>{
      'task_id': 'task-1',
      'run_id': 'run-1',
      'run_log_id': 'run-1',
      'function_id': 'open_settings',
      'status': 'running',
      'message': '第 1 步',
    });

    expect(
      ChatCardMessageHelpers.upsertOobFunctionRunProgress(messages, started),
      isTrue,
    );

    expect(messages, hasLength(1));
    expect(messages.single.cardData?['type'], kAgentToolSummaryCardType);
    expect(messages.single.cardData?['toolType'], 'oob_function');
    expect(messages.single.cardData?['runLogId'], 'run-1');

    final completed = OobFunctionRunProgressEvent.fromMap(<String, dynamic>{
      'task_id': 'task-1',
      'run_id': 'run-1',
      'run_log_id': 'run-1',
      'function_id': 'open_settings',
      'status': 'completed',
      'message': '任务已完成',
    });
    expect(
      ChatCardMessageHelpers.upsertOobFunctionRunProgress(messages, completed),
      isTrue,
    );

    expect(messages, hasLength(1));
    expect(messages.single.cardData?['status'], 'success');
    expect(messages.single.cardData?['toolName'], 'oob_function_run');
  });

  test('thinking card default id is shared by create and update', () {
    final created = ChatThinkingCardMessages.create(
      taskId: 'task-2',
      isDeepThinking: true,
      currentThinkingStage: 1,
      thinkingContent: '开始思考',
    );
    final cardId = ChatThinkingCardMessages.cardIdForTask('task-2');

    expect(created.id, cardId);
    expect(created.cardData?['cardId'], cardId);

    final updated = ChatThinkingCardMessages.update(
      existing: created,
      taskId: 'task-2',
      cardId: cardId,
      deepThinkingContent: '完成思考',
      isDeepThinking: false,
      currentThinkingStage: 4,
      stage: 4,
      isLoading: false,
    );

    expect(updated.id, cardId);
    expect(updated.cardData?['thinkingContent'], '完成思考');
    expect(updated.cardData?['stage'], 4);
    expect(updated.cardData?['isLoading'], isFalse);
    expect(updated.cardData?['endTime'], isNotNull);
  });

  test('thinking card helper can preserve runtime card id shape', () {
    final created = ChatThinkingCardMessages.create(
      taskId: 'task-runtime',
      defaultCardIdSuffix: '',
      includeCollapsible: false,
      isDeepThinking: true,
      currentThinkingStage: 1,
    );

    expect(created.id, 'task-runtime-thinking');
    expect(created.cardData?['isCollapsible'], isNull);

    final updated = ChatThinkingCardMessages.update(
      existing: created,
      taskId: 'task-runtime',
      cardId: 'task-runtime-thinking',
      deepThinkingContent: '继续思考',
      isDeepThinking: true,
      currentThinkingStage: 2,
      clearEndTimeWhenActive: false,
      includeCollapsible: false,
    );

    expect(updated.cardData?['stage'], 2);
    expect(updated.cardData?['isCollapsible'], isNull);
  });

  test('cancels latest thinking card for task in place', () {
    final messages = <ChatMessageModel>[
      ChatThinkingCardMessages.create(
        taskId: 'task-2',
        cardId: 'task-2-thinking-1',
        isDeepThinking: true,
        currentThinkingStage: 1,
      ),
      ChatThinkingCardMessages.create(
        taskId: 'task-2',
        cardId: 'task-2-thinking-2',
        isDeepThinking: true,
        currentThinkingStage: 2,
      ),
    ];

    final cancelled = ChatThinkingCardMessages.cancelForTask(
      messages,
      taskId: 'task-2',
    );

    expect(cancelled, isNotNull);
    expect(cancelled!.id, 'task-2-thinking-2');
    expect(
      cancelled.cardData?['stage'],
      ChatThinkingCardMessages.cancelledStage,
    );
    expect(messages.first.cardData?['stage'], 1);
    expect(
      messages.last.cardData?['stage'],
      ChatThinkingCardMessages.cancelledStage,
    );
  });

  test('upserts cancelled agent run message without duplicates', () {
    final messages = <ChatMessageModel>[];

    expect(
      ChatCardMessageHelpers.upsertCancelledAgentRunMessage(messages, 'task-3'),
      isTrue,
    );
    expect(
      ChatCardMessageHelpers.upsertCancelledAgentRunMessage(messages, 'task-3'),
      isTrue,
    );

    expect(messages, hasLength(1));
    expect(messages.single.id, 'task-3-cancelled');
    expect(messages.single.type, 1);
    expect(messages.single.user, 2);
    expect(messages.single.content?['renderMarkdown'], isFalse);
    expect(messages.single.streamMeta?['parentTaskId'], 'task-3');
    expect(messages.single.streamMeta?['isFinal'], isTrue);
  });

  test('reconciles and resolves link previews through shared helper', () {
    final message = ChatMessageModel.userMessage(
      '看看 https://example.com/page',
      id: 'message-1',
    );

    final previewUpdate = ChatLinkPreviewMessageHelpers.reconcile(message);

    expect(previewUpdate.didUpdate, isTrue);
    expect(previewUpdate.loadingUrls, ['https://example.com/page']);
    expect(previewUpdate.hasResolvedPreview, isFalse);

    final resolvedMessage = ChatLinkPreviewMessageHelpers.replaceLoadingPreview(
      previewUpdate.message,
      url: 'https://example.com/page',
      resolved: const ChatLinkPreview(
        url: 'https://example.com/page',
        domain: 'example.com',
        title: 'Example',
        status: ChatLinkPreview.statusReady,
      ),
    );

    expect(resolvedMessage, isNotNull);
    final resolvedUpdate = ChatLinkPreviewMessageHelpers.reconcile(
      resolvedMessage!,
    );
    expect(resolvedUpdate.didUpdate, isFalse);
    expect(resolvedUpdate.hasResolvedPreview, isTrue);
    expect(resolvedMessage.linkPreviews.single.title, 'Example');
  });
}
