import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/utils/ui.dart';

class RunLogShortcutController {
  const RunLogShortcutController._();

  static Future<void> openRunLogList() async {
    GoRouterManager.push('/task/run_logs');
  }

  static Future<void> openFunctionLibrary() async {
    GoRouterManager.push(
      '/memory/memory_center_page',
      queryParams: const {'tab': 'reusable_commands'},
    );
  }

  static Future<void> openLatestRunLog({
    required BuildContext context,
    required bool Function() isMounted,
    bool isBusy = false,
  }) async {
    if (isBusy) return;
    try {
      final snapshot = await AssistsMessageService.getInternalRunLogs(limit: 1);
      if (!isMounted() || !context.mounted) return;
      UtgRunLogSummary? latest;
      for (final run in snapshot.runs) {
        if (run.runId.trim().isNotEmpty) {
          latest = run;
          break;
        }
      }
      if (latest == null) {
        showToast('暂无可查看的轨迹', type: ToastType.warning);
        return;
      }
      if (!isMounted() || !context.mounted) return;
      unawaited(
        showRunLogTimelineSheet(
          context,
          runId: latest.runId.trim(),
          title: latest.goal.trim().isEmpty ? '当前轨迹' : latest.goal.trim(),
        ),
      );
    } catch (error) {
      if (!isMounted()) return;
      showToast(error.toString(), type: ToastType.error);
    }
  }
}
