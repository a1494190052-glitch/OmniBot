import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/mixins/task_execution_handler.dart';
import 'package:ui/features/home/pages/chat/services/openclaw_context_scope.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test('latest runtime input never falls back to an older user message', () {
    final latestAttachmentOnly = ChatMessageModel.userMessage('');
    final olderText = ChatMessageModel.userMessage('old context');

    expect(
      latestUserMessageForRuntimeContext([latestAttachmentOnly, olderText]),
      same(latestAttachmentOnly),
    );
  });

  test('OpenClaw session key is stable within one conversation', () {
    expect(
      buildOpenClawConversationSessionKey(userId: 'user-1', conversationId: 7),
      'openclaw:user-1:conversation:7',
    );
    expect(
      buildOpenClawConversationSessionKey(userId: 'user-1'),
      'openclaw:user-1',
    );
  });
}
