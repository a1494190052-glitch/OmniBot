import 'package:flutter/foundation.dart';
import 'package:ui/services/assists_core_service.dart';

class ExecutableTaskService {
  static Future<bool> executeTask({
    required String execMode,
    required String instruction,
    required String taskJson,
    String? taskId,
    required String packageName,
    required String runMode,
    bool needSummary = false,
    bool skipGoHome = false,
  }) async {
    // OSS 版本统一走 VLM 执行。
    return AssistsMessageService.createVLMOperationTask(
      instruction,
      taskId: taskId,
      packageName: packageName,
      needSummary: needSummary,
      skipGoHome: skipGoHome,
    );
  }

  /// 从接口返回的 suggestion 构建 taskJson
  ///
  /// [suggestion] 接口返回的 suggestion 数据
  /// [filledParams] 填充的参数（用于覆盖 arguments）
  static Map<String, dynamic>? buildTaskJsonFromSuggestion({
    required Map<String, dynamic>? suggestion,
    Map<String, dynamic>? filledParams,
  }) {
    if (suggestion == null) {
      debugPrint('suggestion 为空，无法构建 taskJson');
      return null;
    }

    try {
      // 获取 tasks 字段
      final tasks = suggestion['tasks'] as List<dynamic>?;
      if (tasks == null || tasks.isEmpty) {
        debugPrint('suggestion 中没有 tasks 字段');
        return null;
      }

      // 为每个 task 添加 arguments
      final processedTasks = tasks.map((task) {
        if (task is Map<String, dynamic>) {
          // 将 filledParams 转换为 Map<String, String>
          final arguments = <String, String>{};
          if (filledParams != null) {
            filledParams.forEach((key, value) {
              arguments[key] = value.toString();
            });
          }
          // 同时从 task 原有的 arguments 中获取数据
          final taskArguments = task['arguments'] as Map<String, dynamic>?;
          if (taskArguments != null) {
            taskArguments.forEach((key, value) {
              if (!arguments.containsKey(key)) {
                arguments[key] = value.toString();
              }
            });
          }

          return {'functionId': task['functionId'], 'arguments': arguments};
        }
        return task;
      }).toList();

      final taskJsonMap = {
        'suggestionId': suggestion['suggestionId'] ?? '',
        'suggestionName': suggestion['suggestionName'] ?? '',
        'suggestionDescription': suggestion['suggestionDescription'] ?? '',
        'nodeId': suggestion['nodeId'] ?? '',
        'packageName': suggestion['packageName'] ?? '',
        'isHomeTask': suggestion['isHomeTask'] ?? false,
        'dispatchHint': suggestion['dispatchHint'] ?? '',
        'icon': suggestion['icon'],
        'tasks': processedTasks,
      };

      return taskJsonMap;
    } catch (e) {
      debugPrint('构建 taskJson 失败: $e');
      return null;
    }
  }
}
