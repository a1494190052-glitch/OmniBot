import 'package:flutter/material.dart';
import 'package:ui/models/task_models.dart';

class ExecutionStatusChip extends StatelessWidget {
  final ExecutionActionStatus action;
  const ExecutionStatusChip({super.key, required this.action});

  @override
  Widget build(BuildContext context) {
    final successStyle = action.success
        ? BoxDecoration(
            color: const Color(0xFFEFF5FF),
            borderRadius: BorderRadius.circular(6),
          )
        : BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: const Color(0xFFFFD6D6)),
          );
    final iconBackground =
        action.success ? const Color(0xFF1A73E8) : const Color(0xFFFF4C4C);
    final iconData = action.success ? Icons.check : Icons.close;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: successStyle,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircleAvatar(
            radius: 6,
            backgroundColor: iconBackground,
            child: Icon(iconData, size: 10, color: Colors.white),
          ),
          const SizedBox(width: 6),
          if (action.icon != null) ...[
            Icon(
              action.icon,
              size: 16,
              color: action.success ? Colors.black87 : const Color(0xFFFF4C4C),
            ),
            const SizedBox(width: 4),
          ],
          Text(
            action.label,
            style: TextStyle(
              fontSize: 14,
              color: action.success ? Colors.black87 : const Color(0xFFFF4C4C),
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}
