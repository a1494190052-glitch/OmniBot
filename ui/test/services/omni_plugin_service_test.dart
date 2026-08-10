import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/omni_plugin_service.dart';

void main() {
  test('sandbox dashboard keeps root and Xiaowan capability', () {
    final dashboard = SandboxPluginDashboard.fromMap(<String, Object>{
      'pluginId': 'local.project.weekly-coach',
      'title': '每周教练',
      'entryPath': '/plugins/weekly-coach/index.html',
      'rootPath': '/plugins/weekly-coach',
      'permissions': <String>['database', 'xiaowan'],
    });

    expect(dashboard.rootPath, '/plugins/weekly-coach');
    expect(dashboard.canUseXiaowan, isTrue);
  });

  test('ai permission enables Xiaowan compatibility', () {
    final dashboard = SandboxPluginDashboard.fromMap(<String, Object>{
      'pluginId': 'local.project.legacy',
      'title': 'Legacy',
      'entryPath': '/plugins/legacy/index.html',
      'rootPath': '/plugins/legacy',
      'permissions': <String>['ai'],
    });

    expect(dashboard.canUseXiaowan, isTrue);
  });
}
