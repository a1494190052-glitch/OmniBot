import 'package:ui/services/assists_core_service.dart';

class OmniFlowToolClient {
  const OmniFlowToolClient._();

  static Future<Map<String, dynamic>> listFunctions({
    int limit = 100,
    int offset = 0,
  }) {
    return _call('list_functions', {'limit': limit, 'offset': offset});
  }

  static Future<Map<String, dynamic>> listRunLogs({
    int limit = 100,
    int offset = 0,
  }) {
    return _call('list_run_logs', {'limit': limit, 'offset': offset});
  }

  static Future<Map<String, dynamic>> getFunction(String functionId) {
    return _call('get_function', {'function_id': functionId});
  }

  static Future<Map<String, dynamic>> getRunLog(String runId) {
    return _call('get_run_log', {'run_id': runId});
  }

  static Future<Map<String, dynamic>> getRunLogState(String stateId) {
    return _call('get_run_log_state', {'state_id': stateId});
  }

  static Future<Map<String, dynamic>> convertRunLog(String runId) {
    return _call('convert_run_log', {
      'run_id': runId,
      'register': true,
      'agent_visible': true,
      'enhance': true,
    });
  }

  static Future<Map<String, dynamic>> startHumanTrajectoryLearning({
    required String name,
    required String description,
    bool enableDebugScreenshots = false,
  }) async {
    final result = await AssistsMessageService.assistCore.invokeMethod<Object?>(
      'startHumanTrajectoryLearning',
      <String, dynamic>{
        'name': name,
        'description': description,
        'enable_debug_screenshots': enableDebugScreenshots,
      },
    );
    if (result is! Map) {
      throw StateError('Manual recording returned an invalid response');
    }
    return result.map(
      (key, value) => MapEntry(key.toString(), _normalize(value)),
    );
  }

  static Future<Map<String, dynamic>> deleteFunction(String functionId) {
    return _call('delete_function', {'function_id': functionId});
  }

  static Future<Map<String, dynamic>> replayFunction(
    String functionId,
    Map<String, dynamic> arguments,
  ) {
    return _call(functionId, arguments);
  }

  static Future<Map<String, dynamic>> _call(
    String name,
    Map<String, dynamic> arguments,
  ) async {
    final result = await AssistsMessageService.assistCore.invokeMethod<Object?>(
      'tools/call',
      {'name': name, 'arguments': arguments},
    );
    if (result is! Map) {
      throw StateError('OmniFlow tool $name returned an invalid response');
    }
    return result.map(
      (key, value) => MapEntry(key.toString(), _normalize(value)),
    );
  }

  static dynamic _normalize(dynamic value) {
    if (value is Map) {
      return value.map(
        (key, nested) => MapEntry(key.toString(), _normalize(nested)),
      );
    }
    if (value is List) {
      return value.map(_normalize).toList(growable: false);
    }
    return value;
  }
}
