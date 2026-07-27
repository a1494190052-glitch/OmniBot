import 'package:flutter_test/flutter_test.dart';
import 'package:ui/core/router/go_router_config.dart';

void main() {
  test('router config includes the main application routes', () {
    final routeNames = AppRouterConfig.getAllRoutes()
        .map((route) => route.name)
        .whereType<String>()
        .toSet();

    expect(routeNames, contains('home/chat'));
    expect(routeNames, contains('welcome/choice'));
    expect(routeNames, contains('home/command_overlay'));
    expect(routeNames, contains('task/execution_history'));
    expect(routeNames, contains('task/run_logs'));
    expect(routeNames, contains('task/run_log_timeline'));
    expect(routeNames, contains('task/function_library'));
  });
}
