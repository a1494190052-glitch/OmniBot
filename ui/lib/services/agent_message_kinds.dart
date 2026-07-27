import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_call_parser.dart';

const String kAgentToolUiStyle = 'agent_tool';
const String kAgentRequestCardType = 'agent_request';

// Read-only compatibility for conversation snapshots created before Agent mode
// replaced the former Codex-specific presentation layer.
const String _legacyAgentToolUiStyle = 'codex_tool';
const String _legacyAgentRequestCardType = 'codex_request';

bool isAgentToolUiStyle(Object? value) {
  final normalized = value?.toString().trim() ?? '';
  return normalized == kAgentToolUiStyle ||
      normalized == _legacyAgentToolUiStyle;
}

String canonicalAgentToolUiStyle(Object? value) {
  return isAgentToolUiStyle(value)
      ? kAgentToolUiStyle
      : (value?.toString().trim() ?? '');
}

bool isAgentRequestCardType(Object? value) {
  final normalized = value?.toString().trim() ?? '';
  return normalized == kAgentRequestCardType ||
      normalized == _legacyAgentRequestCardType;
}

String canonicalAgentRequestCardType(Object? value) {
  return isAgentRequestCardType(value)
      ? kAgentRequestCardType
      : (value?.toString().trim() ?? '');
}

ChatMessageModel canonicalizeAgentHistoryMessage(ChatMessageModel message) {
  final sourceCardData = message.cardData;
  if (sourceCardData == null) {
    return message;
  }
  final cardData = Map<String, dynamic>.from(sourceCardData);
  var changed = false;
  if (isAgentRequestCardType(cardData['type'])) {
    final type = canonicalAgentRequestCardType(cardData['type']);
    if (type != cardData['type']) {
      cardData['type'] = type;
      changed = true;
    }
  }
  if (isAgentToolUiStyle(cardData['uiStyle'])) {
    final uiStyle = canonicalAgentToolUiStyle(cardData['uiStyle']);
    if (uiStyle != cardData['uiStyle']) {
      cardData['uiStyle'] = uiStyle;
      changed = true;
    }
  }
  final rawToolName = cardData['toolName']?.toString();
  if (rawToolName != null) {
    final toolName = canonicalAgentToolName(rawToolName);
    if (toolName != rawToolName) {
      cardData['toolName'] = toolName;
      changed = true;
    }
  }
  if (!changed) {
    return message;
  }
  return message.copyWith(
    content: <String, dynamic>{
      ...?message.content,
      'cardData': cardData,
      'id': message.contentId ?? message.id,
    },
  );
}
