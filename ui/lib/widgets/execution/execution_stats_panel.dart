import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/execution/execution_models.dart';

/// 执行统计面板
class ExecutionStatsPanel extends StatelessWidget {
  final ExecutionStats stats;
  final bool compact;

  const ExecutionStatsPanel({
    super.key,
    required this.stats,
    this.compact = false,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    if (compact) {
      return _buildCompactView(context);
    }

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '执行统计',
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: palette.textPrimary,
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              _buildStatItem(
                context,
                icon: Icons.play_circle_outline,
                label: '总执行',
                value: '${stats.callCount}',
              ),
              const SizedBox(width: 16),
              _buildStatItem(
                context,
                icon: Icons.check_circle_outline,
                label: '成功',
                value: '${stats.successCount}',
                color: const Color(0xFF117A37),
              ),
              const SizedBox(width: 16),
              _buildStatItem(
                context,
                icon: Icons.error_outline,
                label: '失败',
                value: '${stats.failCount}',
                color: const Color(0xFFB42318),
              ),
              const SizedBox(width: 16),
              _buildStatItem(
                context,
                icon: Icons.percent,
                label: '成功率',
                value: '${stats.successRate.toStringAsFixed(0)}%',
                color: stats.successRate >= 80
                    ? const Color(0xFF117A37)
                    : stats.successRate >= 50
                        ? const Color(0xFFD97706)
                        : const Color(0xFFB42318),
              ),
            ],
          ),
          if (stats.lastRunId != null && stats.lastRunId!.isNotEmpty) ...[
            const SizedBox(height: 12),
            Row(
              children: [
                Icon(
                  Icons.history,
                  size: 14,
                  color: palette.textTertiary,
                ),
                const SizedBox(width: 6),
                Text(
                  '最近执行: ',
                  style: TextStyle(
                    fontSize: 12,
                    color: palette.textTertiary,
                  ),
                ),
                if (stats.lastSuccess != null)
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                    decoration: BoxDecoration(
                      color: stats.lastSuccess!
                          ? const Color(0xFFE8F7EE)
                          : const Color(0xFFFDECEC),
                      borderRadius: BorderRadius.circular(4),
                    ),
                    child: Text(
                      stats.lastSuccess! ? '成功' : '失败',
                      style: TextStyle(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        color: stats.lastSuccess!
                            ? const Color(0xFF117A37)
                            : const Color(0xFFB42318),
                      ),
                    ),
                  ),
                const SizedBox(width: 8),
                if (stats.lastRunAt != null)
                  Text(
                    _formatTime(stats.lastRunAt!),
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                    ),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildCompactView(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        _buildPill(
          context,
          '执行 ${stats.callCount} 次',
        ),
        const SizedBox(width: 6),
        _buildPill(
          context,
          '成功率 ${stats.successRate.toStringAsFixed(0)}%',
          backgroundColor: stats.successRate >= 80
              ? const Color(0xFFE8F7EE)
              : stats.successRate >= 50
                  ? const Color(0xFFFFF7ED)
                  : const Color(0xFFFDECEC),
          textColor: stats.successRate >= 80
              ? const Color(0xFF117A37)
              : stats.successRate >= 50
                  ? const Color(0xFFD97706)
                  : const Color(0xFFB42318),
        ),
        if (stats.lastSuccess != null) ...[
          const SizedBox(width: 6),
          _buildPill(
            context,
            stats.lastSuccess! ? '最近成功' : '最近失败',
            backgroundColor: stats.lastSuccess!
                ? const Color(0xFFE8F7EE)
                : const Color(0xFFFDECEC),
            textColor: stats.lastSuccess!
                ? const Color(0xFF117A37)
                : const Color(0xFFB42318),
          ),
        ],
      ],
    );
  }

  Widget _buildStatItem(
    BuildContext context, {
    required IconData icon,
    required String label,
    required String value,
    Color? color,
  }) {
    final palette = context.omniPalette;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 14, color: color ?? palette.textTertiary),
            const SizedBox(width: 4),
            Text(
              label,
              style: TextStyle(
                fontSize: 11,
                color: palette.textTertiary,
              ),
            ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w700,
            color: color ?? palette.textPrimary,
          ),
        ),
      ],
    );
  }

  Widget _buildPill(
    BuildContext context,
    String text, {
    Color? backgroundColor,
    Color? textColor,
  }) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: backgroundColor ?? palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: textColor ?? palette.textSecondary,
        ),
      ),
    );
  }

  String _formatTime(String isoTime) {
    try {
      final dt = DateTime.parse(isoTime);
      final now = DateTime.now();
      final diff = now.difference(dt);
      if (diff.inMinutes < 1) return '刚刚';
      if (diff.inHours < 1) return '${diff.inMinutes}分钟前';
      if (diff.inDays < 1) return '${diff.inHours}小时前';
      if (diff.inDays < 7) return '${diff.inDays}天前';
      return '${dt.month}/${dt.day}';
    } catch (_) {
      return isoTime;
    }
  }
}
