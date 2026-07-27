class ScenarioCard {
  final String type;
  final String id;
  final String taskId;
  final Map<String, dynamic> payload;

  const ScenarioCard({
    required this.type,
    required this.id,
    required this.taskId,
    required this.payload,
  });

  Map<String, dynamic> toJson() => {
    'type': type,
    'id': id,
    'taskId': taskId,
    'payload': payload,
  };

  factory ScenarioCard.fromJson(Map<String, dynamic> json) => ScenarioCard(
    type: json['type'] as String? ?? '',
    id: json['id'] as String? ?? '',
    taskId: json['taskId'] as String? ?? '',
    payload:
    (json['payload'] as Map?)?.cast<String, dynamic>() ?? const {},
  );

  ScenarioCard copyWith({
    String? type,
    String? id,
    String? taskId,
    Map<String, dynamic>? payload,
  }) {
    return ScenarioCard(
      type: type ?? this.type,
      id: id ?? this.id,
      taskId: taskId ?? this.taskId,
      payload: payload ?? this.payload,
    );
  }
}
