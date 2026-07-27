import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/accessibility_permission_prompt.dart';
import 'package:ui/features/home/pages/command_overlay/services/executable_task_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

import 'card_widget_factory.dart';

/// 可执行任务卡片
///
/// 显示任务描述和执行按钮
class ExecutableTaskCard extends StatefulWidget {
  final Map<String, dynamic> cardData;

  /// 任务执行前的回调，用于保存聊天上下文
  final OnBeforeTaskExecute? onBeforeTaskExecute;

  const ExecutableTaskCard({
    super.key,
    required this.cardData,
    this.onBeforeTaskExecute,
  });

  @override
  State<ExecutableTaskCard> createState() => _ExecutableTaskCardState();
}

class _ExecutableTaskCardState extends State<ExecutableTaskCard> {
  bool _isExecuting = false;
  String? _activeTaskId;

  @override
  void initState() {
    super.initState();
    AssistsMessageService.addOnChatTaskMessageEndCallBack(_onAgentTaskFinish);
  }

  @override
  void dispose() {
    AssistsMessageService.removeOnChatTaskMessageEndCallBack(
      _onAgentTaskFinish,
    );
    super.dispose();
  }

  void _onAgentTaskFinish(String taskId, {Map<String, dynamic>? turnUsage}) {
    if (mounted && _isExecuting && taskId == _activeTaskId) {
      setState(() {
        _isExecuting = false;
        _activeTaskId = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    // 优先从 cardData 中获取 instruction（来自 dispatchResult），否则从 suggestion 获取
    final suggestion = widget.cardData['suggestion'] as Map<String, dynamic>?;
    final instruction =
        widget.cardData['instruction'] as String? ??
        suggestion?['dispatchHint'] as String? ??
        suggestion?['suggestionDescription'] as String? ??
        '';

    return Container(
      margin: const EdgeInsets.fromLTRB(0, 0, 0, 16),
      decoration: BoxDecoration(
        color: isDark ? palette.pageBackground : const Color(0xFFF9FCFF),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
        child: SizedBox(
          width: double.infinity,
          height: 44,
          child: ElevatedButton(
            onPressed: _isExecuting
                ? null
                : () async {
                    await _executeTask(instruction);
                  },
            style: ElevatedButton.styleFrom(
              backgroundColor: _isExecuting
                  ? Colors.grey
                  : const Color(0xFF2C7FEB), // 和 order_card 立即下单按钮一致
              foregroundColor: Colors.white.withValues(
                alpha: 0.92,
              ), // 和 order_card 立即下单按钮一致
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(24),
              ),
              elevation: 0,
              disabledBackgroundColor: Colors.grey,
              disabledForegroundColor: Colors.white,
            ),
            child: _isExecuting
                ? Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: const [
                      SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          valueColor: AlwaysStoppedAnimation<Color>(
                            Colors.white,
                          ),
                        ),
                      ),
                      SizedBox(width: 8),
                      Text(
                        '执行中...',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 16,
                          fontFamily: 'PingFang SC',
                          fontWeight: FontWeight.w500,
                          height: 1.25,
                          letterSpacing: 0.50,
                        ),
                      ),
                    ],
                  )
                : const Text(
                    '确认执行',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      fontFamily: 'PingFang SC',
                      fontWeight: FontWeight.w500,
                      height: 1.25,
                      letterSpacing: 0.50,
                    ),
                  ),
          ),
        ),
      ),
    );
  }

  /// 执行任务
  Future<void> _executeTask(String instruction) async {
    if (instruction.isEmpty) {
      showToast('任务描述为空', type: ToastType.error);
      return;
    }

    // 检查无障碍权限
    final hasPermission = await showAccessibilityPermissionPrompt(context);
    if (!hasPermission) {
      return;
    }

    final taskId = 'gui-${DateTime.now().microsecondsSinceEpoch}';
    setState(() {
      _isExecuting = true;
      _activeTaskId = taskId;
    });

    try {
      // 从 cardData 中获取 suggestion 数据
      final suggestion = widget.cardData['suggestion'] as Map<String, dynamic>?;

      // 从 suggestion 中获取必要字段，如果 suggestion 为 null，则从 cardData 的 package_name 获取
      // （GUI 模式下 suggestion 可能为 null，此时 package_name 来自 dispatchResult）
      final String packageName =
          suggestion?['packageName'] as String? ??
          widget.cardData['package_name'] as String? ??
          '';
      final bool isHomeTask = suggestion?['isHomeTask'] as bool? ?? false;

      const String execMode = 'GUI';

      print(
        '执行任务 - packageName: $packageName, isHomeTask: $isHomeTask, execMode: $execMode',
      );

      // 从 cardData 中获取 filled_params
      final filledParams =
          widget.cardData['filled_params'] as Map<String, dynamic>?;

      // 构建 taskJson，传入 filledParams 以填充 slotValues
      final Map<String, dynamic>? taskJsonMap =
          ExecutableTaskService.buildTaskJsonFromSuggestion(
            suggestion: suggestion,
            filledParams: filledParams,
          );

      final String taskJson = jsonEncode(taskJsonMap);

      final bool success = await ExecutableTaskService.executeTask(
        execMode: execMode,
        instruction: instruction,
        taskJson: taskJson,
        taskId: taskId,
        packageName: packageName,
        runMode: "oss",
      );

      if (!success) {
        if (mounted) {
          setState(() {
            _isExecuting = false;
            _activeTaskId = null;
          });
        }
        showToast('任务执行出错', type: ToastType.error);
      }
    } catch (e) {
      print('执行任务出错: $e');
      if (mounted) {
        setState(() {
          _isExecuting = false;
          _activeTaskId = null;
        });
      }
      showToast('任务执行出错：$e', type: ToastType.error);
    }
    // 注意：成功启动任务后不在这里设置 _isExecuting = false
    // 而是等待 onVLMTaskFinish 回调
  }
}
