import 'package:flutter_test/flutter_test.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/services/agent_stream_reducer.dart';

void main() {
  test('accepts increasing todo snapshots and rejects stale sequence', () {
    const reducer = AgentStreamReducer();

    final first = AgentStreamEvent.fromMap({
      'taskId': 'task-1',
      'seq': 1,
      'kind': 'todo_snapshot',
      'todos': [
        {
          'content': 'First',
          'activeForm': 'Doing first',
          'status': 'in_progress',
        },
      ],
    });
    final firstResult = reducer.reduce(null, first);

    expect(firstResult.accepted, isTrue);
    expect(firstResult.nextState.todoItems.single.content, 'First');

    final stale = AgentStreamEvent.fromMap({
      'taskId': 'task-1',
      'seq': 1,
      'kind': 'todo_snapshot',
      'todos': [
        {
          'content': 'Stale',
          'activeForm': 'Doing stale',
          'status': 'in_progress',
        },
      ],
    });
    final staleResult = reducer.reduce(firstResult.nextState, stale);

    expect(staleResult.accepted, isFalse);
    expect(staleResult.nextState.todoItems.single.content, 'First');

    final next = AgentStreamEvent.fromMap({
      'taskId': 'task-1',
      'seq': 2,
      'kind': 'todo_snapshot',
      'todos': [
        {
          'content': 'First',
          'activeForm': 'Doing first',
          'status': 'completed',
        },
        {
          'content': 'Second',
          'activeForm': 'Doing second',
          'status': 'in_progress',
        },
      ],
    });
    final nextResult = reducer.reduce(firstResult.nextState, next);

    expect(nextResult.accepted, isTrue);
    expect(nextResult.nextState.todoItems, hasLength(2));
    expect(
      nextResult.nextState.todoItems.first.status,
      AgentTodoStatus.completed,
    );
  });
}
