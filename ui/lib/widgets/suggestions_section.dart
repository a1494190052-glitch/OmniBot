import 'dart:async';
import 'package:flutter/material.dart';
import '../models/chat_models.dart';

class AnimatedSuggestionChip extends StatefulWidget {
  final Widget child;
  final Duration delay;

  const AnimatedSuggestionChip({
    super.key,
    required this.child,
    required this.delay,
  });

  @override
  State<AnimatedSuggestionChip> createState() =>
      _AnimatedSuggestionChipState();
}

class _AnimatedSuggestionChipState extends State<AnimatedSuggestionChip> {
  bool _isVisible = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer(widget.delay, () {
      if (mounted) {
        setState(() => _isVisible = true);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedOpacity(
      opacity: _isVisible ? 1 : 0,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOut,
      child: AnimatedSlide(
        offset: _isVisible ? Offset.zero : const Offset(0, 0.5),
        duration: const Duration(milliseconds: 400),
        curve: Curves.easeOutCubic,
        child: widget.child,
      ),
    );
  }
}

class SuggestionsSection extends StatelessWidget {
  final bool isAnimationComplete;
  final bool isLatestBotMessage;
  final String? suggestionTitle;
  final List<Suggestion>? suggestions;
  final void Function(String)? onSuggestionTapped;

  const SuggestionsSection({
    super.key,
    required this.isAnimationComplete,
    required this.isLatestBotMessage,
    this.suggestionTitle,
    this.suggestions,
    this.onSuggestionTapped,
  });

  @override
  Widget build(BuildContext context) {
    if (!isAnimationComplete ||
        !isLatestBotMessage ||
        suggestions == null ||
        suggestions!.isEmpty) {
      return const SizedBox.shrink(key: ValueKey('suggestions_empty'));
    }

    final theme = Theme.of(context);
    return Padding(
      key: const ValueKey('suggestions_visible'),
      padding: const EdgeInsets.only(top: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (suggestionTitle != null && suggestionTitle!.isNotEmpty)
            AnimatedSuggestionChip(
              delay: Duration.zero,
              child: Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  suggestionTitle!,
                  style: TextStyle(
                    color: theme.colorScheme.onSurface.withValues(alpha: 0.7),
                    fontSize: 14,
                  ),
                ),
              ),
            ),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: suggestions!
                .asMap()
                .entries
                .map((e) {
                  final idx = e.key;
                  final s = e.value;
                  return AnimatedSuggestionChip(
                    delay: Duration(milliseconds: 100 * (idx + 1)),
                    child: GestureDetector(
                      onTap: () => onSuggestionTapped?.call(s.text),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          vertical: 8,
                          horizontal: 12,
                        ),
                        decoration: BoxDecoration(
                          color: theme.colorScheme.surface,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: Text(
                          s.text,
                          style: TextStyle(
                            color: theme.colorScheme.onSurface,
                            fontSize: 14,
                          ),
                        ),
                      ),
                    ),
                  );
                })
                .toList(),
          ),
        ],
      ),
    );
  }
}
