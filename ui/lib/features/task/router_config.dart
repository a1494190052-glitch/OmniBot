import 'package:go_router/go_router.dart';
import 'pages/execution_history/omniflow_execution_center_page.dart';
import 'pages/execution_history/run_log_detail_page.dart';
import 'pages/scheduled_tasks/scheduled_task_list_page.dart';

/// Task模块路由配置
List<GoRoute> taskRoutes = [
  // 定时任务列表页
  GoRoute(
    path: '/task/scheduled_tasks',
    name: 'task/scheduled_tasks',
    builder: (context, state) =>
        ScheduledTaskListPage(initialTab: state.uri.queryParameters['tab']),
  ),
  GoRoute(
    path: '/task/omniflow',
    name: 'task/omniflow',
    builder: (context, state) => OmniFlowExecutionCenterPage(
      initialTab: state.uri.queryParameters['tab'],
    ),
  ),
  GoRoute(
    path: '/task/run_logs',
    name: 'task/run_logs',
    redirect: (context, state) => '/task/omniflow?tab=run_logs',
  ),
  GoRoute(
    path: '/task/run_log/:runId',
    name: 'task/run_log',
    builder: (context, state) =>
        RunLogDetailPage(runId: state.pathParameters['runId']!),
  ),
];
