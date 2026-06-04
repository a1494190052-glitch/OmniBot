import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';

void main() {
  test('parses todo snapshot events', () {
    final event = AgentStreamEvent.fromMap({
      'taskId': 'task-1',
      'seq': 7,
      'kind': 'todo_snapshot',
      'createdAt': 1234,
      'todos': [
        {
          'content': 'Inspect the code',
          'activeForm': 'Inspecting the code',
          'status': 'completed',
        },
        {
          'content': 'Wire Plan UI',
          'activeForm': 'Wiring Plan UI',
          'status': 'in_progress',
        },
      ],
      'oldTodos': [
        {
          'content': 'Inspect the code',
          'activeForm': 'Inspecting the code',
          'status': 'in_progress',
        },
      ],
    });

    expect(event.kind, AgentStreamEventKind.todoSnapshot);
    expect(event.todos, hasLength(2));
    expect(event.todos.first.status, AgentTodoStatus.completed);
    expect(event.todos.last.activeForm, 'Wiring Plan UI');
    expect(event.oldTodos.single.status, AgentTodoStatus.inProgress);
  });
}
