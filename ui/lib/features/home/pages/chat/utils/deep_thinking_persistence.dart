import 'dart:async';

import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/conversation_history_service.dart';

const int kMaxPersistedThinkingChars = 16 * 1024;
const String _thinkingTruncationNotice = '[Earlier reasoning omitted]\n';

bool isDeepThinkingCardMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  return message.type == 2 && cardData?['type'] == 'deep_thinking';
}

Future<void> upsertPersistentDeepThinkingUiCard({
  required int conversationId,
  required ChatMessageModel message,
  ConversationMode mode = ConversationMode.normal,
}) async {
  final cardData = message.cardData;
  if (!isDeepThinkingCardMessage(message) || cardData == null) {
    return;
  }
  await ConversationHistoryService.upsertConversationUiCard(
    conversationId,
    entryId: message.id,
    cardData: buildPersistentDeepThinkingCardData(
      Map<String, dynamic>.from(cardData),
    ),
    createdAtMillis: message.createAt.millisecondsSinceEpoch,
    mode: mode,
  );
}

void persistDeepThinkingUiCardIfNeeded({
  required int? conversationId,
  required ChatMessageModel message,
  ConversationMode mode = ConversationMode.normal,
  bool isEphemeral = false,
}) {
  if (conversationId == null || isEphemeral) {
    return;
  }
  unawaited(
    upsertPersistentDeepThinkingUiCard(
      conversationId: conversationId,
      message: message,
      mode: mode,
    ),
  );
}

Map<String, dynamic> buildPersistentDeepThinkingCardData(
  Map<String, dynamic> cardData,
) {
  final result = Map<String, dynamic>.from(cardData);
  final thinking = (result['thinkingContent'] ?? '').toString();
  final originalLength = thinking.length;
  if (originalLength <= kMaxPersistedThinkingChars) {
    result['thinkingContentTruncated'] = false;
    result['thinkingOriginalLength'] = originalLength;
    result['thinkingTruncateMode'] = 'none';
    return result;
  }

  final bodyLimit =
      kMaxPersistedThinkingChars - _thinkingTruncationNotice.length;
  final tail = _takeLastRunes(thinking, bodyLimit < 0 ? 0 : bodyLimit);
  result['thinkingContent'] = '$_thinkingTruncationNotice$tail';
  result['thinkingContentTruncated'] = true;
  result['thinkingOriginalLength'] = originalLength;
  result['thinkingTruncateMode'] = 'head_omitted';
  return result;
}

String _takeLastRunes(String value, int maxRunes) {
  if (maxRunes <= 0 || value.isEmpty) return '';
  final runes = value.runes.toList(growable: false);
  if (runes.length <= maxRunes) return value;
  return String.fromCharCodes(runes.skip(runes.length - maxRunes));
}
