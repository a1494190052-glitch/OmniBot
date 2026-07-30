class OmniPluginItem {
  const OmniPluginItem({
    required this.id,
    required this.name,
    required this.version,
    required this.interfaceVersion,
    required this.description,
    required this.publisher,
    required this.kind,
    required this.downloadSizeBytes,
    required this.capabilities,
    required this.settingsSchema,
    required this.installed,
    required this.enabled,
    required this.compatible,
    this.errorMessage,
  });

  final String id;
  final String name;
  final String version;
  final int interfaceVersion;
  final String description;
  final String publisher;
  final String kind;
  final int downloadSizeBytes;
  final List<String> capabilities;
  final Map<String, dynamic> settingsSchema;
  final bool installed;
  final bool enabled;
  final bool compatible;
  final String? errorMessage;

  factory OmniPluginItem.fromMap(Map<dynamic, dynamic> raw) {
    return OmniPluginItem(
      id: (raw['id'] ?? '').toString(),
      name: (raw['name'] ?? '').toString(),
      version: (raw['version'] ?? '').toString(),
      interfaceVersion: _asInt(raw['interfaceVersion']),
      description: (raw['description'] ?? '').toString(),
      publisher: (raw['publisher'] ?? '').toString(),
      kind: (raw['kind'] ?? 'runtime_bundle').toString(),
      downloadSizeBytes: _asInt(raw['downloadSizeBytes']),
      capabilities:
          (raw['capabilities'] as List?)
              ?.map((value) => value.toString())
              .toList(growable: false) ??
          const <String>[],
      settingsSchema: Map<String, dynamic>.from(
        (raw['settingsSchema'] as Map?) ?? const <String, dynamic>{},
      ),
      installed: raw['installed'] == true,
      enabled: raw['enabled'] == true,
      compatible: raw['compatible'] != false,
      errorMessage: raw['errorMessage']?.toString(),
    );
  }

  static int _asInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }
}
