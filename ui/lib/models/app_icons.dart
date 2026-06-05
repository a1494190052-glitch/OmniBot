class AppIcons {
  final int id;
  final String appName;
  final String packageName;
  final String iconBase64;
  final String iconPath;
  final int createdAt;
  final int updatedAt;

  AppIcons({
    required this.id,
    required this.appName,
    required this.packageName,
    required this.iconBase64,
    this.iconPath = "",
    required this.createdAt,
    required this.updatedAt,
  });

  factory AppIcons.fromMap(Map<dynamic, dynamic> map) {
    return AppIcons(
      id: map['id'] as int,
      appName: map['appName'] as String,
      packageName: map['packageName'] as String,
      iconBase64: map['icon_base64'] as String,
      iconPath: map['icon_path'] as String? ?? "",
      createdAt: map['createdAt'] as int,
      updatedAt: map['updatedAt'] as int,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'appName': appName,
      'packageName': packageName,
      'icon_base64': iconBase64,
      'icon_path': iconPath,
      'createdAt': createdAt,
      'updatedAt': updatedAt,
    };
  }

  @override
  String toString() {
    return 'AppIcons(id: $id, appName: $appName, packageName: $packageName, iconPath: $iconPath, iconBase64: ${iconBase64.substring(0,10)}, createdAt: $createdAt, updatedAt: $updatedAt)';
  }
}