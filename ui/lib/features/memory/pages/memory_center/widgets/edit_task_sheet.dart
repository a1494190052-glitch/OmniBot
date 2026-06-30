import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_text_styles.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// 底部弹出：编辑任务 Sheet
class EditTaskSheet extends StatefulWidget {
  /// 初始文字
  final String initialText;
  /// 最大输入长度
  final int maxLength;
  /// 保存回调
  final Future<bool> Function(String) onSave;

  final Future<bool> Function(String)? onCheckNameExists;

  const EditTaskSheet({
    Key? key,
    this.initialText = '',
    this.maxLength = 18,
    required this.onSave,
    this.onCheckNameExists,
  }) : super(key: key);

  @override
  State<EditTaskSheet> createState() => _EditTaskSheetState();
}

class _EditTaskSheetState extends State<EditTaskSheet> {
  late TextEditingController _ctrl;
  String? _errorText;
  bool get _overMaxLength => _ctrl.text.trim().length > widget.maxLength;
  bool get _hasError => _errorText != null || _overMaxLength;
  bool get _enabled => _ctrl.text.trim().isNotEmpty && !_overMaxLength && _errorText == null;
  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(text: widget.initialText);
    _ctrl.addListener(() {
      if (_errorText != null) {
        _errorText = null;
      }
      setState(() {});
    });
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<bool> checkSave() async {
    final name = _ctrl.text.trim();
    // 清除之前的错误
    setState(() { _errorText = null; });

    if (name.isEmpty) {
      setState(() { _errorText = '名称不能为空'; });
      return false;
    }

    if (_overMaxLength) {
      // 长度超限由 errorText 显示
      setState(() { _errorText = '超过最大长度 ${widget.maxLength} 字'; });
      return false;
    }

    try {
      if (widget.onCheckNameExists != null) {
        final exists = await widget.onCheckNameExists!(name);
        if (exists) {
          setState(() { _errorText = '名称已存在'; });
          return false;
        }
      }
    } catch (e) {
      // 查询失败不阻止保存，但记录日志以便排查
      debugPrint('checkSave error: $e');
    }

    // 通过所有检查
    setState(() { _errorText = null; });
    return true;
  }

  void _onSave() async {
    if (_enabled) {
      bool result = await checkSave();
      if (!result) return;
      try {
        final ok = await widget.onSave(_ctrl.text.trim());
        if (ok) {
          Navigator.of(context).pop();
        } else {
          setState(() { _errorText = '保存失败'; });
        }
      } catch (e) {
        setState(() { _errorText = '保存失败'; });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    // 保证键盘抬起时 Sheet 不被遮挡
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottom),
      child: Container(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 标题和关闭
            Row(
              children: [
                Expanded(child: Container()),
                Text(
                  '修改名称',
                  style: TextStyle(
                    fontFamily: AppTextStyles.fontFamily,
                    fontSize: AppTextStyles.fontSizeH2,
                    fontWeight: AppTextStyles.fontWeightRegular,
                    height: AppTextStyles.lineHeightH1,
                    letterSpacing: AppTextStyles.letterSpacingWide,
                  ),
                ),
                Expanded(
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: GestureDetector(
                      onTap: () => Navigator.of(context).pop(),
                      child: SvgPicture.asset(
                        'assets/common/close2.svg',
                        width: 14,
                        height: 14,
                        alignment: Alignment.center,
                        color: AppColors.text90,
                        errorBuilder: (ctx, err, stack) {
                          return const Icon(Icons.close, size: 14, color: AppColors.text90);
                        }
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 30),
            // 输入框和计数
            SizedBox(
              height: 34,
              child: TextField(
                controller: _ctrl,
                style: const TextStyle(
                  fontSize: AppTextStyles.fontSizeMain,
                  fontWeight: AppTextStyles.fontWeightRegular,
                  letterSpacing: AppTextStyles.letterSpacingWide,
                  color: AppColors.text70,
                  height: AppTextStyles.lineHeightH3,
                ),
                decoration: InputDecoration(
                  counterText: '',
                  filled: true,
                  fillColor: AppColors.text03,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 9),
                  errorText: null,
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(50),
                    borderSide: _hasError ? const BorderSide(color: AppColors.alertRed, width: 1) : BorderSide.none,
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(50),
                    borderSide: _hasError ? const BorderSide(color: AppColors.alertRed, width: 1) : BorderSide.none,
                  ),
                  suffix: Text(
                    '${_ctrl.text.length}/${widget.maxLength}',
                    style: TextStyle(
                      fontSize: AppTextStyles.fontSizeSmall,
                      color: _ctrl.text.length > widget.maxLength
                          ? AppColors.alertRed
                          : AppColors.text50,
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            // 预留固定高度的错误区域
            SizedBox(
              height: 20, // 固定高度
              child: _hasError
                  ? Text(
                      _errorText ?? (_overMaxLength ? '超过最大长度 ${widget.maxLength} 字' : ''),
                      style: const TextStyle(
                        color: AppColors.alertRed,
                        fontSize: AppTextStyles.fontSizeSmall,
                        fontWeight: AppTextStyles.fontWeightRegular,
                        height: AppTextStyles.lineHeightH1,
                        letterSpacing: AppTextStyles.letterSpacingMedium,
                      ),
                    )
                  : null,
            ),
            const SizedBox(height: 36),
            // 保存按钮
            Center(
              child: SizedBox(
                width: 166,
                height: 44,
                child: ElevatedButton(
                  onPressed: _enabled ? _onSave : null,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _overMaxLength ? AppColors.text20 : AppColors.primaryBlue,
                    elevation: 0,
                    padding: EdgeInsets.symmetric(horizontal: 30, vertical: 8),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(50)),
                  ),
                  child: const Center(
                    child: Text(
                      '保存',
                      style: TextStyle(
                        fontSize: AppTextStyles.fontSizeH3,
                        fontWeight: AppTextStyles.fontWeightMedium,
                        color: Colors.white,
                        height: AppTextStyles.lineHeightH2,
                        letterSpacing: AppTextStyles.letterSpacingWide,
                      ),
                    ),
                  ),
                ),
              )
            )
          ],
        ),
      ),
    );
  }
}
