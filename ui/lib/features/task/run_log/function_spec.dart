import 'dart:convert';

enum FunctionEnhancementStatus {
  none,
  enhancing,
  enhanced,
  unchanged,
  partial,
  failed,
}

extension FunctionEnhancementStatusX on FunctionEnhancementStatus {
  String get wireName => name;

  bool get isApplied =>
      this == FunctionEnhancementStatus.enhanced ||
      this == FunctionEnhancementStatus.partial;

  bool get isTerminal =>
      this == FunctionEnhancementStatus.enhanced ||
      this == FunctionEnhancementStatus.unchanged ||
      this == FunctionEnhancementStatus.partial ||
      this == FunctionEnhancementStatus.failed;
}

class FunctionSpec {
  FunctionSpec({
    required this.json,
    required this.agentPrompt,
    required this.aiEnhanced,
    this.warning,
    this.rawAiText,
    FunctionEnhancementStatus? enhancementStatus,
    String? enhancementMessage,
    Map<String, dynamic>? enhancementReport,
  }) : enhancementStatus = enhancementStatus ?? FunctionEnhancementStatus.none,
       enhancementMessage = enhancementMessage,
       enhancementReport = enhancementReport;

  final Map<String, dynamic> json;
  final String agentPrompt;
  final bool aiEnhanced;
  final String? warning;
  final String? rawAiText;
  final FunctionEnhancementStatus enhancementStatus;
  final String? enhancementMessage;
  final Map<String, dynamic>? enhancementReport;

  String get functionId => (json['function_id'] ?? '').toString();
  String get name => (json['name'] ?? '').toString();

  FunctionSpec copyWith({
    Map<String, dynamic>? json,
    String? agentPrompt,
    bool? aiEnhanced,
    String? warning,
    String? rawAiText,
    FunctionEnhancementStatus? enhancementStatus,
    String? enhancementMessage,
    Map<String, dynamic>? enhancementReport,
  }) {
    return FunctionSpec(
      json: json ?? this.json,
      agentPrompt: agentPrompt ?? this.agentPrompt,
      aiEnhanced: aiEnhanced ?? this.aiEnhanced,
      warning: warning ?? this.warning,
      rawAiText: rawAiText ?? this.rawAiText,
      enhancementStatus: enhancementStatus ?? this.enhancementStatus,
      enhancementMessage: enhancementMessage ?? this.enhancementMessage,
      enhancementReport: enhancementReport ?? this.enhancementReport,
    );
  }

  int get stepCount {
    final steps = json['steps'];
    return steps is List ? steps.length : 0;
  }

  int get parameterCount {
    final inputSchema = _asStringKeyMap(json['input_schema']);
    final properties = _asStringKeyMap(inputSchema['properties']);
    return properties.length;
  }

  String get prettyJson => const JsonEncoder.withIndent('  ').convert(json);
}

String functionAgentPrompt(Map<String, dynamic> json) {
  return _firstNonBlank([json['description'], json['name']]);
}

Map<String, dynamic> _asStringKeyMap(dynamic value) {
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return <String, dynamic>{};
}

String _firstNonBlank(Iterable<dynamic> values) {
  for (final value in values) {
    final text = (value ?? '').toString().trim();
    if (text.isNotEmpty) return text;
  }
  return '';
}
