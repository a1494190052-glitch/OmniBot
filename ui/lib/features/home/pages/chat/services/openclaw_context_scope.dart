String buildOpenClawContextSessionKey({
  String prefix = 'openclaw',
  String? userId,
  int? conversationId,
  required String contextSegmentId,
}) {
  final normalizedPrefix = prefix.trim().isEmpty ? 'openclaw' : prefix.trim();
  final normalizedUserId = userId?.trim() ?? '';
  final normalizedSegment = contextSegmentId.trim().replaceAll(
    RegExp(r'[^A-Za-z0-9._-]'),
    '_',
  );
  final sessionSegment = normalizedSegment.isEmpty
      ? 'isolated'
      : normalizedSegment;
  return [
    normalizedPrefix,
    if (normalizedUserId.isNotEmpty) normalizedUserId,
    if (conversationId != null) ...['conversation', '$conversationId'],
    'segment',
    sessionSegment,
  ].join(':');
}
