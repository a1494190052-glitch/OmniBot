import 'package:go_router/go_router.dart';
import 'pages/task_center/task_center_page.dart';
import 'pages/task_edit/task_edit_page.dart';
import 'pages/execution_history/run_log_list_page.dart';
import 'pages/execution_history/run_log_timeline_page.dart';
import 'pages/execution_history/function_library_page.dart';
import 'pages/scheduled_tasks/scheduled_task_list_page.dart';
import 'pages/task_modify/task_modify_page.dart';

/// Task模块路由配置
List<GoRoute> taskRoutes = [
  GoRoute(
    path: '/task/task_center',
    name: 'task/task_center',
    builder: (context, state) => const TaskCenterPage(),
  ),
  GoRoute(
    path: '/task/task_edit/:taskId',
    name: 'task/task_edit',
    builder: (context, state) {
      final taskId = state.pathParameters['taskId'] ?? '';
      return TaskEditPage(taskId: taskId);
    },
  ),
  GoRoute(
    path: '/task/execution_history',
    name: 'task/execution_history',
    builder: (context, state) => const RunLogListPage(),
  ),
  GoRoute(
    path: '/task/run_logs',
    name: 'task/run_logs',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>?;
      return RunLogListPage(baseUrl: params?['baseUrl']?.toString());
    },
  ),
  GoRoute(
    path: '/task/run_log_timeline',
    name: 'task/run_log_timeline',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>? ?? const {};
      return RunLogTimelinePage(
        runId:
            params['runId']?.toString() ??
            state.uri.queryParameters['runId'] ??
            '',
        title:
            params['title']?.toString() ??
            state.uri.queryParameters['title'] ??
            '',
        baseUrl:
            params['baseUrl']?.toString() ??
            state.uri.queryParameters['baseUrl'],
      );
    },
  ),
  GoRoute(
    path: '/task/function_library',
    name: 'task/function_library',
    builder: (context, state) => const FunctionLibraryPage(),
  ),
  // 定时任务列表页
  GoRoute(
    path: '/task/scheduled_tasks',
    name: 'task/scheduled_tasks',
    builder: (context, state) =>
        ScheduledTaskListPage(initialTab: state.uri.queryParameters['tab']),
  ),
  GoRoute(
    path: '/task/execution_detail',
    name: 'task/execution_detail',
    builder: (context, state) => const RunLogListPage(),
  ),
  GoRoute(
    path: '/task/task_history',
    name: 'task/task_history',
    builder: (context, state) => const RunLogListPage(),
  ),
  GoRoute(
    path: '/task/task_modify',
    name: 'task/task_modify',
    builder: (context, state) {
      final params = state.extra as Map<String, dynamic>?;
      return TaskModifyPage(
        taskId: params?['taskId'],
        type: params?['type'],
        title: params?['title'],
        payload: params?['payload'],
      );
    },
  ),
];
