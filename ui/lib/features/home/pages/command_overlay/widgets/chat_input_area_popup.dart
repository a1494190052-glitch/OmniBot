part of 'chat_input_area.dart';

mixin _ChatInputAreaPopupMixin on _ChatInputAreaStateBase {
  Widget buildPopupMenu() {
    return const SizedBox.shrink();
  }

  /// 构建悬浮菜单项
  Widget _buildPopupMenuItem({
    required Widget icon,
    required String label,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(width: 20, height: 20, child: icon),
          const SizedBox(height: 2),
          Text(
            label,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 8,
              color: Color(0xFF2C7FEB),
              fontFamily: 'PingFang SC',
              fontWeight: FontWeight.w400,
              height: 2.0,
              letterSpacing: 0.333,
            ),
          ),
        ],
      ),
    );
  }
}
