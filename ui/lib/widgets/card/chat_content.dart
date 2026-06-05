import 'package:flutter/material.dart';

class ChatContent extends StatefulWidget {
  const ChatContent({
    super.key,
    // required this.message,
    required this.text,
    required this.shouldAnimate,
    this.onCharacterTyped,
    this.onAnimationCompleted,
  });

  // final Message message;
  final String text;
  final bool shouldAnimate;
  final VoidCallback? onCharacterTyped;
  final VoidCallback? onAnimationCompleted;

  @override
  State<ChatContent> createState() => _ChatContentState();
}

class _ChatContentState extends State<ChatContent> {
  @override
  void initState() {
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(widget.text.isEmpty ? ' ' : widget.text),
        // TypewriterText(
        //   text: widget.message.text,
        //   style: TextStyle(
        //     color: theme.colorScheme.onSurface,
        //   ),
        //   shouldAnimate: widget.shouldAnimate,
        //   onCharacterTyped: widget.onCharacterTyped,
        //   onAnimationCompleted: () {
        //     if (widget.onAnimationCompleted != null) {
        //       widget.onAnimationCompleted!();
        //     }
        //   },
        // ),
        // TODO actionText
        // if (widget.message.actionText != null)
        //   Padding(
        //     padding: const EdgeInsets.only(top: 10.0),
        //     child: TextButton(
        //       style: TextButton.styleFrom(
        //         backgroundColor: theme.colorScheme.primary
        //             .withValues(alpha: 0.1),
        //         foregroundColor: theme.colorScheme.primary,
        //         disabledBackgroundColor: Colors.grey
        //             .withValues(alpha: 0.1),
        //         disabledForegroundColor: Colors.grey
        //             .withValues(alpha: 0.5),
        //         shape: RoundedRectangleBorder(
        //           borderRadius: BorderRadius.circular(8.0),
        //         ),
        //         padding: const EdgeInsets.symmetric(
        //           horizontal: 12,
        //           vertical: 8,
        //         ),
        //       ),
        //       onPressed: widget.message.onAction,
        //       child: Row(
        //         mainAxisSize: MainAxisSize.min,
        //         children: [
        //           const Icon(
        //             Icons.touch_app_outlined,
        //             size: 16,
        //           ),
        //           const SizedBox(width: 6),
        //           Text(
        //             widget.message.actionText!,
        //             style: const TextStyle(
        //               fontWeight: FontWeight.bold,
        //             ),
        //           ),
        //         ],
        //       ),
        //     ),
        //   ),
      ],
    );
  }
}
