import 'package:ui/features/home/pages/chat/utils/agent_thinking_card_locator.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/widgets/oob_function_run_progress_card.dart';

class ChatCardMessageHelpers {
  const ChatCardMessageHelpers._();

  static bool upsertCardMessage(
    List<ChatMessageModel> messages, {
    required String cardId,
    required Map<String, dynamic> cardData,
    Map<String, dynamic>? streamMeta,
    bool moveToLatest = false,
    DateTime? createAt,
  }) {
    final normalizedCardId = cardId.trim();
    if (normalizedCardId.isEmpty) {
      return false;
    }
    final message = ChatMessageModel.cardMessage(
      cardData,
      id: normalizedCardId,
      streamMeta: streamMeta,
    ).copyWith(createAt: createAt);
    final existingIndex = messages.indexWhere(
      (item) =>
          item.id == normalizedCardId ||
          (item.cardData?['cardId'] ?? '').toString().trim() ==
              normalizedCardId,
    );
    if (existingIndex == -1) {
      messages.insert(0, message);
      return true;
    }
    if (moveToLatest) {
      messages.removeAt(existingIndex);
      messages.insert(0, message);
      return true;
    }
    messages[existingIndex] = message;
    return true;
  }

  static bool upsertOobFunctionRunProgress(
    List<ChatMessageModel> messages,
    OobFunctionRunProgressEvent event,
  ) {
    final cardId = oobFunctionRunProgressCardIdForEvent(event);
    if (cardId.isEmpty) {
      return false;
    }
    return upsertCardMessage(
      messages,
      cardId: cardId,
      cardData: oobFunctionRunProgressCardDataForEvent(event),
    );
  }

  static bool upsertCancelledAgentRunMessage(
    List<ChatMessageModel> messages,
    String taskId,
  ) {
    final message = cancelledAgentRunMessage(taskId);
    if (message == null) {
      return false;
    }
    final existingIndex = messages.indexWhere((item) => item.id == message.id);
    if (existingIndex == -1) {
      messages.insert(0, message);
      return true;
    }
    messages[existingIndex] = messages[existingIndex].copyWith(
      content: message.content,
      isLoading: false,
      isError: false,
      streamMeta: message.streamMeta,
    );
    return true;
  }

  static ChatMessageModel? cancelledAgentRunMessage(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return null;
    }
    final messageId = cancelledAgentRunMessageId(normalizedTaskId);
    return ChatMessageModel(
      id: messageId,
      type: 1,
      user: 2,
      content: <String, dynamic>{
        'text': LegacyTextLocalizer.localize('任务已取消'),
        'id': messageId,
        'renderMarkdown': false,
      },
      streamMeta: ensureAgentStreamMessageMeta(
        null,
        seq: 1000000000,
        roundIndex: 1000000000,
        kind: 'text_snapshot',
        parentTaskId: normalizedTaskId,
        entryId: messageId,
        isFinal: true,
      ),
    );
  }

  static String cancelledAgentRunMessageId(String taskId) {
    return '${taskId.trim()}-cancelled';
  }
}

class ChatLinkPreviewMessageUpdate {
  const ChatLinkPreviewMessageUpdate({
    required this.message,
    required this.previews,
    required this.didUpdate,
  });

  final ChatMessageModel message;
  final List<Map<String, dynamic>> previews;
  final bool didUpdate;

  bool get hasResolvedPreview {
    return previews.any(
      (item) =>
          ChatLinkPreview.fromJson(item).status !=
          ChatLinkPreview.statusLoading,
    );
  }

  List<String> get loadingUrls {
    return previews
        .map(ChatLinkPreview.fromJson)
        .where(
          (preview) =>
              preview.status == ChatLinkPreview.statusLoading &&
              preview.url.isNotEmpty,
        )
        .map((preview) => preview.url)
        .toList();
  }
}

class ChatLinkPreviewMessageHelpers {
  const ChatLinkPreviewMessageHelpers._();

  static ChatLinkPreviewMessageUpdate reconcile(ChatMessageModel message) {
    final content = Map<String, dynamic>.from(message.content ?? const {});
    final nextPreviews = LinkPreviewService.instance.reconcilePreviewMaps(
      text: message.text ?? '',
      existing: content['linkPreviews'],
    );
    if (previewMapListsEqual(content['linkPreviews'], nextPreviews)) {
      return ChatLinkPreviewMessageUpdate(
        message: message,
        previews: nextPreviews,
        didUpdate: false,
      );
    }
    if (nextPreviews.isEmpty) {
      content.remove('linkPreviews');
    } else {
      content['linkPreviews'] = nextPreviews;
    }
    return ChatLinkPreviewMessageUpdate(
      message: message.copyWith(content: content),
      previews: nextPreviews,
      didUpdate: true,
    );
  }

  static ChatMessageModel? replaceLoadingPreview(
    ChatMessageModel message, {
    required String url,
    required ChatLinkPreview resolved,
  }) {
    final content = Map<String, dynamic>.from(message.content ?? const {});
    final rawPreviews = content['linkPreviews'];
    if (rawPreviews is! List) {
      return null;
    }

    var didUpdate = false;
    final updatedPreviews = rawPreviews
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .map((previewMap) {
          final preview = ChatLinkPreview.fromJson(previewMap);
          if (preview.url != url ||
              preview.status != ChatLinkPreview.statusLoading) {
            return previewMap;
          }
          didUpdate = true;
          return resolved.toJson();
        })
        .toList();
    if (!didUpdate) {
      return null;
    }

    content['linkPreviews'] = updatedPreviews;
    return message.copyWith(content: content);
  }

  static bool previewMapListsEqual(
    dynamic left,
    List<Map<String, dynamic>> right,
  ) {
    if (left is! List) {
      return right.isEmpty;
    }
    final normalizedLeft = left
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
    if (normalizedLeft.length != right.length) {
      return false;
    }
    for (var index = 0; index < normalizedLeft.length; index += 1) {
      if (!_previewMapEquals(normalizedLeft[index], right[index])) {
        return false;
      }
    }
    return true;
  }

  static bool _previewMapEquals(
    Map<String, dynamic> left,
    Map<String, dynamic> right,
  ) {
    return left['url'] == right['url'] &&
        left['domain'] == right['domain'] &&
        left['siteName'] == right['siteName'] &&
        left['title'] == right['title'] &&
        left['description'] == right['description'] &&
        left['imageUrl'] == right['imageUrl'] &&
        left['status'] == right['status'];
  }
}

class ChatThinkingCardMessages {
  const ChatThinkingCardMessages._();

  static const int completedStage = 4;
  static const int cancelledStage = 5;

  static String cardIdForTask(
    String taskId, {
    String? cardId,
    String defaultCardIdSuffix = '-1',
  }) {
    final explicitCardId = cardId?.trim() ?? '';
    if (explicitCardId.isNotEmpty) {
      return explicitCardId;
    }
    return '$taskId-thinking$defaultCardIdSuffix';
  }

  static ChatMessageModel create({
    required String taskId,
    required bool isDeepThinking,
    required int currentThinkingStage,
    String? cardId,
    String defaultCardIdSuffix = '-1',
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool includeCollapsible = true,
  }) {
    final startTime = DateTime.now().millisecondsSinceEpoch;
    final thinkingCardId = cardIdForTask(
      taskId,
      cardId: cardId,
      defaultCardIdSuffix: defaultCardIdSuffix,
    );
    final cardData = <String, dynamic>{
      'type': 'deep_thinking',
      'isLoading': isLoading ?? isDeepThinking,
      'thinkingContent': thinkingContent ?? '',
      'stage': stage ?? currentThinkingStage,
      'taskID': taskId,
      'cardId': thinkingCardId,
      'startTime': startTime,
      'endTime': null,
    };
    if (includeCollapsible) {
      cardData['isCollapsible'] = true;
    }
    return ChatMessageModel(
      id: thinkingCardId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': thinkingCardId},
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
      streamMeta: ensureAgentStreamMessageMeta(
        streamMeta,
        entryId: thinkingCardId,
      ),
    );
  }

  static ChatMessageModel update({
    required ChatMessageModel existing,
    required String taskId,
    required String cardId,
    required String deepThinkingContent,
    required bool isDeepThinking,
    required int currentThinkingStage,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
    bool clearEndTimeWhenActive = true,
    bool includeCollapsible = true,
  }) {
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final cardData = Map<String, dynamic>.from(
      content['cardData'] ?? const <String, dynamic>{},
    );
    final currentStage = _asInt(cardData['stage']) ?? 1;
    final targetStage = stage ?? currentThinkingStage;
    final newStage = lockCompleted && currentStage == completedStage
        ? completedStage
        : targetStage;

    final startTime = _asInt(cardData['startTime']);
    int? endTime = _asInt(cardData['endTime']);
    if (newStage == completedStage && endTime == null) {
      endTime = DateTime.now().millisecondsSinceEpoch;
    } else if (clearEndTimeWhenActive &&
        newStage != completedStage &&
        newStage != cancelledStage) {
      endTime = null;
    }

    cardData['thinkingContent'] = thinkingContent ?? deepThinkingContent;
    cardData['isLoading'] = isLoading ?? isDeepThinking;
    cardData['stage'] = newStage;
    cardData['taskID'] = taskId;
    cardData['cardId'] = cardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] = endTime;
    if (includeCollapsible) {
      cardData['isCollapsible'] = cardData['isCollapsible'] ?? true;
    }

    content['cardData'] = cardData;
    content['id'] = cardId;
    return existing.copyWith(
      content: content,
      streamMeta: ensureAgentStreamMessageMeta(
        streamMeta ?? existing.streamMeta,
        entryId: cardId,
      ),
    );
  }

  static ChatMessageModel cancel(ChatMessageModel existing) {
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final cardData = Map<String, dynamic>.from(
      content['cardData'] ?? const <String, dynamic>{},
    );
    final cardId = (cardData['cardId'] ?? existing.id).toString();
    cardData['stage'] = cancelledStage;
    cardData['isLoading'] = false;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    cardData['isCollapsible'] = cardData['isCollapsible'] ?? true;
    content['cardData'] = cardData;
    content['id'] = cardId;
    return existing.copyWith(content: content);
  }

  static ChatMessageModel? cancelForTask(
    List<ChatMessageModel> messages, {
    required String taskId,
    String? preferredCardId,
  }) {
    final thinkingCard = resolveAgentThinkingCardForTask(
      messages,
      taskId: taskId,
      preferredCardId: preferredCardId,
    );
    if (thinkingCard == null) {
      return null;
    }
    final index = messages.indexWhere(
      (message) => message.id == thinkingCard.id,
    );
    if (index == -1) {
      return null;
    }
    final updated = cancel(messages[index]);
    messages[index] = updated;
    return updated;
  }

  static int? _asInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }
}
