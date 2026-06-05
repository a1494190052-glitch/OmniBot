// 组件不再使用
import 'package:flutter/material.dart';
import '../../models/block_models.dart';

class ScreenshotCard extends StatefulWidget {
  final ScreenShotCardBlock block;

  const ScreenshotCard({
    super.key,
    required this.block,
  });

  @override
  State<ScreenshotCard> createState() => _ScreenshotCardState();
}

class _ScreenshotCardState extends State<ScreenshotCard> with TickerProviderStateMixin {

  @override
  void initState() {
    super.initState();
  }


  @override
  Widget build(BuildContext context) {
    final block = widget.block;
    final dateTime = block.dateTime;
    final title = block.title;
    final tag = block.tag;
    final imagePath = block.imagePath;
    return Container(
      margin: const EdgeInsets.fromLTRB(0, 0, 0, 0),
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 12),
      decoration: BoxDecoration(
        color: const Color(0x33C5E0E6),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 日期时间
              if (dateTime != null)
                Text(
                  dateTime,
                  style: const TextStyle(
                    color: Color(0xFF666666),
                    fontSize: 12,
                  ),
                ),
              const SizedBox(height: 8),

              // 标题
              if (title != null)
                Text(
                  title,
                  style: const TextStyle(
                    color: Colors.black,
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              const SizedBox(height: 12),
              // 底部行：标签和图片
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  // 标签
                  if (tag != null)
                    Row(
                      children: [
                        Container(
                          width: 16,
                          height: 16,
                          decoration: BoxDecoration(
                            color: const Color(0xFF007AFF),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: const Icon(
                            Icons.description,
                            color: Colors.white,
                            size: 12,
                          ),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          tag,
                          style: const TextStyle(
                            color: Color(0xFF007AFF),
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                ],
              ),
            ],
          ),
          Container(
            width: 70,
            height: 70,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(6),
              image: DecorationImage(
                image: AssetImage(imagePath ?? 'assets/images/scene1.png'),
                fit: BoxFit.cover,
              ),
            ),
          ),
        ]
      ),
    );
  }
}