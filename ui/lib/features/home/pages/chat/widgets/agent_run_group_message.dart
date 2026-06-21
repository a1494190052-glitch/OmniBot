import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/card_widget_factory.dart'
    show OnBeforeTaskExecute, OnRequestAuthorize;
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/features/task/pages/execution_history/run_log_timeline_page.dart';
import 'package:ui/l10n/app_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_avatar.dart';

class AgentRunGroupMessage extends StatefulWidget {
  const AgentRunGroupMessage({
    super.key,
    required this.group,
    required this.expanded,
    required this.onToggleExpanded,
    required this.onBeforeTaskExecute,
    this.onCancelTask,
    this.onRetryAgentMessage,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onStreamingTextLayoutChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
  });

  final AgentRunTimelineGroup group;
  final bool expanded;
  final VoidCallback onToggleExpanded;
  final OnBeforeTaskExecute onBeforeTaskExecute;
  final void Function(String taskId)? onCancelTask;
  final ValueChanged<ChatMessageModel>? onRetryAgentMessage;
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final OnRequestAuthorize? onRequestAuthorize;
  final VoidCallback? onStreamingTextLayoutChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  @override
  State<AgentRunGroupMessage> createState() => _AgentRunGroupMessageState();
}

class _AgentRunGroupMessageState extends State<AgentRunGroupMessage>
    with SingleTickerProviderStateMixin {
  static const Duration _kToggleDuration = Duration(milliseconds: 260);
  static const double _processFontSize = 12;

  late final AnimationController _expandController;
  late final Animation<double> _sizeFactor;
  late final Animation<double> _opacity;
  late final Animation<double> _lift;
  bool _isNotifyingParentDuringAnimation = false;
  final Set<String> _expandedToolGroupKeys = <String>{};

  @override
  void initState() {
    super.initState();
    _expandController = AnimationController(
      vsync: this,
      duration: _kToggleDuration,
      reverseDuration: _kToggleDuration,
      value: widget.expanded ? 1.0 : 0.0,
    );
    _sizeFactor = CurvedAnimation(
      parent: _expandController,
      curve: Curves.easeInOutCubicEmphasized,
    );
    _opacity = CurvedAnimation(
      parent: _expandController,
      curve: const Interval(0.12, 1.0, curve: Curves.easeOutCubic),
      reverseCurve: const Interval(0.0, 0.72, curve: Curves.easeOutCubic),
    );
    _lift = Tween<double>(begin: -6, end: 0).animate(
      CurvedAnimation(
        parent: _expandController,
        curve: Curves.easeOutCubic,
        reverseCurve: Curves.easeInCubic,
      ),
    );
    _expandController.addListener(_handleAnimationTick);
    _expandController.addStatusListener(_handleAnimationStatusChanged);
    AgentAvatarService.ensureLoaded();
  }

  @override
  void didUpdateWidget(covariant AgentRunGroupMessage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.group.taskId != oldWidget.group.taskId) {
      _expandedToolGroupKeys.clear();
    }
    if (widget.expanded == oldWidget.expanded) {
      return;
    }
    _isNotifyingParentDuringAnimation = true;
    if (widget.expanded) {
      _expandController.forward();
    } else {
      _expandController.reverse();
    }
  }

  @override
  void dispose() {
    _expandController
      ..removeListener(_handleAnimationTick)
      ..removeStatusListener(_handleAnimationStatusChanged)
      ..dispose();
    super.dispose();
  }

  void _handleAnimationTick() {
    if (!mounted || !_isNotifyingParentDuringAnimation) {
      return;
    }
    widget.onStreamingTextLayoutChanged?.call();
  }

  void _handleAnimationStatusChanged(AnimationStatus status) {
    if (status != AnimationStatus.completed &&
        status != AnimationStatus.dismissed) {
      return;
    }
    final shouldNotifyParent = _isNotifyingParentDuringAnimation;
    _isNotifyingParentDuringAnimation = false;
    if (!mounted) {
      return;
    }
    setState(() {});
    if (shouldNotifyParent) {
      widget.onStreamingTextLayoutChanged?.call();
    }
  }

  @override
  Widget build(BuildContext context) {
    final processMessages = widget.group.processMessagesOldestFirst;
    final visibleMessages = widget.group.visibleMessagesOldestFirst;
    final hasProcessMessages = processMessages.isNotEmpty;
    final isRunLogOnly = widget.group.isRunLogOnly;
    final showProcessToggle = hasProcessMessages;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _AgentRunSummaryHeader(
          key: ValueKey('agent-run-summary-${widget.group.taskId}'),
          group: widget.group,
          taskId: widget.group.taskId,
          runLogId: widget.group.runLogId,
          isActiveRun: widget.group.isActiveRun,
          expanded: widget.expanded,
          thinkingCount: widget.group.thinkingCount,
          toolCount: widget.group.toolCount,
          outputSegmentCount: widget.group.outputSegmentCount,
          latestProcessSummary: _latestProcessSummary(context, processMessages),
          isRunLogOnly: isRunLogOnly,
          onTap: hasProcessMessages
              ? () => _toggleProcessSection(processMessages)
              : isRunLogOnly
              ? () => _openRunLog(context)
              : null,
          showToggle: showProcessToggle,
        ),
        _buildAnimatedProcessSection(processMessages),
        ...visibleMessages.map(
          (message) => MessageBubble(
            key: ValueKey('agent-run-${widget.group.taskId}-${message.id}'),
            message: message,
            onBeforeTaskExecute: widget.onBeforeTaskExecute,
            onCancelTask: widget.onCancelTask,
            onRetryAgentMessage: () =>
                widget.onRetryAgentMessage?.call(message),
            enableThinkingCollapse: false,
            parentScrollController: widget.parentScrollController,
            onParentScrollHandoff: widget.onParentScrollHandoff,
            onRequestAuthorize: widget.onRequestAuthorize,
            onStreamingTextLayoutChanged: widget.onStreamingTextLayoutChanged,
            visualProfile: widget.visualProfile,
            appearanceConfig: widget.appearanceConfig,
          ),
        ),
      ],
    );
  }

  void _openRunLog(BuildContext context) {
    final runLogId = widget.group.runLogId.trim().isEmpty
        ? widget.group.taskId
        : widget.group.runLogId.trim();
    showRunLogTimelineSheet(context, runId: runLogId);
  }

  void _toggleProcessSection(List<ChatMessageModel> processMessages) {
    widget.onToggleExpanded();
  }

  Widget _buildAnimatedProcessSection(List<ChatMessageModel> processMessages) {
    if (processMessages.isEmpty) {
      return const SizedBox.shrink();
    }

    final shouldShow =
        widget.expanded ||
        _expandController.isAnimating ||
        _expandController.value > 0.001;
    if (!shouldShow) {
      return const SizedBox.shrink();
    }

    final firstThinkingMessageId = _firstThinkingMessageId(processMessages);

    return AnimatedBuilder(
      animation: _expandController,
      child: Column(
        key: ValueKey('agent-run-process-${widget.group.taskId}'),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: _buildProcessWidgets(processMessages, firstThinkingMessageId),
      ),
      builder: (context, child) {
        final sizeFactor = _sizeFactor.value.clamp(0.0, 1.0);
        final opacity = _opacity.value.clamp(0.0, 1.0);
        return Padding(
          padding: const EdgeInsets.only(top: 2, bottom: 6),
          child: ClipRect(
            child: Align(
              alignment: Alignment.topCenter,
              heightFactor: sizeFactor,
              child: Transform.translate(
                offset: Offset(0, _lift.value),
                child: IgnorePointer(
                  ignoring: !widget.expanded && !_expandController.isAnimating,
                  child: Opacity(opacity: opacity, child: child),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  List<Widget> _buildProcessWidgets(
    List<ChatMessageModel> processMessages,
    String? firstThinkingMessageId,
  ) {
    final widgets = <Widget>[];
    var index = 0;
    while (index < processMessages.length) {
      final message = processMessages[index];
      if (_isAgentToolSummaryMessage(message)) {
        final toolMessages = <ChatMessageModel>[message];
        var nextIndex = index + 1;
        while (nextIndex < processMessages.length &&
            _isAgentToolSummaryMessage(processMessages[nextIndex])) {
          toolMessages.add(processMessages[nextIndex]);
          nextIndex += 1;
        }
        if (toolMessages.length > 1) {
          final groupKey = _toolGroupKey(widget.group.taskId, toolMessages);
          widgets.add(
            _AgentToolCallGroup(
              key: ValueKey('agent-tool-call-group-$groupKey'),
              groupKey: groupKey,
              messages: toolMessages,
              expanded: _expandedToolGroupKeys.contains(groupKey),
              onToggle: () => _toggleToolGroup(groupKey),
              buildMessageBubble: _buildMessageBubble,
            ),
          );
        } else {
          widgets.add(
            _buildMessageBubble(
              toolMessages.single,
              firstThinkingMessageId: firstThinkingMessageId,
            ),
          );
        }
        index = nextIndex;
        continue;
      }

      widgets.add(
        _buildMessageBubble(
          message,
          firstThinkingMessageId: firstThinkingMessageId,
        ),
      );
      index += 1;
    }
    return widgets;
  }

  MessageBubble _buildMessageBubble(
    ChatMessageModel message, {
    String? firstThinkingMessageId,
  }) {
    final hideAvatar =
        firstThinkingMessageId != null && message.id == firstThinkingMessageId;
    return MessageBubble(
      key: ValueKey('agent-run-${widget.group.taskId}-${message.id}'),
      message: message,
      onBeforeTaskExecute: widget.onBeforeTaskExecute,
      onCancelTask: widget.onCancelTask,
      onRetryAgentMessage: () => widget.onRetryAgentMessage?.call(message),
      enableThinkingCollapse: true,
      thinkingAutoCollapseOnComplete: true,
      showThinkingAvatarOverride: hideAvatar ? false : null,
      parentScrollController: widget.parentScrollController,
      onParentScrollHandoff: widget.onParentScrollHandoff,
      onRequestAuthorize: widget.onRequestAuthorize,
      onStreamingTextLayoutChanged: widget.onStreamingTextLayoutChanged,
      visualProfile: widget.visualProfile,
      appearanceConfig: widget.appearanceConfig,
    );
  }

  void _toggleToolGroup(String groupKey) {
    setState(() {
      if (!_expandedToolGroupKeys.add(groupKey)) {
        _expandedToolGroupKeys.remove(groupKey);
      }
    });
    widget.onStreamingTextLayoutChanged?.call();
  }

  String? _firstThinkingMessageId(List<ChatMessageModel> processMessages) {
    for (final message in processMessages) {
      if ((message.cardData?['type'] ?? '').toString() == 'deep_thinking') {
        return message.id;
      }
    }
    return null;
  }

  String _latestProcessSummary(
    BuildContext context,
    List<ChatMessageModel> processMessages,
  ) {
    final locale = Localizations.localeOf(context);
    for (final message in processMessages.reversed) {
      final cardData = message.cardData;
      final cardType = (cardData?['type'] ?? '').toString();
      if (cardType == kAgentToolSummaryCardType && cardData != null) {
        final title = resolveAgentToolTitle(cardData, locale: locale).trim();
        if (title.isNotEmpty) {
          return title;
        }
      }
      if (cardType == 'deep_thinking') {
        final text = (cardData?['thinkingContent'] ?? '').toString().trim();
        if (text.isNotEmpty) {
          final firstLine = text
              .split('\n')
              .map((line) => line.trim())
              .firstWhere((line) => line.isNotEmpty, orElse: () => '');
          if (firstLine.isNotEmpty) {
            return firstLine;
          }
        }
      }
    }
    return '';
  }
}

bool _isAgentToolSummaryMessage(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString() ==
      kAgentToolSummaryCardType;
}

String _toolGroupKey(String taskId, List<ChatMessageModel> messages) {
  return '$taskId-${messages.map((message) => message.id).join('-')}';
}

class _AgentToolCallGroup extends StatelessWidget {
  const _AgentToolCallGroup({
    super.key,
    required this.groupKey,
    required this.messages,
    required this.expanded,
    required this.onToggle,
    required this.buildMessageBubble,
  });

  final String groupKey;
  final List<ChatMessageModel> messages;
  final bool expanded;
  final VoidCallback onToggle;
  final MessageBubble Function(
    ChatMessageModel message, {
    String? firstThinkingMessageId,
  })
  buildMessageBubble;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final primaryCard = _primaryCardData(messages);
    final status = (primaryCard['status'] ?? 'running').toString();
    final toolType = (primaryCard['toolType'] ?? '').toString();
    final mutedColor = palette.textSecondary.withValues(
      alpha: context.isDarkTheme ? 0.78 : 0.68,
    );
    final titleColor = palette.textSecondary.withValues(
      alpha: context.isDarkTheme ? 0.94 : 0.88,
    );
    final overlayColor = palette.accentPrimary.withValues(
      alpha: context.isDarkTheme ? 0.10 : 0.06,
    );
    final title = AppTextLocalizer.choose(
      zh: '已处理',
      en: 'Processed',
      locale: locale,
    );

    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(top: 6, bottom: 4),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * 0.90,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              message: _toolGroupTooltip(messages, locale),
              child: Material(
                color: Colors.transparent,
                borderRadius: BorderRadius.circular(8),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  key: ValueKey('agent-tool-call-group-toggle-$groupKey'),
                  onTap: onToggle,
                  splashColor: overlayColor,
                  highlightColor: overlayColor,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(2, 5, 5, 5),
                    child: Row(
                      children: [
                        Icon(
                          resolveAgentToolStatusIcon(status, toolType),
                          size: 16,
                          color: mutedColor,
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: titleColor,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                              letterSpacing: 0,
                              height: 1.18,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          '${messages.length}',
                          style: TextStyle(
                            color: mutedColor,
                            fontSize: 10.5,
                            fontWeight: FontWeight.w700,
                            height: 1,
                          ),
                        ),
                        const SizedBox(width: 4),
                        AnimatedRotation(
                          turns: expanded ? 0.5 : 0,
                          duration: const Duration(milliseconds: 220),
                          curve: Curves.easeOutCubic,
                          child: Icon(
                            Icons.keyboard_arrow_down_rounded,
                            size: 18,
                            color: mutedColor,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            AnimatedSize(
              duration: const Duration(milliseconds: 260),
              curve: Curves.easeOutCubic,
              alignment: Alignment.topLeft,
              child: expanded
                  ? Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: messages
                            .map((message) => buildMessageBubble(message))
                            .toList(growable: false),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
          ],
        ),
      ),
    );
  }

  Map<String, dynamic> _primaryCardData(List<ChatMessageModel> messages) {
    for (final message in messages) {
      final cardData = message.cardData;
      if ((cardData?['status'] ?? '').toString() == 'running') {
        return cardData!;
      }
    }
    return messages.first.cardData ?? const <String, dynamic>{};
  }

  String _toolGroupTooltip(List<ChatMessageModel> messages, Locale locale) {
    return messages
        .map((message) => message.cardData)
        .whereType<Map<String, dynamic>>()
        .map((cardData) => resolveAgentToolTitle(cardData, locale: locale))
        .where((title) => title.trim().isNotEmpty)
        .join('\n');
  }
}

class _AgentRunSummaryHeader extends StatelessWidget {
  const _AgentRunSummaryHeader({
    super.key,
    required this.group,
    required this.taskId,
    required this.runLogId,
    required this.isActiveRun,
    required this.expanded,
    required this.thinkingCount,
    required this.toolCount,
    required this.outputSegmentCount,
    required this.latestProcessSummary,
    required this.isRunLogOnly,
    required this.onTap,
    required this.showToggle,
  });

  final AgentRunTimelineGroup group;
  final String taskId;
  final String runLogId;
  final bool isActiveRun;
  final bool expanded;
  final int thinkingCount;
  final int toolCount;
  final int outputSegmentCount;
  final String latestProcessSummary;
  final bool isRunLogOnly;
  final VoidCallback? onTap;
  final bool showToggle;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final label = AppTextLocalizer.choose(
      zh: '步骤',
      en: 'Steps',
      locale: locale,
    );
    if (isRunLogOnly) {
      return _RunLogOnlySummaryHeader(
        taskId: taskId,
        runLogId: runLogId,
        label: label,
        onTap: onTap,
      );
    }
    final statusLabel = isRunLogOnly
        ? AppTextLocalizer.choose(zh: '已记录', en: 'Logged', locale: locale)
        : isActiveRun
        ? AppTextLocalizer.choose(zh: '进行中', en: 'Running', locale: locale)
        : AppTextLocalizer.choose(zh: '已完成', en: 'Done', locale: locale);
    final summary = _summaryText(locale);
    final labelColor = expanded ? palette.textSecondary : palette.textTertiary;
    final lineColor = expanded
        ? palette.textSecondary.withValues(
            alpha: context.isDarkTheme ? 0.32 : 0.28,
          )
        : palette.borderSubtle.withValues(
            alpha: context.isDarkTheme ? 0.56 : 0.8,
          );

    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ValueListenableBuilder<AgentAvatarState>(
                  valueListenable: AgentAvatarService.avatarStateNotifier,
                  builder: (context, state, _) {
                    return AgentAvatarCircle(
                      key: ValueKey('agent-run-avatar-$taskId'),
                      state: state,
                      size: 30,
                    );
                  },
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final showSummary =
                          summary.isNotEmpty && constraints.maxWidth >= 142;
                      final showDivider = constraints.maxWidth >= 188;
                      return Row(
                        children: [
                          Flexible(
                            child: Text(
                              label,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize:
                                    _AgentRunGroupMessageState._processFontSize,
                                fontWeight: FontWeight.w600,
                                color: labelColor,
                                fontFamily: 'PingFang SC',
                                letterSpacing: 0,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          _ProcessStatusPill(
                            label: statusLabel,
                            isActiveRun: isActiveRun,
                            expanded: expanded,
                          ),
                          if (showSummary) ...[
                            const SizedBox(width: 8),
                            Flexible(
                              child: Text(
                                summary,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: _AgentRunGroupMessageState
                                      ._processFontSize,
                                  fontWeight: FontWeight.w500,
                                  color: palette.textTertiary,
                                  letterSpacing: 0,
                                  height: 1.1,
                                ),
                              ),
                            ),
                          ],
                          if (showDivider) ...[
                            const SizedBox(width: 10),
                            SizedBox(
                              width: 42,
                              child: Container(height: 1, color: lineColor),
                            ),
                          ],
                        ],
                      );
                    },
                  ),
                ),
                if (showToggle) ...[
                  const SizedBox(width: 8),
                  AnimatedRotation(
                    turns: expanded ? 0 : -0.25,
                    duration: _AgentRunGroupMessageState._kToggleDuration,
                    curve: Curves.easeInOutCubicEmphasized,
                    child: Icon(
                      Icons.keyboard_arrow_down_rounded,
                      size: 18,
                      color: labelColor,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _summaryText(Locale locale) {
    final parts = <String>[];
    final stepCount = thinkingCount + toolCount;
    if (stepCount > 0) {
      parts.add(
        AppTextLocalizer.choose(
          zh: '$stepCount 步',
          en: '$stepCount ${stepCount == 1 ? 'step' : 'steps'}',
          locale: locale,
        ),
      );
    }
    if (outputSegmentCount > 0) {
      parts.add(
        AppTextLocalizer.choose(
          zh: '$outputSegmentCount 段输出',
          en: '$outputSegmentCount output ${outputSegmentCount == 1 ? 'segment' : 'segments'}',
          locale: locale,
        ),
      );
    }
    final trimmed = latestProcessSummary.trim();
    if (trimmed.isNotEmpty) {
      parts.add(trimmed);
    }
    return parts.join(' · ');
  }
}

class _RunLogOnlySummaryHeader extends StatelessWidget {
  const _RunLogOnlySummaryHeader({
    required this.taskId,
    required this.runLogId,
    required this.label,
    required this.onTap,
  });

  final String taskId;
  final String runLogId;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final palette = context.omniPalette;
    final labelColor = palette.textTertiary;
    final statusLabel = AppTextLocalizer.choose(
      zh: '无可展开步骤',
      en: 'No steps',
      locale: locale,
    );
    return Padding(
      padding: const EdgeInsets.only(top: 6, bottom: 1),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 2, 2, 2),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ValueListenableBuilder<AgentAvatarState>(
                  valueListenable: AgentAvatarService.avatarStateNotifier,
                  builder: (context, state, _) {
                    return AgentAvatarCircle(
                      key: ValueKey('agent-run-avatar-$taskId'),
                      state: state,
                      size: 24,
                    );
                  },
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Row(
                    children: [
                      Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: _AgentRunGroupMessageState._processFontSize,
                          fontWeight: FontWeight.w600,
                          color: labelColor,
                          fontFamily: 'PingFang SC',
                          letterSpacing: 0,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          statusLabel,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize:
                                _AgentRunGroupMessageState._processFontSize,
                            fontWeight: FontWeight.w500,
                            color: palette.textTertiary,
                            letterSpacing: 0,
                            height: 1.1,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ProcessStatusPill extends StatelessWidget {
  const _ProcessStatusPill({
    required this.label,
    required this.isActiveRun,
    required this.expanded,
  });

  final String label;
  final bool isActiveRun;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = isActiveRun
        ? palette.accentPrimary
        : (expanded ? palette.textSecondary : palette.textTertiary);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.12 : 0.08),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: _AgentRunGroupMessageState._processFontSize,
            fontWeight: FontWeight.w600,
            color: color,
            letterSpacing: 0,
            height: 1,
          ),
        ),
      ),
    );
  }
}
