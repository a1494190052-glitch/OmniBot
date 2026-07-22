import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/services/manual_recording_flow_controller.dart';

void main() {
  testWidgets(
    'opens recorded trajectory when recording succeeds but conversion fails',
    (tester) async {
      late BuildContext context;
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (builderContext) {
              context = builderContext;
              return const SizedBox();
            },
          ),
        ),
      );

      final focusNode = FocusNode();
      addTearDown(focusNode.dispose);
      String? openedRunId;

      final started = await ManualRecordingFlowController.start(
        context: context,
        inputFocusNode: focusNode,
        userMessageText: '',
        recordDebugScreenshots: false,
        isMounted: () => true,
        addUserMessage: (_) => const ManualRecordingFlowMessageIds(
          userMessageId: '',
          aiMessageId: '',
        ),
        beforeNativeRecording: () async {},
        afterNativeRecording: () async {},
        ensureAuthorized: (_) async => true,
        startNativeRecording: ({required enableDebugScreenshots}) async => {
          'success': true,
          'run_log': <String, dynamic>{
            'schema_version': 'omniflow.canonical_run_log.v1',
            'run_id': 'manual-run-1',
            'goal': 'manual recording',
            'status': 'succeeded',
            'success': true,
            'steps': <dynamic>[],
          },
          'function_error': <String, dynamic>{
            'code': 'RUN_LOG_NO_REPLAYABLE_STEPS',
            'message': 'RunLog has no replayable steps',
          },
        },
        openRunLogTimeline: (runId) => openedRunId = runId,
      );

      expect(started, isTrue);
      expect(openedRunId, 'manual-run-1');
    },
  );
}
