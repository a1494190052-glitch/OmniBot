import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/function_run_result_sheet.dart';
import 'package:ui/features/task/run_log/run_log_function_service.dart';

void main() {
  testWidgets('shows transfer screenshots and top three candidates', (
    tester,
  ) async {
    final image = File(
      '${Directory.systemTemp.path}/omniflow_transfer_test.png',
    );
    image.writeAsBytesSync(
      base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
      ),
    );
    addTearDown(() => image.deleteSync());
    final result = UtgManualRunResult.fromMap({
      'success': false,
      'function_id': 'tap_date',
      'error_code': 'OOB_OMNIFLOW_CONTROL_FAILED',
      'error_message': 'omnitransfer_target_identity_not_unique',
      'step_count': 1,
      'success_step_count': 0,
      'step_results': [
        {
          'step_id': 'step-0',
          'tool': 'click',
          'success': false,
          'summary': 'omnitransfer_target_identity_not_unique',
          'transfer': {
            'source': {
              'text': 'Date',
              'bounds': [10, 10, 90, 90],
              'display': {'width': 100, 'height': 100},
              'screenshot_path': image.path,
            },
            'target': {
              'display': {'width': 300, 'height': 300},
              'screenshot_path': image.path,
            },
            'candidates': [
              {
                'rank': 1,
                'text': 'Date',
                'bounds': [10, 10, 90, 90],
                'score': 10.0,
              },
              {
                'rank': 2,
                'text': 'Date',
                'bounds': [110, 10, 190, 90],
                'score': 10.0,
              },
              {
                'rank': 3,
                'text': 'Date',
                'bounds': [210, 10, 290, 90],
                'score': 10.0,
              },
            ],
          },
        },
      ],
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: FunctionRunResultInlinePanel(result: result),
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.text('Recorded target'), findsOneWidget);
    expect(find.text('Current page'), findsOneWidget);
    expect(find.textContaining('Top 3'), findsOneWidget);
    expect(find.textContaining('score 10.00'), findsNWidgets(3));
  });
}
