import 'package:flutter/foundation.dart';
import 'package:ui/services/assists_core_service.dart';

/// AI聊天服务
class AiChatService {
  /// 消息回调
  Function(String taskId, String content, String? type)? _onMessageCallback;

  /// 消息结束回调
  Function(String taskId)? _onMessageEndCallback;

  AiChatService() {
    _initializeService();
  }

  /// 初始化服务
  void _initializeService() {
    AssistsMessageService.initialize();

    AssistsMessageService.addOnChatTaskMessageCallBack(
      _handleChatMessageCallback,
    );
    AssistsMessageService.addOnChatTaskMessageEndCallBack(
      _handleChatMessageEndCallback,
    );
  }

  void _handleChatMessageCallback(String taskId, String content, String? type) {
    debugPrint(
      '_handleChatMessageCallback called: taskId=$taskId, content=$content, type=$type',
    );
    try {
      _onMessageCallback?.call(taskId, content, type);
    } catch (e) {
      debugPrint('解析聊天消息失败: $e');
    }
  }

  void _handleChatMessageEndCallback(String taskId) {
    _onMessageEndCallback?.call(taskId);
  }

  /// 设置消息回调
  void setOnMessageCallback(
    Function(String taskId, String content, String? type) callback,
  ) {
    _onMessageCallback = callback;
  }

  /// 设置消息结束回调
  void setOnMessageEndCallback(Function(String taskId) callback) {
    _onMessageEndCallback = callback;
  }

  /// 发送消息
  ///
  /// [taskId] 任务ID
  /// [conversationHistory] 对话历史记录
  Future<bool> sendMessage(
    String taskId,
    List<Map<String, dynamic>> conversationHistory,
  ) async {
    return await AssistsMessageService.createChatTask(
      taskId,
      conversationHistory,
    );
  }

  /// 发送消息（支持指定 provider）
  Future<bool> sendMessageWithProvider(
    String taskId,
    List<Map<String, dynamic>> conversationHistory, {
    String? provider,
    Map<String, dynamic>? openClawConfig,
  }) async {
    return await AssistsMessageService.createChatTask(
      taskId,
      conversationHistory,
      provider: provider,
      openClawConfig: openClawConfig,
    );
  }

  /// 清理资源
  void dispose() {
    AssistsMessageService.removeOnChatTaskMessageCallBack(
      _handleChatMessageCallback,
    );
    AssistsMessageService.removeOnChatTaskMessageEndCallBack(
      _handleChatMessageEndCallback,
    );
    _onMessageCallback = null;
    _onMessageEndCallback = null;
  }
}
