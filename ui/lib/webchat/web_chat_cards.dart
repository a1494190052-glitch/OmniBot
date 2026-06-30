// Webchat-only mirrors of the in-app DeepThinkingCard / AgentToolSummaryCard
// surfaces. They intentionally re-implement the cards instead of importing
// CardWidgetFactory because the native factory transitively pulls in
// dart:ffi / dart:io modules (omnibot router, agent_avatar, background
// settings) that fail to compile under Flutter Web.
//
// The visual language, motion (auto-collapse, shimmer flow, status pill,
// elapsed timer), copy and dispatch table mirror
// `command_overlay/widgets/cards/{deep_thinking_card,agent_tool_summary_card}.dart`
// so that the LAN web UI renders identically to the in-app chat.

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart'
    as native_transcript;
import 'package:ui/l10n/legacy_text_localizer.dart';

const Color _kPrimaryText = Color(0xFF353E53);
const Color _kSecondaryText = Color(0xFF617390);
const Color _kSubtleText = Color(0xFF9DA9BB);
const Color _kThinkingSurface = Color(0xCCF1F8FF);
const Color _kThinkingBorder = Color(0x33617390);

class WebChatCards {
  WebChatCards._();

  static Widget createCard(
    Map<String, dynamic> cardData, {
    ScrollController? parentScrollController,
    bool enableThinkingCollapse = true,
    bool thinkingAutoCollapseOnComplete = true,
  }) {
    final type = (cardData['type'] ?? '').toString();
    switch (type) {
      case 'deep_thinking':
        final stage = _asInt(cardData['stage']) ?? 1;
        final isLoading = _asBool(
          cardData['isLoading'],
          fallback: stage != 4 && stage != 5,
        );
        final thinkingText = (cardData['thinkingContent'] ?? '').toString();
        final startTime = _asInt(cardData['startTime']);
        final endTime = _asInt(cardData['endTime']);
        final taskId = _asNullableString(cardData['taskID']);
        final isCollapsible = _asBool(
          cardData['isCollapsible'],
          fallback: enableThinkingCollapse,
        );
        return WebDeepThinkingCard(
          key: taskId != null
              ? ValueKey('web_deep_thinking_${taskId}_${startTime ?? 'na'}')
              : null,
          thinkingText: thinkingText,
          isLoading: isLoading,
          stage: stage,
          startTime: startTime,
          endTime: endTime,
          isCollapsible: isCollapsible,
          autoCollapseOnComplete: thinkingAutoCollapseOnComplete,
          parentScrollController: parentScrollController,
        );
      case 'agent_tool_summary':
        return WebAgentToolSummaryCard(
          cardData: cardData,
          parentScrollController: parentScrollController,
        );
      case 'history_omitted_card':
        return WebHistoryOmittedCard(cardData: cardData);
      case 'stage_hint':
        return WebStageHintCard(cardData: cardData);
      default:
        return WebUnknownCard(type: type);
    }
  }
}

// ---------------------------------------------------------------------------
// DeepThinkingCard
// ---------------------------------------------------------------------------

class WebDeepThinkingCard extends StatefulWidget {
  const WebDeepThinkingCard({
    super.key,
    required this.thinkingText,
    this.isLoading = true,
    this.maxHeight = 210,
    this.stage = 1,
    this.startTime,
    this.endTime,
    this.isCollapsible = true,
    this.autoCollapseOnComplete = true,
    this.parentScrollController,
  });

  final String thinkingText;
  final bool isLoading;
  final double maxHeight;
  final int stage;
  final int? startTime;
  final int? endTime;
  final bool isCollapsible;
  final bool autoCollapseOnComplete;
  final ScrollController? parentScrollController;

  @override
  State<WebDeepThinkingCard> createState() => _WebDeepThinkingCardState();
}

class _WebDeepThinkingCardState extends State<WebDeepThinkingCard>
    with SingleTickerProviderStateMixin {
  static const Duration _collapseDuration = Duration(milliseconds: 170);
  static const Cubic _collapseCurve = Cubic(0.22, 1.0, 0.36, 1.0);
  static const Cubic _expandCurve = Cubic(0.2, 0.8, 0.2, 1.0);
  static const double _bottomTolerance = 1.0;

  Timer? _timer;
  int _elapsedSeconds = 0;
  final ScrollController _scrollController = ScrollController();
  bool _isCollapsed = false;
  bool _autoScrollToLatest = true;
  bool _hasAutoCollapsedForCurrentCompletion = false;
  bool _showGradient = false;
  bool? _pendingGradientVisibility;
  bool _gradientUpdateScheduled = false;
  late final AnimationController _collapseController;
  late Animation<double> _collapseSizeFactor;
  late Animation<double> _collapseOpacity;

  bool _isCompletedStage(int stage) => stage == 4 || stage == 5;

  bool _shouldAutoCollapse(WebDeepThinkingCard w) {
    return w.autoCollapseOnComplete &&
        w.isCollapsible &&
        w.stage == 4 &&
        !w.isLoading;
  }

  @override
  void initState() {
    super.initState();
    _hasAutoCollapsedForCurrentCompletion = _shouldAutoCollapse(widget);
    _isCollapsed = _hasAutoCollapsedForCurrentCompletion;
    _collapseController = AnimationController(
      vsync: this,
      duration: _collapseDuration,
      reverseDuration: _collapseDuration,
      value: _isCollapsed ? 0.0 : 1.0,
    );
    _rebuildCollapseAnimations();
    _updateElapsedTime(notify: false);
    if (!_isCompletedStage(widget.stage)) {
      _startTimer();
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollToLatestIfNeeded(force: true);
      _checkOverflow();
    });
  }

  @override
  void didUpdateWidget(WebDeepThinkingCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    _updateElapsedTime();

    final becameCompleted =
        !_isCompletedStage(oldWidget.stage) && _isCompletedStage(widget.stage);
    final becameThinking =
        _isCompletedStage(oldWidget.stage) && !_isCompletedStage(widget.stage);
    final completionSettled =
        _shouldAutoCollapse(widget) &&
        (!_shouldAutoCollapse(oldWidget) ||
            oldWidget.isLoading != widget.isLoading ||
            oldWidget.isCollapsible != widget.isCollapsible);

    if (becameCompleted) {
      _stopTimer();
    }
    if (becameThinking) {
      _startTimer();
      _autoScrollToLatest = true;
      _hasAutoCollapsedForCurrentCompletion = false;
    }
    if (completionSettled && !_hasAutoCollapsedForCurrentCompletion) {
      _setCollapsed(true, markCompletionHandled: true);
    } else if (becameThinking && _isCollapsed) {
      _setCollapsed(false);
    }

    final textChanged = widget.thinkingText != oldWidget.thinkingText;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollToLatestIfNeeded(force: textChanged);
      _checkOverflow();
    });
  }

  void _updateElapsedTime({bool notify = true}) {
    final next = widget.startTime == null
        ? 0
        : (widget.endTime != null
                  ? DateTime.fromMillisecondsSinceEpoch(
                      widget.endTime!,
                    ).difference(
                      DateTime.fromMillisecondsSinceEpoch(widget.startTime!),
                    )
                  : DateTime.now().difference(
                      DateTime.fromMillisecondsSinceEpoch(widget.startTime!),
                    ))
              .inSeconds;
    if (next == _elapsedSeconds) return;
    if (!notify || !mounted) {
      _elapsedSeconds = next;
      return;
    }
    setState(() => _elapsedSeconds = next);
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      _updateElapsedTime();
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  void _checkOverflow() {
    if (!_scrollController.hasClients) return;
    final position = _scrollController.position;
    final maxExtent = position.maxScrollExtent;
    final hasOverflow = maxExtent > 0;
    final distance = (maxExtent - position.pixels).clamp(0.0, maxExtent);
    final atBottom = distance <= _bottomTolerance;
    final shouldShow = hasOverflow && !atBottom;
    if (shouldShow == _showGradient && _pendingGradientVisibility == null) {
      return;
    }
    _pendingGradientVisibility = shouldShow;
    if (_gradientUpdateScheduled) return;
    _gradientUpdateScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _gradientUpdateScheduled = false;
      final pending = _pendingGradientVisibility;
      _pendingGradientVisibility = null;
      if (!mounted || pending == null || pending == _showGradient) return;
      setState(() => _showGradient = pending);
    });
  }

  void _scrollToLatestIfNeeded({bool force = false}) {
    if (!mounted || !_scrollController.hasClients) return;
    if (_isCollapsed || widget.stage == 5) return;
    if (!force && widget.stage == 4) return;
    if (!_autoScrollToLatest) return;
    final position = _scrollController.position;
    final max = position.maxScrollExtent;
    if (max <= 0) return;
    final current = position.pixels.clamp(0.0, max);
    if ((max - current).abs() <= _bottomTolerance) return;
    _scrollController.jumpTo(max);
  }

  void _rebuildCollapseAnimations() {
    _collapseSizeFactor = CurvedAnimation(
      parent: _collapseController,
      curve: _isCollapsed ? _collapseCurve : _expandCurve,
      reverseCurve: _collapseCurve,
    );
    _collapseOpacity = CurvedAnimation(
      parent: _collapseController,
      curve: _isCollapsed
          ? const Interval(0.0, 0.72, curve: Curves.easeOut)
          : const Interval(0.16, 1.0, curve: Curves.easeOut),
      reverseCurve: const Interval(0.16, 1.0, curve: Curves.easeOut),
    );
  }

  void _toggleCollapsed() {
    if (!widget.isCollapsible || widget.stage != 4) return;
    _setCollapsed(
      !_isCollapsed,
      markCompletionHandled: _shouldAutoCollapse(widget),
    );
  }

  void _setCollapsed(bool collapsed, {bool markCompletionHandled = false}) {
    if (_isCollapsed == collapsed) {
      if (markCompletionHandled) {
        _hasAutoCollapsedForCurrentCompletion = true;
      }
      return;
    }
    setState(() {
      _isCollapsed = collapsed;
      if (markCompletionHandled) {
        _hasAutoCollapsedForCurrentCompletion = true;
      }
    });
    _collapseController.stop();
    _rebuildCollapseAnimations();
    if (collapsed) {
      _collapseController.reverse();
    } else {
      _collapseController.forward();
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !_scrollController.hasClients) return;
        final position = _scrollController.position;
        if ((position.pixels - position.minScrollExtent).abs() >
            _bottomTolerance) {
          _scrollController.jumpTo(position.minScrollExtent);
        }
        _checkOverflow();
      });
    }
  }

  @override
  void dispose() {
    _stopTimer();
    _collapseController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  String _formatElapsed(int seconds) {
    if (seconds < 60) {
      return LegacyTextLocalizer.localize('$seconds 秒');
    }
    final m = seconds ~/ 60;
    final s = seconds % 60;
    return LegacyTextLocalizer.localize('$m 分 $s 秒');
  }

  @override
  Widget build(BuildContext context) {
    final bool hasContent = widget.thinkingText.trim().isNotEmpty;
    final bool canCollapse = widget.isCollapsible && widget.stage == 4;
    final bool isThinking = !_isCompletedStage(widget.stage);

    final headerLabel = isThinking
        ? LegacyTextLocalizer.localize('正在思考')
        : LegacyTextLocalizer.localize('完成思考');

    final elapsedLabel = widget.startTime != null && _elapsedSeconds > 0
        ? '${LegacyTextLocalizer.localize('用时')} ${_formatElapsed(_elapsedSeconds)}'
        : '';

    final headerTextStyle = TextStyle(
      color: _kSecondaryText,
      fontSize: 12,
      fontWeight: FontWeight.w500,
      height: 1.5,
      letterSpacing: 0.33,
    );

    final headerRow = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (isThinking)
          const SizedBox(
            width: 14,
            height: 14,
            child: CircularProgressIndicator(
              strokeWidth: 1.6,
              valueColor: AlwaysStoppedAnimation<Color>(_kSecondaryText),
            ),
          )
        else
          const Icon(
            Icons.check_circle_outline_rounded,
            size: 16,
            color: _kSecondaryText,
          ),
        const SizedBox(width: 8),
        isThinking
            ? _FlowingThinkingTitle(
                text: headerLabel,
                style: headerTextStyle,
              )
            : Text(headerLabel, style: headerTextStyle),
        if (elapsedLabel.isNotEmpty) ...[
          const SizedBox(width: 6),
          Text(
            elapsedLabel,
            style: headerTextStyle.copyWith(
              color: _kSubtleText,
              fontWeight: FontWeight.w400,
            ),
          ),
        ],
        if (canCollapse && hasContent) ...[
          const SizedBox(width: 2),
          AnimatedBuilder(
            animation: _collapseController,
            builder: (context, child) {
              return Transform.rotate(
                angle: (1 - _collapseController.value) * math.pi,
                child: child,
              );
            },
            child: const Icon(
              Icons.keyboard_arrow_down_rounded,
              size: 16,
              color: _kSecondaryText,
            ),
          ),
        ],
      ],
    );

    final header = (canCollapse && hasContent)
        ? InkWell(
            onTap: _toggleCollapsed,
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Align(
                alignment: Alignment.centerLeft,
                child: headerRow,
              ),
            ),
          )
        : headerRow;

    final placeholderText = widget.stage == 5
        ? LegacyTextLocalizer.localize('任务已取消')
        : LegacyTextLocalizer.localize('正在生成思考内容...');

    final displayedText = widget.thinkingText.trim().isEmpty
        ? placeholderText
        : widget.thinkingText
              .split('\n')
              .map(LegacyTextLocalizer.localize)
              .join('\n');

    final contentChild = (hasContent || isThinking) && widget.stage != 5
        ? Container(
            width: double.infinity,
            constraints: BoxConstraints(maxHeight: widget.maxHeight),
            margin: const EdgeInsets.only(top: 8),
            decoration: const BoxDecoration(
              border: Border(
                left: BorderSide(color: _kThinkingBorder, width: 1),
              ),
            ),
            child: Stack(
              children: [
                NotificationListener<ScrollNotification>(
                  onNotification: (notification) {
                    _checkOverflow();
                    final isUserDrag =
                        (notification is ScrollUpdateNotification &&
                            notification.dragDetails != null) ||
                        (notification is OverscrollNotification &&
                            notification.dragDetails != null);
                    if (isUserDrag) {
                      final m = notification.metrics;
                      _autoScrollToLatest =
                          (m.maxScrollExtent - m.pixels).abs() <=
                              _bottomTolerance;
                    } else if (notification is ScrollEndNotification &&
                        (notification.metrics.maxScrollExtent -
                                    notification.metrics.pixels)
                                .abs() <=
                            _bottomTolerance) {
                      _autoScrollToLatest = true;
                    }
                    return false;
                  },
                  child: SingleChildScrollView(
                    controller: _scrollController,
                    physics: const ClampingScrollPhysics(),
                    child: Padding(
                      padding: const EdgeInsets.only(left: 12),
                      child: SelectableText(
                        displayedText,
                        style: const TextStyle(
                          color: _kSecondaryText,
                          fontSize: 12,
                          fontWeight: FontWeight.w400,
                          height: 1.5,
                          letterSpacing: 0.33,
                        ),
                      ),
                    ),
                  ),
                ),
                if (_showGradient)
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 40,
                    child: IgnorePointer(
                      child: Container(
                        decoration: const BoxDecoration(
                          borderRadius: BorderRadius.only(
                            bottomLeft: Radius.circular(4),
                            bottomRight: Radius.circular(4),
                          ),
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [
                              Color(0x00F1F8FF),
                              Color(0xCCF1F8FF),
                              _kThinkingSurface,
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          )
        : const SizedBox.shrink();

    final content = canCollapse
        ? AnimatedBuilder(
            animation: _collapseController,
            child: RepaintBoundary(child: contentChild),
            builder: (context, child) {
              final size = _collapseSizeFactor.value.clamp(0.0, 1.0);
              final opacity = _collapseOpacity.value.clamp(0.0, 1.0);
              if (size <= 0.001 && !_collapseController.isAnimating) {
                return const SizedBox.shrink();
              }
              return ClipRect(
                child: Align(
                  alignment: Alignment.topCenter,
                  heightFactor: size,
                  child: IgnorePointer(
                    ignoring: size <= 0.001,
                    child: Opacity(opacity: opacity, child: child),
                  ),
                ),
              );
            },
          )
        : contentChild;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [header, content],
    );
  }
}

class _FlowingThinkingTitle extends StatefulWidget {
  const _FlowingThinkingTitle({required this.text, required this.style});

  final String text;
  final TextStyle style;

  @override
  State<_FlowingThinkingTitle> createState() => _FlowingThinkingTitleState();
}

class _FlowingThinkingTitleState extends State<_FlowingThinkingTitle>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final child = Text(widget.text, style: widget.style);
    if (MediaQuery.disableAnimationsOf(context)) return child;
    final baseColor = widget.style.color ?? _kSecondaryText;
    return AnimatedBuilder(
      animation: _controller,
      child: child,
      builder: (context, child) {
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (bounds) {
            final width = bounds.width <= 0 ? 1.0 : bounds.width;
            final shimmerWidth = (width * 0.72).clamp(52.0, 180.0).toDouble();
            final travel = width + shimmerWidth;
            final left =
                bounds.left - shimmerWidth + travel * _controller.value;
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [
                baseColor,
                Colors.white.withValues(alpha: 0.95),
                baseColor,
              ],
              stops: const [0.08, 0.5, 0.92],
            ).createShader(
              Rect.fromLTWH(left, bounds.top, shimmerWidth, bounds.height),
            );
          },
          child: child,
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// AgentToolSummaryCard
// ---------------------------------------------------------------------------

class WebAgentToolSummaryCard extends StatelessWidget {
  const WebAgentToolSummaryCard({
    super.key,
    required this.cardData,
    this.parentScrollController,
  });

  final Map<String, dynamic> cardData;
  final ScrollController? parentScrollController;

  @override
  Widget build(BuildContext context) {
    final status = (cardData['status'] ?? 'running').toString();
    final title = resolveAgentToolTitle(cardData);
    final statusLabel = resolveAgentToolStatusLabel(cardData);
    final typeLabel = resolveAgentToolTypeLabel(cardData);
    final statusColor = _statusColor(status);
    final cardBackground = statusColor.withValues(alpha: 0.08);
    final statusTagBackground = Colors.white.withValues(alpha: 0.78);
    const titleStyle = TextStyle(
      color: _kPrimaryText,
      fontSize: 12,
      fontWeight: FontWeight.w500,
      letterSpacing: 0,
      height: 1.15,
    );

    final capsule = Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(999),
      clipBehavior: Clip.antiAlias,
      child: Ink(
        decoration: BoxDecoration(
          color: cardBackground,
          borderRadius: BorderRadius.circular(999),
        ),
        child: InkWell(
          onTap: () => _showDetailSheet(context),
          borderRadius: BorderRadius.circular(999),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 10, 8),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _StatusIcon(status: status, toolType: cardData['toolType']),
                const SizedBox(width: 8),
                Flexible(
                  child: status == 'running'
                      ? _FlowingToolTitle(text: title, style: titleStyle)
                      : Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: titleStyle,
                        ),
                ),
                const SizedBox(width: 8),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 7,
                    vertical: 4,
                  ),
                  decoration: BoxDecoration(
                    color: statusTagBackground,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    status == 'running' ? typeLabel : statusLabel,
                    style: TextStyle(
                      color: statusColor,
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      height: 1,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );

    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.78,
          minHeight: 34,
        ),
        child: Container(
          margin: const EdgeInsets.only(top: 6, bottom: 2),
          child: capsule,
        ),
      ),
    );
  }

  void _showDetailSheet(BuildContext context) {
    // Delegate to the in-app implementation so the detail sheet renders the
    // same terminal-style transcript (traffic lights, title, type/status tag,
    // ANSI-coloured output, diff viewer) as the native chat surface. The
    // native sheet uses `isDismissible: true` so tapping anywhere on the
    // dimmed barrier outside the panel closes it.
    native_transcript.showAgentToolDetailSheet(context, cardData: cardData);
  }
}

Color _statusColor(String status) {
  switch (status) {
    case 'success':
      return const Color(0xFF2F8F4E);
    case 'error':
      return const Color(0xFFFF6464);
    case 'timeout':
      return const Color(0xFFFF8A3D);
    case 'interrupted':
      return const Color(0xFFFFC04D);
    default:
      return const Color(0xFF2C7FEB);
  }
}

IconData _statusIcon(String status, String toolType) {
  if (status == 'timeout') return Icons.hourglass_bottom_rounded;
  if (status == 'interrupted') return Icons.stop_circle_outlined;
  if (status == 'error') return Icons.error_outline_rounded;
  switch (toolType) {
    case 'terminal':
      return Icons.terminal_rounded;
    case 'browser':
      return Icons.public_rounded;
    case 'search':
      return Icons.search_rounded;
    case 'image':
      return Icons.image_outlined;
    case 'file':
      return Icons.description_outlined;
    case 'calendar':
      return Icons.calendar_today_rounded;
    case 'alarm':
    case 'schedule':
      return Icons.alarm_outlined;
    case 'memory':
      return Icons.psychology_outlined;
    case 'workspace':
      return Icons.folder_outlined;
    case 'subagent':
      return Icons.hub_outlined;
    case 'review':
      return Icons.rate_review_outlined;
    case 'mcp':
      return Icons.extension_outlined;
    default:
      return Icons.check_circle_outline_rounded;
  }
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status, required this.toolType});

  final String status;
  final dynamic toolType;

  @override
  Widget build(BuildContext context) {
    final color = _statusColor(status);
    final bg = color.withValues(alpha: 0.12);
    return Container(
      width: 18,
      height: 18,
      decoration: BoxDecoration(color: bg, shape: BoxShape.circle),
      child: Center(
        child: status == 'running'
            ? SizedBox(
                width: 9,
                height: 9,
                child: CircularProgressIndicator(
                  strokeWidth: 1.4,
                  valueColor: AlwaysStoppedAnimation<Color>(color),
                ),
              )
            : Icon(
                _statusIcon(status, (toolType ?? '').toString()),
                size: 10,
                color: color,
              ),
      ),
    );
  }
}

class _FlowingToolTitle extends StatefulWidget {
  const _FlowingToolTitle({required this.text, required this.style});

  final String text;
  final TextStyle style;

  @override
  State<_FlowingToolTitle> createState() => _FlowingToolTitleState();
}

class _FlowingToolTitleState extends State<_FlowingToolTitle>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final child = Text(
      widget.text,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: widget.style,
    );
    if (MediaQuery.disableAnimationsOf(context)) return child;
    final base = widget.style.color ?? _kPrimaryText;
    return AnimatedBuilder(
      animation: _controller,
      child: child,
      builder: (context, child) {
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (bounds) {
            final width = bounds.width <= 0 ? 1.0 : bounds.width;
            final shimmerWidth = (width * 0.72).clamp(52.0, 180.0).toDouble();
            final travel = width + shimmerWidth;
            final left =
                bounds.left - shimmerWidth + travel * _controller.value;
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [base, Colors.white.withValues(alpha: 0.95), base],
              stops: const [0.08, 0.5, 0.92],
            ).createShader(
              Rect.fromLTWH(left, bounds.top, shimmerWidth, bounds.height),
            );
          },
          child: child,
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// Aux cards
// ---------------------------------------------------------------------------

class WebHistoryOmittedCard extends StatelessWidget {
  const WebHistoryOmittedCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    final summary = (cardData['summary'] ?? '').toString().trim();
    final originalType = (cardData['originalType'] ?? '').toString().trim();
    final title = summary.isEmpty
        ? LegacyTextLocalizer.localize('历史过程卡片已折叠')
        : LegacyTextLocalizer.localize(summary);
    final subtitle = originalType.isEmpty
        ? LegacyTextLocalizer.localize('历史过程')
        : originalType;
    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.78,
        ),
        child: Container(
          margin: const EdgeInsets.only(top: 6, bottom: 2),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          decoration: BoxDecoration(
            color: const Color(0xFFEFF3FA),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: const Color(0xFFD8E2F1)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.history_toggle_off_rounded,
                size: 16,
                color: _kSecondaryText,
              ),
              const SizedBox(width: 8),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: _kPrimaryText,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        height: 1.2,
                      ),
                    ),
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: _kSecondaryText,
                        fontSize: 10,
                        height: 1.2,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class WebStageHintCard extends StatelessWidget {
  const WebStageHintCard({super.key, required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    final hint = (cardData['hint'] ?? '').toString().trim();
    if (hint.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Text(
        LegacyTextLocalizer.localize(hint),
        style: const TextStyle(
          color: _kSubtleText,
          fontSize: 11,
          fontWeight: FontWeight.w500,
          height: 1.4,
        ),
      ),
    );
  }
}

class WebUnknownCard extends StatelessWidget {
  const WebUnknownCard({super.key, required this.type});

  final String type;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFFE3EAF7)),
      ),
      child: Text(
        '${LegacyTextLocalizer.localize('未知卡片类型')}：$type',
        style: const TextStyle(color: _kSecondaryText, fontSize: 12),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

int? _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) {
    final n = value.toDouble();
    if (n.isFinite) return n.round();
    return null;
  }
  final t = value?.toString().trim() ?? '';
  if (t.isEmpty) return null;
  return int.tryParse(t) ?? double.tryParse(t)?.round();
}

bool _asBool(dynamic value, {bool fallback = false}) {
  if (value is bool) return value;
  final t = value?.toString().trim().toLowerCase() ?? '';
  if (t == 'true') return true;
  if (t == 'false') return false;
  return fallback;
}

String? _asNullableString(dynamic value) {
  final t = value?.toString().trim() ?? '';
  return t.isEmpty ? null : t;
}
