import 'package:flutter/material.dart';
import 'package:ui/features/local_model/local_model_feature.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

class ConversationModelSelection {
  const ConversationModelSelection({
    required this.providerProfileId,
    required this.modelId,
  });

  final String providerProfileId;
  final String modelId;
}

Widget buildConversationModelIdTooltip({
  required String modelId,
  required Widget child,
}) {
  return Tooltip(
    message: modelId,
    triggerMode: TooltipTriggerMode.longPress,
    waitDuration: Duration.zero,
    showDuration: const Duration(seconds: 3),
    preferBelow: false,
    textAlign: TextAlign.start,
    constraints: const BoxConstraints(maxWidth: 320),
    child: child,
  );
}

class ConversationModelSelectorContent extends StatefulWidget {
  const ConversationModelSelectorContent({
    super.key,
    required this.width,
    required this.maxHeight,
    required this.profiles,
    required this.providerModelsByProfileId,
    required this.currentSelection,
    this.onSearchSubmitted,
    this.onSelect,
    this.footer,
    this.emptyProvidersLabel,
    this.emptyMatchesLabel,
    this.emptyModelsLabel,
    this.modelRowKeyPrefix,
    this.showSearchField = true,
    this.showProfileHeaders = true,
    this.allowProfileCollapse = true,
    this.groupBuiltinLocalModels = true,
  });

  final double width;
  final double maxHeight;
  final List<ModelProviderProfileSummary> profiles;
  final Map<String, List<ProviderModelOption>> providerModelsByProfileId;
  final ConversationModelSelection? currentSelection;
  final VoidCallback? onSearchSubmitted;
  final ValueChanged<ConversationModelSelection>? onSelect;
  final Widget? footer;
  final String? emptyProvidersLabel;
  final String? emptyMatchesLabel;
  final String? emptyModelsLabel;
  final String? modelRowKeyPrefix;
  final bool showSearchField;
  final bool showProfileHeaders;
  final bool allowProfileCollapse;
  final bool groupBuiltinLocalModels;

  @override
  State<ConversationModelSelectorContent> createState() =>
      _ConversationModelSelectorContentState();
}

class _ConversationModelSelectorContentState
    extends State<ConversationModelSelectorContent> {
  static const Map<String, String> _kBackendDisplayNames = {
    'llama.cpp': 'llama.cpp',
    'omniinfer-mnn': 'MNN',
    'llm': 'NPU',
    'manual': '手动添加',
  };
  static const List<String> _kBackendOrder = [
    'llama.cpp',
    'omniinfer-mnn',
    'manual',
  ];

  static const double _kProfileHeaderExtent = 43.0;
  static const double _kModelRowExtent = 43.0;
  static const double _kBackendHeaderExtent = 28.0;
  static const double _kProfileGapExtent = 6.0;

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _listScrollController = ScrollController();
  final GlobalKey _selectedModelRowKey = GlobalKey();
  late final Set<String> _expandedProfileIds;
  late final Set<String> _expandedBackendKeys;

  bool get _hasSearchQuery =>
      widget.showSearchField && _searchController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    _expandedProfileIds = <String>{
      if (widget.currentSelection != null)
        widget.currentSelection!.providerProfileId,
    };
    if (_expandedProfileIds.isEmpty && widget.profiles.isNotEmpty) {
      _expandedProfileIds.add(widget.profiles.first.id);
    }
    _expandedBackendKeys = <String>{};
    if (widget.currentSelection != null) {
      final pid = widget.currentSelection!.providerProfileId;
      if (localModelFeature.isBuiltinLocalProvider(pid)) {
        final models =
            widget.providerModelsByProfileId[pid] ??
            const <ProviderModelOption>[];
        for (final model in models) {
          if (model.id == widget.currentSelection!.modelId &&
              model.ownedBy != null &&
              model.ownedBy!.isNotEmpty) {
            _expandedBackendKeys.add('$pid::${model.ownedBy}');
            break;
          }
        }
      }
    }
    _searchController.addListener(() {
      setState(() {});
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _autoScrollToSelectedModel();
      }
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    _listScrollController.dispose();
    super.dispose();
  }

  void _autoScrollToSelectedModel() {
    final selection = widget.currentSelection;
    if (selection == null || !_listScrollController.hasClients) {
      return;
    }
    final profiles = _visibleProfiles;
    var offset = 0.0;
    var found = false;
    for (final profile in profiles) {
      if (widget.showProfileHeaders) {
        offset += _kProfileHeaderExtent;
      }
      final isTargetProfile = profile.id == selection.providerProfileId;
      final expanded = _isExpanded(profile.id);
      if (expanded) {
        if (_needsBackendGrouping(profile.id)) {
          final groups = _groupByBackend(profile.id);
          for (final backend in _sortedBackendKeys(groups.keys)) {
            offset += _kBackendHeaderExtent;
            final models = groups[backend]!;
            if (!_isBackendExpanded(profile.id, backend)) {
              continue;
            }
            final index = isTargetProfile
                ? models.indexWhere((m) => m.id == selection.modelId)
                : -1;
            if (index >= 0) {
              offset += index * _kModelRowExtent;
              found = true;
              break;
            }
            offset += models.length * _kModelRowExtent;
          }
        } else {
          final models = _filteredModels(profile.id);
          final index = isTargetProfile
              ? models.indexWhere((m) => m.id == selection.modelId)
              : -1;
          if (index >= 0) {
            offset += index * _kModelRowExtent;
            found = true;
          } else {
            offset += models.length * _kModelRowExtent;
          }
        }
      }
      if (found || isTargetProfile) {
        found = found || isTargetProfile;
        break;
      }
      offset += _kProfileGapExtent;
    }
    if (!found) {
      return;
    }
    final position = _listScrollController.position;
    if (position.maxScrollExtent <= 0) {
      return;
    }
    final target = (offset - position.viewportDimension * 0.35).clamp(
      0.0,
      position.maxScrollExtent,
    );
    _listScrollController.jumpTo(target);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final rowContext = _selectedModelRowKey.currentContext;
      if (rowContext != null) {
        Scrollable.ensureVisible(
          rowContext,
          alignment: 0.35,
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  List<ProviderModelOption> _filteredModels(String profileId) {
    final query = _searchController.text.trim().toLowerCase();
    final models = widget.providerModelsByProfileId[profileId] ?? const [];
    if (query.isEmpty) {
      return models;
    }
    return models.where((item) {
      final modelId = item.id.toLowerCase();
      final displayName = item.displayName.toLowerCase();
      return modelId.contains(query) || displayName.contains(query);
    }).toList();
  }

  List<ModelProviderProfileSummary> get _visibleProfiles {
    final configuredProfiles = widget.profiles
        .where((profile) => profile.configured)
        .toList();
    if (!_hasSearchQuery) {
      return configuredProfiles;
    }
    return configuredProfiles.where((profile) {
      return _filteredModels(profile.id).isNotEmpty;
    }).toList();
  }

  bool _isExpanded(String profileId) {
    if (!widget.allowProfileCollapse) return true;
    if (_hasSearchQuery) return true;
    return _expandedProfileIds.contains(profileId);
  }

  bool _needsBackendGrouping(String profileId) {
    return widget.groupBuiltinLocalModels &&
        localModelFeature.isBuiltinLocalProvider(profileId);
  }

  Map<String, List<ProviderModelOption>> _groupByBackend(String profileId) {
    final models = _filteredModels(profileId);
    final groups = <String, List<ProviderModelOption>>{};
    for (final model in models) {
      final key = (model.ownedBy != null && model.ownedBy!.isNotEmpty)
          ? model.ownedBy!
          : 'other';
      groups.putIfAbsent(key, () => []).add(model);
    }
    return groups;
  }

  List<String> _sortedBackendKeys(Iterable<String> keys) {
    final list = keys.toList();
    list.sort((a, b) {
      final ia = _kBackendOrder.indexOf(a);
      final ib = _kBackendOrder.indexOf(b);
      return (ia < 0 ? 999 : ia).compareTo(ib < 0 ? 999 : ib);
    });
    return list;
  }

  bool _isBackendExpanded(String profileId, String backend) {
    if (_hasSearchQuery) return true;
    return _expandedBackendKeys.contains('$profileId::$backend');
  }

  Widget _buildSearchRow() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      key: const ValueKey('conversation-model-selector-search'),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: isDark
              ? palette.surfaceSecondary.withValues(alpha: 0.58)
              : Colors.white.withValues(alpha: 0.38),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDark
                ? palette.borderSubtle.withValues(alpha: 0.62)
                : Colors.white.withValues(alpha: 0.58),
          ),
        ),
        child: Row(
          children: [
            Icon(
              Icons.search,
              size: 18,
              color: isDark ? palette.textTertiary : const Color(0xFF9AA4B6),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _searchController,
                autofocus: false,
                scrollPadding: EdgeInsets.zero,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => widget.onSearchSubmitted?.call(),
                cursorColor: isDark ? palette.accentPrimary : null,
                style: TextStyle(
                  fontSize: 13,
                  color: isDark ? palette.textPrimary : const Color(0xFF1F2937),
                  fontWeight: FontWeight.w500,
                ),
                decoration: InputDecoration(
                  isDense: true,
                  filled: false,
                  fillColor: Colors.transparent,
                  hintText: LegacyTextLocalizer.localize('搜索模型 ID'),
                  hintStyle: TextStyle(
                    fontSize: 13,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF9AA4B6),
                    fontWeight: FontWeight.w500,
                  ),
                  border: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  contentPadding: EdgeInsets.zero,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildProfileHeader(ModelProviderProfileSummary profile) {
    final expanded = _isExpanded(profile.id);
    final models = _filteredModels(profile.id);
    final isSelectedProvider =
        widget.currentSelection?.providerProfileId == profile.id;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: !widget.allowProfileCollapse || _hasSearchQuery
            ? null
            : () {
                setState(() {
                  if (expanded) {
                    _expandedProfileIds.remove(profile.id);
                  } else {
                    _expandedProfileIds.add(profile.id);
                  }
                });
              },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: isSelectedProvider
                ? (isDark
                      ? Color.lerp(
                          palette.surfaceSecondary.withValues(alpha: 0.46),
                          palette.accentPrimary,
                          0.14,
                        )!
                      : const Color(0xFF2C7FEB).withValues(alpha: 0.10))
                : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  profile.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark
                        ? palette.textSecondary
                        : const Color(0xFF64748B),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                '${models.length}',
                style: TextStyle(
                  fontSize: 11,
                  color: isDark
                      ? palette.textTertiary
                      : const Color(0xFF9AA4B6),
                  fontWeight: FontWeight.w600,
                ),
              ),
              if (isSelectedProvider) ...[
                const SizedBox(width: 6),
                Icon(
                  Icons.check_circle_rounded,
                  size: 13,
                  color: isDark
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
              ],
              if (widget.allowProfileCollapse) ...[
                const SizedBox(width: 6),
                Icon(
                  _hasSearchQuery
                      ? Icons.unfold_more_rounded
                      : expanded
                      ? Icons.expand_less_rounded
                      : Icons.expand_more_rounded,
                  size: 16,
                  color: isDark
                      ? palette.textTertiary
                      : const Color(0xFF94A3B8),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildModelRow({
    required ModelProviderProfileSummary profile,
    required ProviderModelOption model,
  }) {
    final selected =
        widget.currentSelection?.providerProfileId == profile.id &&
        widget.currentSelection?.modelId == model.id;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final rowKeyPrefix = widget.modelRowKeyPrefix;
    return Padding(
      key: selected ? _selectedModelRowKey : null,
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: buildConversationModelIdTooltip(
        modelId: model.id,
        child: InkWell(
          key: rowKeyPrefix == null
              ? null
              : ValueKey('$rowKeyPrefix-${model.id}'),
          onTap: () {
            final selection = ConversationModelSelection(
              providerProfileId: profile.id,
              modelId: model.id,
            );
            final onSelect = widget.onSelect;
            if (onSelect != null) {
              onSelect(selection);
            } else {
              Navigator.of(context).pop(selection);
            }
          },
          borderRadius: BorderRadius.circular(12),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: selected
                  ? (isDark
                        ? Color.lerp(
                            palette.surfaceSecondary.withValues(alpha: 0.48),
                            palette.accentPrimary,
                            0.18,
                          )!
                        : const Color(0xFF2C7FEB).withValues(alpha: 0.12))
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                ProviderVendorIcon(
                  vendor: ModelVendorCatalog.resolve(
                    model.id,
                    ownedBy: model.ownedBy,
                    providerId: model.modelsDevProviderId,
                  ),
                  size: 14,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    model.id,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: isDark
                          ? palette.textPrimary
                          : const Color(0xFF1F2937),
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                if (selected)
                  Icon(
                    Icons.check_rounded,
                    size: 15,
                    color: isDark
                        ? palette.accentPrimary
                        : const Color(0xFF2C7FEB),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildBackendSubHeader(
    String profileId,
    String backend,
    int modelCount,
  ) {
    final expanded = _isBackendExpanded(profileId, backend);
    final displayName = _kBackendDisplayNames[backend] ?? backend;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 1, 10, 1),
      child: InkWell(
        onTap: _hasSearchQuery
            ? null
            : () {
                final key = '$profileId::$backend';
                setState(() {
                  if (_expandedBackendKeys.contains(key)) {
                    _expandedBackendKeys.remove(key);
                  } else {
                    _expandedBackendKeys.add(key);
                  }
                });
              },
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(8)),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  displayName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF94A3B8),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Text(
                '$modelCount',
                style: TextStyle(
                  fontSize: 10,
                  color: isDark
                      ? palette.textTertiary
                      : const Color(0xFFB0BAC9),
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                _hasSearchQuery
                    ? Icons.unfold_more_rounded
                    : expanded
                    ? Icons.expand_less_rounded
                    : Icons.expand_more_rounded,
                size: 14,
                color: isDark ? palette.textTertiary : const Color(0xFFB0BAC9),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBackendGroupedModels(ModelProviderProfileSummary profile) {
    final groups = _groupByBackend(profile.id);
    if (groups.isEmpty) {
      return _buildMutedMessage(widget.emptyModelsLabel);
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final backend in _sortedBackendKeys(groups.keys)) ...[
          _buildBackendSubHeader(profile.id, backend, groups[backend]!.length),
          if (_isBackendExpanded(profile.id, backend))
            ...groups[backend]!.map(
              (model) => _buildModelRow(profile: profile, model: model),
            ),
        ],
      ],
    );
  }

  Widget _buildMutedMessage(String? label) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
      child: Text(
        label ?? LegacyTextLocalizer.localize('该 Provider 暂无可选模型'),
        style: TextStyle(
          fontSize: 12,
          color: context.isDarkTheme
              ? context.omniPalette.textTertiary
              : const Color(0xFF94A3B8),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(220.0, widget.maxHeight)
            .toDouble();
    final configuredProfiles = widget.profiles
        .where((profile) => profile.configured)
        .toList();
    final visibleProfiles = _visibleProfiles;
    return SizedBox(
      width: widget.width,
      child: OmniGlassPanel(
        width: widget.width,
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (widget.showSearchField) _buildSearchRow(),
                if (configuredProfiles.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      widget.emptyProvidersLabel ??
                          LegacyTextLocalizer.localize('请先在模型提供商页配置 Provider'),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: context.isDarkTheme
                            ? palette.textTertiary
                            : const Color(0xFF94A3B8),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else if (visibleProfiles.isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      widget.emptyMatchesLabel ??
                          LegacyTextLocalizer.localize('没有匹配的模型'),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: context.isDarkTheme
                            ? palette.textTertiary
                            : const Color(0xFF94A3B8),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else
                  Flexible(
                    child: Scrollbar(
                      controller: _listScrollController,
                      child: ListView.builder(
                        controller: _listScrollController,
                        padding: EdgeInsets.only(
                          top: widget.showSearchField ? 0 : 8,
                          bottom: 8,
                        ),
                        itemCount: visibleProfiles.length,
                        itemBuilder: (context, index) {
                          final profile = visibleProfiles[index];
                          final expanded = _isExpanded(profile.id);
                          final models = _filteredModels(profile.id);
                          return Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (widget.showProfileHeaders)
                                _buildProfileHeader(profile),
                              if (expanded)
                                if (_needsBackendGrouping(profile.id))
                                  _buildBackendGroupedModels(profile)
                                else if (models.isEmpty)
                                  _buildMutedMessage(widget.emptyModelsLabel)
                                else
                                  Column(
                                    children: models
                                        .map(
                                          (item) => _buildModelRow(
                                            profile: profile,
                                            model: item,
                                          ),
                                        )
                                        .toList(),
                                  ),
                              if (index != visibleProfiles.length - 1)
                                const SizedBox(height: 6),
                            ],
                          );
                        },
                      ),
                    ),
                  ),
                if (widget.footer != null) widget.footer!,
              ],
            ),
          ),
        ),
      ),
    );
  }
}
