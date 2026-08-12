import 'package:go_router/go_router.dart';
import 'pages/scheduled_tasks/scheduled_task_list_page.dart';
import 'pages/usage_statistics/usage_statistics_page.dart';

/// Task模块路由配置
List<GoRoute> taskRoutes = [
  GoRoute(
    path: '/task/execution_history',
    name: 'task/execution_history',
    builder: (context, state) => const UsageStatisticsPage(),
  ),

  // 定时任务列表页
  GoRoute(
    path: '/task/scheduled_tasks',
    name: 'task/scheduled_tasks',
    builder: (context, state) =>
        ScheduledTaskListPage(initialTab: state.uri.queryParameters['tab']),
  ),
];
