import 'package:flutter/material.dart';
import 'package:ui/widgets/normal_choices_group.dart';
import 'package:ui/widgets/normal_options_card.dart';
import 'package:ui/constants/push_card/scenario_map.dart';

sealed class Block {
  final String id;
  final String taskId;
  final String type; // 卡片类型标识
  final Map<String, dynamic>? meta;
  const Block(this.id, this.taskId, this.type, {this.meta});

  /// 追加文本内容的通用方法，子类需要重写
  Block appendText(String appendText) {
    throw UnimplementedError('appendText must be implemented by subclass');
  }

  factory Block.fromJson(Map<String, dynamic> j) {
    var cardData = j;
    final taskId = cardData['task_id'] as String;
    var type = cardData['type'] as String;
    if (type == 'PushTaskOptionsCardRef') {
      final scenarioId = cardData['scenario_id'] as String;
      final scenarioCardData = scenarioCardMap[scenarioId];
      type = scenarioCardData?.type ?? '';
      if (scenarioCardData != null) {
        cardData = {...j, ...scenarioCardData.toJson()};
      }
    }
    switch (type) {
      case 'PushTaskStepsCard': return ActionStepsBlock.fromJson(cardData);
      case 'PushAppOptionsCard': return AppOptionsBlock.fromJson(cardData);
      case 'PushTaskOptionsCard': return TaskOptionsBlock.fromJson(cardData);
      case 'PushOptionsCard': return QueryOptionsBlock.fromJson(cardData);
      case 'ScreenshotCard': return ScreenShotCardBlock.fromJson(cardData);
      case 'orderCard': return OrderCardBlock.fromJson(cardData);
      default: return UnknownBlock(cardData['id'] ?? 'unknown', raw: cardData, taskId: taskId);
    }
  }
}

abstract class ConsumableBlock {
  bool get isConsumed;
  ConsumableBlock copyWith({bool? isConsumed});
}

extension ConsumableBlockExt on Block {
  /// 是否已消费（对非 ConsumableBlock 统一视为 true）
  bool get consumedOrTrue {
    if (this is ConsumableBlock) {
      return (this as ConsumableBlock).isConsumed;
    }
    return true;
  }

  /// 如果是未消费的 ConsumableBlock，则返回标记已消费后的新实例；否则返回自身
  Block markConsumed() {
    if (this is ConsumableBlock) {
      final c = this as ConsumableBlock;
      if (!c.isConsumed) {
        return (c.copyWith(isConsumed: true) as Block);
      }
    }
    return this;
  }
}

class ThoughtBlock extends Block {
  final String text;        // 思考内容
  final bool collapsible;        // 是否可折叠
  
  ThoughtBlock({
    required String id,
    required String taskId,
    required this.text,
    Map<String, dynamic>? meta, 
    this.collapsible = true
  }) : super(id, taskId, 'thought', meta: meta);

  factory ThoughtBlock.fromJson(Map<String, dynamic> j) => ThoughtBlock(
    id: j['id'], 
    taskId: j['task_id'],
    text: j['payload']['text'] ?? '',
    meta: j['meta'], 
    collapsible: j['meta']?['collapsible'] ?? true,
  );

  @override
  Block appendText(String appendText) {
    return ThoughtBlock(
      id: id,
      taskId: taskId,
      text: '$text$appendText',
      meta: meta,
      collapsible: collapsible,
    );
  }
}

class ChatContentBlock extends Block {
  final String text;             // 聊天内容
  final bool collapsible;        // 是否可折叠

  ChatContentBlock({
    required String id,
    required String taskId,
    required this.text,
    Map<String, dynamic>? meta, 
    this.collapsible = true
  }) : super(id, taskId, 'chat_content', meta: meta);

  factory ChatContentBlock.fromJson(Map<String, dynamic> j) => ChatContentBlock(
    id: j['id'],
    taskId: j['task_id'],
    text: j['payload']['text'] ?? '',
    meta: j['meta'], 
    collapsible: j['meta']?['collapsible'] ?? true,
  );

  @override
  Block appendText(String appendText) {
    return ChatContentBlock(
      id: id,
      taskId: taskId,
      text: '$text$appendText',
      meta: meta,
      collapsible: collapsible,
    );
  }
}

class ActionStepsBlock extends Block implements ConsumableBlock {
  final String? title;
  final List<List<ActionStep>> steps;
  final List<ButtonModel> buttonList;
  final String? costTime;
  @override
  final bool isConsumed;
  
  ActionStepsBlock({
    required String id,
    required String taskId,
    this.title,
    required this.steps,
    required this.buttonList,
    this.costTime = '',
    this.isConsumed = false,
    Map<String, dynamic>? meta,
  }) : super(id, taskId, 'steps', meta: meta);

  factory ActionStepsBlock.fromJson(Map<String, dynamic> j) => ActionStepsBlock(
    id: j['id'],
    taskId: j['task_id'],
    title: j['payload']['title'],
    steps: (j['payload']['optionList'] as List? ?? [])
        .map((opt) {
          // 每个 opt 本身是一个 Map，取其中的 steps 列表
          final rawList = (opt as Map<String, dynamic>)['steps'] as List? ?? [];
          return rawList
              .map((e) => ActionStep.fromJson(e as Map<String, dynamic>))
              .toList();
        }).toList(),
    buttonList: (j['payload']['buttonList'] as List? ?? [])
        .map((e) => ButtonModel.fromJson(e))
        .toList(),
    costTime: j['payload']['costTime'] ?? '',
    meta: j['meta'] as Map<String, dynamic>?,
  );

  @override
  Block appendText(String appendText) {
    // ActionStepsBlock通常不支持文本追加，返回自身
    return this;
  }

  @override
  ConsumableBlock copyWith({bool? isConsumed}) {
    return ActionStepsBlock(
      id: id,
      taskId: taskId,
      title: title,
      steps: steps,
      buttonList: buttonList,
      costTime: costTime,
      meta: meta,
      isConsumed: isConsumed ?? this.isConsumed,
    );
  }
}

class ActionStep {
  final String description; // 任务标题或任务步骤描述 (可能需要修改)
  final String status;      // 任务状态: 'completed', 'pending', 'in_progress'
  final bool isUserAction;  // 是否为用户操作
  final IconData? icon;
  final bool isHeader; // 是否为任务头部

  const ActionStep({
    required this.description,
    this.status = 'pending',
    this.isUserAction = false,
    this.icon,
    this.isHeader = false,
  });

  factory ActionStep.fromJson(Map<String, dynamic> j) => ActionStep(
    description: j['description'] ?? '',
    status: j['status'] ?? 'pending',
    isUserAction: j['isUserAction'] ?? false,
    isHeader: j['isHeader'] ?? false,
  );

  Map<String, dynamic> toJson() => {
    'description': description,
    'status': status,
    'isUserAction': isUserAction,
    'isHeader': isHeader,
  };
}

class ReferenceBlock extends Block {
  final List<ReferenceItem> items;

  ReferenceBlock({
    required String id,
    required String taskId,
    required this.items,
    Map<String, dynamic>? meta,
  }) : super(id, taskId, 'reference', meta: meta);

  factory ReferenceBlock.fromJson(Map<String, dynamic> j) => ReferenceBlock(
    id: j['id'],
    taskId: j['task_id'],
    items: (j['payload']['items'] as List? ?? [])
        .map((e) => ReferenceItem.fromJson(e))
        .toList(),
    meta: j['meta'],
  );

  @override
  Block appendText(String appendText) {
    // ReferenceBlock通常不支持文本追加，返回自身
    return this;
  }
}

class ReferenceItem {
  final String title;
  final String url;

  ReferenceItem(this.title, this.url);

  factory ReferenceItem.fromJson(Map<String, dynamic> j) =>
      ReferenceItem(j['title'] ?? '', j['url'] ?? '');
}

class AppOptionsBlock extends Block implements ConsumableBlock {
  final String? title;
  final String? content;
  final List<AppOption> applications;
  final bool multiSelect;
  @override
  final bool isConsumed;
  
  AppOptionsBlock({
    required String id,
    required String taskId,
    this.title,
    this.content,
    required this.applications,
    this.multiSelect = false,
    this.isConsumed = false,
    Map<String, dynamic>? meta
  }) : super(id, taskId, 'appOptions', meta: meta);

  factory AppOptionsBlock.fromJson(Map<String, dynamic> j) => AppOptionsBlock(
    id: j['id'],
    taskId: j['task_id'],
    title: j['payload']['title'],
    content: j['payload']['content'],
    applications: (j['payload']['applications'] as List? ?? [])
      .map((e) => AppOption.fromJson(e)).toList(),
    multiSelect: j['payload']['multiSelect'] ?? false,
    meta: j['meta'],
  );

  @override
  ConsumableBlock copyWith({bool? isConsumed}) {
    return AppOptionsBlock(
      id: id,
      taskId: taskId,
      title: title,
      content: content,
      applications: applications,
      multiSelect: multiSelect,
      meta: meta,
      isConsumed: isConsumed ?? this.isConsumed,
    );
  }
}

class AppOption {
  final String name;
  final String packageName;
  final String? iconUrl;
  final Color? iconBackgroundColor;

  AppOption({
    required this.name,
    required this.packageName,
    this.iconUrl,
    this.iconBackgroundColor,
  });

  factory AppOption.fromJson(Map<String, dynamic> j) => AppOption(
    name: j['name'] ?? '',
    packageName: j['packageName'] ?? '',
    iconUrl: j['iconUrl'],
    iconBackgroundColor: j['iconBackgroundColor'] != null
        ? Color(j['iconBackgroundColor'])
        : null,
  );

  // 转换为ChoiceOption供NormalChoicesGroup使用
  ChoiceOption toChoiceOption() {
    return ChoiceOption(
      label: name,
      icon: iconUrl != null ? const Icon(Icons.network_cell) // TODO: app图标
              : const Icon(Icons.apps, color: Colors.white, size: 14),
      iconBackgroundColor: iconBackgroundColor ?? Colors.blue,
      value: packageName,
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is AppOption && other.packageName == packageName;
  }

  @override
  int get hashCode => packageName.hashCode;
}

class ScreenShotCardBlock extends Block {
  final String? title;
  final String? dateTime;
  final String? tag;
  final String? content;
  final String? imagePath;

  ScreenShotCardBlock({
    required String id,
    required String taskId,
    this.title,
    this.dateTime,
    this.tag,
    this.content,
    this.imagePath,
    Map<String, dynamic>? meta
  }) : super(id, taskId, 'ScreenshotCard', meta: meta);

  factory ScreenShotCardBlock.fromJson(Map<String, dynamic> j) => ScreenShotCardBlock(
    id: j['id'],
    taskId: j['task_id'],
    title: j['payload']['title'],
    dateTime: j['payload']['dateTime'],
    tag: j['payload']['tag'],
    content: j['payload']['content'],
    imagePath: j['payload']['imagePath'],
    meta: j['meta'],
  );
}

class OrderCardBlock extends Block {
  final String? platformType;
  final List<OrderStore> stores;

  OrderCardBlock({
    required String id,
    required String taskId,
    this.platformType,
    required this.stores,
    Map<String, dynamic>? meta
  }) : super(id, taskId, 'orderCard', meta: meta);

  factory OrderCardBlock.fromJson(Map<String, dynamic> j) => OrderCardBlock(
    id: j['id'],
    taskId: j['task_id'],
    platformType: j['payload']['source'],
    stores: (j['payload']['stores'] as List? ?? [])
        .map((e) => OrderStore.fromJson(e))
        .toList(),
    meta: j['meta'],
  );
}

class OrderStore {
  final String id;
  final String name;
  final String? iconUrl;
  final List<OrderProduct> products;

  OrderStore({
    required this.id,
    required this.name,
    this.iconUrl,
    required this.products,
  });

  factory OrderStore.fromJson(Map<String, dynamic> j) => OrderStore(
    id: j['id'] ?? '',
    name: j['name'] ?? '',
    iconUrl: j['icon_url'],
    products: (j['products'] as List? ?? [])
        .map((e) => OrderProduct.fromJson(e))
        .toList(),
  );
}

class OrderProduct {
  final String id;
  final String name;
  final List<OrderOption> options;

  OrderProduct({
    required this.id,
    required this.name,
    required this.options,
  });

  factory OrderProduct.fromJson(Map<String, dynamic> j) => OrderProduct(
    id: j['id'] ?? '',
    name: j['name'] ?? '',
    options: (j['options'] as List? ?? [])
        .map((e) => OrderOption.fromJson(e))
        .toList(),
  );
}

class OrderOption {
  final String key;
  final String label;
  final String type;
  final List<OrderOptionValue> values;

  OrderOption({
    required this.key,
    required this.label,
    required this.type,
    required this.values,
  });

  factory OrderOption.fromJson(Map<String, dynamic> j) => OrderOption(
    key: j['key'] ?? '',
    label: j['label'] ?? '',
    type: j['type'] ?? 'radio',
    values: (j['values'] as List? ?? [])
        .map((e) => OrderOptionValue.fromJson(e))
        .toList(),
  );
}

class OrderOptionValue {
  final String label;
  final String value;
  final bool isDefault;

  OrderOptionValue({
    required this.label,
    required this.value,
    this.isDefault = false,
  });

  factory OrderOptionValue.fromJson(Map<String, dynamic> j) => OrderOptionValue(
    label: j['label'] ?? '',
    value: j['value'] ?? '',
    isDefault: j['default'] ?? false,
  );
}

class TaskOptionsBlock extends Block implements ConsumableBlock {
  final String? title;
  final String? taskDesc;
  final List<ButtonModel> buttonList;
  final List<TaskOption> options;
  final bool multiSelect;
  @override
  final bool isConsumed;

  TaskOptionsBlock({
    required String id,
    required String taskId,
    this.title,
    this.taskDesc,
    required this.buttonList,
    required this.options,
    this.multiSelect = false,
    this.isConsumed = false,
    Map<String, dynamic>? meta,
  }) : super(id, taskId, 'task_options', meta: meta);

  factory TaskOptionsBlock.fromJson(Map<String, dynamic> j) => TaskOptionsBlock(
    id: j['id'],
    taskId: j['task_id'],
    title: j['payload']['title'],
    taskDesc: j['payload']['taskDesc'],
    buttonList: (j['payload']['buttonList'] as List? ?? [])
      .map((e) => ButtonModel.fromJson(e)).toList(),
    options: (j['payload']['options'] as List? ?? [])
      .map((e) => TaskOption.fromJson(e)).toList(),
    multiSelect: j['payload']['multiSelect'] ?? false,
    meta: j['meta'],
  );

  @override
  ConsumableBlock copyWith({bool? isConsumed}) {
    return TaskOptionsBlock(
      id: id,
      taskId: taskId,
      title: title,
      taskDesc: taskDesc,
      buttonList: buttonList,
      options: options,
      multiSelect: multiSelect,
      meta: meta,
      isConsumed: isConsumed ?? this.isConsumed,
    );
  }
}

class TaskOption {
  final String name;
  final String? iconUrl;
  final String? optionId;
  
  TaskOption({
    required this.name,
    this.iconUrl,
    this.optionId,
  });
  
  factory TaskOption.fromJson(Map<String, dynamic> j) => TaskOption(
    name: j['name'] ?? '',
    iconUrl: j['iconUrl'],
    optionId: j['optionId'],
  );

  // 转换为OptionItem供NormalOptionsCard使用
  OptionItem toOptionItem() {
    return OptionItem(
      title: name,
      icon: iconUrl != null ? const Icon(Icons.network_cell) // TODO: app图标 
              : const Icon(Icons.apps, color: Colors.white, size: 14),
      value: optionId,
    );
  }
  
  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is AppOption && other.name == name;
  }
  
  @override
  int get hashCode => name.hashCode;
}

class QueryOptionsBlock extends Block implements ConsumableBlock {
  final String? title;
  final List<ButtonModel> buttonList;
  final bool countDown;
  @override
  final bool isConsumed;

  QueryOptionsBlock({
    required String id,
    required String taskId,
    this.title,
    required this.buttonList,
    this.countDown = false,
    this.isConsumed = false,
  }):super(id, taskId, 'query_options');

  factory QueryOptionsBlock.fromJson(Map<String, dynamic> j) => QueryOptionsBlock(
    id: j['id'],
    taskId: j['task_id'],
    title: j['payload']['title'],
    buttonList: (j['payload']['buttonList'] as List? ?? [])
      .map((e) => ButtonModel.fromJson(e)).toList(),
    countDown: j['payload']['countDown'] ?? false,
  );

  @override
  ConsumableBlock copyWith({bool? isConsumed}) {
    return QueryOptionsBlock(
      id: id,
      taskId: taskId,
      title: title,
      buttonList: buttonList,
      isConsumed: isConsumed ?? this.isConsumed,
      countDown: false,
    );
  }
}

class ButtonModel{
  final String id;
  final String? text;
  final String? action;

  ButtonModel({
    this.id  = 'default_id',
    this.text,
    this.action,
  });

  factory ButtonModel.fromJson(Map<String, dynamic> j) => ButtonModel(
    id: j['id'],
    text: j['text'],
    action: j['action'],
  );
}

class UnknownBlock extends Block {
  final Map<String, dynamic> raw;

  UnknownBlock(String id, {required this.raw, required String taskId}) : super(id, taskId, 'unknown', meta: const {});

  @override
  Block appendText(String appendText) {
    final updatedRaw = Map<String, dynamic>.from(raw);
    final existingText = updatedRaw['text'] ?? '';
    updatedRaw['text'] = '$existingText$appendText';
    return UnknownBlock(id, raw: updatedRaw, taskId: taskId);
  }
}

// AI Chunk Response model for streaming data from onMessagePush
class AIChunkResponse {
  final String messageId;
  final String type;
  final String text;
  final DateTime timestamp;
  final bool isFinal;

  AIChunkResponse({
    required this.messageId,
    required this.type,
    required this.text,
    DateTime? timestamp,
    this.isFinal = false,
  }) : timestamp = timestamp ?? DateTime.now();

  factory AIChunkResponse.fromJson(Map<String, dynamic> chunkData, String messageId) {
    return AIChunkResponse(
      messageId: messageId,
      type: chunkData['type'] ?? 'THINKING',
      text: chunkData['text'] ?? '',
      isFinal: (chunkData['isFinal'] is bool)
          ? chunkData['isFinal'] as bool
          : false,
    );
  }
}

// AI Response model that contains a single block with task_id
class AIResponse {
  final String taskId;
  final String replyId;
  final List<Block> blocks;

  AIResponse({
    required this.taskId,
    required this.replyId,
    required this.blocks,
  });

  factory AIResponse.fromJson(Map<String, dynamic> json) {
    final block = Block.fromJson(json);
    return AIResponse(
      taskId: json['task_id'] ?? '',
      replyId: json['id'] ?? '',
      blocks: [block],
    );
  }

  // 从AIChunkResponse创建AIResponse
  factory AIResponse.fromChunkResponse(AIChunkResponse chunkResponse) {
    final block = _createBlockFromChunk(chunkResponse);
    return AIResponse(
      taskId: chunkResponse.messageId,
      replyId: chunkResponse.messageId,
      blocks: [block],
    );
  }

  // 根据chunk类型创建对应的Block
  static Block _createBlockFromChunk(AIChunkResponse chunk) {
    switch (chunk.type) {
      case 'THINKING':
        return ThoughtBlock(
          id: chunk.messageId,
          taskId: chunk.messageId,
          text: chunk.text,
          collapsible: true,
        );
      case 'CONTENT':
        return ChatContentBlock(
          id: chunk.messageId,
          taskId: chunk.messageId,
          text: chunk.text,
          collapsible: true,
        );
      default:
        return UnknownBlock(
          chunk.messageId,
          raw: {
            'type': chunk.type,
            'text': chunk.text,
            'timestamp': chunk.timestamp.toIso8601String(),
          },
          taskId: chunk.messageId,
        );
    }
  }

  /// 根据chunk类型和现有block创建新的block（用于文本追加）
  static Block createUpdatedBlockFromChunk(Block existingBlock, AIChunkResponse chunk) {
    // 如果chunk类型与现有block类型匹配，则追加文本
    if (_getChunkTypeMapping(chunk.type) == existingBlock.type) {
      return existingBlock.appendText(chunk.text);
    }
    // 否则创建新的block
    return _createBlockFromChunk(chunk);
  }

  /// 将chunk类型映射到block类型
  static String _getChunkTypeMapping(String chunkType) {
    switch (chunkType) {
      case 'THINKING':
        return 'thought';
      case 'CONTENT':
        return 'chat_content';
      default:
        return 'unknown';
    }
  }
}
