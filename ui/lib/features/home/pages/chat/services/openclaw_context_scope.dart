String buildOpenClawConversationSessionKey({
  String prefix = 'openclaw',
  String? userId,
  int? conversationId,
}) {
  final normalizedPrefix = prefix.trim().isEmpty ? 'openclaw' : prefix.trim();
  final normalizedUserId = userId?.trim() ?? '';
  return [
    normalizedPrefix,
    if (normalizedUserId.isNotEmpty) normalizedUserId,
    if (conversationId != null) ...['conversation', '$conversationId'],
  ].join(':');
}
