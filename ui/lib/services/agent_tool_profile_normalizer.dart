class AgentToolProfileNormalizer {
  static const String omniflow = 'omniflow';
  static const String legacyFunctionManagement = 'function_management';

  static String? canonicalize(String? profile) {
    final trimmed = profile?.trim() ?? '';
    if (trimmed.isEmpty) return null;
    final normalized = trimmed.toLowerCase().replaceAll('-', '_');
    if (normalized == omniflow || normalized == legacyFunctionManagement) {
      return omniflow;
    }
    return trimmed;
  }
}
