import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/workbench/models/workbench_models.dart';
import 'package:ui/features/workbench/services/workbench_project_service.dart';
import 'package:ui/features/workbench/services/workbench_shape_recognizer.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_context.dart';
import 'package:ui/features/workbench/widgets/workbench_annotation_overlay.dart';
import 'package:ui/features/workbench/widgets/workbench_layout_profile.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class WorkbenchProjectDisplayPage extends StatefulWidget {
  const WorkbenchProjectDisplayPage({
    super.key,
    WorkbenchProjectService? service,
    String? projectId,
    String? displayId,
    String? returnTo,
    bool debugMode = false,
    bool annotationMode = false,
    bool embedded = false,
  }) : _service = service,
       _projectId = projectId,
       _displayId = displayId,
       _returnTo = returnTo,
       _debugMode = debugMode,
       _annotationMode = annotationMode,
       _embedded = embedded;

  final WorkbenchProjectService? _service;
  final String? _projectId;
  final String? _displayId;
  final String? _returnTo;
  final bool _debugMode;
  final bool _annotationMode;
  final bool _embedded;

  @override
  State<WorkbenchProjectDisplayPage> createState() =>
      _WorkbenchProjectDisplayPageState();
}

class _WorkbenchProjectDisplayPageState
    extends State<WorkbenchProjectDisplayPage> {
  late final WorkbenchProjectService _service;
  late final bool _ownsService = widget._service == null;
  String? _lastReportedFrontendKey;

  @override
  void initState() {
    super.initState();
    _service =
        widget._service ??
        WorkbenchProjectService.native(
          projectId: widget._projectId ?? '',
          autoCreateIfMissing: false,
        );
    _service.initialize();
  }

  @override
  void dispose() {
    if (_ownsService) {
      _service.dispose();
    }
    super.dispose();
  }

  Future<bool> _applyAnnotationUpdate(
    WorkbenchProject project,
    WorkbenchAnnotationPayload payload,
    String prompt,
  ) async {
    final themeProfile = buildWorkbenchThemeProfile(context);
    // Run screenshot capture and DOM hit-test concurrently — both are fast.
    final drawingPaths = payload.strokes
        .map((s) => Map<String, dynamic>.from(s.toMap(payload.canvasSize)))
        .toList();
    final results = await Future.wait([
      AssistsMessageService.captureWorkbenchAnnotationAttachment(
        canvasWidth: payload.canvasSize.width,
        canvasHeight: payload.canvasSize.height,
        drawingPaths: drawingPaths,
        source: 'workbench_display_annotation',
      ),
      resolveAnnotationTarget(payload),
    ]);
    final attachment = results[0] as Map<String, dynamic>?;
    final target = results[1] as WorkbenchAnnotationTarget;

    final baseFrontendContext = buildWorkbenchAnnotationFrontendContext(
      themeProfile: themeProfile,
      project: project,
      display: _selectedDisplay(project),
      payload: payload,
      prompt: prompt,
      fallbackRoute: '/workbench/project',
    );

    final frontendContext = {
      ...baseFrontendContext,
      // Composite screenshot (HTML display + red strokes) for VLM analysis.
      if (attachment?['path'] != null)
        'annotationImagePath': attachment!['path'],
      // DOM element at the annotation centroid/tip — use oobId for htmlPatches.
      'selectedElement': target.toMap(),
      'annotationDescription': target.toAnnotationDescription(),
      'screenshotSummary': attachment == null
          ? 'Screenshot capture failed. Use drawingPaths coordinates to locate the target.'
          : 'Composite screenshot (display + red annotation strokes) saved at annotationImagePath. '
                'Call vlm_task on that path to understand what the user annotated before patching HTML.',
    };

    final result = await _service.applyHotUpdate(
      prompt,
      frontendContext: frontendContext,
    );
    if (!mounted) return false;
    if (!result.success) {
      showToast(
        context.l10n.workbenchAnnotationHotUpdateFailed,
        type: ToastType.error,
      );
      return false;
    }
    showToast(
      context.l10n.workbenchAnnotationHotUpdateSuccess,
      type: ToastType.success,
    );
    return true;
  }

  void _handleBackNavigation() {
    final returnTo = widget._returnTo?.trim();
    if (returnTo != null && returnTo.isNotEmpty) {
      context.go(returnTo);
      return;
    }
    if (GoRouterManager.canPop()) {
      GoRouterManager.pop();
      return;
    }
    context.go(GoRouterManager.homeRoute);
  }

  void _reportVisibleDisplay(WorkbenchProject project) {
    final display = _selectedDisplay(project);
    final route = workbenchRouteForDisplay(
      project,
      display,
      fallbackRoute: '/workbench/project',
    );
    final key = '${project.projectId}:${display.id}:$route:${widget._embedded}';
    if (_lastReportedFrontendKey == key) return;
    _lastReportedFrontendKey = key;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(
        NativeWorkbenchProjectBackend().setActiveFrontendContext(
          buildWorkbenchVisibleFrontendContext(
            context: context,
            project: project,
            display: display,
            source: 'workbench_project_display_page',
            fallbackRoute: '/workbench/project',
            extraVisibleState: {'embedded': widget._embedded},
          ),
        ),
      );
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final body = AnimatedBuilder(
      animation: _service,
      builder: (context, _) {
        final project = _service.project;
        if (project == null) {
          return _buildLoadingOrError();
        }
        _reportVisibleDisplay(project);
        final annotationActive = widget._debugMode || widget._annotationMode;
        final description = _businessDescription(project);
        final content = ListView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
          children: [
            if (_service.loading) ...[
              const LinearProgressIndicator(minHeight: 2),
              const SizedBox(height: 12),
            ],
            if (description.isNotEmpty) ...[
              _buildBusinessDescription(description),
              const SizedBox(height: 12),
            ],
            _buildItemsCard(project),
          ],
        );
        if (!annotationActive) {
          return content;
        }
        return WorkbenchAnnotationOverlay(
          onSubmit: (payload, prompt) =>
              _applyAnnotationUpdate(project, payload, prompt),
          child: content,
        );
      },
    );
    if (widget._embedded) {
      return Material(color: palette.pageBackground, child: body);
    }
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBackNavigation();
      },
      child: Scaffold(
        backgroundColor: palette.pageBackground,
        appBar: CommonAppBar(
          title: _service.project == null
              ? context.l10n.workbenchProjectModeTitle
              : _displayTitle(_service.project!),
          primary: true,
          onBackPressed: _handleBackNavigation,
        ),
        body: SafeArea(child: body),
      ),
    );
  }

  Widget _buildLoadingOrError() {
    final palette = context.omniPalette;
    final error = _service.errorMessage;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Text(
          error == null || error.isEmpty ? context.l10n.commonLoading : error,
          textAlign: TextAlign.center,
          style: TextStyle(color: palette.textSecondary, fontSize: 13),
        ),
      ),
    );
  }

  Widget _buildBusinessDescription(String description) {
    final palette = context.omniPalette;
    return Text(
      description,
      style: TextStyle(
        color: palette.textSecondary,
        fontSize: 14,
        height: 1.35,
      ),
    );
  }

  Widget _buildItemsCard(WorkbenchProject project) {
    final items = project.items;
    return _buildSectionCard(
      title: context.l10n.workbenchProjectItemsTitle(_entityName(project)),
      icon: Icons.view_list_rounded,
      child: items.isEmpty
          ? _buildEmpty(project)
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (project.activeItems.isNotEmpty) ...[
                  _buildGroupLabel(context.l10n.workbenchProjectActiveItems),
                  const SizedBox(height: 8),
                  ...project.activeItems.map(_buildActiveItemTile),
                ],
                if (project.archivedItems.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  _buildGroupLabel(context.l10n.workbenchProjectArchivedItems),
                  const SizedBox(height: 8),
                  ...project.archivedItems.map(_buildArchivedItemTile),
                ],
              ],
            ),
    );
  }

  Widget _buildActiveItemTile(WorkbenchProjectItem item) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: ListTile(
        leading: Icon(
          Icons.radio_button_unchecked,
          color: palette.textTertiary,
        ),
        title: Text(
          item.title,
          style: TextStyle(
            color: palette.textPrimary,
            fontSize: 14,
            fontWeight: FontWeight.w700,
          ),
        ),
        subtitle: _buildFieldSummary(item),
      ),
    );
  }

  Widget _buildArchivedItemTile(WorkbenchProjectItem item) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(8),
      ),
      child: ListTile(
        leading: Icon(Icons.archive_rounded, color: palette.textTertiary),
        title: Text(
          item.title,
          style: TextStyle(
            color: palette.textSecondary,
            fontSize: 14,
            decoration: TextDecoration.lineThrough,
          ),
        ),
        subtitle: Text(
          context.l10n.workbenchProjectArchivedItems,
          style: TextStyle(color: palette.textTertiary, fontSize: 12),
        ),
      ),
    );
  }

  Widget _buildFieldSummary(WorkbenchProjectItem item) {
    final visibleFields = item.fields.entries
        .where((entry) => entry.value?.toString().trim().isNotEmpty == true)
        .take(2)
        .map((entry) => '${entry.key}: ${entry.value}')
        .join(' · ');
    return Text(
      visibleFields.isEmpty
          ? context.l10n.workbenchProjectActiveItems
          : visibleFields,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(color: context.omniPalette.textSecondary, fontSize: 12),
    );
  }

  Widget _buildEmpty(WorkbenchProject project) {
    final palette = context.omniPalette;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        context.l10n.workbenchProjectEmpty(_entityName(project)),
        textAlign: TextAlign.center,
        style: TextStyle(color: palette.textSecondary, fontSize: 13),
      ),
    );
  }

  Widget _buildSectionCard({
    required String title,
    required IconData icon,
    required Widget child,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor,
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: palette.accentPrimary),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }

  Widget _buildGroupLabel(String label) {
    return Text(
      label,
      style: TextStyle(
        color: context.omniPalette.textSecondary,
        fontSize: 12,
        fontWeight: FontWeight.w700,
      ),
    );
  }

  WorkbenchDisplaySpec _selectedDisplay(WorkbenchProject project) {
    final displayId = widget._displayId?.trim();
    if (displayId != null && displayId.isNotEmpty) {
      for (final display in project.displays) {
        if (display.id == displayId) return display;
      }
    }
    return project.primaryDisplay;
  }

  String _displayTitle(WorkbenchProject project) {
    final label = _selectedDisplay(project).label.trim();
    return label.isEmpty ? project.name : label;
  }

  String _entityName(WorkbenchProject project) {
    final entity = _pageSpecString(project, 'entityName');
    return entity.isEmpty ? context.l10n.workbenchProjectDefaultEntity : entity;
  }

  String _businessDescription(WorkbenchProject project) {
    return _pageSpecString(project, 'description');
  }

  String _pageSpecString(WorkbenchProject project, String key) {
    return project.pageSpec[key]?.toString().trim() ?? '';
  }
}
