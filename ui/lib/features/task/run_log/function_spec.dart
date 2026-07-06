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
  }) : enhancementStatus =
           enhancementStatus ?? _enhancementStatusFromFunctionJson(json),
       enhancementMessage =
           enhancementMessage ?? _enhancementMessageFromFunctionJson(json),
       enhancementReport =
           enhancementReport ?? _enhancementReportFromFunctionJson(json);

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
    final execution = _asStringKeyMap(json['execution']);
    final steps = execution['steps'];
    return steps is List ? steps.length : 0;
  }

  int get parameterCount {
    final parameters = json['parameters'];
    return parameters is List ? parameters.length : 0;
  }

  String get prettyJson => const JsonEncoder.withIndent('  ').convert(json);
}

String functionAgentPrompt(Map<String, dynamic> json) {
  final metadata = _asStringKeyMap(json['metadata']);
  return _firstNonBlank([
    json['agent_prompt'],
    json['display_prompt'],
    metadata['agent_prompt'],
    metadata['display_prompt'],
  ]);
}

FunctionEnhancementStatus _enhancementStatusFromFunctionJson(
  Map<String, dynamic> functionJson,
) {
  final report = _enhancementReportFromFunctionJson(functionJson);
  final rawStatus = _firstNonBlank([
    report?['status'],
    _asStringKeyMap(functionJson['metadata'])['enhancement_status'],
    functionJson['enhancement_status'],
  ]).trim().toLowerCase();
  switch (rawStatus) {
    case 'enhanced':
    case 'applied':
      return FunctionEnhancementStatus.enhanced;
    case 'partial':
    case 'partially_enhanced':
      return FunctionEnhancementStatus.partial;
    case 'unchanged':
    case 'checked':
    case 'no_change':
      return FunctionEnhancementStatus.unchanged;
    case 'failed':
    case 'error':
      return FunctionEnhancementStatus.failed;
    case 'enhancing':
      return FunctionEnhancementStatus.enhancing;
  }
  return FunctionEnhancementStatus.none;
}

String? _enhancementMessageFromFunctionJson(Map<String, dynamic> functionJson) {
  final report = _enhancementReportFromFunctionJson(functionJson);
  final message = _firstNonBlank([
    report?['message'],
    _asStringKeyMap(functionJson['metadata'])['enhancement_message'],
    functionJson['enhancement_message'],
  ]);
  return message.isEmpty ? null : message;
}

Map<String, dynamic>? _enhancementReportFromFunctionJson(
  Map<String, dynamic> functionJson,
) {
  final metadata = _asStringKeyMap(functionJson['metadata']);
  final report = _asStringKeyMap(
    metadata['oob_enhancement'] ??
        metadata['enhancement'] ??
        functionJson['oob_enhancement'],
  );
  return report.isEmpty ? null : report;
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
