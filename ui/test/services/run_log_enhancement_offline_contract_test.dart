import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('enhancement jobs are not resumed from app startup', () {
    final source = File('lib/app_bootstrap.dart').readAsStringSync();

    expect(source, isNot(contains('run_log_function_enhancement_job_service')));
    expect(source, isNot(contains('resumePendingJobs')));
  });

  test('RunLog detail restores only completed enhancement state', () {
    final source = File(
      'lib/features/task/pages/execution_history/run_log_timeline_page.dart',
    ).readAsStringSync();
    final restore = _sourceBetween(
      source,
      'Future<void> _restoreEnhancementJob() async',
      'Future<void> _enqueueEnhancementJob',
    );

    expect(restore, isNot(contains('resumePendingJobs')));
    expect(restore, contains('job == null || job.isRunning'));
    expect(source, contains('RunLogFunctionEnhancementJobService.enqueue('));
  });
}

String _sourceBetween(String source, String startToken, String endToken) {
  final start = source.indexOf(startToken);
  expect(start, isNot(-1), reason: 'Missing start token: $startToken');
  final end = source.indexOf(endToken, start);
  expect(end, isNot(-1), reason: 'Missing end token: $endToken');
  return source.substring(start, end);
}
