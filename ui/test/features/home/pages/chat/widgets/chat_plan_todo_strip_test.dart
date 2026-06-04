import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_plan_todo_strip.dart';
import 'package:ui/models/agent_stream_event.dart';

void main() {
  const todos = [
    AgentTodoItem(
      content: 'Inspect code',
      activeForm: 'Inspecting code',
      status: AgentTodoStatus.completed,
    ),
    AgentTodoItem(
      content: 'Wire UI',
      activeForm: 'Wiring UI',
      status: AgentTodoStatus.inProgress,
    ),
    AgentTodoItem(
      content: 'Add tests',
      activeForm: 'Adding tests',
      status: AgentTodoStatus.pending,
    ),
  ];

  testWidgets('collapsed state only shows the active todo form', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: ChatPlanTodoStrip(todos: todos)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Wiring UI'), findsOneWidget);
    expect(find.text('Inspect code'), findsNothing);
    expect(find.text('Add tests'), findsNothing);
  });

  testWidgets('expanded state shows completed and pending todos', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: ChatPlanTodoStrip(todos: todos)),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(kChatPlanTodoToggleKey));
    await tester.pumpAndSettle();

    expect(find.text('Inspect code'), findsOneWidget);
    expect(find.text('Wiring UI'), findsOneWidget);
    expect(find.text('Add tests'), findsOneWidget);

    final completedText = tester.widget<Text>(find.text('Inspect code'));
    expect(completedText.style?.decoration, TextDecoration.lineThrough);
  });
}
