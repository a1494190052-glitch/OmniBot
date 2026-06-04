import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

const ValueKey<String> kChatPlanTodoStripKey = ValueKey<String>(
  'chat-plan-todo-strip',
);
const ValueKey<String> kChatPlanTodoPanelKey = ValueKey<String>(
  'chat-plan-todo-panel',
);
const ValueKey<String> kChatPlanTodoToggleKey = ValueKey<String>(
  'chat-plan-todo-toggle',
);

const double _kPlanTodoRowHeight = 34;
const double _kPlanTodoSurfaceRadius = 18;
const double _kPlanTodoHorizontalInset = 20;
const double _kPlanTodoDrawerMaxHeight = 264;
const double _kPlanTodoTrailingSlotWidth = 24;
const Color _kPlanTodoSurfaceColor = Color(0xFFF9FCFF);
const BorderRadius _kPlanTodoSurfaceBorderRadius = BorderRadius.only(
  topLeft: Radius.circular(_kPlanTodoSurfaceRadius),
  topRight: Radius.circular(_kPlanTodoSurfaceRadius),
);

class ChatPlanTodoStrip extends StatefulWidget {
  const ChatPlanTodoStrip({
    super.key,
    required this.todos,
    this.anchorRect,
    this.onOccupiedHeightChanged,
    this.expanded,
    this.onExpandedChanged,
    this.suppressSurfaceShadow = false,
  });

  final List<AgentTodoItem> todos;
  final Rect? anchorRect;
  final ValueChanged<double>? onOccupiedHeightChanged;
  final bool? expanded;
  final ValueChanged<bool>? onExpandedChanged;
  final bool suppressSurfaceShadow;

  @override
  State<ChatPlanTodoStrip> createState() => _ChatPlanTodoStripState();
}

class _ChatPlanTodoStripState extends State<ChatPlanTodoStrip> {
  bool _expanded = false;
  double? _lastReportedOccupiedHeight;

  bool get _resolvedExpanded => widget.expanded ?? _expanded;

  @override
  Widget build(BuildContext context) {
    final todos = widget.todos;
    if (todos.isEmpty) {
      _reportOccupiedHeight(0);
      return const SizedBox.shrink();
    }

    final activeIndex = todos.indexWhere((item) => item.isInProgress);
    final activeTodo = activeIndex >= 0 ? todos[activeIndex] : null;
    final canExpand = todos.length > 1;
    final isExpanded = _resolvedExpanded && canExpand;
    final visibleTodos = isExpanded
        ? todos
        : activeTodo == null
        ? const <AgentTodoItem>[]
        : <AgentTodoItem>[activeTodo];
    if (visibleTodos.isEmpty) {
      _scheduleExpandedResetIfNeeded();
      _reportOccupiedHeight(0);
      return const SizedBox.shrink();
    }
    if (!canExpand && _resolvedExpanded) {
      _scheduleExpandedResetIfNeeded();
    }

    final contentHeight = isExpanded
        ? _resolveTodoListHeight(todos)
        : _kPlanTodoRowHeight;
    _reportOccupiedHeight(contentHeight);

    return AnimatedSize(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutQuart,
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        width: widget.anchorRect?.width ?? double.infinity,
        height: contentHeight,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: _kPlanTodoHorizontalInset,
              right: _kPlanTodoHorizontalInset,
              bottom: 0,
              child: _PlanTodoSurface(
                todos: visibleTodos,
                activeTodo: activeTodo,
                expanded: isExpanded,
                canExpand: canExpand,
                height: contentHeight,
                suppressShadow: widget.suppressSurfaceShadow,
                onToggle: () => _handleExpandedChanged(!isExpanded),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ChatPlanTodoStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.todos.isEmpty && _lastReportedOccupiedHeight != 0) {
      _reportOccupiedHeight(0);
    }
  }

  double _resolveTodoListHeight(List<AgentTodoItem> todos) {
    final visibleCount = todos.length.clamp(1, 6);
    final estimated = visibleCount * _kPlanTodoRowHeight;
    return math.min(_kPlanTodoDrawerMaxHeight, estimated.toDouble());
  }

  void _handleExpandedChanged(bool expanded) {
    if (_resolvedExpanded == expanded && widget.expanded != null) {
      return;
    }
    if (widget.expanded == null) {
      if (_expanded == expanded) {
        return;
      }
      setState(() {
        _expanded = expanded;
      });
    }
    widget.onExpandedChanged?.call(expanded);
  }

  void _scheduleExpandedResetIfNeeded() {
    if (!_resolvedExpanded) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_resolvedExpanded) {
        return;
      }
      _handleExpandedChanged(false);
    });
  }

  void _reportOccupiedHeight(double height) {
    if (widget.onOccupiedHeightChanged == null) {
      return;
    }
    final normalized = height.isFinite ? height : 0.0;
    if (_lastReportedOccupiedHeight != null &&
        (_lastReportedOccupiedHeight! - normalized).abs() < 0.5) {
      return;
    }
    _lastReportedOccupiedHeight = normalized;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      widget.onOccupiedHeightChanged?.call(normalized);
    });
  }
}

class _PlanTodoSurface extends StatelessWidget {
  const _PlanTodoSurface({
    required this.todos,
    required this.activeTodo,
    required this.expanded,
    required this.canExpand,
    required this.height,
    required this.suppressShadow,
    required this.onToggle,
  });

  final List<AgentTodoItem> todos;
  final AgentTodoItem? activeTodo;
  final bool expanded;
  final bool canExpand;
  final double height;
  final bool suppressShadow;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final surfaceColor = context.isDarkTheme
        ? palette.surfacePrimary
        : _kPlanTodoSurfaceColor;
    final shadowColor = suppressShadow
        ? Colors.transparent
        : context.isDarkTheme
        ? palette.shadowColor.withValues(alpha: 0.42)
        : const Color(0x18111B2D);
    final content = expanded
        ? ListView.builder(
            key: kChatPlanTodoPanelKey,
            padding: EdgeInsets.zero,
            itemExtent: _kPlanTodoRowHeight,
            physics: todos.length > 6
                ? const BouncingScrollPhysics(parent: ClampingScrollPhysics())
                : const NeverScrollableScrollPhysics(),
            itemCount: todos.length,
            itemBuilder: (context, index) {
              final todo = todos[index];
              return _PlanTodoRow(
                todo: todo,
                selected: todo == activeTodo,
                expanded: true,
                canExpand: canExpand,
                showToggle: index == _toggleRowIndex,
                onToggle: onToggle,
              );
            },
          )
        : _PlanTodoRow(
            todo: todos.first,
            selected: true,
            expanded: false,
            canExpand: canExpand,
            showToggle: canExpand,
            onToggle: onToggle,
          );

    return Material(
      key: kChatPlanTodoStripKey,
      color: surfaceColor,
      elevation: suppressShadow ? 0 : (expanded ? 8 : 6),
      shadowColor: shadowColor,
      borderRadius: _kPlanTodoSurfaceBorderRadius,
      clipBehavior: Clip.antiAlias,
      child: SizedBox(height: height, child: content),
    );
  }

  int get _toggleRowIndex {
    final index = todos.indexWhere((item) => item == activeTodo);
    return index >= 0 ? index : 0;
  }
}

class _PlanTodoRow extends StatelessWidget {
  const _PlanTodoRow({
    required this.todo,
    required this.selected,
    required this.expanded,
    required this.canExpand,
    required this.showToggle,
    required this.onToggle,
  });

  final AgentTodoItem todo;
  final bool selected;
  final bool expanded;
  final bool canExpand;
  final bool showToggle;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final primaryTextColor = isDark ? palette.textPrimary : AppColors.text;
    final completedColor = primaryTextColor.withValues(alpha: 0.48);
    final rowColor = selected
        ? (isDark ? palette.accentPrimary : const Color(0xFF9E315D)).withValues(
            alpha: isDark ? 0.16 : 0.10,
          )
        : Colors.transparent;
    final textColor = todo.isCompleted ? completedColor : primaryTextColor;
    final text = todo.isInProgress && todo.activeForm.trim().isNotEmpty
        ? todo.activeForm
        : todo.content;

    return Material(
      color: rowColor,
      child: InkWell(
        onTap: canExpand ? onToggle : null,
        child: SizedBox(
          height: _kPlanTodoRowHeight,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 0, 8, 0),
            child: Row(
              children: [
                _PlanTodoStatusGlyph(status: todo.status, selected: selected),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    text,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: textColor,
                      fontSize: 11,
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w600,
                      letterSpacing: 0,
                      height: 1.05,
                      decoration: todo.isCompleted
                          ? TextDecoration.lineThrough
                          : TextDecoration.none,
                      decorationColor: textColor,
                      decorationThickness: 1.5,
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                SizedBox(
                  width: _kPlanTodoTrailingSlotWidth,
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: showToggle
                        ? _PlanTodoToggle(expanded: expanded, onTap: onToggle)
                        : null,
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

class _PlanTodoStatusGlyph extends StatelessWidget {
  const _PlanTodoStatusGlyph({required this.status, required this.selected});

  final AgentTodoStatus status;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final accent = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF9E315D);
    final muted = context.isDarkTheme
        ? palette.textSecondary.withValues(alpha: 0.62)
        : const Color(0xFF8A97AA);
    final color = switch (status) {
      AgentTodoStatus.completed => accent.withValues(alpha: 0.7),
      AgentTodoStatus.inProgress => accent,
      AgentTodoStatus.pending => muted,
    };
    final icon = switch (status) {
      AgentTodoStatus.completed => Icons.check_rounded,
      AgentTodoStatus.inProgress => Icons.radio_button_checked_rounded,
      AgentTodoStatus.pending => Icons.radio_button_unchecked_rounded,
    };
    return SizedBox(
      width: 16,
      height: 16,
      child: Icon(icon, size: selected ? 14 : 13, color: color),
    );
  }
}

class _PlanTodoToggle extends StatelessWidget {
  const _PlanTodoToggle({required this.expanded, required this.onTap});

  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : const Color(0xFF657891);
    return GestureDetector(
      key: kChatPlanTodoToggleKey,
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 1),
        child: AnimatedRotation(
          turns: expanded ? 0 : 0.5,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          child: Icon(Icons.keyboard_arrow_up_rounded, size: 14, color: color),
        ),
      ),
    );
  }
}
