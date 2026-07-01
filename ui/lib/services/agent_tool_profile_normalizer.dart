class AgentToolProfileNormalizer {
  static const String function = 'function';
  static const String legacyOmniflow = 'omniflow';
  static const String legacyFunctionManagement = 'function_management';

  static String? canonicalize(String? profile) {
    final trimmed = profile?.trim() ?? '';
    if (trimmed.isEmpty) return null;
    final normalized = trimmed.toLowerCase().replaceAll('-', '_');
    if (normalized == function ||
        normalized == legacyOmniflow ||
        normalized == legacyFunctionManagement) {
      return function;
    }
    return trimmed;
  }
}
