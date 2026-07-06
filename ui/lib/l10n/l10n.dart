import 'package:flutter/material.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/generated/app_localizations_zh.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

extension AppL10nBuildContextX on BuildContext {
  AppLocalizations get l10n =>
      AppLocalizations.of(this) ?? AppLocalizationsZh();

  String trLegacy(String text) {
    final resolvedLocale = AppLocalizations.of(this) == null
        ? const Locale('zh')
        : Localizations.localeOf(this);
    return LegacyTextLocalizer.localize(text, locale: resolvedLocale);
  }

  String trText(String text) => trLegacy(text);
}

extension OmniFlowL10nX on AppLocalizations {
  bool get _isEn => localeName.toLowerCase().startsWith('en');

  String get runLogTimelineTitle => _isEn ? 'RunLog' : '轨迹详情';
  String get runLogTimelineUnknown => _isEn ? 'Untitled' : '未命名';
  String get runLogTimelineLoadFailed => _isEn ? 'Load failed' : '加载失败';
  String get runLogTimelineEmpty => _isEn ? 'No replayable steps' : '暂无可重放步骤';
  String runLogTimelineStepCount(int count) =>
      _isEn ? '$count steps' : '$count 步';

  String get omniflowAssetRunLogNotReady =>
      _isEn ? 'RunLog is not ready' : '轨迹尚未准备好';
  String get omniflowAssetGoal => _isEn ? 'Goal' : '目标';
  String get omniflowAssetNoSteps => _isEn ? 'No steps' : '暂无步骤';
  String get omniflowCancel => _isEn ? 'Cancel' : '取消';
  String get omniflowSaveConfig => _isEn ? 'Save' : '保存';

  String get functionLibraryEnhanceOfflineHint =>
      _isEn ? 'Enhancement runs in the background' : '增强将在后台执行';
  String get functionLibraryDelete => _isEn ? 'Delete' : '删除';
  String get functionLibraryStepEditTitle => _isEn ? 'Edit step' : '编辑步骤';
  String get functionLibraryStepDeleteTitle => _isEn ? 'Delete step' : '删除步骤';
  String functionLibraryStepDeleteConfirm(String title) => _isEn
      ? 'Delete "$title"? This cannot be undone.'
      : '确定删除「$title」吗？此操作不可恢复。';
  String get functionLibraryStepSaveFailed =>
      _isEn ? 'Failed to save step' : '保存步骤失败';
  String get functionLibraryStepEditMissing =>
      _isEn ? 'Step not found' : '未找到要编辑的步骤';
  String get functionLibraryStepDeleteMissing =>
      _isEn ? 'Step not found' : '未找到要删除的步骤';
  String get functionLibraryStepKeepOne =>
      _isEn ? 'Keep at least one step' : '至少保留一个步骤';
  String get functionLibraryStepToolRequired =>
      _isEn ? 'Tool is required' : '工具名不能为空';
  String get functionLibraryStepArgsInvalid =>
      _isEn ? 'Arguments must be valid JSON' : '参数必须是合法 JSON';
  String get functionLibraryStepArgsObjectRequired =>
      _isEn ? 'Arguments must be a JSON object' : '参数必须是 JSON 对象';
  String get functionLibraryStepTitleLabel => _isEn ? 'Title' : '标题';
  String get functionLibraryStepToolLabel => _isEn ? 'Tool' : '工具';
  String get functionLibraryStepArgsLabel => _isEn ? 'Arguments' : '参数';

  String get executionRouteMemorized => _isEn ? 'Function' : '复用指令';
  String get executionRouteAiPlanning => _isEn ? 'Agent' : '智能规划';
  String get memoryCommandsTitle => _isEn ? 'Functions' : '复用指令';
  String memoryLongTermLoadFailed(Object error) =>
      _isEn ? 'Failed to load long-term memory: $error' : '长期记忆加载失败：$error';
}
